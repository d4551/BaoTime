package com.baotime.app.config

import android.content.Context
import androidx.core.content.edit

/** A coherent server destination, credential state and source generation. */
data class ServerConnection(val url: String, val password: PasswordRead, val revision: Long)

/** Persisted settings for the user's own BlueBubbles server. */
class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun serverUrl(): String = prefs.getString(KEY_SERVER_URL, "")?.trim()?.trimEnd('/') ?: ""

    /** Captures the tuple under the same lock as its sole production writer. Reads never mutate it. */
    fun connection(): ServerConnection = synchronized(prefs) {
        ServerConnection(serverUrl(),
            SecureStore.decrypt(prefs.getString(KEY_SERVER_PASSWORD, "").orEmpty()),
            connectionRevision())
    }

    /** Encrypts before committing URL, password and one new source generation together. */
    fun saveConnection(url: String, password: String) {
        val encrypted = SecureStore.encrypt(password)
        synchronized(prefs) {
            prefs.edit {
                putString(KEY_SERVER_URL, url.trim().trimEnd('/'))
                putString(KEY_SERVER_PASSWORD, encrypted)
                putLong(KEY_CONNECTION_REVISION, connectionRevision() + 1L)
            }
        }
    }

    fun connectionRevision(): Long = prefs.getLong(KEY_CONNECTION_REVISION, 0L)

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
        const val KEY_FAVORITES = "favorites"

        fun favoriteKey(address: String): String =
            if (address.contains('@')) address.trim().lowercase() else address.filter { it.isDigit() }
    }
}
