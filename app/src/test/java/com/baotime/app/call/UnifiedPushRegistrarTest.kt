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
import java.util.UUID

class UnifiedPushRegistrarTest {

    private lateinit var server: MockWebServer

    private class FakePushStore : PushRegistrationStore {
        private var token: String? = null
        private var endpoint: String? = null
        private var distributor: String? = null

        override fun currentToken(): String? = token

        override fun getOrCreateToken(): String {
            val current = token
            if (!current.isNullOrBlank()) return current
            val generated = UUID.randomUUID().toString()
            token = generated
            return generated
        }

        override fun adoptTokenIfAbsent(token: String) {
            val clean = token.trim()
            if (clean.isNotEmpty() && this.token == null) {
                this.token = clean
            }
        }

        override fun currentEndpoint(): String? = endpoint

        override fun saveEndpoint(endpoint: String) {
            val clean = endpoint.trim()
            if (clean.isNotEmpty()) {
                this.endpoint = clean
            }
        }

        override fun clearEndpoint() {
            endpoint = null
        }

        override fun currentDistributor(): String? = distributor

        override fun saveDistributor(packageName: String?) {
            distributor = packageName?.trim()?.takeIf { it.isNotEmpty() }
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
    fun selectDistributor_excludesSelfPrefersBoundAndFallsBackToFirstAvailable() {
        assertNull(
            UnifiedPushRegistrar.selectDistributor(
                candidates = listOf("com.baotime.app", " "),
                selfPackage = "com.baotime.app",
                preferredDistributor = null
            )
        )
        assertEquals(
            "io.heckel.ntfy",
            UnifiedPushRegistrar.selectDistributor(
                candidates = listOf("com.baotime.app", "org.unifiedpush.distributor.sunup", "io.heckel.ntfy"),
                selfPackage = "com.baotime.app",
                preferredDistributor = "io.heckel.ntfy"
            )
        )
        assertEquals(
            "org.unifiedpush.distributor.sunup",
            UnifiedPushRegistrar.selectDistributor(
                candidates = listOf("org.unifiedpush.distributor.sunup", "io.heckel.ntfy"),
                selfPackage = "com.baotime.app",
                preferredDistributor = "uninstalled.distributor"
            )
        )
    }

    @Test
    fun onNewEndpoint_adoptsTokenValidatesMatchAndRejectsRogueSender() {
        val store = FakePushStore()
        val accepted = UnifiedPushRegistrar.onNewEndpoint(
            store = store,
            incomingToken = "instance-token-1",
            rawEndpoint = "  https://ntfy.example/up/abc123?up=1  "
        )
        assertEquals("https://ntfy.example/up/abc123?up=1", accepted)
        assertEquals("instance-token-1", store.currentToken())
        assertEquals("https://ntfy.example/up/abc123?up=1", store.currentEndpoint())

        // Mismatched token must not overwrite the active endpoint
        val rejectedRogue = UnifiedPushRegistrar.onNewEndpoint(
            store = store,
            incomingToken = "rogue-token-999",
            rawEndpoint = "https://attacker.invalid/up"
        )
        assertNull(rejectedRogue)
        assertEquals("https://ntfy.example/up/abc123?up=1", store.currentEndpoint())

        // Matching token updates rotated endpoint cleanly
        val rotated = UnifiedPushRegistrar.onNewEndpoint(
            store = store,
            incomingToken = "instance-token-1",
            rawEndpoint = "https://ntfy.example/up/rotated-456?up=1"
        )
        assertEquals("https://ntfy.example/up/rotated-456?up=1", rotated)
        assertEquals("https://ntfy.example/up/rotated-456?up=1", store.currentEndpoint())
    }

    @Test
    fun onUnregistered_clearsEndpointOnlyWhenTokenMatchesOrIsSingleInstance() {
        val store = FakePushStore()
        UnifiedPushRegistrar.onNewEndpoint(store, "instance-token-2", "https://up.example/token-2")

        assertFalse(UnifiedPushRegistrar.onUnregistered(store, "wrong-token"))
        assertEquals("https://up.example/token-2", store.currentEndpoint())

        assertTrue(UnifiedPushRegistrar.onUnregistered(store, "instance-token-2"))
        assertNull(store.currentEndpoint())

        UnifiedPushRegistrar.onNewEndpoint(store, null, "https://up.example/default")
        assertTrue(UnifiedPushRegistrar.onUnregistered(store, null))
        assertNull(store.currentEndpoint())
    }

    @Test
    fun persistedEndpoint_reRegistersWhenServerConfigChangesAndPropagatesHttpFailure() = runBlocking {
        val store = FakePushStore()
        val endpoint = checkNotNull(
            UnifiedPushRegistrar.onNewEndpoint(store, "tok-77", "https://push.example/up/77")
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
        assertTrue(firstReq.body.readUtf8().contains("\"identifier\":\"https://push.example/up/77\""))

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
