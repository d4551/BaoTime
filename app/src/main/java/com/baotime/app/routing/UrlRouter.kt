package com.baotime.app.routing

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/** A routing decision from an incoming Android Intent or URL string. */
sealed class RoutedAction {
    data class JoinFaceTimeWebLink(
        val url: String,
        val callerOrTitle: String?,
        val isIncomingAnswer: Boolean
    ) : RoutedAction()

    data class InitiateFaceTimeCall(
        val handle: String
    ) : RoutedAction()

    data class OpenHome(
        val prefillHandle: String? = null
    ) : RoutedAction()
}

/** Hosts, schemes, and URL classification for BaoTime. */
object UrlRouter {

    const val FACETIME_HOST = "facetime.apple.com"
    const val BLANK_URL = "about:blank"

    private val APPLE_MEDIA_HOST_SUFFIXES = listOf("apple.com", "icloud.com")

    private const val SCHEME_HTTP = "http"
    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_FACETIME = "facetime"
    private const val SCHEME_FACETIME_AUDIO = "facetime-audio"
    private const val SCHEME_TEL = "tel"

    private val WEB_URL_REGEX = Regex(
        """^(https?)://([A-Za-z0-9.-]+)(?::(\d{1,5}))?(/[^\s"'<>]*)?$""",
        RegexOption.IGNORE_CASE
    )

    private val FACETIME_URL_REGEX = Regex(
        """https://facetime\.apple\.com/(?:join)?[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    fun isFacetimeHost(host: String?): Boolean =
        !host.isNullOrBlank() && host.trim().lowercase() == FACETIME_HOST

    /** Canonical room entry; credentials, custom ports and non-HTTPS URLs are rejected. */
    fun isFaceTimeRoomUrl(value: String): Boolean {
        val uri = value.toUri()
        return uri.scheme == SCHEME_HTTPS && isFacetimeHost(uri.host) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }

    fun isAppleMediaHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().lowercase().trimEnd('.')
        return APPLE_MEDIA_HOST_SUFFIXES.any { suffix ->
            normalized == suffix || normalized.endsWith(".$suffix")
        }
    }

    fun isWebScheme(scheme: String?): Boolean {
        val normalized = scheme?.lowercase() ?: return false
        return normalized == SCHEME_HTTP || normalized == SCHEME_HTTPS
    }

    fun webUrlParts(url: String): WebUrlParts? {
        val match = WEB_URL_REGEX.find(url.trim()) ?: return null
        val scheme = match.groupValues[1].lowercase()
        if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) return null
        return WebUrlParts(
            scheme = scheme,
            host = match.groupValues[2].lowercase(),
            port = match.groupValues[3].toIntOrNull(),
            pathAndQuery = match.groupValues[4].ifEmpty { "/" }
        )
    }

    data class WebUrlParts(val scheme: String, val host: String, val port: Int?, val pathAndQuery: String)

    fun dialUri(phoneNumber: String): Uri =
        "$SCHEME_TEL:${Uri.encode(phoneNumber)}".toUri()

    /** The field is one FaceTime link and nothing else. */
    fun wholeFaceTimeUrl(rawText: String?): String? {
        val extracted = extractFaceTimeUrl(rawText) ?: return null
        return extracted.takeIf { it == rawText?.trim() }
    }

    /** Finds one `https://facetime.apple.com/...` URL inside arbitrary text. */
    fun extractFaceTimeUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val match = FACETIME_URL_REGEX.find(rawText.trim()) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', '>', ';')
    }

    fun routeIntent(intent: Intent?): RoutedAction {
        if (intent == null) return RoutedAction.OpenHome()

        val callerExtra = intent.getStringExtra("caller")
            ?: intent.getStringExtra("handle")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_DIAL, Intent.ACTION_CALL -> {
                val dataUri = intent.data
                val dataString = intent.dataString?.trim()
                if (dataUri != null && !dataString.isNullOrBlank()) {
                    val scheme = dataUri.scheme?.lowercase()

                    if (scheme == SCHEME_HTTPS) {
                        if (isFacetimeHost(dataUri.host)) {
                            return RoutedAction.JoinFaceTimeWebLink(
                                url = dataString,
                                callerOrTitle = callerExtra,
                                isIncomingAnswer = true
                            )
                        }
                    }

                    if (scheme == SCHEME_FACETIME || scheme == SCHEME_FACETIME_AUDIO) {
                        val rawTarget = dataUri.schemeSpecificPart
                            ?.removePrefix("//")
                            ?.substringBefore("?")
                            ?.let { Uri.decode(it).trim() }
                            .orEmpty()
                        if (rawTarget.isNotEmpty()) {
                            return RoutedAction.InitiateFaceTimeCall(
                                handle = rawTarget
                            )
                        }
                    }

                    if (scheme == SCHEME_TEL) {
                        val phone = dataUri.schemeSpecificPart
                            ?.let { Uri.decode(it).trim() }
                            .orEmpty()
                        if (phone.isNotEmpty()) {
                            return RoutedAction.OpenHome(prefillHandle = phone)
                        }
                    }
                }
            }

            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val extractedUrl = wholeFaceTimeUrl(sharedText)
                if (extractedUrl != null) {
                    return RoutedAction.JoinFaceTimeWebLink(
                        url = extractedUrl,
                        callerOrTitle = callerExtra,
                        isIncomingAnswer = false
                    )
                }
                if (!sharedText.isNullOrBlank()) {
                    return RoutedAction.OpenHome(prefillHandle = sharedText.trim())
                }
            }
        }

        val extraUrl = wholeFaceTimeUrl(intent.getStringExtra("url") ?: intent.getStringExtra("link"))
        if (extraUrl != null) {
            return RoutedAction.JoinFaceTimeWebLink(
                url = extraUrl,
                callerOrTitle = callerExtra,
                isIncomingAnswer = true
            )
        }

        return RoutedAction.OpenHome()
    }
}
