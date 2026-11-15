package com.baotime.app.call

import com.baotime.app.routing.UrlRouter

/**
 * Incoming-invite intake algorithm, extracted from [CallLifecycle] so the state store stays under
 * the monolith bound. This collaborator owns ONLY the intake logic: source-specific dedupe guards,
 * [CallInvite] construction, displaced-reason selection, and post-lock leave/missed dispatch. All
 * state and locked helpers remain owned by [CallLifecycle] and are reached through [Core].
 *
 * The shared-state contract is defined ONCE on [Core] and referenced from [CallLifecycle]; it is
 * not duplicated here.
 */
internal class InviteIntake(private val core: Core) {

    /**
     * CANONICAL shared-state contract between [CallLifecycle] (owner) and [InviteIntake] (algorithm).
     *
     * - [lock] is the SAME [CallLifecycle.StateMonitor] instance owned by [CallLifecycle]; there is
     *   no second lock and no parallel state. Every `*Locked` member below MUST be called while
     *   holding [lock]; the intake methods wrap those calls in `synchronized(lock)`.
     * - Read under lock: [generationLocked], [stateLocked], [isTerminalUuidLocked],
     *   [activeInviteLocked]. Write under lock: [nextInviteGenerationLocked] (advances the invite
     *   counter) and [displaceAndRingLocked] (displaces the active invite and emits
     *   [CallLifecycleState.Ringing] DIRECTLY — no intermediate, conflation-prone assignment;
     *   `markTerminal` records the displaced UUID as terminal for UUID/NLS intake, while legacy
     *   intake passes false to leave the displaced invite re-ringable).
     * - [leaveInvite] and [notifyMissed] fire user/network callbacks and MUST be called OUTSIDE the
     *   lock (re-entrancy safety), after the synchronized block returns the displaced invite.
     * - The state (`_state`, terminalUuids, inviteCounter, pendingOpening, generations, jobs) is
     *   never duplicated here; [InviteIntake] only reads/writes it through this interface.
     */
    internal interface Core {
        val lock: CallLifecycle.StateMonitor
        fun generationLocked(): Long
        fun stateLocked(): CallLifecycleState
        fun isTerminalUuidLocked(uuid: String): Boolean
        fun activeInviteLocked(): CallInvite?
        fun nextInviteGenerationLocked(): Long
        fun displaceAndRingLocked(next: CallInvite, active: CallInvite?, markTerminal: Boolean = true): CallInvite?
        fun leaveInvite(invite: CallInvite)
        fun notifyMissed(invite: CallInvite, reason: TerminalReason)
    }

    fun onIncomingUuid(
        callUuid: String,
        caller: String,
        configGeneration: Long,
        source: InviteSource
    ): Boolean {
        val cleanUuid = callUuid.trim()
        if (cleanUuid.isEmpty()) return false
        var displacedInvite: CallInvite? = null
        var displacedWasAnswering = false
        synchronized(core.lock) {
            if (configGeneration != core.generationLocked() || core.isTerminalUuidLocked(cleanUuid) ||
                core.stateLocked() is CallLifecycleState.InRoom) return false
            val active = core.activeInviteLocked()
            if (active != null && active.callUuid == cleanUuid) return false
            displacedWasAnswering = core.stateLocked() is CallLifecycleState.Answering
            val next = CallInvite(
                callUuid = cleanUuid,
                faceTimeUrl = null,
                caller = caller.trim(),
                configGeneration = configGeneration,
                inviteGeneration = core.nextInviteGenerationLocked(),
                source = source
            )
            displacedInvite = core.displaceAndRingLocked(next, active)
        }
        displacedInvite?.let { previous ->
            val prevUuid = previous.callUuid
            if (prevUuid != cleanUuid) core.leaveInvite(previous)
            val reason = if (displacedWasAnswering) TerminalReason.Disconnected else TerminalReason.Missed
            core.notifyMissed(previous, reason)
        }
        return true
    }

    fun onIncomingNlsUrl(
        faceTimeUrl: String,
        caller: String,
        configGeneration: Long
    ): Boolean {
        val cleanUrl = UrlRouter.extractFaceTimeUrl(faceTimeUrl) ?: return false
        var displacedInvite: CallInvite? = null
        synchronized(core.lock) {
            if (configGeneration != core.generationLocked() || core.stateLocked() is CallLifecycleState.InRoom) return false
            val current = core.stateLocked()
            val active = core.activeInviteLocked()
            if (active != null && active.source == InviteSource.NlsUrl && active.faceTimeUrl == cleanUrl) return false
            if ((current is CallLifecycleState.Ringing || current is CallLifecycleState.Answering) && active?.callUuid != null) {
                return false
            }
            val next = CallInvite(
                callUuid = null,
                faceTimeUrl = cleanUrl,
                caller = caller.trim(),
                configGeneration = configGeneration,
                inviteGeneration = core.nextInviteGenerationLocked(),
                source = InviteSource.NlsUrl
            )
            displacedInvite = core.displaceAndRingLocked(next, active)
        }
        displacedInvite?.let { previous ->
            core.leaveInvite(previous)
            core.notifyMissed(previous, TerminalReason.Declined)
        }
        return true
    }

    fun onIncomingLegacy(
        caller: String,
        timestampMs: Long?,
        configGeneration: Long,
        uiEnabled: Boolean
    ): Boolean {
        val cleanCaller = caller.trim()
        var displacedInvite: CallInvite? = null
        synchronized(core.lock) {
            if (configGeneration != core.generationLocked() || core.stateLocked() is CallLifecycleState.InRoom) return false
            val active = core.activeInviteLocked()
            if (active != null && active.source == InviteSource.SocketLegacy &&
                active.caller == cleanCaller && active.legacyTimestampMs == timestampMs
            ) {
                return false
            }
            if (!uiEnabled || core.stateLocked() is CallLifecycleState.Ringing || core.stateLocked() is CallLifecycleState.Answering) {
                return false
            }
            val next = CallInvite(
                callUuid = null,
                faceTimeUrl = null,
                caller = cleanCaller,
                configGeneration = configGeneration,
                inviteGeneration = core.nextInviteGenerationLocked(),
                source = InviteSource.SocketLegacy,
                legacyTimestampMs = timestampMs
            )
            displacedInvite = core.displaceAndRingLocked(next, active, markTerminal = false)
        }
        displacedInvite?.let { previous ->
            core.leaveInvite(previous)
            core.notifyMissed(previous, TerminalReason.Declined)
        }
        return true
    }
}