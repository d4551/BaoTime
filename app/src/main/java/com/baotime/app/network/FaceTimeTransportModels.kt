package com.baotime.app.network

import com.baotime.app.config.PasswordRead

/** Typed reason a BlueBubbles request did not succeed. */
sealed class ServerFailure {
    data object PasswordMissing : ServerFailure()
    data object CredentialsUnavailable : ServerFailure()
    data object UrlMissing : ServerFailure()
    data object UrlRejected : ServerFailure()
    data object ConnectionChanged : ServerFailure()
    data object Unauthorized : ServerFailure()
    data object HelperUnavailable : ServerFailure()
    data object UnsupportedVersion : ServerFailure()
    data object HttpFailed : ServerFailure()
    data object EmptyLink : ServerFailure()
}

/** Live status of the configured BlueBubbles server. */
data class BlueBubblesServerStatus(
    val reachable: Boolean,
    val serverVersion: String = "",
    val privateApiEnabled: Boolean = false,
    val helperConnected: Boolean = false,
    val detectedImessage: String = "",
    val failure: ServerFailure? = null
) {
    /** Whether the server satisfies the pinned 1.9.12 + Private API + helper capability gate. */
    val isFaceTimeCapable: Boolean
        get() = ServerCapability.verifyFaceTimeCapability(this) == null
}

/** Result of asking the server to create a FaceTime session link. */
sealed class FaceTimeSessionResult {
    data class Created(val link: String) : FaceTimeSessionResult()
    data class Failed(val failure: ServerFailure) : FaceTimeSessionResult()
}

/** Result of `GET /api/v1/ping` authentication check. */
sealed class ServerPingResult {
    data object Authenticated : ServerPingResult()
    data object Unauthorized : ServerPingResult()
    data class Unreachable(val failure: ServerFailure) : ServerPingResult()
}

/**
 * Advisory result of `GET /api/v1/handle/availability/facetime?address=`.
 *
 * Per `facetimeRouter.ts` @ `76d2b2e`, `POST /api/v1/facetime/session` takes `{}` with no
 * callee handle (`FaceTimeInterface.create()`). Availability is strictly advisory and must
 * not block generic session link creation.
 */
enum class FaceTimeAvailabilityResult {
    Available,
    Unavailable,
    Indeterminate
}

/** Snapshot of connection credentials for a single request. */
data class EndpointSnapshot @JvmOverloads constructor(
    val url: String,
    val password: PasswordRead,
    val revision: Long,
    val macGeneration: Long? = null
)
