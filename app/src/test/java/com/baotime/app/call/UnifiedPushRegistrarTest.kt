package com.baotime.app.call

import com.baotime.app.config.PasswordRead
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.EndpointSnapshot
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedPushRegistrarTest {

    private lateinit var server: MockWebServer

    private class FakePushStore(initialToken: String? = null) : PushRegistrationStore {
        private var token = initialToken
        private var endpoint: String? = null

        override fun currentToken(): String? = token
        override fun currentEndpoint(): String? = endpoint

        override fun saveEndpoint(endpoint: String) {
            this.endpoint = endpoint
        }

        override fun clearEndpoint() {
            token = null
            endpoint = null
        }
    }

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
    fun connectorCallbacksRejectUnboundOrNonMatchingTokensAndInvalidEndpoints() {
        val endpoint = "https://push.example/audit?up=1"
        val replacement = "https://push.example/replacement?up=1"
        val unboundStore = FakePushStore()
        assertNull(UnifiedPushRegistrar.onNewEndpoint(unboundStore, "unknown", endpoint))
        assertFalse(UnifiedPushRegistrar.onUnregistered(unboundStore, null))
        assertNull(unboundStore.currentToken())
        assertNull(unboundStore.currentEndpoint())

        val token = "known-connector"
        val store = FakePushStore(token)
        assertEquals(endpoint, UnifiedPushRegistrar.onNewEndpoint(store, token, endpoint))
        for (invalidToken in listOf(null, "", "$token ", "different")) {
            assertNull(UnifiedPushRegistrar.onNewEndpoint(store, invalidToken, replacement))
            assertFalse(UnifiedPushRegistrar.onUnregistered(store, invalidToken))
            assertEquals(token, store.currentToken())
            assertEquals(endpoint, store.currentEndpoint())
        }

        val invalidEndpoints = listOf(
            null,
            "",
            " $replacement ",
            "http://push.example/audit?up=1",
            "https://push.example/audit",
            "https://push.example/?up=1",
            "https://push.example/audit?up=1#fragment",
            "https://push.example:/audit?up=1",
            "https://push.example:0/audit?up=1",
            "https://push.example:65536/audit?up=1"
        )
        for (invalidEndpoint in invalidEndpoints) {
            assertNull(UnifiedPushRegistrar.onNewEndpoint(store, token, invalidEndpoint))
            assertEquals(endpoint, store.currentEndpoint())
        }
    }

    @Test
    fun onNewEndpointStoresAndRotatesForTheRegisteredConnector() {
        val store = FakePushStore("instance-token-1")
        val accepted = UnifiedPushRegistrar.onNewEndpoint(
            store = store,
            incomingToken = "instance-token-1",
            rawEndpoint = "https://ntfy.example/up/abc123?up=1"
        )
        assertEquals("https://ntfy.example/up/abc123?up=1", accepted)
        assertEquals("instance-token-1", store.currentToken())
        assertEquals("https://ntfy.example/up/abc123?up=1", store.currentEndpoint())

        // Mismatched token must not overwrite the active endpoint
        val rejectedRogue = UnifiedPushRegistrar.onNewEndpoint(
            store = store,
            incomingToken = "rogue-token-999",
            rawEndpoint = "https://ntfy.example/up/other?up=1"
        )
        assertNull(rejectedRogue)
        assertEquals("https://ntfy.example/up/abc123?up=1", store.currentEndpoint())

        // Matching token updates rotated endpoint cleanly
        val rotated = UnifiedPushRegistrar.onNewEndpoint(
            store = store,
            incomingToken = "instance-token-1",
            rawEndpoint = "https://ntfy.example:65535/up/rotated-456?up=1"
        )
        assertEquals("https://ntfy.example:65535/up/rotated-456?up=1", rotated)
        assertEquals(rotated, store.currentEndpoint())
    }

    @Test
    fun onUnregisteredClearsTheEndpointAndRetiresTheMatchingConnectorToken() {
        val token = "instance-token-2"
        val endpoint = "https://up.example/token-2?up=1"
        val store = FakePushStore(token)
        assertEquals(endpoint, UnifiedPushRegistrar.onNewEndpoint(store, token, endpoint))

        assertFalse(UnifiedPushRegistrar.onUnregistered(store, "wrong-token"))
        assertEquals(endpoint, store.currentEndpoint())

        assertTrue(UnifiedPushRegistrar.onUnregistered(store, token))
        assertNull(store.currentEndpoint())
        assertNull(store.currentToken())
        assertNull(UnifiedPushRegistrar.onNewEndpoint(store, token, endpoint))
        assertNull(store.currentEndpoint())
    }

    @Test
    fun blueBubblesDeviceRegistrationUsesTheLatestConnectionAndPropagatesHttpFailure() = runBlocking {
        val store = FakePushStore("tok-77")
        val endpoint = checkNotNull(
            UnifiedPushRegistrar.onNewEndpoint(store, "tok-77", "https://push.example/up/77?up=1")
        )

        var revision = 1L
        var password = "secret-one"
        val client = BlueBubblesClient(
            endpointProvider = {
                EndpointSnapshot(
                    url = server.url("/").toString(),
                    password = PasswordRead.Available(password),
                    revision = revision
                )
            }
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"message":"Success"}"""))
        assertTrue(client.registerPushDevice("BaoTime Audit", endpoint))
        val firstReq = server.takeRequest()
        assertTrue(firstReq.path.orEmpty().contains("password=secret-one"))
        assertTrue(firstReq.body.readUtf8().contains("\"identifier\":\"$endpoint\""))

        // User updates BlueBubbles password in Settings (revision 2): stored endpoint re-syncs to new server config
        revision = 2L
        password = "secret-two"
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"message":"Success"}"""))
        val syncedEndpoint = checkNotNull(store.currentEndpoint())
        assertTrue(client.registerPushDevice("BaoTime Audit", syncedEndpoint))
        val secondReq = server.takeRequest()
        assertTrue(secondReq.path.orEmpty().contains("password=secret-two"))
        assertFalse(client.lastRedactedRequestUrl.contains("secret-two"))
        assertTrue(client.lastRedactedRequestUrl.contains("password=[REDACTED]"))

        // Server 500 returns false without corrupting stored UnifiedPush endpoint
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":500}"""))
        assertFalse(client.registerPushDevice("BaoTime Audit", syncedEndpoint))
        assertEquals(endpoint, store.currentEndpoint())
    }
}
