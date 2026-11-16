package com.baotime.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Encode and decode for the persisted call-history store. */
object CallHistoryCodec {

    fun encode(records: List<BaoCallRecord>): String {
        val arr = JSONArray()
        for (record in records) {
            val obj = JSONObject()
            for (field in fields) field.put(obj, record)
            arr.put(obj)
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
            list.add(
                BaoCallRecord(
                    id = ID.read(obj),
                    displayName = DISPLAY_NAME.read(obj),
                    handleOrNumber = HANDLE_OR_NUMBER.read(obj),
                    faceTimeUrl = FACE_TIME_URL.read(obj),
                    timestampMs = TIMESTAMP_MS.read(obj),
                    durationSec = DURATION_SEC.read(obj),
                    callType = CALL_TYPE.read(obj),
                    isFaceTime = IS_FACE_TIME.read(obj)
                )
            )
        }
        return list
    }

    private fun readDisplayName(obj: JSONObject, key: String): String {
        val name = readString(obj, key)
        if (name.isBlank()) throw JSONException("record displayName is blank")
        return name
    }

    private fun readFaceTimeUrl(obj: JSONObject, key: String): String? =
        readString(obj, key).takeIf { it.isNotEmpty() }

    private fun readString(obj: JSONObject, key: String): String =
        obj.get(key) as? String ?: throw JSONException("record $key must be a string")

    private fun readBoolean(obj: JSONObject, key: String): Boolean =
        obj.get(key) as? Boolean ?: throw JSONException("record $key must be a boolean")

    private fun readNonnegativeLong(obj: JSONObject, key: String): Long {
        val number = when (val value = obj.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw JSONException("record $key must be a signed 64-bit integer")
        }
        if (number < 0L) throw JSONException("record $key is negative")
        return number
    }

    private fun readCallType(obj: JSONObject, key: String): Int {
        return when (readNonnegativeLong(obj, key)) {
            BaoCallRecord.TYPE_INCOMING.toLong() -> BaoCallRecord.TYPE_INCOMING
            BaoCallRecord.TYPE_OUTGOING.toLong() -> BaoCallRecord.TYPE_OUTGOING
            BaoCallRecord.TYPE_MISSED.toLong() -> BaoCallRecord.TYPE_MISSED
            else -> throw JSONException("record callType is outside the contract")
        }
    }

    private fun writeString(obj: JSONObject, key: String, value: String) {
        obj.put(key, value)
    }

    private fun writeLong(obj: JSONObject, key: String, value: Long) {
        obj.put(key, value)
    }

    private fun writeInt(obj: JSONObject, key: String, value: Int) {
        obj.put(key, value)
    }

    private fun writeBoolean(obj: JSONObject, key: String, value: Boolean) {
        obj.put(key, value)
    }

    private fun writeFaceTimeUrl(obj: JSONObject, key: String, value: String?) {
        obj.put(key, value ?: "")
    }

    /** One persisted field: JSON key, record accessor, and both codec directions are defined together. */
    private class Field<T>(
        private val key: String,
        private val value: (BaoCallRecord) -> T,
        private val reader: (JSONObject, String) -> T,
        private val writer: (JSONObject, String, T) -> Unit
    ) {
        fun read(obj: JSONObject): T = reader(obj, key)
        fun put(obj: JSONObject, record: BaoCallRecord) = writer(obj, key, value(record))
    }

    private val ID = Field("id", BaoCallRecord::id, ::readString, ::writeString)
    private val DISPLAY_NAME = Field("displayName", BaoCallRecord::displayName, ::readDisplayName, ::writeString)
    private val HANDLE_OR_NUMBER = Field(
        "handleOrNumber", BaoCallRecord::handleOrNumber, ::readString, ::writeString
    )
    private val FACE_TIME_URL = Field("faceTimeUrl", BaoCallRecord::faceTimeUrl, ::readFaceTimeUrl, ::writeFaceTimeUrl)
    private val TIMESTAMP_MS = Field("timestampMs", BaoCallRecord::timestampMs, ::readNonnegativeLong, ::writeLong)
    private val DURATION_SEC = Field("durationSec", BaoCallRecord::durationSec, ::readNonnegativeLong, ::writeLong)
    private val CALL_TYPE = Field("callType", BaoCallRecord::callType, ::readCallType, ::writeInt)
    private val IS_FACE_TIME = Field("isFaceTime", BaoCallRecord::isFaceTime, ::readBoolean, ::writeBoolean)

    /** The persisted field set, expressed once: encode walks it, decode reads the same descriptors. */
    private val fields = listOf(
        ID, DISPLAY_NAME, HANDLE_OR_NUMBER, FACE_TIME_URL,
        TIMESTAMP_MS, DURATION_SEC, CALL_TYPE, IS_FACE_TIME
    )
}
