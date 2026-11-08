package com.baotime.app.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallHistoryCodecTest {

    private fun sampleRecord(): BaoCallRecord = BaoCallRecord(
        id = "ft_0",
        displayName = "Ada",
        handleOrNumber = "ada@example.com",
        faceTimeUrl = "https://facetime.apple.com/join#v=1&p=abc&k=def",
        timestampMs = 1730000000000L,
        durationSec = 125L,
        callType = BaoCallRecord.TYPE_OUTGOING,
        isFaceTime = true
    )

    @Test
    fun encodeThenDecode_roundTripsAllFields() {
        val decoded = CallHistoryCodec.decode(CallHistoryCodec.encode(listOf(sampleRecord())))
        assertEquals(listOf(sampleRecord()), decoded)
    }

    @Test
    fun decode_nullYieldsEmptyList() {
        assertEquals(emptyList<BaoCallRecord>(), CallHistoryCodec.decode(null))
    }

    @Test
    fun decode_structurallyCorruptPayloadThrows() {
        assertThrows(JSONException::class.java) {
            CallHistoryCodec.decode("{\"not\":\"an array\"}")
        }
    }

    @Test
    fun decode_negativeTimestampThrows() {
        val raw =
            "[{\"id\":\"ft_0\",\"displayName\":\"Ada\",\"handleOrNumber\":\"ada@example.com\"," +
                "\"faceTimeUrl\":\"\",\"timestampMs\":-5,\"durationSec\":0,\"callType\":1,\"isFaceTime\":true}]"
        assertThrows(JSONException::class.java) {
            CallHistoryCodec.decode(raw)
        }
    }

    @Test
    fun decode_missingDisplayNameThrows() {
        val raw =
            "[{\"id\":\"ft_0\",\"handleOrNumber\":\"ada@example.com\"," +
                "\"faceTimeUrl\":\"\",\"timestampMs\":5,\"durationSec\":0,\"callType\":1,\"isFaceTime\":true}]"
        assertThrows(JSONException::class.java) {
            CallHistoryCodec.decode(raw)
        }
    }

    @Test
    fun decode_blankDisplayNameThrows() {
        val raw =
            "[{\"id\":\"ft_0\",\"displayName\":\"  \",\"handleOrNumber\":\"ada@example.com\"," +
                "\"faceTimeUrl\":\"\",\"timestampMs\":5,\"durationSec\":0,\"callType\":1,\"isFaceTime\":true}]"
        assertThrows(JSONException::class.java) {
            CallHistoryCodec.decode(raw)
        }
    }

    @Test
    fun decode_callTypeOutsideContractThrows() {
        val raw =
            "[{\"id\":\"ft_0\",\"displayName\":\"Ada\",\"handleOrNumber\":\"ada@example.com\"," +
                "\"faceTimeUrl\":\"\",\"timestampMs\":5,\"durationSec\":0,\"callType\":9,\"isFaceTime\":true}]"
        assertThrows(JSONException::class.java) {
            CallHistoryCodec.decode(raw)
        }
    }
}
