package com.baotime.app.network

import com.baotime.app.config.PasswordRead
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BlueBubblesClientTest {
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

    @Test
    fun `LogRedactor scrubs password guid and token query parameters`() {
        val raw = "https://192.168.1.10:1234/api/v1/server/info?password=superSecret123&guid=mySocketGuid&token=abc999"
        val redacted = LogRedactor.redact(raw)

        assertFalse(redacted.contains("superSecret123"))
        assertFalse(redacted.contains("mySocketGuid"))
        assertFalse(redacted.contains("abc999"))
        assertEquals(
            "https://192.168.1.10:1234/api/v1/server/info?password=[REDACTED]&guid=[REDACTED]&token=[REDACTED]",
            redacted
        )
    }

    private fun readFixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture $path"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `pingServer classifies 200 pong as Authenticated and 401 as Unauthorized`() = runBlocking {
        val fixture = readFixture("bluebubbles/ping.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Unauthorized"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = BlueBubblesClient(
            endpointProvider = { EndpointSnapshot(baseUrl, PasswordRead.Available("raw-secret-pw"), 1L) }
        )

        assertEquals(ServerPingResult.Authenticated, client.pingServer())
        assertFalse(client.lastRedactedRequestUrl.contains("raw-secret-pw"))
        assertTrue(client.lastRedactedRequestUrl.contains("password=[REDACTED]"))

        assertEquals(ServerPingResult.Unauthorized, client.pingServer())
    }

    @Test
    fun `fetchContacts parses phone and email contacts and handles 401`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": 200,
                  "message": "Success",
                  "data": [
                    {
                      "id": "c1",
                      "displayName": "Avery Chen",
                      "phoneNumbers": [{"address": "+15550101234"}],
                      "emails": []
                    },
                    {
                      "id": "c2",
                      "displayName": "",
                      "firstName": "River",
                      "lastName": "Song",
                      "phoneNumbers": [],
                      "emails": [{"address": "river@example.com"}]
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = BlueBubblesClient(
            endpointProvider = { EndpointSnapshot(baseUrl, PasswordRead.Available("secret-pw"), 1L) }
        )

        val result = client.fetchContacts()
        assertTrue(result.reachable)
        assertEquals(2, result.contacts.size)
        assertEquals("Avery Chen", result.contacts[0].displayName)
        assertEquals("+15550101234", result.contacts[0].primaryAddress)
        assertFalse(result.contacts[0].isEmail)
        assertEquals("River Song", result.contacts[1].displayName)
        assertEquals("river@example.com", result.contacts[1].primaryAddress)
        assertTrue(result.contacts[1].isEmail)

        val unauthorized = client.fetchContacts()
        assertFalse(unauthorized.reachable)
        assertEquals(ServerFailure.Unauthorized, unauthorized.failure)
    }

    @Test
    fun `createFaceTimeSession validates FaceTime room URL and rejects root or non-Apple link`() = runBlocking {
        val helperOn = readFixture("bluebubbles/server-info-helper-on.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(helperOn))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"https://facetime.apple.com/join#v=1&p=test&k=key"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"https://facetime.apple.com/"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"message":"Success","data":{"link":"https://example.com/not-facetime"}}"""
            )
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = BlueBubblesClient(
            endpointProvider = { EndpointSnapshot(baseUrl, PasswordRead.Available("secret-pw"), 1L) }
        )

        val valid = client.createFaceTimeSession()
        assertEquals(
            FaceTimeSessionResult.Created("https://facetime.apple.com/join#v=1&p=test&k=key"),
            valid
        )

        val rootInvalid = client.createFaceTimeSession()
        assertEquals(
            FaceTimeSessionResult.Failed(ServerFailure.EmptyLink),
            rootInvalid
        )

        val invalid = client.createFaceTimeSession()
        assertEquals(
            FaceTimeSessionResult.Failed(ServerFailure.EmptyLink),
            invalid
        )
    }

    @Test
    fun `coroutine cancellation cancels in-flight OkHttp request`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(5, TimeUnit.SECONDS)
                .setResponseCode(200)
                .setBody("""{"status":200,"message":"Success","data":"pong"}""")
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = BlueBubblesClient(
            endpointProvider = { EndpointSnapshot(baseUrl, PasswordRead.Available("secret-pw"), 1L) }
        )

        val job = async {
            client.pingServer()
        }
        repeat(50) {
            if (client.lastRedactedRequestUrl.contains("/api/v1/ping")) return@repeat
            delay(20)
        }
        assertTrue(client.lastRedactedRequestUrl.contains("/api/v1/ping"))
        withTimeout(1_000L) {
            job.cancelAndJoin()
        }
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
    }
}
