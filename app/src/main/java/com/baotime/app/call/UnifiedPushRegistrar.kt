package com.baotime.app.call

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import com.baotime.app.network.ServerUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

/** Storage contract for UnifiedPush connector registration state. */
interface PushRegistrationStore {
    fun currentToken(): String?
    fun currentEndpoint(): String?
    fun saveEndpoint(endpoint: String)
    fun clearEndpoint()
}

/** Dedicated `baotime_push` preferences store so `AppConfig.PREFS_NAME` remains isolated. */
class SharedPreferencesPushStore(context: Context) : PushRegistrationStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val endpoint = MutableStateFlow(currentEndpoint()?.let(UnifiedPushRegistrar::isNtfyEndpoint) == true)
    internal val endpointAvailable = endpoint.asStateFlow()

    internal data class Binding(val distributor: String, val token: String)

    internal data class BindingChange(val current: Binding?, val previous: Binding?)

    internal fun bind(macGeneration: Long, distributor: String?): BindingChange = synchronized(prefs) {
        val previous = currentDistributor()?.let { packageName -> currentToken()?.let { Binding(packageName, it) } }
        if (distributor != null && distributor == previous?.distributor && isCurrentMac(macGeneration)) {
            return@synchronized BindingChange(previous, null)
        }
        val current = distributor?.let { Binding(it, UUID.randomUUID().toString()) }
        prefs.edit {
            remove(KEY_ENDPOINT)
            putLong(KEY_MAC_GENERATION, macGeneration)
            putString(KEY_INSTANCE_TOKEN, current?.token)
            putString(KEY_DISTRIBUTOR_PACKAGE, current?.distributor)
        }
        endpoint.value = false
        BindingChange(current, previous)
    }

    internal fun handleBoundCallback(macGeneration: Long, token: String?, handle: () -> Unit) = synchronized(prefs) {
        if (isCurrentMac(macGeneration) && currentDistributor() == UnifiedPushRegistrar.NTFY_PACKAGE &&
            UnifiedPushRegistrar.acceptsConnectorToken(currentToken(), token)) handle()
    }

    internal fun boundEndpoint(macGeneration: Long): String? = synchronized(prefs) {
        if (!isCurrentMac(macGeneration) || currentDistributor() != UnifiedPushRegistrar.NTFY_PACKAGE || currentToken() == null) {
            return@synchronized null
        }
        currentEndpoint()?.takeIf(UnifiedPushRegistrar::isNtfyEndpoint)
    }

    private fun isCurrentMac(macGeneration: Long): Boolean = prefs.getLong(KEY_MAC_GENERATION, -1L) == macGeneration

    override fun currentToken(): String? =
        prefs.getString(KEY_INSTANCE_TOKEN, null)?.takeIf { it.isNotBlank() }

    override fun currentEndpoint(): String? =
        prefs.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotEmpty() }

    override fun saveEndpoint(endpoint: String) {
        if (!UnifiedPushRegistrar.isNtfyEndpoint(endpoint)) return
        synchronized(prefs) {
            prefs.edit { putString(KEY_ENDPOINT, endpoint) }
            this.endpoint.value = true
        }
    }

    override fun clearEndpoint() {
        synchronized(prefs) {
            prefs.edit {
                remove(KEY_INSTANCE_TOKEN)
                remove(KEY_ENDPOINT)
            }
            endpoint.value = false
        }
    }

    private fun currentDistributor(): String? =
        prefs.getString(KEY_DISTRIBUTOR_PACKAGE, null)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val PREFS_NAME = "baotime_push"
        private const val KEY_INSTANCE_TOKEN = "instance_token"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MAC_GENERATION = "mac_generation"
        private const val KEY_DISTRIBUTOR_PACKAGE = "distributor_package"
    }
}

/** Registers the phone's current Mac binding with the ntfy UnifiedPush distributor. */
object UnifiedPushRegistrar {

    internal const val NTFY_PACKAGE = "io.heckel.ntfy"
    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PI = "pi"

    /** Exported connector actions require the token generated before sending REGISTER. */
    internal fun acceptsConnectorToken(storedToken: String?, incomingToken: String?): Boolean =
        !storedToken.isNullOrBlank() && incomingToken == storedToken

    /** Persists a valid `NEW_ENDPOINT` URL for the exact registered connector token. */
    internal fun onNewEndpoint(store: PushRegistrationStore, incomingToken: String?, rawEndpoint: String?): String? {
        if (!acceptsConnectorToken(store.currentToken(), incomingToken)) return null
        val endpoint = rawEndpoint?.takeIf(::isNtfyEndpoint) ?: return null
        store.saveEndpoint(endpoint)
        return endpoint
    }

    /** Clears stored endpoint state on `UNREGISTERED` or `REGISTRATION_FAILED` when the token matches. */
    internal fun onUnregistered(store: PushRegistrationStore, incomingToken: String?): Boolean {
        if (!acceptsConnectorToken(store.currentToken(), incomingToken)) return false
        store.clearEndpoint()
        return true
    }

    internal fun isNtfyEndpoint(endpoint: String): Boolean {
        val url = runCatching { URI(endpoint) }.getOrElse { outcome ->
            if (outcome !is URISyntaxException) throw outcome
            return false
        }
        return url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() &&
            url.rawUserInfo == null && ServerUrlPolicy.hasValidHttpPort(url) &&
            url.rawFragment == null && !url.rawPath.isNullOrBlank() &&
            url.rawPath != "/" && url.rawQuery == "up=1"
    }

    internal fun registerWithDistributorIfAvailable(context: Context, store: SharedPreferencesPushStore,
        macGeneration: Long, macConfigured: Boolean) {
        val distributor = if (macConfigured) {
            val query = Intent(ACTION_REGISTER).setPackage(NTFY_PACKAGE)
            context.packageManager.queryBroadcastReceivers(query, 0).firstOrNull()?.activityInfo?.packageName
        } else null
        val change = store.bind(macGeneration, distributor)
        change.previous?.takeIf { it.distributor == NTFY_PACKAGE }?.let { send(context, ACTION_UNREGISTER, it) }
        change.current?.let { send(context, ACTION_REGISTER, it) }
    }

    private fun send(context: Context, action: String, binding: SharedPreferencesPushStore.Binding) {
        val intent = Intent(action).setPackage(binding.distributor).putExtra(EXTRA_TOKEN, binding.token)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
            context.sendBroadcast(intent, null, options.toBundle())
        } else {
            val verification = PendingIntent.getBroadcast(context, 0,
                Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            context.sendBroadcast(intent.putExtra(EXTRA_PI, verification))
        }
    }
}
