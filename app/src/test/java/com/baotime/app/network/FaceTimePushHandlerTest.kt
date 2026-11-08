package com.baotime.app.network

import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.InviteSource
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for Task 11 (`FaceTimePushHandler` + `CallLifecycle` push ingress + `registerPushDevice`). */
class FaceTimePushHandlerTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun parsesCallingStatusChangedPushEnvelope() {
        val raw = """
            {
              "type": "ft-call-status-changed",
              "data": {
                "uuid": "550E8400-E29B-41D4-A716-446655440011",
                "status_id": 4,
                "address": "+15550199999",
                "is_audio": false,
                "is_video": true
              }
            }
        """.trimIndent()
        val parsed = FaceTimePushHandler.parsePushPayload(raw) as? IncomingFaceTimeEvent.CallStatusChanged
        assertNotNull(parsed)
        assertEquals("550E8400-E29B-41D4-A716-446655440011", parsed?.uuid)
        assertEquals(FaceTimeSessionStatus.INCOMING, parsed?.status)
        assertEquals(4, parsed?.statusId)
        assertEquals("+15550199999", parsed?.address)
    }

    @Test
    fun parsesIncomingFaceTimePushEnvelopeWithUuidForTask6AnswerPath() {
        val raw = """
            {
              "type": "incoming-facetime",
              "data": "{\"call_uuid\":\"UUID-PUSH-777\",\"caller\":\"caller@example.com\",\"timestamp\":1762603200000}"
            }
        """.trimIndent()
        val parsed = FaceTimePushHandler.parsePushPayload(raw) as? IncomingFaceTimeEvent.CallStatusChanged
        assertNotNull(parsed)
        assertEquals("UUID-PUSH-777", parsed?.uuid)
        assertEquals(FaceTimeSessionStatus.INCOMING, parsed?.status)
        assertEquals("caller@example.com", parsed?.address)
    }

    @Test
    fun parsesLegacyIncomingPushEnvelopeWithoutUuidAsLegacy() {
        val raw = """
            {
              "type": "incoming-facetime",
              "data": "{\"caller\":\"legacy@example.com\",\"timestamp\":1762603200000}"
            }
        """.trimIndent()
        val parsed = FaceTimePushHandler.parsePushPayload(raw) as? IncomingFaceTimeEvent.LegacyIncoming
        assertNotNull(parsed)
        assertEquals("legacy@example.com", parsed?.caller)
        assertEquals(1762603200000L, parsed?.timestampMs)
    }

    @Test
    fun ignoresNonFaceTimePushTypesAndMalformedPayloads() {
        val newMessage = """{"type":"new-message","data":{"guid":"msg-1","text":"hello"}}"""
        assertNull(FaceTimePushHandler.parsePushPayload(newMessage))
        assertNull(FaceTimePushHandler.parsePushPayload("not-json"))
        assertNull(FaceTimePushHandler.parsePushPayload(""))
    }

    @Test
    fun pushUuidUsesSameAnswerPathAsTask6AndCollapsesSameUuidWithSocket() = runBlocking {
        val helperOn = checkNotNull(javaClass.classLoader?.getResourceAsStream("bluebubbles/server-info-helper-on.json")) {
            "Missing bluebubbles/server-info-helper-on.json"
        }.bufferedReader().use { it.readText() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(helperOn))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"https://facetime.apple.com/join#push-answered-UUID-SAME-101"}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"message":"Success"}"""))

        val client = BlueBubblesClient(
            endpointProvider = {
                EndpointSnapshot(
                    url = server.url("/").toString().trimEnd('/'),
                    password = PasswordRead.Available("push-secret-999"),
                    revision = 1L
                )
            }
        )
        val answeredUuids = mutableListOf<String>()
        val lifecycle = CallLifecycle(
            scope = scope,
            initialConfigGeneration = 1L,
            ringingTimeoutMs = 0L,
            answerCall = { uuid ->
                answeredUuids.add(uuid)
                client.answerFaceTimeCall(uuid)
            },
            leaveCall = { uuid -> client.leaveFaceTimeCall(uuid) }
        )

        val pushPayload = """
            {
              "type": "ft-call-status-changed",
              "data": {
                "uuid": "UUID-SAME-101",
                "status_id": 4,
                "address": "Alice Push"
              }
            }
        """.trimIndent()
        val event = FaceTimePushHandler.parsePushPayload(pushPayload) as IncomingFaceTimeEvent.CallStatusChanged
        val firstAccepted = lifecycle.onIncomingUuid(
            callUuid = event.uuid,
            caller = event.address.orEmpty(),
            configGeneration = 1L,
            source = InviteSource.Fcm
        )
        assertTrue(firstAccepted)

        // Duplicate same UUID arriving on SocketCalling collapses to the single active invite
        val duplicateSocket = lifecycle.onIncomingUuid(
            callUuid = "UUID-SAME-101",
            caller = "Alice Push",
            configGeneration = 1L,
            source = InviteSource.SocketCalling
        )
        assertFalse(duplicateSocket)

        // Accepting uses the exact same Task 6 POST /api/v1/facetime/answer/:call_uuid path
        assertTrue(lifecycle.accept())
        val preflightReq = server.takeRequest()
        assertTrue(preflightReq.path.orEmpty().startsWith("/api/v1/server/info?password="))
        val answerReq = server.takeRequest()
        assertEquals("POST", answerReq.method)
        assertTrue(answerReq.path.orEmpty().startsWith("/api/v1/facetime/answer/UUID-SAME-101?password="))

        repeat(20) {
            if (lifecycle.state.value is CallLifecycleState.Connecting) return@repeat
            delay(20)
        }
        val connecting = lifecycle.state.value as? CallLifecycleState.Connecting
        assertNotNull(connecting)
        assertEquals("https://facetime.apple.com/join#push-answered-UUID-SAME-101", connecting?.url)
        assertEquals(listOf("UUID-SAME-101"), answeredUuids)
        assertEquals(InviteSource.Fcm, connecting?.invite?.source)

        // Disconnected status via push on a new ringing call transitions to Terminal(Disconnected)
        assertTrue(lifecycle.onIncomingUuid("UUID-DISC-202", "Bob Push", 1L, InviteSource.Fcm))
        assertTrue(lifecycle.onCallDisconnected("UUID-DISC-202", 1L))
        val term = lifecycle.state.value as? CallLifecycleState.Terminal
        assertEquals(TerminalReason.Disconnected, term?.reason)
    }

    @Test
    fun registersPushDeviceAndRedactsPasswordFromLogs() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"message":"Success"}"""))
        val client = BlueBubblesClient(
            endpointProvider = {
                EndpointSnapshot(
                    url = server.url("/").toString(),
                    password = PasswordRead.Available("push-secret-999"),
                    revision = 1L
                )
            }
        )
        val registered = client.registerPushDevice("BaoTime Pixel", "https://unifiedpush.example/up?token=xyz")
        assertTrue(registered)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path.orEmpty().startsWith("/api/v1/fcm/device?password="))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"name\":\"BaoTime Pixel\""))
        assertTrue(body.contains("\"identifier\":\"https://unifiedpush.example/up?token=xyz\""))
        assertFalse(client.lastRedactedRequestUrl.contains("push-secret-999"))
        assertTrue(client.lastRedactedRequestUrl.contains("password=[REDACTED]"))
    }
}
