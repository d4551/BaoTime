package com.baotime.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFaceTimePayloadTest {

    @Test
    fun parseCallStatusChanged_parsesIncomingAndDisconnectedFixtures() {
        val incoming = IncomingFaceTimeParser.parseCallStatusChanged(
            BlueBubblesFixtures.read("ft-call-status-changed-incoming.json")
        )
        assertNotNull(incoming)
        assertEquals("00000000-0000-4000-8000-000000000001", incoming?.uuid)
        assertEquals(FaceTimeSessionStatus.INCOMING, incoming?.status)
        assertEquals(4, incoming?.statusId)
        assertEquals("caller@example.com", incoming?.address)
        assertFalse(incoming?.isAudio ?: true)
        assertTrue(incoming?.isVideo ?: false)

        val disconnected = IncomingFaceTimeParser.parseCallStatusChanged(
            BlueBubblesFixtures.read("ft-call-status-changed-disconnected.json")
        )
        assertNotNull(disconnected)
        assertEquals("00000000-0000-4000-8000-000000000001", disconnected?.uuid)
        assertEquals(FaceTimeSessionStatus.DISCONNECTED, disconnected?.status)
        assertEquals(6, disconnected?.statusId)
    }

    @Test
    fun parseCallStatusChanged_rejectsMissingUuidOrMalformedPayload() {
        assertNull(IncomingFaceTimeParser.parseCallStatusChanged("""{"status_id":4}"""))
        assertNull(IncomingFaceTimeParser.parseCallStatusChanged("""{"uuid":"","status_id":4}"""))
        assertNull(IncomingFaceTimeParser.parseCallStatusChanged("not-json"))
        assertNull(IncomingFaceTimeParser.parseCallStatusChanged(null))
    }

    @Test
    fun parseLegacyIncoming_parsesJsonStringWithoutFabricatingUuid() {
        val legacy = IncomingFaceTimeParser.parseLegacyIncoming(
            BlueBubblesFixtures.read("incoming-facetime-legacy.json")
        )
        assertNotNull(legacy)
        assertEquals("caller@example.com", legacy?.caller)
        assertEquals(1730000000000L, legacy?.timestampMs)
    }

    @Test
    fun faceTimeSessionStatus_mapsAllUpstreamEnumValues() {
        assertEquals(FaceTimeSessionStatus.UNKNOWN, FaceTimeSessionStatus.fromId(0))
        assertEquals(FaceTimeSessionStatus.ANSWERED, FaceTimeSessionStatus.fromId(1))
        assertEquals(FaceTimeSessionStatus.OUTGOING, FaceTimeSessionStatus.fromId(3))
        assertEquals(FaceTimeSessionStatus.INCOMING, FaceTimeSessionStatus.fromId(4))
        assertEquals(FaceTimeSessionStatus.DISCONNECTED, FaceTimeSessionStatus.fromId(6))
        assertEquals(FaceTimeSessionStatus.UNKNOWN, FaceTimeSessionStatus.fromId(99))
    }
}
