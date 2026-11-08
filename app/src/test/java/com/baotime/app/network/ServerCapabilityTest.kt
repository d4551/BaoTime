package com.baotime.app.network

import com.baotime.app.config.PasswordRead
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerCapabilityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun readFixture(name: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("bluebubbles/$name")) {
            "Missing fixture bluebubbles/$name"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun fixtures_existAndMatchBlueBubbles1912SchemaWithoutSecrets() {
        val ping = JSONObject(readFixture("ping.json"))
        assertEquals(200, ping.getInt("status"))
        assertEquals("pong", ping.getString("data"))

        val helperOn = JSONObject(readFixture("server-info-helper-on.json")).getJSONObject("data")
        assertEquals("1.9.12", helperOn.getString("server_version"))
        assertTrue(helperOn.getBoolean("private_api"))
        assertTrue(helperOn.getBoolean("helper_connected"))
        assertFalse(helperOn.has("facetime_calling"))

        val helperOff = JSONObject(readFixture("server-info-helper-off.json")).getJSONObject("data")
        assertEquals("1.9.12", helperOff.getString("server_version"))
        assertTrue(helperOff.getBoolean("private_api"))
        assertFalse(helperOff.getBoolean("helper_connected"))
        assertFalse(helperOff.has("facetime_calling"))

        val incoming = readFixture("ft-call-status-changed-incoming.json")
        val parsedIncoming = IncomingFaceTimeParser.parseCallStatusChanged(incoming)
        assertNotNull(parsedIncoming)
        assertEquals(4, parsedIncoming?.statusId)

        val disconnected = readFixture("ft-call-status-changed-disconnected.json")
        val parsedDisconnected = IncomingFaceTimeParser.parseCallStatusChanged(disconnected)
        assertNotNull(parsedDisconnected)
        assertEquals(6, parsedDisconnected?.statusId)

        val legacy = readFixture("incoming-facetime-legacy.json")
        val parsedLegacy = IncomingFaceTimeParser.parseLegacyIncoming(legacy)
        assertNotNull(parsedLegacy)
        assertEquals("caller@example.com", parsedLegacy?.caller)
    }

    @Test
    fun isSupportedVersion_acceptsOnlyPinned1912AndNeverCrashes() {
        assertTrue(ServerCapability.isSupportedVersion("1.9.12"))
        assertTrue(ServerCapability.isSupportedVersion("v1.9.12"))
        assertFalse(ServerCapability.isSupportedVersion("1.9.0"))
        assertFalse(ServerCapability.isSupportedVersion("1.9.9"))
        assertFalse(ServerCapability.isSupportedVersion("1.9.11"))
        assertFalse(ServerCapability.isSupportedVersion("1.9.13"))
        assertFalse(ServerCapability.isSupportedVersion("2.0.0"))
        assertFalse(ServerCapability.isSupportedVersion("1.9.12-beta.1"))
        assertFalse(ServerCapability.isSupportedVersion(""))
        assertFalse(ServerCapability.isSupportedVersion(null))
        assertFalse(ServerCapability.isSupportedVersion("not-a-version"))
    }

    @Test
    fun createFaceTimeSession_failsClosedWhenHelperIsOffWithoutCallingCreateEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("server-info-helper-off.json")))
        val client = BlueBubblesClient(
            endpointProvider = {
                EndpointSnapshot(
                    url = server.url("/").toString().trimEnd('/'),
                    password = PasswordRead.Available("test-secret"),
                    revision = 1L
                )
            }
        )

        val result = client.createFaceTimeSession()
        assertEquals(FaceTimeSessionResult.Failed(ServerFailure.HelperUnavailable), result)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/api/v1/server/info"))
    }

    @Test
    fun createFaceTimeSession_failsClosedOnUnsupportedVersion() = runBlocking {
        val oldVersionJson = readFixture("server-info-helper-on.json")
            .replace("\"1.9.12\"", "\"1.9.9\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody(oldVersionJson))
        val client = BlueBubblesClient(
            endpointProvider = {
                EndpointSnapshot(
                    url = server.url("/").toString().trimEnd('/'),
                    password = PasswordRead.Available("test-secret"),
                    revision = 1L
                )
            }
        )

        val status = client.checkServerStatus()
        assertTrue(status.reachable)
        assertFalse(status.isFaceTimeCapable)
        assertEquals(ServerFailure.UnsupportedVersion, status.failure)

        val createResult = client.createFaceTimeSession()
        assertEquals(FaceTimeSessionResult.Failed(ServerFailure.UnsupportedVersion), createResult)
        // Cached status for revision 1 prevented a second network call
        assertEquals(1, server.requestCount)
    }

    @Test
    fun createFaceTimeSession_succeedsWhenCapable1912ServerReturnsFaceTimeUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("server-info-helper-on.json")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"https://facetime.apple.com/join#v=1&p=abc&k=def"}}"""
            )
        )
        val client = BlueBubblesClient(
            endpointProvider = {
                EndpointSnapshot(
                    url = server.url("/").toString().trimEnd('/'),
                    password = PasswordRead.Available("test-secret"),
                    revision = 1L
                )
            }
        )

        val status = client.checkServerStatus()
        assertTrue(status.isFaceTimeCapable)
        assertNull(status.failure)

        val result = client.createFaceTimeSession()
        assertEquals(
            FaceTimeSessionResult.Created("https://facetime.apple.com/join#v=1&p=abc&k=def"),
            result
        )
        assertEquals(2, server.requestCount)
    }
}
