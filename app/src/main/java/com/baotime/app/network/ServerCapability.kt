package com.baotime.app.network

import com.baotime.app.R

/**
 * Evaluates whether a BlueBubbles server satisfies BaoTime's pinned FaceTime capability gate.
 *
 * Per the workstream contract (Section 2.1), only BlueBubbles Server 1.9.12 with both
 * Private API enabled and the FaceTime helper connected is supported for session creation,
 * answering, and leaving.
 */
object ServerCapability {

    private const val PINNED_SERVER_VERSION = "1.9.12"

    /** Returns true only for the pinned supported baseline version `1.9.12`. */
    fun isSupportedVersion(raw: String?): Boolean {
        val version = raw?.trim() ?: return false
        val normalized = when (version.firstOrNull()) {
            'v', 'V' -> version.drop(1)
            else -> version
        }
        return normalized == PINNED_SERVER_VERSION
    }

    /**
     * Returns null when the server is reachable and meets all FaceTime capability requirements,
     * or the specific [ServerFailure] blocking FaceTime operations.
     */
    fun verifyFaceTimeCapability(status: BlueBubblesServerStatus): ServerFailure? {
        if (!status.reachable) return status.failure ?: ServerFailure.HttpFailed
        if (!status.privateApiEnabled || !status.helperConnected) {
            return ServerFailure.HelperUnavailable
        }
        if (!isSupportedVersion(status.serverVersion)) {
            return ServerFailure.UnsupportedVersion
        }
        return null
    }

    /** Fixed string resource for each typed [ServerFailure]. No diagnostic text is interpolated. */
    fun bannerResource(failure: ServerFailure?): Int = when (failure) {
        ServerFailure.PasswordMissing -> R.string.banner_unreachable_password
        ServerFailure.CredentialsUnavailable -> R.string.banner_credentials_unavailable
        ServerFailure.UrlMissing -> R.string.banner_unreachable_url
        ServerFailure.UrlRejected -> R.string.banner_unreachable_rejected
        ServerFailure.ConnectionChanged -> R.string.banner_checking
        ServerFailure.Unauthorized -> R.string.banner_unauthorized
        ServerFailure.HelperUnavailable -> R.string.banner_helper_unavailable
        ServerFailure.UnsupportedVersion -> R.string.banner_unsupported_version
        ServerFailure.HttpFailed, ServerFailure.EmptyLink -> R.string.banner_unreachable_http
        null -> R.string.banner_unreachable_unknown
    }
}
