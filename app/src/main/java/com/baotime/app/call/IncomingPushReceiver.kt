package com.baotime.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baotime.app.BaoTimeApp
import com.baotime.app.network.FaceTimePushHandler
import com.baotime.app.network.FaceTimeSessionStatus
import com.baotime.app.network.IncomingFaceTimeEvent
import kotlinx.coroutines.launch

/**
 * Clean-room UnifiedPush / push receiver for incoming FaceTime call events (`Task 11`).
 *
 * Routes push payloads through [FaceTimePushHandler] into the process-wide [CallLifecycle]
 * (`InviteSource.Fcm`), sharing the same `uuid` deduplication and `POST /api/v1/facetime/answer/:uuid`
 * path as `Task 5` and `Task 6`.
 *
 * Process-state boundary:
 * - Foreground / Background / OS process-kill: delivered when the distributor wakes the receiver.
 * - Settings Force-Stop (`am force-stop`): Android marks the package `stopped=true` and drops
 *   broadcasts/push until the user manually reopens BaoTime once.
 */
class IncomingPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? BaoTimeApp ?: return
        val incomingToken = intent.getStringExtra(UnifiedPushRegistrar.EXTRA_TOKEN)
        when (action) {
            ACTION_UNIFIED_PUSH_NEW_ENDPOINT -> {
                val endpoint = UnifiedPushRegistrar.onNewEndpoint(
                    store = app.pushStore,
                    incomingToken = incomingToken,
                    rawEndpoint = intent.getStringExtra(EXTRA_ENDPOINT)
                ) ?: return
                val pendingResult = goAsync()
                app.applicationScope.launch {
                    try {
                        app.registerPushEndpointNow(endpoint)
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
            ACTION_UNIFIED_PUSH_MESSAGE -> {
                if (!UnifiedPushRegistrar.acceptsToken(app.pushStore.currentToken(), incomingToken)) return
                val rawPayload = extractPayloadString(intent) ?: return
                val event = FaceTimePushHandler.parsePushPayload(rawPayload) ?: return
                handlePushEvent(context, app, event)
            }
            ACTION_UNIFIED_PUSH_UNREGISTERED,
            ACTION_UNIFIED_PUSH_REGISTRATION_FAILED -> {
                UnifiedPushRegistrar.onUnregistered(app.pushStore, incomingToken)
            }
        }
    }

    private fun extractPayloadString(intent: Intent): String? {
        val bytes = intent.getByteArrayExtra(EXTRA_BYTES)
        if (bytes != null && bytes.isNotEmpty()) {
            return bytes.toString(Charsets.UTF_8)
        }
        return intent.getStringExtra(EXTRA_MESSAGE)
            ?: intent.getStringExtra(EXTRA_PAYLOAD)
    }

    companion object {
        const val ACTION_UNIFIED_PUSH_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
        const val ACTION_UNIFIED_PUSH_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        const val ACTION_UNIFIED_PUSH_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        const val ACTION_UNIFIED_PUSH_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"

        const val EXTRA_BYTES = "bytes"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_PUSH_CALL_UUID = "com.baotime.app.extra.PUSH_CALL_UUID"

        /** Routes a parsed [IncomingFaceTimeEvent] into [BaoTimeApp] and posts/cancels call notification. */
        fun handlePushEvent(context: Context, app: BaoTimeApp, event: IncomingFaceTimeEvent) {
            when (event) {
                is IncomingFaceTimeEvent.CallStatusChanged -> {
                    when (event.status) {
                        FaceTimeSessionStatus.INCOMING -> {
                            val caller = event.address.orEmpty()
                            val accepted = app.callLifecycle.onIncomingUuid(
                                callUuid = event.uuid,
                                caller = caller,
                                configGeneration = app.appConfig.connectionRevision(),
                                source = InviteSource.Fcm
                            )
                            if (accepted) {
                                val ringing = app.callLifecycle.state.value as? CallLifecycleState.Ringing
                                if (ringing != null) {
                                    app.notifyBackgroundInviteIfNeeded(ringing.invite)
                                } else if (!app.hasStartedActivity) {
                                    IncomingCallNotifications.postUuidInvite(context, event.uuid, caller)
                                }
                            }
                        }
                        FaceTimeSessionStatus.DISCONNECTED -> {
                            app.callLifecycle.onCallDisconnected(
                                callUuid = event.uuid,
                                configGeneration = app.appConfig.connectionRevision()
                            )
                            cancelCallNotification(context, event.uuid)
                        }
                        else -> Unit
                    }
                }
                is IncomingFaceTimeEvent.LegacyIncoming -> {
                    app.callLifecycle.onIncomingLegacy(
                        caller = event.caller.orEmpty(),
                        timestampMs = event.timestampMs,
                        configGeneration = app.appConfig.connectionRevision()
                    )
                }
            }
        }

        fun cancelCallNotification(context: Context, callUuid: String) {
            IncomingCallNotifications.cancelUuidInvite(context, callUuid)
        }

        fun cancelAllCallNotifications(context: Context) {
            IncomingCallNotifications.cancelAllCallNotifications(context)
        }
    }
}
