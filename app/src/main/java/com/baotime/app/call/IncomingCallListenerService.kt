package com.baotime.app.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.baotime.app.MainActivity
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
        showIncomingCall(url, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    private fun showIncomingCall(url: String, caller: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(this)
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = url.toUri()
            putExtra(EXTRA_INCOMING_CALL, true)
            putExtra(Intent.EXTRA_TITLE, caller)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, url.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callerLabel = caller?.takeIf { it.isNotBlank() } ?: getString(R.string.default_call_label)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.incoming_call_title))
            .setContentText(getString(R.string.incoming_call_body_fmt, callerLabel))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(url.hashCode(), notification)
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
