package com.baotime.app.network

/** Redacts BlueBubbles authentication query parameters (`password`, `guid`, `token`) from URLs and log messages. */
object LogRedactor {

    private val AUTH_QUERY_REGEX = Regex(
        """([?&](?:password|guid|token)=)[^&#\s"']*""",
        RegexOption.IGNORE_CASE
    )

    fun redact(text: String): String =
        AUTH_QUERY_REGEX.replace(text, "$1[REDACTED]")
}
