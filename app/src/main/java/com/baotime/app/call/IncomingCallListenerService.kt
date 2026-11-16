package com.baotime.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    private val sourceRooms = mutableMapOf<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SystemIntegration.PACKAGE_BLUEBUBBLES &&
            sbn.packageName != SystemIntegration.PACKAGE_BEEPER
        ) return
        val key = sbn.key
        val extras = sbn.notification?.extras
        val text = buildString {
            for (field in TEXT_KEYS) {
                extras?.getCharSequence(field)?.let { append(it).append(' ') }
            }
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.forEach { append(it).append(' ') }
        }
        val room = UrlRouter.extractFaceTimeUrl(text)
        if (sourceRooms[key] == room) return
        removeSource(key)
        if (room == null) return

        val alreadyMirrored = room in sourceRooms.values
        sourceRooms[key] = room
        if (!alreadyMirrored) {
            IncomingCallNotifications.postNlsUrlInvite(
                this, room, extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        removeSource(sbn.key)
    }

    override fun onListenerDisconnected() {
        clearSources()
    }

    override fun onDestroy() {
        clearSources()
        super.onDestroy()
    }

    private fun removeSource(key: String) {
        val room = sourceRooms.remove(key) ?: return
        if (room !in sourceRooms.values) {
            IncomingCallNotifications.cancelNlsUrlInvite(this, room)
        }
    }

    private fun clearSources() {
        sourceRooms.values.toSet().forEach { IncomingCallNotifications.cancelNlsUrlInvite(this, it) }
        sourceRooms.clear()
    }

    companion object {
        const val CHANNEL_ID = "incoming_calls"
        private val TEXT_KEYS = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )

        /** Whether the user granted BaoTime notification access. */
        fun hasNotificationAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(ComponentName(context, IncomingCallListenerService::class.java)) == true

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
