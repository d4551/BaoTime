package com.baotime.app.network

import org.json.JSONException
import org.json.JSONObject

/**
 * BlueBubbles FaceTime session status codes (`FaceTimeSession.ts` @ `76d2b2e`).
 * Note: upstream `PrivateApiFaceTimeStatusHandler.handleCalling` suppresses OUTGOING (3)
 * and ANSWERED (1), emitting INCOMING (4) and DISCONNECTED (6).
 */
enum class FaceTimeSessionStatus(val id: Int) {
    UNKNOWN(0),
    ANSWERED(1),
    OUTGOING(3),
    INCOMING(4),
    DISCONNECTED(6);

    companion object {
        fun fromId(id: Int): FaceTimeSessionStatus =
            entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Parsed Socket.IO or push FaceTime event from the BlueBubbles server. */
sealed class IncomingFaceTimeEvent {

    /**
     * `ft-call-status-changed` event (when server `facetime_calling` is enabled).
     * Carries a non-null [uuid] suitable for `POST /api/v1/facetime/answer/:call_uuid`
     * and `POST /api/v1/facetime/leave/:call_uuid`.
     */
    data class CallStatusChanged(
        val uuid: String,
        val status: FaceTimeSessionStatus,
        val statusId: Int,
        val address: String?,
        val isAudio: Boolean,
        val isVideo: Boolean
    ) : IncomingFaceTimeEvent()

    /**
     * Legacy `incoming-facetime` event (when server `facetime_calling` is false).
     * Payload is a JSON string `{"caller":"...","timestamp":...}` with NO `uuid`.
     * Cannot call `/api/v1/facetime/answer`.
     */
    data class LegacyIncoming(
        val caller: String?,
        val timestampMs: Long?
    ) : IncomingFaceTimeEvent()
}

/** Parser for BlueBubbles Socket.IO and push FaceTime event payloads. */
object IncomingFaceTimeParser {

    const val EVENT_CALL_STATUS_CHANGED = "ft-call-status-changed"
    const val EVENT_INCOMING_FACETIME = "incoming-facetime"

    /**
     * Parses a `ft-call-status-changed` payload (either a [JSONObject] or JSON string).
     * Returns null if malformed or missing a non-blank `uuid` / `call_uuid`.
     */
    fun parseCallStatusChanged(raw: String?): IncomingFaceTimeEvent.CallStatusChanged? =
        toJsonObject(raw)?.let(::parseCallStatusChanged)

    fun parseCallStatusChanged(obj: JSONObject): IncomingFaceTimeEvent.CallStatusChanged? {
        val uuid = obj.callUuid() ?: return null
        val statusId = obj.optInt("status_id", -1)
        if (statusId < 0) return null
        return statusEvent(obj, uuid, statusId, obj.optNonBlankString("address"))
    }

    internal fun parseIncomingPush(obj: JSONObject): IncomingFaceTimeEvent? {
        val uuid = obj.callUuid() ?: return parseLegacyIncoming(obj)
        val statusId = obj.optInt("status_id", FaceTimeSessionStatus.INCOMING.id)
        val address = obj.optNonBlankString("address")
            ?: obj.optNonBlankString("caller")
        return statusEvent(obj, uuid, statusId, address)
    }

    private fun JSONObject.callUuid(): String? = optNonBlankString("uuid") ?: optNonBlankString("call_uuid")

    private fun statusEvent(obj: JSONObject, uuid: String, statusId: Int, address: String?) =
        IncomingFaceTimeEvent.CallStatusChanged(
            uuid = uuid,
            status = FaceTimeSessionStatus.fromId(statusId),
            statusId = statusId,
            address = address ?: obj.optJSONObject("handle")?.optNonBlankString("address"),
            isAudio = obj.optBoolean("is_audio", false),
            isVideo = obj.optBoolean("is_video", true)
        )

    /**
     * Parses a legacy `incoming-facetime` payload (JSON string `{"caller":"...","timestamp":...}`
     * or [JSONObject]). Never fabricates a `uuid`.
     */
    fun parseLegacyIncoming(raw: String?): IncomingFaceTimeEvent.LegacyIncoming? =
        toJsonObject(raw)?.let(::parseLegacyIncoming)

    fun parseLegacyIncoming(obj: JSONObject): IncomingFaceTimeEvent.LegacyIncoming? {
        val caller = obj.optNonBlankString("caller")
            ?: obj.optNonBlankString("address")
        val timestamp = if (obj.has("timestamp") && !obj.isNull("timestamp")) {
            obj.optLong("timestamp", -1L).takeIf { it >= 0L }
        } else {
            null
        }
        if (caller == null && timestamp == null) return null
        return IncomingFaceTimeEvent.LegacyIncoming(
            caller = caller,
            timestampMs = timestamp
        )
    }

    internal fun toJsonObject(raw: String?): JSONObject? {
        val trimmed = raw?.trim() ?: return null
        return try {
            val decoded = if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
                JSONObject("{\"v\":$trimmed}").optString("v").trim()
            } else trimmed
            if (decoded.startsWith('{') && decoded.endsWith('}')) JSONObject(decoded) else null
        } catch (_: JSONException) {
            null
        }
    }

    internal fun JSONObject.optNonBlankString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() }
    }
}
