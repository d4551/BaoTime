package com.baotime.app.call

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baotime.app.BaoTimeApp
import com.baotime.app.MainActivity
import com.baotime.app.R
import com.baotime.app.network.FaceTimePushHandler
import com.baotime.app.network.FaceTimeSessionStatus
import com.baotime.app.network.IncomingFaceTimeEvent

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
        when (action) {
            ACTION_UNIFIED_PUSH_NEW_ENDPOINT -> {
                val endpoint = intent.getStringExtra(EXTRA_ENDPOINT)?.trim().orEmpty()
                if (endpoint.isNotEmpty()) {
                    app.onPushEndpointRegistered(endpoint)
                }
            }
            ACTION_UNIFIED_PUSH_MESSAGE -> {
                val rawPayload = extractPayloadString(intent) ?: return
                val event = FaceTimePushHandler.parsePushPayload(rawPayload) ?: return
                handlePushEvent(context, app, event)
            }
            ACTION_UNIFIED_PUSH_UNREGISTERED,
            ACTION_UNIFIED_PUSH_REGISTRATION_FAILED -> Unit
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
                                postIncomingCallNotification(context, event.uuid, caller)
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

        private fun postIncomingCallNotification(context: Context, callUuid: String, caller: String?) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            IncomingCallListenerService.ensureChannel(context)
            val open = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(EXTRA_PUSH_CALL_UUID, callUuid)
                putExtra(Intent.EXTRA_TITLE, caller)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val notificationId = callUuid.hashCode()
            val pending = PendingIntent.getActivity(
                context,
                notificationId,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val callerLabel = caller?.takeIf { it.isNotBlank() } ?: context.getString(R.string.default_call_label)
            val notification = NotificationCompat.Builder(context, IncomingCallListenerService.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.incoming_call_title))
                .setContentText(context.getString(R.string.incoming_call_body_fmt, callerLabel))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTimeoutAfter(CallLifecycle.DEFAULT_RINGING_TIMEOUT_MS)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }

        fun cancelCallNotification(context: Context, callUuid: String) {
            NotificationManagerCompat.from(context).cancel(callUuid.hashCode())
        }

        fun cancelAllCallNotifications(context: Context) {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }
}
