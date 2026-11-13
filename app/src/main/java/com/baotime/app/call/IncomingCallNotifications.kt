package com.baotime.app.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import com.baotime.app.MainActivity
import com.baotime.app.R
import com.baotime.app.system.SystemIntegration
import java.security.MessageDigest

/**
 * Canonical owner for incoming-call status-bar / full-screen notifications across
 * [BlueBubblesSocket][com.baotime.app.network.BlueBubblesSocket], [IncomingPushReceiver],
 * and [IncomingCallListenerService].
 *
 * Uses a monochrome vector status-bar icon ([R.drawable.ic_video_call]), enforces the 30-second
 * ringing timeout ([CallLifecycle.DEFAULT_RINGING_TIMEOUT_MS]), respects Android 14+ full-screen
 * intent permission ([SystemIntegration.canUseFullScreenIncomingAlerts]), and tracks active call
 * notification tags so cancellation cleans up call alerts without touching unrelated state.
 */
object IncomingCallNotifications {

    private const val CALL_NOTIFICATION_ID = 1
    private val activeNotificationTags = mutableSetOf<String>()

    /** Posts a `CATEGORY_CALL` incoming notification for a UUID-backed Socket.IO or UnifiedPush call. */
    @JvmOverloads
    fun postUuidInvite(context: Context, callUuid: String, caller: String?, quiet: Boolean = false,
        stillRinging: () -> Boolean): Boolean {
        val cleanUuid = callUuid.trim()
        if (cleanUuid.isEmpty()) return false
        val tag = notificationTag("uuid:$cleanUuid")
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            identifier = tag
            putExtra(IncomingPushReceiver.EXTRA_PUSH_CALL_UUID, cleanUuid)
            putExtra(Intent.EXTRA_TITLE, caller)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return postCallNotification(context, tag, open, caller, quiet, stillRinging)
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
        postCallNotification(context, notificationTag("url:$cleanUrl"), open, caller)
    }

    /** Cancels a specific UUID-backed call notification. */
    fun cancelUuidInvite(context: Context, callUuid: String) {
        cancelCallNotification(context, notificationTag("uuid:${callUuid.trim()}"))
    }

    /** Cancels the mirror alert for an NLS URL-only invitation (source removed or room opened). */
    fun cancelNlsUrlInvite(context: Context, url: String) {
        cancelCallNotification(context, notificationTag("url:${url.trim()}"))
    }

    private fun cancelCallNotification(context: Context, tag: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        synchronized(activeNotificationTags) {
            manager.cancel(tag, CALL_NOTIFICATION_ID)
            activeNotificationTags.remove(tag)
        }
    }

    private fun notificationTag(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray()).toHexString()

    /** Cancels all tracked incoming-call notifications owned by BaoTime. */
    fun cancelAllCallNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        synchronized(activeNotificationTags) {
            activeNotificationTags.forEach { manager.cancel(it, CALL_NOTIFICATION_ID) }
            activeNotificationTags.clear()
        }
    }

    private fun postCallNotification(
        context: Context,
        tag: String,
        openIntent: Intent,
        caller: String?,
        quiet: Boolean = false,
        canPost: () -> Boolean = { true }
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        IncomingCallListenerService.ensureChannel(context)
        val pending = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callerLabel = caller?.takeIf { it.isNotBlank() } ?: context.getString(R.string.default_call_label)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val builder = Notification.Builder(context, IncomingCallListenerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_video_call)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setContentText(context.getString(R.string.incoming_call_body_fmt, callerLabel))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(CallLifecycle.DEFAULT_RINGING_TIMEOUT_MS)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (quiet) {
            builder.setGroup(tag).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        } else if (SystemIntegration.canUseFullScreenIncomingAlerts(context)) {
            builder.setFullScreenIntent(pending, true)
        }
        val notification = builder.build()
        synchronized(activeNotificationTags) {
            if (!canPost()) return false
            manager.notify(tag, CALL_NOTIFICATION_ID, notification)
            activeNotificationTags.add(tag)
        }
        return true
    }
}
