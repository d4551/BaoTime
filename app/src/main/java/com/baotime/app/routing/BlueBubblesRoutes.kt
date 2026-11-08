package com.baotime.app.routing

import java.net.URLEncoder

/** Canonical BlueBubbles API paths. */
object BlueBubblesRoutes {
    const val PING = "/api/v1/ping"
    const val SERVER_INFO = "/api/v1/server/info"
    const val FACETIME_SESSION = "/api/v1/facetime/session"
    const val CONTACTS = "/api/v1/contact"
    const val FACETIME_AVAILABILITY = "/api/v1/handle/availability/facetime"
    const val FCM_DEVICE = "/api/v1/fcm/device"

    fun facetimeAnswer(callUuid: String): String =
        "/api/v1/facetime/answer/${URLEncoder.encode(callUuid.trim(), Charsets.UTF_8.name())}"

    fun facetimeLeave(callUuid: String): String =
        "/api/v1/facetime/leave/${URLEncoder.encode(callUuid.trim(), Charsets.UTF_8.name())}"
}
