package com.baotime.app.call

import com.baotime.app.network.ServerFailure

/** Source of an incoming FaceTime invitation. */
enum class InviteSource {
    SocketCalling,
    SocketLegacy,
    NlsUrl,
    Fcm
}

/**
 * Immutable identity for one incoming FaceTime invitation.
 *
 * Same-uuid duplicates (where both events carry the same non-null [callUuid]) collapse
 * to a single invite. Cross-source NLS URL-only ([InviteSource.NlsUrl], which has [faceTimeUrl]
 * and no [callUuid]) and Socket/Push calling ([InviteSource.SocketCalling] / [InviteSource.Fcm],
 * which have [callUuid] and no [faceTimeUrl]) are strictly separate identities and are never
 * merged by time, proximity, or caller-window (`tasks/plan.md` Call lifecycle contract).
 */
data class CallInvite(
    val callUuid: String?,
    val faceTimeUrl: String?,
    val caller: String,
    val configGeneration: Long,
    val inviteGeneration: Long,
    val source: InviteSource,
    val legacyTimestampMs: Long? = null
)

/** Terminal disposition of an invitation or session. */
enum class TerminalReason {
    Declined,
    Missed,
    Disconnected,
    Expired,
    CancelledByConfig,
    Failed,
    Completed
}

/** Authoritative state of the single process-wide FaceTime call lifecycle. */
sealed class CallLifecycleState {
    data object Idle : CallLifecycleState()
    data class Ringing(val invite: CallInvite) : CallLifecycleState()
    data class Answering(val invite: CallInvite) : CallLifecycleState()
    data class JoiningUrl(val invite: CallInvite, val url: String) : CallLifecycleState()
    data class Connecting(val invite: CallInvite, val url: String) : CallLifecycleState()
    data class InRoom(val invite: CallInvite?, val url: String, val recordId: String) : CallLifecycleState()
    data class Terminal(
        val invite: CallInvite,
        val reason: TerminalReason,
        val answerFailure: ServerFailure? = null
    ) : CallLifecycleState()
}
