package com.baotime.app.call

import com.baotime.app.network.FaceTimeSessionResult
import com.baotime.app.network.ServerFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CallLifecycleTest {

    private lateinit var scope: CoroutineScope
    private val answeredUuids = CopyOnWriteArrayList<String>()
    private val leftUuids = CopyOnWriteArrayList<String>()
    private val missedInvites = CopyOnWriteArrayList<Pair<CallInvite, TerminalReason>>()
    private val failedInvites = CopyOnWriteArrayList<Pair<CallInvite, ServerFailure>>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        answeredUuids.clear()
        leftUuids.clear()
        missedInvites.clear()
        failedInvites.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun createLifecycle(
        ringingTimeoutMs: Long = 0L,
        answerHandler: suspend (String) -> FaceTimeSessionResult = { uuid ->
            answeredUuids.add(uuid)
            FaceTimeSessionResult.Created("https://facetime.apple.com/join#v=1&p=abc&k=def")
        },
        leaveHandler: suspend (String) -> Boolean = { uuid ->
            leftUuids.add(uuid)
            true
        }
    ): CallLifecycle = CallLifecycle(
        scope = scope,
        initialConfigGeneration = 1L,
        ringingTimeoutMs = ringingTimeoutMs,
        answerCall = answerHandler,
        leaveCall = leaveHandler,
        onMissedInvite = { invite, reason -> missedInvites.add(invite to reason) },
        onAnswerFailed = { invite, failure -> failedInvites.add(invite to failure) }
    )

    @Test
    fun sameUuidDuplicates_collapseToSingleRingingInvite() {
        val lifecycle = createLifecycle()
        assertTrue(lifecycle.onIncomingUuid("uuid-1", "alice@example.com", 1L, InviteSource.SocketCalling))
        val firstState = lifecycle.state.value as CallLifecycleState.Ringing
        assertEquals(1L, firstState.invite.inviteGeneration)

        // Second socket event or FCM event with same non-null UUID collapses
        assertFalse(lifecycle.onIncomingUuid("uuid-1", "alice@example.com", 1L, InviteSource.SocketCalling))
        assertFalse(lifecycle.onIncomingUuid("uuid-1", "alice@example.com", 1L, InviteSource.Fcm))

        val currentState = lifecycle.state.value as CallLifecycleState.Ringing
        assertEquals(1L, currentState.invite.inviteGeneration)
        assertTrue(missedInvites.isEmpty())
    }

    @Test
    fun wrongCallDisconnect_doesNotDismissActiveUuid() {
        val lifecycle = createLifecycle()
        assertTrue(lifecycle.onIncomingUuid("uuid-active", "bob@example.com", 1L))

        // Disconnect for uuid-other must be ignored
        assertFalse(lifecycle.onCallDisconnected("uuid-other", 1L))
        val state = lifecycle.state.value
        assertTrue(state is CallLifecycleState.Ringing)
        assertEquals("uuid-active", (state as CallLifecycleState.Ringing).invite.callUuid)
        assertTrue(missedInvites.isEmpty())

        // Disconnect for the active UUID dismisses it
        assertTrue(lifecycle.onCallDisconnected("uuid-active", 1L))
        val terminal = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.Disconnected, terminal.reason)
        assertEquals(1, missedInvites.size)
    }

    @Test
    fun wrongCallAnswer_onlyPostsActiveUuid() {
        val lifecycle = createLifecycle()
        assertTrue(lifecycle.onIncomingUuid("uuid-first", "first@example.com", 1L))
        assertTrue(lifecycle.onIncomingUuid("uuid-second", "second@example.com", 1L))

        assertTrue(lifecycle.accept())
        assertEquals(listOf("uuid-second"), answeredUuids)
        assertEquals(listOf("uuid-first"), leftUuids)
    }

    @Test
    fun declineWhileAnswerPending_cancelsAndLeavesBestEffortWithoutOpeningRoom() = runBlocking {
        val gate = CompletableDeferred<FaceTimeSessionResult>()
        val lifecycle = createLifecycle(
            answerHandler = { uuid ->
                answeredUuids.add(uuid)
                gate.await()
            }
        )
        assertTrue(lifecycle.onIncomingUuid("uuid-pending", "caller@example.com", 1L))
        assertTrue(lifecycle.accept())
        assertTrue(lifecycle.state.value is CallLifecycleState.Answering)

        // User declines while HTTP answer is still in flight
        assertTrue(lifecycle.decline())
        val terminal = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.Declined, terminal.reason)
        assertTrue(leftUuids.contains("uuid-pending"))

        // Even if the deferred completes late, state remains Terminal (never Connecting/InRoom)
        gate.complete(FaceTimeSessionResult.Created("https://facetime.apple.com/join#v=1&p=abc&k=def"))
        assertTrue(lifecycle.state.value is CallLifecycleState.Terminal)
    }

    @Test
    fun remoteHangupWhileAnswerPending_cancelsAndLeavesBestEffort() = runBlocking {
        val gate = CompletableDeferred<FaceTimeSessionResult>()
        val lifecycle = createLifecycle(
            answerHandler = { uuid ->
                answeredUuids.add(uuid)
                gate.await()
            }
        )
        assertTrue(lifecycle.onIncomingUuid("uuid-hangup", "caller@example.com", 1L))
        assertTrue(lifecycle.accept())
        assertTrue(lifecycle.state.value is CallLifecycleState.Answering)

        // status_id=6 arrives for active UUID while Answering
        assertTrue(lifecycle.onCallDisconnected("uuid-hangup", 1L))
        val terminal = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.Disconnected, terminal.reason)
        assertEquals(listOf("uuid-hangup"), leftUuids)
        assertEquals(1, missedInvites.size)
    }

    @Test
    fun configChange_cancelsPendingAnswerAndDropsRingingInvite() = runBlocking {
        val gate = CompletableDeferred<FaceTimeSessionResult>()
        val lifecycle = createLifecycle(
            answerHandler = { uuid ->
                answeredUuids.add(uuid)
                gate.await()
            }
        )
        assertTrue(lifecycle.onIncomingUuid("uuid-cfg", "caller@example.com", 1L))
        assertTrue(lifecycle.accept())

        lifecycle.onConfigChanged(2L)
        val terminal = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.CancelledByConfig, terminal.reason)

        // Stale configGeneration event is rejected
        assertFalse(lifecycle.onIncomingUuid("uuid-stale", "caller@example.com", 1L))
        // Current configGeneration event is accepted
        assertTrue(lifecycle.onIncomingUuid("uuid-fresh", "caller@example.com", 2L))
    }

    @Test
    fun nlsUrlAndSocketUuid_staySeparateAndUnrelatedUuidReplacesNlsWithoutMerging() {
        val lifecycle = createLifecycle()
        val nlsUrl = "https://facetime.apple.com/join#v=1&p=nls&k=key"
        assertTrue(lifecycle.onIncomingNlsUrl(nlsUrl, "NLS Caller", 1L))
        val nlsState = lifecycle.state.value as CallLifecycleState.Ringing
        assertNull(nlsState.invite.callUuid)
        assertEquals(nlsUrl, nlsState.invite.faceTimeUrl)

        // Duplicate NLS URL collapses
        assertFalse(lifecycle.onIncomingNlsUrl(nlsUrl, "NLS Caller", 1L))

        // Unrelated socket UUID arrives while NLS overlay is showing:
        // replaces the NLS invite; NLS terminates as its own missed identity; no leaveCall for NLS!
        assertTrue(lifecycle.onIncomingUuid("uuid-unrelated", "Socket Caller", 1L))
        val uuidState = lifecycle.state.value as CallLifecycleState.Ringing
        assertEquals("uuid-unrelated", uuidState.invite.callUuid)
        assertNull(uuidState.invite.faceTimeUrl)
        assertTrue(leftUuids.isEmpty())
        assertEquals(1, missedInvites.size)
        assertEquals(nlsUrl, missedInvites[0].first.faceTimeUrl)
        assertNull(missedInvites[0].first.callUuid)
    }

    @Test
    fun nlsUrlAccept_joinsUrlDirectlyWithoutCallingRestAnswer() {
        val lifecycle = createLifecycle()
        val nlsUrl = "https://facetime.apple.com/join#v=1&p=direct&k=key"
        assertTrue(lifecycle.onIncomingNlsUrl(nlsUrl, "NLS Caller", 1L))
        assertTrue(lifecycle.accept())

        val joining = lifecycle.state.value as CallLifecycleState.JoiningUrl
        assertEquals(nlsUrl, joining.url)
        assertTrue(answeredUuids.isEmpty())
    }

    @Test
    fun legacyIncomingWithoutUuid_neverCallsRestAnswer() {
        val lifecycle = createLifecycle()
        assertFalse(lifecycle.onIncomingLegacy("legacy@example.com", 1730000000000L, 1L, uiEnabled = false))
        assertTrue(lifecycle.onIncomingLegacy("legacy@example.com", 1730000000000L, 1L, uiEnabled = true))
        // Duplicate legacy event with same caller + timestamp collapses
        assertFalse(lifecycle.onIncomingLegacy("legacy@example.com", 1730000000000L, 1L, uiEnabled = true))

        // Attempting to accept a legacy UI-only invite fails closed without calling answerCall
        assertFalse(lifecycle.accept())
        assertTrue(answeredUuids.isEmpty())
        assertEquals(1, failedInvites.size)
    }

    @Test
    fun ringingExpiry_transitionsToExpiredAndLeavesUuid() = runBlocking {
        val lifecycle = createLifecycle(ringingTimeoutMs = 40L)
        assertTrue(lifecycle.onIncomingUuid("uuid-expire", "caller@example.com", 1L))
        delay(120L)

        val terminal = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.Expired, terminal.reason)
        assertEquals(listOf("uuid-expire"), leftUuids)
        assertEquals(1, missedInvites.size)
        assertEquals(TerminalReason.Expired, missedInvites[0].second)
    }
}
