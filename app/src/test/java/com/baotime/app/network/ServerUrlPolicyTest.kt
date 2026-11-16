package com.baotime.app.network

import com.baotime.app.network.ServerUrlPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlPolicyTest {

    private fun invalidReason(url: String): String =
        when (val verdict = ServerUrlPolicy.check(url)) {
            is Verdict.Invalid -> verdict.reason
            is Verdict.Valid -> throw AssertionError("expected Invalid for $url, got $verdict")
        }

    @Test
    fun check_acceptsPublicHosts() {
        assertEquals(Verdict.Valid("8.8.8.8"), ServerUrlPolicy.check("https://8.8.8.8:1234/api"))
        assertEquals(Verdict.Valid("bb.example.com"), ServerUrlPolicy.check("https://bb.example.com"))
    }

    @Test
    fun check_acceptsLanAndLoopbackServers() {
        assertEquals(Verdict.Valid("192.168.1.10"), ServerUrlPolicy.check("http://192.168.1.10:1234"))
        assertEquals(Verdict.Valid("10.0.0.5"), ServerUrlPolicy.check("http://10.0.0.5:1234"))
        assertEquals(Verdict.Valid("172.16.0.1"), ServerUrlPolicy.check("https://172.16.0.1"))
        assertEquals(Verdict.Valid("127.0.0.1"), ServerUrlPolicy.check("http://127.0.0.1:1234"))
        assertEquals(Verdict.Valid("localhost"), ServerUrlPolicy.check("http://localhost:1234"))
    }

    @Test
    fun check_acceptsTailscaleCgnatServers() {
        assertEquals(Verdict.Valid("100.73.92.85"), ServerUrlPolicy.check("http://100.73.92.85:1234"))
        assertEquals(Verdict.Valid("100.64.0.0"), ServerUrlPolicy.check("http://100.64.0.0:1234"))
    }

    @Test
    fun check_acceptsLocalAndIpv6Hostnames() {
        assertEquals(Verdict.Valid("macbook.local"), ServerUrlPolicy.check("http://macbook.local:1234"))
        assertEquals(Verdict.Valid("[fe80::1]"), ServerUrlPolicy.check("http://[fe80::1]:1234"))
    }

    @Test
    fun check_trimsWhitespaceAndTrailingHostCase() {
        assertEquals(Verdict.Valid("bb.example.com"), ServerUrlPolicy.check("  https://BB.Example.COM  "))
    }

    @Test
    fun check_rejectsNonHttpSchemes() {
        assertTrue(invalidReason("ftp://8.8.8.8").contains("http"))
        assertTrue(invalidReason("file:///etc/hosts").contains("http"))
        assertTrue(invalidReason("facetime:8.8.8.8").contains("http"))
    }

    @Test
    fun check_rejectsMalformedUrls() {
        assertTrue(invalidReason("not a url").contains("http"))
        assertTrue(invalidReason("").contains("http"))
        assertTrue(invalidReason("192.168.1.10:1234").contains("http"))
    }

    @Test
    fun check_rejectsUserinfo() {
        assertTrue(invalidReason("https://user:secret@8.8.8.8:1234/api").contains("userinfo"))
        assertTrue(invalidReason("http://user@192.168.1.10").contains("userinfo"))
    }

    @Test
    fun check_rejectsMissingHost() {
        assertTrue(invalidReason("https://").contains("http"))
        assertTrue(invalidReason("http:///path").contains("host"))
    }

    @Test
    fun check_rejectsInvalidExplicitPorts() {
        for (authority in listOf("mac.local:", "mac.local:0", "mac.local:65536", "[::1]:65536")) {
            assertEquals("port must be between 1 and 65535", invalidReason("https://$authority"))
        }
        assertEquals(Verdict.Valid("mac.local"), ServerUrlPolicy.check("https://mac.local:65535"))
    }
}
