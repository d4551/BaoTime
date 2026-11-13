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

    /**
     * Parses a raw JSON push message string or [JSONObject] into an [IncomingFaceTimeEvent].
     * Returns `null` if the payload is malformed or is an unrelated push event type.
     */
    fun parsePushPayload(raw: String?): IncomingFaceTimeEvent? =
        IncomingFaceTimeParser.toJsonObject(raw)?.let(::parsePushPayload)

    fun parsePushPayload(root: JSONObject): IncomingFaceTimeEvent? = when (root.optNonBlankString("type")) {
        IncomingFaceTimeParser.EVENT_CALL_STATUS_CHANGED ->
            eventPayload(root)?.let(IncomingFaceTimeParser::parseCallStatusChanged)
        IncomingFaceTimeParser.EVENT_INCOMING_FACETIME ->
            eventPayload(root)?.let(IncomingFaceTimeParser::parseIncomingPush)
        null -> IncomingFaceTimeParser.parseCallStatusChanged(root) ?: IncomingFaceTimeParser.parseLegacyIncoming(root)
        else -> null
    }

    private fun eventPayload(root: JSONObject): JSONObject? = when (val data = root.opt("data")) {
        null, JSONObject.NULL -> root
        is JSONObject -> data
        is String -> IncomingFaceTimeParser.toJsonObject(data)
        else -> null
    }
}
