package com.baotime.app.network

import com.baotime.app.network.IncomingFaceTimeParser.optNonBlankString
import org.json.JSONObject

/**
 * Clean-room parser for BlueBubbles FaceTime push payloads (UnifiedPush or FCM data messages).
 *
 * Maps incoming push payloads into [IncomingFaceTimeEvent] (`Task 5` ingress) so that
 * `uuid`-backed calls use the exact same `CallLifecycle` + `POST /api/v1/facetime/answer/:call_uuid`
 * path as `Task 6`, while non-FaceTime messages (`new-message`, etc.) are ignored.
 *
 * Process-state limitation (Android OS contract):
 * - When the user force-stops BaoTime in Android Settings (`stopped=true`), Android blocks
 *   broadcasts and push delivery until the user manually reopens BaoTime once
 *   (`https://firebase.google.com/docs/cloud-messaging/flutter/receive`).
 */
object FaceTimePushHandler {

    const val TYPE_CALL_STATUS_CHANGED = IncomingFaceTimeParser.EVENT_CALL_STATUS_CHANGED
    const val TYPE_INCOMING_FACETIME = IncomingFaceTimeParser.EVENT_INCOMING_FACETIME

    /**
     * Parses a raw JSON push message string or [JSONObject] into an [IncomingFaceTimeEvent].
     * Returns `null` if the payload is malformed or is an unrelated push event type.
     */
    fun parsePushPayload(raw: Any?): IncomingFaceTimeEvent? {
        val root = IncomingFaceTimeParser.toJsonObject(raw) ?: return null
        val type = root.optNonBlankString("type")
        val dataNode: Any? = if (root.has("data") && !root.isNull("data")) root.get("data") else null

        return when (type) {
            TYPE_CALL_STATUS_CHANGED -> {
                IncomingFaceTimeParser.parseCallStatusChanged(dataNode ?: root)
            }
            TYPE_INCOMING_FACETIME -> {
                val target = dataNode ?: root
                val innerObj = IncomingFaceTimeParser.toJsonObject(target) ?: return null
                val uuid = innerObj.optNonBlankString("uuid") ?: innerObj.optNonBlankString("call_uuid")
                if (uuid != null) {
                    val statusId = if (innerObj.has("status_id") && !innerObj.isNull("status_id")) {
                        innerObj.optInt("status_id", FaceTimeSessionStatus.INCOMING.id)
                    } else {
                        FaceTimeSessionStatus.INCOMING.id
                    }
                    val handleObj = innerObj.optJSONObject("handle")
                    val address = innerObj.optNonBlankString("address")
                        ?: innerObj.optNonBlankString("caller")
                        ?: handleObj?.optNonBlankString("address")
                    IncomingFaceTimeEvent.CallStatusChanged(
                        uuid = uuid,
                        status = FaceTimeSessionStatus.fromId(statusId),
                        statusId = statusId,
                        address = address,
                        isAudio = innerObj.optBoolean("is_audio", false),
                        isVideo = innerObj.optBoolean("is_video", true)
                    )
                } else {
                    IncomingFaceTimeParser.parseLegacyIncoming(innerObj)
                }
            }
            null -> {
                IncomingFaceTimeParser.parseCallStatusChanged(root)
                    ?: IncomingFaceTimeParser.parseLegacyIncoming(root)
            }
            else -> null
        }
    }
}
