package com.baotime.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baotime.app.BaoTimeApp
import com.baotime.app.network.FaceTimePushHandler

/** Receives ntfy connector callbacks for the current Mac and forwards FaceTime events to CallLifecycle. */
class IncomingPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext
        if (app !is BaoTimeApp) return
        val incomingToken = intent.getStringExtra(UnifiedPushRegistrar.EXTRA_TOKEN)
        app.onPushCallback(incomingToken, message = action == ACTION_UNIFIED_PUSH_MESSAGE) { revision ->
            when (action) {
                ACTION_UNIFIED_PUSH_NEW_ENDPOINT -> {
                    UnifiedPushRegistrar.onNewEndpoint(app.pushStore, incomingToken, intent.getStringExtra(EXTRA_ENDPOINT))
                }
                ACTION_UNIFIED_PUSH_MESSAGE -> {
                    val rawPayload = extractPayloadString(intent) ?: return@onPushCallback
                    val event = FaceTimePushHandler.parsePushPayload(rawPayload) ?: return@onPushCallback
                    app.callLifecycle.onIncomingEvent(event, configGeneration = revision, source = InviteSource.Fcm)
                }
                ACTION_UNIFIED_PUSH_UNREGISTERED,
                ACTION_UNIFIED_PUSH_REGISTRATION_FAILED -> UnifiedPushRegistrar.onUnregistered(app.pushStore, incomingToken)
            }
        }
    }

    private fun extractPayloadString(intent: Intent): String? {
        val bytes = intent.getByteArrayExtra(EXTRA_BYTES_MESSAGE)
        if (bytes != null && bytes.isNotEmpty()) {
            return bytes.toString(Charsets.UTF_8)
        }
        return intent.getStringExtra(EXTRA_MESSAGE)
    }

    companion object {
        const val ACTION_UNIFIED_PUSH_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
        const val ACTION_UNIFIED_PUSH_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        const val ACTION_UNIFIED_PUSH_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        const val ACTION_UNIFIED_PUSH_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"

        const val EXTRA_BYTES_MESSAGE = "bytesMessage"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ENDPOINT = "endpoint"
    }
}
