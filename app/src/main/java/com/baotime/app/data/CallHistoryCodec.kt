package com.baotime.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Encode and decode for the persisted call-history store. */
object CallHistoryCodec {

    fun encode(records: List<BaoCallRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("displayName", r.displayName)
                    put("handleOrNumber", r.handleOrNumber)
                    put("faceTimeUrl", r.faceTimeUrl ?: "")
                    put("timestampMs", r.timestampMs)
                    put("durationSec", r.durationSec)
                    put("callType", r.callType)
                    put("isFaceTime", r.isFaceTime)
                }
            )
        }
        return arr.toString()
    }

    /**
     * Decodes the persisted payload; blank input decodes to an empty list.
     * @throws JSONException when the payload is structurally corrupt or a field is out of range.
     */
    fun decode(raw: String?): List<BaoCallRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return decodeRecords(JSONArray(raw))
    }

    private fun decodeRecords(arr: JSONArray): List<BaoCallRecord> {
        val list = ArrayList<BaoCallRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val timestampMs = obj.getLong("timestampMs")
            if (timestampMs < 0L) {
                throw JSONException("record timestamp is negative")
            }
            val callType = obj.getInt("callType")
            when (callType) {
                BaoCallRecord.TYPE_INCOMING, BaoCallRecord.TYPE_OUTGOING, BaoCallRecord.TYPE_MISSED -> Unit
                else -> throw JSONException("record callType is outside the contract")
            }
            val displayName = obj.getString("displayName")
            if (displayName.isBlank()) {
                throw JSONException("record displayName is blank")
            }
            val durationSec = obj.getLong("durationSec")
            if (durationSec < 0L) {
                throw JSONException("record duration is negative")
            }
            list.add(
                BaoCallRecord(
                    id = obj.getString("id"),
                    displayName = displayName,
                    handleOrNumber = obj.getString("handleOrNumber"),
                    faceTimeUrl = obj.getString("faceTimeUrl").takeIf { it.isNotEmpty() },
                    timestampMs = timestampMs,
                    durationSec = durationSec,
                    callType = callType,
                    isFaceTime = obj.getBoolean("isFaceTime")
                )
            )
        }
        return list
    }
}
