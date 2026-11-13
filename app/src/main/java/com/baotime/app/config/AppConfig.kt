package com.baotime.app.config

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A coherent server destination, credential state and source generation. */
data class ServerConnection(
    val url: String, val password: PasswordRead, val revision: Long, val macGeneration: Long
)

/** Persisted settings for the user's own BlueBubbles server. */
class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val committedRevision = MutableStateFlow(connectionRevision())
    private var pendingSave: ConnectionSave? = null
    val connectionRevisions = committedRevision.asStateFlow()

    fun serverUrl(): String = prefs.getString(KEY_SERVER_URL, "")?.trim()?.trimEnd('/') ?: ""

    /** Captures the tuple under the same lock as its sole production writer. Reads never mutate it. */
    fun connection(): ServerConnection = synchronized(prefs) {
        ServerConnection(serverUrl(),
            SecureStore.decrypt(prefs.getString(KEY_SERVER_PASSWORD, "").orEmpty()),
            connectionRevision(), macBindingGeneration())
    }

    /** Encrypts before committing URL, password and one new source generation together. */
    fun saveConnection(url: String, password: String) {
        saveConnection(beginConnectionSave(), url, password)
    }

    internal class ConnectionSave

    internal fun beginConnectionSave(): ConnectionSave = synchronized(prefs) {
        ConnectionSave().also { pendingSave = it }
    }

    internal fun saveConnection(request: ConnectionSave, url: String, password: String): Long? {
        val encrypted = SecureStore.encrypt(password)
        return synchronized(prefs) {
            if (pendingSave !== request) return@synchronized null
            val revision = connectionRevision() + 1L
            val server = url.trim().trimEnd('/')
            val macGeneration = macBindingGeneration() + if (server == serverUrl()) 0L else 1L
            prefs.edit {
                putString(KEY_SERVER_URL, server)
                putString(KEY_SERVER_PASSWORD, encrypted)
                putLong(KEY_CONNECTION_REVISION, revision)
                putLong(KEY_MAC_BINDING_GENERATION, macGeneration)
            }
            pendingSave = null
            committedRevision.value = revision
            revision
        }
    }

    /** Serializes room admission with connection saves so a stale server cannot publish a room. */
    fun admitConnectionOperation(revision: Long, admission: () -> Boolean): Boolean = synchronized(prefs) {
        connectionRevision() == revision && admission()
    }

    fun connectionRevision(): Long = prefs.getLong(KEY_CONNECTION_REVISION, 0L)

    internal fun macBindingGeneration(): Long = prefs.getLong(KEY_MAC_BINDING_GENERATION, 0L)

    internal fun connectionIdentity(): Pair<Long, Long> = synchronized(prefs) {
        connectionRevision() to macBindingGeneration()
    }

    internal fun connectionDestination(): Pair<String, Long> = synchronized(prefs) {
        serverUrl() to connectionRevision()
    }

    fun notificationPermissionBlocked(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_BLOCKED, false)

    fun setNotificationPermissionBlocked(blocked: Boolean) = prefs.edit {
        if (blocked) putBoolean(KEY_NOTIFICATION_PERMISSION_BLOCKED, true) else remove(KEY_NOTIFICATION_PERMISSION_BLOCKED)
    }

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    fun toggleFavorite(addressKey: String) {
        val updated = favorites().toMutableSet().apply {
            if (!add(addressKey)) remove(addressKey)
        }
        prefs.edit { putStringSet(KEY_FAVORITES, updated) }
    }

    companion object {
        const val PREFS_NAME = "baotime_config"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SERVER_PASSWORD = "server_password"
        private const val KEY_CONNECTION_REVISION = "connection_revision"
        private const val KEY_MAC_BINDING_GENERATION = "mac_binding_generation"
        private const val KEY_NOTIFICATION_PERMISSION_BLOCKED = "notification_permission_blocked"
        const val KEY_FAVORITES = "favorites"

        fun favoriteKey(address: String): String =
            if (address.contains('@')) address.trim().lowercase() else address.filter { it.isDigit() }
    }
}
