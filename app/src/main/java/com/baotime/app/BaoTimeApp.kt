package com.baotime.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.baotime.app.call.CallInvite
import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.IncomingCallNotifications
import com.baotime.app.call.PushRegistrationStore
import com.baotime.app.call.SharedPreferencesPushStore
import com.baotime.app.call.UnifiedPushRegistrar
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.BlueBubblesSocket
import com.baotime.app.network.EndpointSnapshot
import com.baotime.app.network.ServerFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Application owner for process-wide FaceTime signaling state ([CallLifecycle] and [BlueBubblesSocket]).
 *
 * Keeping [callLifecycle] and [socket] in the Application process ensures `MainActivity.onDestroy`
 * only unbinds UI and never drops an active Socket.IO connection or in-flight answer/leave call.
 */
class BaoTimeApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appConfig: AppConfig by lazy { AppConfig(this) }
    val pushStore: PushRegistrationStore by lazy { SharedPreferencesPushStore(this) }
    val callHistory: CallHistory by lazy { CallHistory(this) }
    val contactsRepository: ContactsRepository by lazy { ContactsRepository(this) }
    val bbClient: BlueBubblesClient by lazy { BlueBubblesClient(appConfig) }

    private val startedActivities = AtomicInteger(0)
    private val lastNotifiedInviteGeneration = AtomicLong(-1L)

    /** True while at least one Activity in this process is in the STARTED/RESUMED window. */
    val hasStartedActivity: Boolean
        get() = startedActivities.get() > 0

    private val _historyUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val historyUpdated: SharedFlow<Unit> = _historyUpdated.asSharedFlow()

    private val _answerFailures = MutableSharedFlow<ServerFailure>(extraBufferCapacity = 8)
    val answerFailures: SharedFlow<ServerFailure> = _answerFailures.asSharedFlow()

    val callLifecycle: CallLifecycle by lazy {
        CallLifecycle(
            scope = applicationScope,
            initialConfigGeneration = appConfig.connectionRevision(),
            answerCall = { callUuid -> bbClient.answerFaceTimeCall(callUuid) },
            leaveCall = { callUuid -> bbClient.leaveFaceTimeCall(callUuid) },
            onMissedInvite = { invite, _ -> recordMissedCall(invite) },
            onAnswerFailed = { _, failure -> _answerFailures.tryEmit(failure) }
        )
    }

    val socket: BlueBubblesSocket by lazy {
        BlueBubblesSocket(
            scope = applicationScope,
            endpointProvider = {
                val conn = appConfig.connection()
                EndpointSnapshot(conn.url, conn.password, conn.revision)
            },
            pingCheck = { bbClient.pingServer() },
            callLifecycle = callLifecycle
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.incrementAndGet() == 1) {
                    IncomingCallNotifications.cancelAllCallNotifications(this@BaoTimeApp)
                }
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                val remaining = startedActivities.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                if (remaining == 0) {
                    (callLifecycle.state.value as? CallLifecycleState.Ringing)?.let { ringing ->
                        notifyBackgroundInviteIfNeeded(ringing.invite)
                    }
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        applicationScope.launch {
            callLifecycle.state.collect { state ->
                when (state) {
                    is CallLifecycleState.Ringing -> notifyBackgroundInviteIfNeeded(state.invite)
                    is CallLifecycleState.Answering -> Unit
                    is CallLifecycleState.JoiningUrl,
                    is CallLifecycleState.Connecting,
                    is CallLifecycleState.InRoom,
                    is CallLifecycleState.Declining,
                    is CallLifecycleState.Terminal,
                    CallLifecycleState.Idle -> {
                        IncomingCallNotifications.cancelAllCallNotifications(this@BaoTimeApp)
                    }
                }
            }
        }
    }

    /** Posts a background `CATEGORY_CALL` notification at most once per [CallInvite.inviteGeneration]. */
    fun notifyBackgroundInviteIfNeeded(invite: CallInvite) {
        val uuid = invite.callUuid ?: return
        if (hasStartedActivity) return
        while (true) {
            val previous = lastNotifiedInviteGeneration.get()
            if (previous >= invite.inviteGeneration) return
            if (lastNotifiedInviteGeneration.compareAndSet(previous, invite.inviteGeneration)) {
                IncomingCallNotifications.postUuidInvite(this, uuid, invite.caller)
                return
            }
        }
    }

    /** Discovers an installed UnifiedPush distributor and re-syncs any stored endpoint with BlueBubbles. */
    fun syncPushRegistration() {
        UnifiedPushRegistrar.registerWithDistributorIfAvailable(this, pushStore)
        val storedEndpoint = pushStore.currentEndpoint()
        if (!storedEndpoint.isNullOrBlank()) {
            onPushEndpointRegistered(storedEndpoint)
        }
    }

    /** Registers a UnifiedPush or FCM endpoint token with the configured BlueBubbles server (`Task 11`). */
    fun onPushEndpointRegistered(endpoint: String) {
        pushStore.saveEndpoint(endpoint)
        applicationScope.launch {
            registerPushEndpointNow(endpoint)
        }
    }

    /** Suspending registration entrypoint used by `IncomingPushReceiver` (`goAsync`) and background sync. */
    suspend fun registerPushEndpointNow(endpoint: String): Boolean {
        val cleanEndpoint = endpoint.trim()
        if (cleanEndpoint.isEmpty()) return false
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { getString(R.string.app_name) }
        return bbClient.registerPushDevice(deviceName, cleanEndpoint)
    }

    private suspend fun recordMissedCall(invite: CallInvite) {
        val rawCaller = invite.caller.ifBlank { getString(R.string.default_call_label) }
        val resolvedName = contactsRepository.lookupContactName(rawCaller) ?: rawCaller
        callHistory.recordCall(
            handleOrNumber = rawCaller,
            displayName = resolvedName,
            faceTimeUrl = invite.faceTimeUrl,
            callType = BaoCallRecord.TYPE_MISSED
        )
        _historyUpdated.tryEmit(Unit)
    }
}
