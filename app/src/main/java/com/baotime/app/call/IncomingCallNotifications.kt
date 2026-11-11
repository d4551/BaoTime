package com.baotime.app.call

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.baotime.app.MainActivity
import com.baotime.app.R
import com.baotime.app.system.SystemIntegration
import java.util.concurrent.ConcurrentHashMap

/**
 * Canonical owner for incoming-call status-bar / full-screen notifications across
 * [BlueBubblesSocket][com.baotime.app.network.BlueBubblesSocket], [IncomingPushReceiver],
 * and [IncomingCallListenerService].
 *
 * Uses a monochrome vector status-bar icon ([R.drawable.ic_video_call]), enforces the 30-second
 * ringing timeout ([CallLifecycle.DEFAULT_RINGING_TIMEOUT_MS]), respects Android 14+ full-screen
 * intent permission ([SystemIntegration.canUseFullScreenIncomingAlerts]), and tracks active call
 * notification IDs so cancellation cleans up call alerts without touching unrelated state.
 */
object IncomingCallNotifications {

    private val activeNotificationIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Posts a `CATEGORY_CALL` incoming notification for a UUID-backed Socket.IO or UnifiedPush call. */
    fun postUuidInvite(context: Context, callUuid: String, caller: String?) {
        val cleanUuid = callUuid.trim()
        if (cleanUuid.isEmpty()) return
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(IncomingPushReceiver.EXTRA_PUSH_CALL_UUID, cleanUuid)
            putExtra(Intent.EXTRA_TITLE, caller)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        postCallNotification(context, cleanUuid.hashCode(), open, caller)
    }

    /** Posts a `CATEGORY_CALL` incoming notification for an NLS URL-only BlueBubbles/Beeper alert. */
    fun postNlsUrlInvite(context: Context, url: String, caller: String?) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = cleanUrl.toUri()
            putExtra(IncomingCallListenerService.EXTRA_INCOMING_CALL, true)
            putExtra(Intent.EXTRA_TITLE, caller)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        postCallNotification(context, cleanUrl.hashCode(), open, caller)
    }

    /** Cancels a specific UUID-backed call notification. */
    fun cancelUuidInvite(context: Context, callUuid: String) {
        val notificationId = callUuid.trim().hashCode()
        activeNotificationIds.remove(notificationId)
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /** Cancels all tracked incoming-call notifications owned by BaoTime. */
    fun cancelAllCallNotifications(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val snapshot = activeNotificationIds.toList()
        activeNotificationIds.removeAll(snapshot.toSet())
        for (id in snapshot) {
            manager.cancel(id)
        }
    }

    private fun postCallNotification(
        context: Context,
        notificationId: Int,
        openIntent: Intent,
        caller: String?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        IncomingCallListenerService.ensureChannel(context)
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callerLabel = caller?.takeIf { it.isNotBlank() } ?: context.getString(R.string.default_call_label)
        val builder = NotificationCompat.Builder(context, IncomingCallListenerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_video_call)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setContentText(context.getString(R.string.incoming_call_body_fmt, callerLabel))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(CallLifecycle.DEFAULT_RINGING_TIMEOUT_MS)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (SystemIntegration.canUseFullScreenIncomingAlerts(context)) {
            builder.setFullScreenIntent(pending, true)
        }
        activeNotificationIds.add(notificationId)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}
