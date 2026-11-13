package com.baotime.app.data

/**
 * A single call entry. The companion owns the record-id prefix and stored call types.
 * Decode requires every field.
 */
data class BaoCallRecord(
    val id: String,
    val displayName: String,
    val handleOrNumber: String,
    val faceTimeUrl: String?,
    val timestampMs: Long,
    val durationSec: Long,
    val callType: Int,
    val isFaceTime: Boolean
) {
    companion object {
        /** Prefix of every generated record id. */
        const val ID_PREFIX = "ft_"

        const val TYPE_INCOMING = 1
        const val TYPE_OUTGOING = 2
        const val TYPE_MISSED = 3
    }
}

/** A contact address (phone number or Apple ID email) from the device contacts provider. */
data class DeviceContact(
    val id: String,
    val displayName: String,
    val primaryAddress: String,
    val isEmail: Boolean
)
