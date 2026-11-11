package com.baotime.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.baotime.app.R
import com.baotime.app.routing.UrlRouter
import com.baotime.app.system.SystemIntegration

/**
 * Watches BlueBubbles/Beeper notifications for incoming FaceTime links and
 * raises BaoTime's native call surface through a full-screen intent — the same
 * mechanism the stock dialer uses — instead of leaving the call in a browser
 * or in the status bar. Requires user-granted notification access (Setup tab).
 */
class IncomingCallListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SystemIntegration.PACKAGE_BLUEBUBBLES &&
            sbn.packageName != SystemIntegration.PACKAGE_BEEPER
        ) return
        val extras = sbn.notification?.extras ?: return
        val text = buildString {
            for (key in TEXT_KEYS) {
                extras.getCharSequence(key)?.let { append(it).append(' ') }
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.forEach { append(it).append(' ') }
        }
        val url = UrlRouter.extractFaceTimeUrl(text) ?: return
        val key = sbn.key ?: url
        synchronized(seenKeys) {
            if (!seenKeys.add(key)) return
            while (seenKeys.size > MAX_SEEN_KEYS) {
                seenKeys.remove(seenKeys.first())
            }
        }
        IncomingCallNotifications.postNlsUrlInvite(
            context = this,
            url = url,
            caller = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
    }

    companion object {
        const val CHANNEL_ID = "incoming_calls"
        const val EXTRA_INCOMING_CALL = "com.baotime.app.extra.INCOMING_CALL"
        private const val MAX_SEEN_KEYS = 64
        private val TEXT_KEYS = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        private val seenKeys = LinkedHashSet<String>()

        /** Whether the user granted BaoTime notification access. */
        fun hasNotificationAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Creates the high-importance incoming-call channel (idempotent). */
        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.incoming_calls_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
