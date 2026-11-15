package com.baotime.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import com.baotime.app.call.CallInvite
import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.CallSignaling
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.call.IncomingCallNotifications
import com.baotime.app.call.PushRegistrationStore
import com.baotime.app.call.SharedPreferencesPushStore
import com.baotime.app.call.UnifiedPushRegistrar
import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordRead
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.BlueBubblesServerStatus
import com.baotime.app.network.BlueBubblesSocket
import com.baotime.app.network.EndpointSnapshot
import com.baotime.app.network.ServerCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application owner for process-wide FaceTime signaling state ([CallLifecycle] and [BlueBubblesSocket]).
 *
 * Keeping [callLifecycle] and [socket] in the Application process ensures `MainActivity.onDestroy`
 * only unbinds UI and never drops an active Socket.IO connection or in-flight answer/leave call.
 */
class BaoTimeApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appConfig: AppConfig by lazy { AppConfig(this) }
    private val storedPush by lazy { SharedPreferencesPushStore(this) }
    val pushStore: PushRegistrationStore get() = storedPush
    internal val pushEndpointAvailable get() = storedPush.endpointAvailable
    internal fun hasPushEndpoint(): Boolean = appConfig.admitConnectionOperation(appConfig.connectionRevision()) {
        storedPush.boundEndpoint(appConfig.macBindingGeneration()) != null
    }

    internal fun copyPushWebhook(copy: (String) -> Boolean): Boolean =
        appConfig.admitConnectionOperation(appConfig.connectionRevision()) {
            val endpoint = storedPush.boundEndpoint(appConfig.macBindingGeneration()) ?: return@admitConnectionOperation false
            copy("$endpoint&cache=no")
        }
    val callHistory: CallHistory by lazy { CallHistory(this) }
    val contactsRepository: ContactsRepository by lazy { ContactsRepository(this) }
    val bbClient: BlueBubblesClient by lazy { BlueBubblesClient(appConfig) }
    internal val serverBanner: ServerBannerCoordinator by lazy { ServerBannerCoordinator() }
    internal val permissionConsent: PermissionConsent by lazy { PermissionConsent() }

    private val startedActivities = AtomicInteger(0)
    private var lastNotifiedInviteGeneration = -1L
    private var postedInvite: CallInvite? = null

    /** True while at least one Activity in this process is in the STARTED/RESUMED window. */
    val hasStartedActivity: Boolean
        get() = startedActivities.get() > 0

    val callLifecycle: CallLifecycle by lazy {
        val (revision, mac) = appConfig.connectionIdentity()
        CallLifecycle(
            scope = applicationScope,
            initialConfigGeneration = revision,
            signaling = object : CallSignaling {
                override suspend fun answer(callUuid: String, connectionRevision: Long) =
                    bbClient.answerFaceTimeCall(callUuid, connectionRevision)

                override suspend fun leave(callUuid: String, connectionRevision: Long) =
                    bbClient.leaveFaceTimeCall(callUuid, connectionRevision)
            },
            onMissedInvite = { invite, _ -> recordMissedCall(invite) }
        ).apply { onConfigChanged(revision, mac) }
    }

    // Keystore decryption never runs on the caller's (main) thread. The socket's endpoint
    // provider serves the last decrypted snapshot; a revision change triggers an IO refresh
    // that reconnects the socket once the fresh credentials are available.
    @Volatile
    private var endpointSnapshot: EndpointSnapshot? = null
    private val endpointRefreshInFlight = AtomicBoolean(false)

    private fun refreshEndpointSnapshot() {
        // One decrypt at a time: repeated onResume calls must not queue duplicate Keystore reads.
        if (!endpointRefreshInFlight.compareAndSet(false, true)) return
        applicationScope.launch(Dispatchers.IO) {
            val conn = appConfig.connection()
            ensureActive()
            endpointSnapshot = EndpointSnapshot(conn.url, conn.password, conn.revision, conn.macGeneration)
            socket.ensureConnected()
        }.invokeOnCompletion { failure ->
            // Completion also runs when cancellation prevents the coroutine body from starting.
            endpointRefreshInFlight.set(false)
            if (failure == null && endpointSnapshot?.revision != appConfig.connectionRevision()) {
                refreshEndpointSnapshot()
            }
        }
    }

    val socket: BlueBubblesSocket by lazy {
        BlueBubblesSocket(
            scope = applicationScope,
            endpointProvider = {
                val (revision, mac) = appConfig.connectionIdentity()
                val cached = endpointSnapshot
                if (cached != null && cached.revision == revision) {
                    cached
                } else {
                    // Never hand back a stale revision: the socket would cancel lifecycle work
                    // against an obsolete generation. Report the current revision with no
                    // credentials until the IO refresh publishes the fresh snapshot.
                    refreshEndpointSnapshot()
                    EndpointSnapshot("", PasswordRead.Missing, revision, mac)
                }
            },
            pingEndpoint = bbClient::pingServer,
            callLifecycle = callLifecycle
        )
    }

    override fun onCreate() {
        super.onCreate()
        val startupRevision = appConfig.connectionRevision()
        applicationScope.launch {
            appConfig.connectionRevisions.collect { revision ->
                if (revision > startupRevision) {
                    val (currentRevision, mac) = appConfig.connectionIdentity()
                    socket.onConfigChanged(currentRevision, mac)
                    syncPushRegistration()
                }
            }
        }
        // Debug builds only: lets accessibility tooling (axe over CDP) inspect the room WebView.
        // Release builds keep WebView contents undebuggable.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.incrementAndGet() == 1) {
                    IncomingCallNotifications.cancelAllCallNotifications(this@BaoTimeApp)
                    postedInvite = null
                }
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                val remaining = startedActivities.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                if (remaining == 0) reconcileBackgroundInvite()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        applicationScope.launch(Dispatchers.Main) {
            callLifecycle.state.collect { reconcileBackgroundInvite() }
        }
    }

    /** Runs on main with Activity callbacks so foreground cancellation cannot overtake a post. */
    private fun reconcileBackgroundInvite() {
        val ringing = callLifecycle.state.value
        if (hasStartedActivity || ringing !is CallLifecycleState.Ringing || ringing.invite.callUuid == null) {
            postedInvite?.callUuid?.let { IncomingCallNotifications.cancelUuidInvite(this, it) }
            postedInvite = null
            return
        }
        val invite = ringing.invite
        if (postedInvite?.inviteGeneration == invite.inviteGeneration) return
        postedInvite?.callUuid?.let { IncomingCallNotifications.cancelUuidInvite(this, it) }
        postedInvite = null
        val uuid = requireNotNull(invite.callUuid)
        val alreadyAlerted = invite.inviteGeneration == lastNotifiedInviteGeneration
        val posted = IncomingCallNotifications.postUuidInvite(this, uuid, invite.caller, alreadyAlerted) {
            callLifecycle.state.value == ringing && appConfig.connectionRevision() == invite.configGeneration
        }
        if (posted) {
            lastNotifiedInviteGeneration = invite.inviteGeneration
            postedInvite = invite
        }
    }

    fun syncPushRegistration() {
        appConfig.admitConnectionOperation(appConfig.connectionRevision()) {
            UnifiedPushRegistrar.registerWithDistributorIfAvailable(this, storedPush, appConfig.macBindingGeneration(),
                appConfig.serverUrl().isNotEmpty())
            true
        }
    }

    internal fun onPushCallback(token: String?, message: Boolean, handle: (Long) -> Unit) {
        val revision = appConfig.connectionRevision()
        appConfig.admitConnectionOperation(revision) {
            val mac = appConfig.macBindingGeneration()
            storedPush.handleBoundCallback(mac, token) {
                if (!message || storedPush.boundEndpoint(mac) != null) {
                    if (message) callLifecycle.onConfigChanged(revision, mac)
                    handle(revision)
                }
            }
            true
        }
    }

    private suspend fun recordMissedCall(invite: CallInvite) {
        val rawCaller = invite.caller.ifBlank { getString(R.string.default_call_label) }
        val resolvedName = contactsRepository.lookupContactName(rawCaller) ?: rawCaller
        callHistory.recordMissedCall(
            handle = rawCaller,
            displayName = resolvedName,
            url = invite.faceTimeUrl
        )
    }

    /**
     * Revision-gated server banner orchestration. Chrome binds [ServerBanner] tones to theme
     * color tokens; outgoing-call presentation stays with the Activity's call-link controller.
     */
    internal inner class ServerBannerCoordinator {
        val state: StateFlow<ServerBanner> by lazy {
            appConfig.connectionRevisions.combine(bbClient.serverStatuses) { _, _ -> current() }
                .stateIn(applicationScope, SharingStarted.WhileSubscribed(), current())
        }

        /** Re-verifies a saved server and returns the observation for the committed revision. */
        suspend fun onConfigSaved(cancelOutgoing: () -> Unit): BlueBubblesServerStatus {
            cancelOutgoing()
            val status = bbClient.checkServerStatus()
            return currentStatus() ?: status
        }

        private fun current(): ServerBanner {
            val status = currentStatus()
            return when {
                status == null -> ServerBanner(R.string.banner_checking, ServerBanner.Tone.Checking)
                status.isFaceTimeCapable -> ServerBanner(R.string.banner_connected, ServerBanner.Tone.Connected)
                else -> ServerBanner(ServerCapability.bannerResource(status.failure), ServerBanner.Tone.Fault)
            }
        }

        private fun currentStatus(): BlueBubblesServerStatus? =
            bbClient.serverStatuses.value?.takeIf { it.revision == appConfig.connectionRevision() }?.status
    }

    /**
     * Notification-consent decisions and the persisted blocked flag, owned process-wide.
     * The Activity keeps the result launchers, the rationale probe and the settings jump,
     * which require an Activity context.
     */
    internal inner class PermissionConsent {
        private var notificationRequested = false

        fun restoreNotificationRequested(requested: Boolean) {
            notificationRequested = requested
        }

        fun notificationRequested(): Boolean = notificationRequested

        /** Persists permanent denial; a shown rationale means the user can still be asked. */
        fun onNotificationResult(granted: Boolean, showRationale: () -> Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appConfig.setNotificationPermissionBlocked(!granted && !showRationale())
            }
        }

        /** Clears a stale blocked flag once the grant exists (user enabled notifications in settings). */
        fun syncBlockedFlag() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                appConfig.notificationPermissionBlocked()
            ) {
                appConfig.setNotificationPermissionBlocked(false)
            }
        }

        fun requestNotification(showRationale: () -> Boolean, requestConsent: (() -> Unit) -> Boolean, launch: () -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationRequested &&
                canRequestNotification(showRationale) &&
                IncomingCallListenerService.hasNotificationAccess(this@BaoTimeApp) &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                requestConsent(launch)
            ) {
                notificationRequested = true
            }
        }

        /** True when consent sequencing handled the alert-controls request; false opens system settings. */
        fun requestCallAlertConsent(showRationale: () -> Boolean, requestConsent: (() -> Unit) -> Boolean, launch: () -> Unit): Boolean {
            val permissionDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (permissionDenied && canRequestNotification(showRationale)) {
                if (requestConsent(launch)) notificationRequested = true
                return true
            }
            return false
        }

        private fun canRequestNotification(showRationale: () -> Boolean): Boolean =
            !appConfig.notificationPermissionBlocked() || showRationale()
    }
}

/** Server-banner presentation; chrome maps each tone to its theme color token. */
internal data class ServerBanner(val messageRes: Int, val tone: Tone) {
    enum class Tone { Checking, Connected, Fault }
}
