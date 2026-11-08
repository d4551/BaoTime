package com.baotime.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordFailure
import com.baotime.app.config.PasswordRead
import com.baotime.app.config.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore invariants; run only on the dedicated disposable audit AVD. */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val config = AppConfig(context)
    private var originalPreferences: Map<String, *> = emptyMap<String, Any>()
    private var fixtureRetained = false

    @Before fun retainFixtureConfiguration() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
        originalPreferences = preferences.all.toMap()
        fixtureRetained = true
    }

    @After fun restoreFixtureConfiguration() {
        if (!fixtureRetained) return
        preferences.edit {
            clear()
            originalPreferences.forEach { (key, value) -> restoreValue(this, key, value) }
        }
    }

    @Test fun writesVersionedAuthenticatedCiphertextAndReadsWithoutMutation() {
        val previousRevision = config.connectionRevision()
        config.saveConnection(config.serverUrl(), "audit-password-'\\\n")
        val stored = preferences.getString(AppConfig.KEY_SERVER_PASSWORD, "").orEmpty()
        assertTrue(stored.startsWith("v1:"))
        assertFalse(stored.contains("audit-password"))
        assertEquals(previousRevision + 1L, config.connectionRevision())
        assertEquals(PasswordRead.Available("audit-password-'\\\n"), config.connection().password)
        assertEquals(stored, preferences.getString(AppConfig.KEY_SERVER_PASSWORD, null))
        assertEquals(previousRevision + 1L, config.connectionRevision())
    }

    @Test fun legacyEncryptedPasswordAuthenticatesWithoutRewriting() {
        val legacy = SecureStore.encrypt("audit-legacy-password").removePrefix("v1:")
        preferences.edit { putString(AppConfig.KEY_SERVER_PASSWORD, legacy) }
        val revision = config.connectionRevision()
        assertEquals(PasswordRead.Available("audit-legacy-password"), config.connection().password)
        assertEquals(legacy, preferences.getString(AppConfig.KEY_SERVER_PASSWORD, null))
        assertEquals(revision, config.connectionRevision())
    }

    @Test fun failedAuthenticationNeverBecomesPlaintextOrReencryptsStoredBytes() {
        val encrypted = SecureStore.encrypt("audit-original")
        val bytes = Base64.decode(encrypted.removePrefix("v1:"), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val corrupt = "v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        preferences.edit { putString(AppConfig.KEY_SERVER_PASSWORD, corrupt) }
        val revision = config.connectionRevision()
        assertEquals(PasswordRead.Unavailable(PasswordFailure.AuthenticationFailed), config.connection().password)
        assertEquals(corrupt, preferences.getString(AppConfig.KEY_SERVER_PASSWORD, null))
        assertEquals(revision, config.connectionRevision())
    }

    @Test fun unknownEnvelopeAndPlaintextRequireExplicitReplacement() {
        val unsupported = "v9:" + Base64.encodeToString(ByteArray(32), Base64.NO_WRAP)
        preferences.edit { putString(AppConfig.KEY_SERVER_PASSWORD, unsupported) }
        assertEquals(PasswordRead.Unavailable(PasswordFailure.UnsupportedVersion), config.connection().password)
        assertEquals(unsupported, preferences.getString(AppConfig.KEY_SERVER_PASSWORD, null))
        val plaintext = "legacy plaintext password"
        preferences.edit { putString(AppConfig.KEY_SERVER_PASSWORD, plaintext) }
        assertEquals(PasswordRead.Unavailable(PasswordFailure.InvalidEncoding), config.connection().password)
        assertEquals(plaintext, preferences.getString(AppConfig.KEY_SERVER_PASSWORD, null))
        config.saveConnection(config.serverUrl(), "audit-reentered")
        assertEquals(PasswordRead.Available("audit-reentered"), config.connection().password)
    }

    @Test fun absentPasswordIsDistinctFromUnavailableAndUrlWritesAdvanceRevision() {
        preferences.edit { remove(AppConfig.KEY_SERVER_PASSWORD) }
        assertEquals(PasswordRead.Missing, config.connection().password)
        val revision = config.connectionRevision()
        config.saveConnection(" https://audit.example.invalid/ ", "audit-url-password")
        assertEquals("https://audit.example.invalid", config.serverUrl())
        assertEquals(revision + 1L, config.connectionRevision())
    }

    @Test fun connectionSnapshotsKeepUrlCredentialAndRevisionTogether() {
        config.saveConnection("https://first.audit.invalid", "first-audit-password")
        val first = config.connection()
        config.saveConnection("https://second.audit.invalid/", "second-audit-password")
        val second = config.connection()
        assertEquals("https://first.audit.invalid", first.url)
        assertEquals(PasswordRead.Available("first-audit-password"), first.password)
        assertEquals("https://second.audit.invalid", second.url)
        assertEquals(PasswordRead.Available("second-audit-password"), second.password)
        assertEquals(first.revision + 1L, second.revision)
        assertEquals(second.revision, config.connectionRevision())
    }

    @Test fun concurrentEncryptionsRetainOneReadableKeyOwner() = runBlocking {
        val encrypted = (0 until 12).map { index ->
            async(Dispatchers.IO) { SecureStore.encrypt("audit-concurrent-$index") to "audit-concurrent-$index" }
        }.awaitAll()
        encrypted.forEach { (encoded, plain) -> assertEquals(PasswordRead.Available(plain), SecureStore.decrypt(encoded)) }
        assertEquals(encrypted.size, encrypted.map { it.first }.toSet().size)
    }

    private fun restoreValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> {
                require(value.all { it is String })
                editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            else -> error("Unsupported preference fixture type")
        }
    }
}
