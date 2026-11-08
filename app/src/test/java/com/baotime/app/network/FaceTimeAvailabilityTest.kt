package com.baotime.app.network

import com.baotime.app.config.PasswordRead
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FaceTimeAvailabilityTest {

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
    fun checkFaceTimeAvailability_returnsAvailableTrueAndUnavailableFalse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"data":{"available":true}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"data":{"available":false}}"""))
        val client = createClient()

        assertEquals(
            FaceTimeAvailabilityResult.Available,
            client.checkFaceTimeAvailability("friend@example.com")
        )
        val firstReq = server.takeRequest()
        assertTrue(firstReq.path.orEmpty().startsWith("/api/v1/handle/availability/facetime?address=friend%40example.com&password="))

        assertEquals(
            FaceTimeAvailabilityResult.Unavailable,
            client.checkFaceTimeAvailability("no-ft@example.com")
        )
    }

    @Test
    fun unavailableOr500Handle_doesNotBlockCreateFaceTimeSession() = runBlocking {
        val helperOn = checkNotNull(javaClass.classLoader?.getResourceAsStream("bluebubbles/server-info-helper-on.json")) {
            "Missing bluebubbles/server-info-helper-on.json"
        }.bufferedReader().use { it.readText() }
        // 1. Availability returns false (Unavailable)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"data":{"available":false}}"""))
        // 2. Capability preflight for revision 1L + Create session still succeeds (`facetimeRouter.ts` @ `76d2b2e` takes `{}` with no callee)
        server.enqueue(MockResponse().setResponseCode(200).setBody(helperOn))
        val mintedLink = "https://facetime.apple.com/join#v=1&p=mint&k=key"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"data":{"link":"$mintedLink"}}"""
            )
        )
        // 3. Availability returns 500 (Indeterminate)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":500}"""))
        // 4. Create session still succeeds (uses cached server/info for revision 1L)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":200,"data":{"link":"$mintedLink"}}"""
            )
        )

        val client = createClient()
        assertEquals(
            FaceTimeAvailabilityResult.Unavailable,
            client.checkFaceTimeAvailability("random@example.com")
        )
        assertEquals(
            FaceTimeSessionResult.Created(mintedLink),
            client.createFaceTimeSession()
        )

        assertEquals(
            FaceTimeAvailabilityResult.Indeterminate,
            client.checkFaceTimeAvailability("error@example.com")
        )
        assertEquals(
            FaceTimeSessionResult.Created(mintedLink),
            client.createFaceTimeSession()
        )
        assertEquals(5, server.requestCount)
    }
}
