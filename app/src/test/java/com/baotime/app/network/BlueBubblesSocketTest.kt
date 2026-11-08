package com.baotime.app.network

import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.TerminalReason
import com.baotime.app.config.PasswordRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlueBubblesSocketTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeSocketEngine(val listener: SocketEngineListener) : SocketEngine {
        var connected = false
        var closed = false

        override fun connect() {
            connected = true
            listener.onConnect()
        }

        override fun disconnect() {
            connected = false
            listener.onDisconnect()
        }

        override fun close() {
            connected = false
            closed = true
        }
    }

    @Test
    fun ensureConnected_createsOneSocketPerConfigGenerationAndDropsOldOnSave() {
        var currentRevision = 1L
        val createdEngines = mutableListOf<FakeSocketEngine>()
        val lifecycle = CallLifecycle(
            scope = scope,
            initialConfigGeneration = 1L,
            ringingTimeoutMs = 0L,
            answerCall = { FaceTimeSessionResult.Failed(ServerFailure.HttpFailed) },
            leaveCall = { true }
        )
        val socket = BlueBubblesSocket(
            scope = scope,
            endpointProvider = {
                EndpointSnapshot("http://127.0.0.1:1234", PasswordRead.Available("secret"), currentRevision)
            },
            pingCheck = { ServerPingResult.Authenticated },
            callLifecycle = lifecycle,
            engineFactory = { _, _, listener ->
                FakeSocketEngine(listener).also { createdEngines.add(it) }
            }
        )

        socket.ensureConnected()
        assertEquals(1, createdEngines.size)
        assertEquals(SocketConnectionState.Connected(1L), socket.state.value)

        // Repeated ensureConnected on same configGeneration does not create a second socket
        socket.ensureConnected()
        assertEquals(1, createdEngines.size)

        // Incoming invite arrives on generation 1
        createdEngines[0].listener.onCallStatusChanged(
            """{"uuid":"call-1","status":"incoming","status_id":4,"address":"alice@example.com"}"""
        )
        assertTrue(lifecycle.state.value is CallLifecycleState.Ringing)

        // Config save bumps generation to 2: old socket closes, pending invite is cancelled, new socket connects
        currentRevision = 2L
        socket.onConfigChanged(2L)
        assertTrue(createdEngines[0].closed)
        assertEquals(2, createdEngines.size)
        assertEquals(SocketConnectionState.Connected(2L), socket.state.value)
        val terminal = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.CancelledByConfig, terminal.reason)
    }

    @Test
    fun authFailure_isDistinctFromOffline() {
        var currentRevision = 1L
        var pingResult: ServerPingResult = ServerPingResult.Unauthorized
        val lifecycle = CallLifecycle(
            scope = scope,
            initialConfigGeneration = 1L,
            ringingTimeoutMs = 0L,
            answerCall = { FaceTimeSessionResult.Failed(ServerFailure.HttpFailed) },
            leaveCall = { true }
        )
        val socket = BlueBubblesSocket(
            scope = scope,
            endpointProvider = {
                EndpointSnapshot("http://127.0.0.1:1234", PasswordRead.Available("bad-pass"), currentRevision)
            },
            pingCheck = { pingResult },
            callLifecycle = lifecycle,
            engineFactory = { _, _, listener -> FakeSocketEngine(listener) }
        )

        socket.ensureConnected()
        assertEquals(SocketConnectionState.AuthFailed(1L), socket.state.value)

        currentRevision = 2L
        pingResult = ServerPingResult.Unreachable(ServerFailure.HttpFailed)
        socket.onConfigChanged(2L)
        assertEquals(SocketConnectionState.Offline(2L), socket.state.value)
    }
}
