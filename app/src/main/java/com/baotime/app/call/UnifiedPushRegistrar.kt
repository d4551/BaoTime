package com.baotime.app.call

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.util.UUID

/** Storage contract for UnifiedPush connector registration state (`Task 11`). */
interface PushRegistrationStore {
    fun currentToken(): String?
    fun getOrCreateToken(): String
    fun adoptTokenIfAbsent(token: String)
    fun currentEndpoint(): String?
    fun saveEndpoint(endpoint: String)
    fun clearEndpoint()
    fun currentDistributor(): String?
    fun saveDistributor(packageName: String?)
}

/** Dedicated `baotime_push` preferences store so `AppConfig.PREFS_NAME` remains isolated. */
class SharedPreferencesPushStore(context: Context) : PushRegistrationStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun currentToken(): String? =
        prefs.getString(KEY_INSTANCE_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }

    override fun getOrCreateToken(): String = synchronized(lock) {
        currentToken() ?: UUID.randomUUID().toString().also { generated ->
            prefs.edit { putString(KEY_INSTANCE_TOKEN, generated) }
        }
    }

    override fun adoptTokenIfAbsent(token: String) = synchronized(lock) {
        val clean = token.trim()
        if (clean.isNotEmpty() && currentToken() == null) {
            prefs.edit { putString(KEY_INSTANCE_TOKEN, clean) }
        }
    }

    override fun currentEndpoint(): String? =
        prefs.getString(KEY_ENDPOINT, null)?.trim()?.takeIf { it.isNotEmpty() }

    override fun saveEndpoint(endpoint: String) {
        val clean = endpoint.trim()
        if (clean.isEmpty()) return
        prefs.edit { putString(KEY_ENDPOINT, clean) }
    }

    override fun clearEndpoint() {
        prefs.edit { remove(KEY_ENDPOINT) }
    }

    override fun currentDistributor(): String? =
        prefs.getString(KEY_DISTRIBUTOR_PACKAGE, null)?.trim()?.takeIf { it.isNotEmpty() }

    override fun saveDistributor(packageName: String?) {
        val clean = packageName?.trim().orEmpty()
        prefs.edit {
            if (clean.isEmpty()) remove(KEY_DISTRIBUTOR_PACKAGE) else putString(KEY_DISTRIBUTOR_PACKAGE, clean)
        }
    }

    companion object {
        const val PREFS_NAME = "baotime_push"
        private const val KEY_INSTANCE_TOKEN = "instance_token"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_DISTRIBUTOR_PACKAGE = "distributor_package"
    }
}

/**
 * Clean-room UnifiedPush v2.1 distributor discovery, registration broadcast, and endpoint state owner.
 *
 * Discovers installed distributors handling [ACTION_REGISTER], sends a verified registration
 * broadcast carrying an immutable [EXTRA_PI] `PendingIntent`, and persists delivered endpoints
 * so saving/changing BlueBubbles credentials re-registers the active push endpoint cleanly.
 */
object UnifiedPushRegistrar {

    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_APPLICATION = "application"
    const val EXTRA_PI = "pi"
    const val EXTRA_FEATURES = "features"
    const val FEATURE_BYTES_MESSAGE = "BYTES_MESSAGE"

    /** Validates an optional incoming broadcast `token` against the stored connector token. */
    fun acceptsToken(storedToken: String?, incomingToken: String?): Boolean {
        val cleanIncoming = incomingToken?.trim().orEmpty()
        val cleanStored = storedToken?.trim().orEmpty()
        return cleanIncoming.isEmpty() || cleanStored.isEmpty() || cleanIncoming == cleanStored
    }

    /** Chooses a distributor package from resolved broadcast receivers, preferring an existing binding. */
    fun selectDistributor(
        candidates: List<String>,
        selfPackage: String,
        preferredDistributor: String?
    ): String? {
        val valid = candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfPackage }
            .distinct()
        if (valid.isEmpty()) return null
        val preferred = preferredDistributor?.trim().orEmpty()
        return if (preferred in valid) preferred else valid.first()
    }

    /** Validates and persists a newly delivered `NEW_ENDPOINT` URL, returning the normalized endpoint if accepted. */
    fun onNewEndpoint(store: PushRegistrationStore, incomingToken: String?, rawEndpoint: String?): String? {
        if (!acceptsToken(store.currentToken(), incomingToken)) return null
        val cleanEndpoint = rawEndpoint?.trim().orEmpty()
        if (cleanEndpoint.isEmpty()) return null
        if (!incomingToken.isNullOrBlank()) {
            store.adoptTokenIfAbsent(incomingToken)
        }
        store.saveEndpoint(cleanEndpoint)
        return cleanEndpoint
    }

    /** Clears stored endpoint state on `UNREGISTERED` or `REGISTRATION_FAILED` when the token matches. */
    fun onUnregistered(store: PushRegistrationStore, incomingToken: String?): Boolean {
        if (!acceptsToken(store.currentToken(), incomingToken)) return false
        store.clearEndpoint()
        return true
    }

    /** Discovers an installed UnifiedPush distributor and sends `REGISTER` if one is present. */
    fun registerWithDistributorIfAvailable(context: Context, store: PushRegistrationStore): Boolean {
        val query = Intent(ACTION_REGISTER)
        val receivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryBroadcastReceivers(
                query,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(query, 0)
        }
        val candidates = receivers.mapNotNull { it.activityInfo?.packageName }
        val distributor = selectDistributor(
            candidates = candidates,
            selfPackage = context.packageName,
            preferredDistributor = store.currentDistributor()
        )
        if (distributor == null) {
            store.saveDistributor(null)
            return false
        }
        val token = store.getOrCreateToken()
        store.saveDistributor(distributor)
        val verificationPi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE
        )
        val registerIntent = Intent(ACTION_REGISTER).apply {
            setPackage(distributor)
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_APPLICATION, context.packageName)
            putStringArrayListExtra(EXTRA_FEATURES, arrayListOf(FEATURE_BYTES_MESSAGE))
            putExtra(EXTRA_PI, verificationPi)
        }
        context.sendBroadcast(registerIntent)
        return true
    }
}
