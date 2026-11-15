package com.baotime.app.network

import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.TerminalReason
import com.baotime.app.config.PasswordRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class FaceTimeAnswerTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun enqueueCapableServerInfo() {
        val helperOn = BlueBubblesFixtures.read("server-info-helper-on.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(helperOn))
    }

    private fun createClient(): BlueBubblesClient = BlueBubblesClient(
        endpointProvider = {
            EndpointSnapshot(
                url = server.url("/").toString().trimEnd('/'),
                password = PasswordRead.Available("secret-pw"),
                revision = 1L
            )
        }
    )

    @Test
    fun answerFaceTimeCall_returnsCreatedLinkOnValidFaceTimeRoomUrl() = runBlocking {
        val link = "https://facetime.apple.com/join#v=1&p=ans&k=key"
        enqueueCapableServerInfo()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"$link"}}"""
            )
        )
        val client = createClient()
        val result = client.answerFaceTimeCall("call-uuid-123")
        assertEquals(FaceTimeSessionResult.Created(link), result)

        val preflight = server.takeRequest()
        assertTrue(preflight.path.orEmpty().startsWith("/api/v1/server/info?"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path.orEmpty().startsWith("/api/v1/facetime/answer/call-uuid-123?"))
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun answerFaceTimeCall_rejectsEmptyOrNonFaceTimeLink() = runBlocking {
        enqueueCapableServerInfo()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"https://evil.example.com/join"}}"""
            )
        )
        val client = createClient()
        assertEquals(
            FaceTimeSessionResult.Failed(ServerFailure.EmptyLink),
            client.answerFaceTimeCall("call-uuid-123")
        )
    }

    @Test
    fun answerFaceTimeCall_maps401ToUnauthorized() = runBlocking {
        enqueueCapableServerInfo()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401}"""))
        val client = createClient()
        assertEquals(
            FaceTimeSessionResult.Failed(ServerFailure.Unauthorized),
            client.answerFaceTimeCall("call-uuid-123")
        )
    }

    @Test
    fun leaveFaceTimeCall_returnsTrueOn200AndFalseOn500WithoutCrashing() = runBlocking {
        enqueueCapableServerInfo()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"message":"Success"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":500}"""))
        val client = createClient()

        assertTrue(client.leaveFaceTimeCall("call-uuid-leave"))
        val preflight = server.takeRequest()
        assertTrue(preflight.path.orEmpty().startsWith("/api/v1/server/info?"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path.orEmpty().startsWith("/api/v1/facetime/leave/call-uuid-leave?"))

        assertFalse(client.leaveFaceTimeCall("call-uuid-leave"))
    }

    @Test
    fun declineDuringSlowHttpAnswer_abortsRequestAndNeverOpensRoom() = runBlocking {
        val link = "https://facetime.apple.com/join#v=1&p=slow&k=key"
        enqueueCapableServerInfo()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
                .setBody("""{"status":200,"message":"Success","data":{"link":"$link"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"message":"Success"}"""))

        val client = createClient()
        val lifecycle = CallLifecycle(
            scope = scope,
            initialConfigGeneration = 1L,
            ringingTimeoutMs = 0L,
            answerCall = { uuid -> client.answerFaceTimeCall(uuid) },
            leaveCall = { uuid -> client.leaveFaceTimeCall(uuid) }
        )

        assertTrue(lifecycle.onIncomingUuid("uuid-slow", "caller@example.com", 1L))
        assertTrue(lifecycle.accept())
        delay(80L)
        assertTrue(lifecycle.decline())

        val state = lifecycle.state.value as CallLifecycleState.Terminal
        assertEquals(TerminalReason.Declined, state.reason)
        delay(600L)
        // Late response after decline is never applied
        assertTrue(lifecycle.state.value is CallLifecycleState.Terminal)
    }
}
