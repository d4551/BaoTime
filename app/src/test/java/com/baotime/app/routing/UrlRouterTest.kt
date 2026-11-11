package com.baotime.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRouterTest {

    @Test
    fun extractFaceTimeUrl_findsJoinLinkInMessageText() {
        val expected = java.net.URI(
            "https", UrlRouter.FACETIME_HOST, "/join", null, javaClass.simpleName
        ).toASCIIString()
        val sample = "Incoming call! Join here: $expected."
        assertEquals(expected, UrlRouter.extractFaceTimeUrl(sample))
    }

    @Test
    fun extractFaceTimeUrl_rejectsHttp() {
        assertNull(UrlRouter.extractFaceTimeUrl("http://facetime.apple.com/join#v=1&p=abc&k=def"))
    }

    @Test
    fun extractFaceTimeUrl_returnsNullForUnrelatedText() {
        assertNull(UrlRouter.extractFaceTimeUrl("Hello from BlueBubbles and Beeper"))
    }

    @Test
    fun wholeFaceTimeUrl_rejectsTextAroundTheLink() {
        val sample = "Join here: https://facetime.apple.com/join#v=1&p=abc&k=def"
        assertNull(UrlRouter.wholeFaceTimeUrl(sample))
        assertEquals(
            "https://facetime.apple.com/join#v=1&p=abc&k=def",
            UrlRouter.wholeFaceTimeUrl("https://facetime.apple.com/join#v=1&p=abc&k=def")
        )
    }

    @Test
    fun isWebScheme_acceptsHttpAndHttps() {
        assertTrue(UrlRouter.isWebScheme("https"))
        assertTrue(UrlRouter.isWebScheme("HTTP"))
        assertTrue(UrlRouter.isWebScheme("http"))
        assertFalse(UrlRouter.isWebScheme("ftp"))
        assertFalse(UrlRouter.isWebScheme("facetime"))
        assertFalse(UrlRouter.isWebScheme(""))
        assertFalse(UrlRouter.isWebScheme(null))
    }

    @Test
    fun isAppleMediaHost_acceptsAppleHostsAndRejectsLookalikes() {
        assertTrue(UrlRouter.isAppleMediaHost("apple.com"))
        assertTrue(UrlRouter.isAppleMediaHost("www.apple.com"))
        assertTrue(UrlRouter.isAppleMediaHost("icloud.com"))
        assertFalse(UrlRouter.isAppleMediaHost("evilapple.com"))
        assertFalse(UrlRouter.isAppleMediaHost("notapple.com"))
        assertFalse(UrlRouter.isAppleMediaHost("evil.icloud.com.attacker.net"))
        assertFalse(UrlRouter.isAppleMediaHost(""))
        assertFalse(UrlRouter.isAppleMediaHost(null))
    }

    @Test
    fun isFacetimeHost_matchesExactHostCaseInsensitively() {
        assertTrue(UrlRouter.isFacetimeHost("facetime.apple.com"))
        assertTrue(UrlRouter.isFacetimeHost("FaceTime.Apple.Com"))
        assertFalse(UrlRouter.isFacetimeHost("facetime.apple.com.evil.com"))
    }

    @Test
    fun isFaceTimeRoomUrl_acceptsCanonicalRoomsAndRejectsRootCredentialsPortsSchemesAndLookalikes() {
        assertTrue(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com/join#v=1&p=abc&k=def"))
        assertTrue(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com:443/join#v=1&p=abc&k=def"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com/"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com.evil.invalid/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://user:secret@facetime.apple.com/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com:444/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("http://facetime.apple.com/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("javascript:alert(1)"))
    }
}
