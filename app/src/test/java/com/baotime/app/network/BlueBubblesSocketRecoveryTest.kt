package com.baotime.app.network

import com.baotime.app.call.CallLifecycle
import com.baotime.app.config.PasswordRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlueBubblesSocketRecoveryTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun offlineSocket_recoversToConnectedWhenEnsureConnectedRunsAgain() {
        var pingResult: ServerPingResult = ServerPingResult.Unreachable(ServerFailure.HttpFailed)
        var pingCalls = 0
        val engines = mutableListOf<FakeSocketEngine>()
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
                EndpointSnapshot("http://127.0.0.1:1234", PasswordRead.Available("secret"), 1L)
            },
            pingCheck = {
                pingCalls++
                pingResult
            },
            callLifecycle = lifecycle,
            engineFactory = { _, _, listener ->
                FakeSocketEngine(listener).also { engines.add(it) }
            }
        )

        // Launch while Mac/Wi-Fi is unreachable -> Offline with zero engines
        socket.ensureConnected()
        assertEquals(1, pingCalls)
        assertEquals(0, engines.size)
        assertEquals(SocketConnectionState.Offline(1L), socket.state.value)

        // App resumes once Mac becomes reachable -> recovers cleanly on same configGeneration
        pingResult = ServerPingResult.Authenticated
        socket.ensureConnected()
        assertEquals(2, pingCalls)
        assertEquals(1, engines.size)
        assertTrue(engines[0].connected)
        assertEquals(SocketConnectionState.Connected(1L), socket.state.value)

        // If engine later disconnects into Offline, ensureConnected closes stale engine and reconnects
        engines[0].disconnect()
        assertEquals(SocketConnectionState.Offline(1L), socket.state.value)
        socket.ensureConnected()
        assertTrue(engines[0].closed)
        assertEquals(2, engines.size)
        assertEquals(SocketConnectionState.Connected(1L), socket.state.value)
    }

    @Test
    fun ensureConnected_whilePingSuspended_doesNotCancelOrDuplicateHandshake() {
        val gate = CompletableDeferred<ServerPingResult>()
        var pingCalls = 0
        val engines = mutableListOf<FakeSocketEngine>()
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
                EndpointSnapshot("http://127.0.0.1:1234", PasswordRead.Available("secret"), 1L)
            },
            pingCheck = {
                pingCalls++
                gate.await()
            },
            callLifecycle = lifecycle,
            engineFactory = { _, _, listener ->
                FakeSocketEngine(listener).also { engines.add(it) }
            }
        )

        socket.ensureConnected()
        assertEquals(1, pingCalls)
        assertEquals(SocketConnectionState.Connecting(1L), socket.state.value)

        // Calling ensureConnected again while Connecting with ping in-flight must be a no-op
        socket.ensureConnected()
        assertEquals(1, pingCalls)
        assertEquals(0, engines.size)

        gate.complete(ServerPingResult.Authenticated)
        assertEquals(1, pingCalls)
        assertEquals(1, engines.size)
        assertEquals(SocketConnectionState.Connected(1L), socket.state.value)
    }
}
