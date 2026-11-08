package com.baotime.app.network

import java.net.URI

/**
 * Structural acceptance gate for the user-configured BlueBubbles base URL.
 * BlueBubbles servers are private by nature: LAN addresses, loopback, Tailscale
 * CGNAT ranges, `.local` hostnames, and plain http on the local network are all
 * legitimate targets, so no address-family or DNS checks happen here. Saving must
 * not depend on the server being reachable; connectivity problems surface later
 * as typed server-status failures. Only the URL shape is validated: http(s)
 * scheme, a real host, and no embedded credentials.
 */
object ServerUrlPolicy {

    /** Result of validating a candidate server base URL. */
    sealed class Verdict {
        /** URL passed the policy; [host] is the validated lowercase host. */
        data class Valid(val host: String) : Verdict()

        /** URL was rejected; [reason] explains which rule failed (never includes credentials). */
        data class Invalid(val reason: String) : Verdict()
    }

    fun check(baseUrl: String): Verdict {
        val parsed = runCatching { URI(baseUrl.trim()) }.fold(onSuccess = { it }, onFailure = { failure ->
            if (failure is java.net.URISyntaxException) return Verdict.Invalid("not an absolute http(s) URL")
            throw failure
        })
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return Verdict.Invalid("scheme must be http or https")
        }
        if (parsed.userInfo != null) {
            return Verdict.Invalid("userinfo in URL is not allowed")
        }
        if (parsed.host.isNullOrBlank()) {
            return Verdict.Invalid("URL has no host")
        }
        return Verdict.Valid(parsed.host.lowercase())
    }
}
