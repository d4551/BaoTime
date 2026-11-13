package com.baotime.app.call

import com.baotime.app.network.FaceTimeSessionResult
import com.baotime.app.network.ServerFailure
import com.baotime.app.routing.UrlRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

interface CallSignaling {
    suspend fun answer(callUuid: String, connectionRevision: Long): FaceTimeSessionResult
    suspend fun leave(callUuid: String, connectionRevision: Long): Boolean
}

/**
 * Process-scoped state store governing incoming FaceTime invitations, answer/leave HTTP
 * cancellation, config-generation invalidation, and 30-second local ringing expiry.
 *
 * Activity instances observe [state] and bind/unbind UI without owning or tearing down
 * this store or the process-wide Socket.IO connection.
 *
 * Limitation: Socket.IO v4 default delivery is at-most-once (`https://socket.io/docs/v4/delivery-guarantees/`);
 * BlueBubbles `emitMessage` does not buffer or replay missed events on reconnect.
 */
class CallLifecycle(
    private val scope: CoroutineScope,
    initialConfigGeneration: Long = 0L,
    private val ringingTimeoutMs: Long = DEFAULT_RINGING_TIMEOUT_MS,
    private val signaling: CallSignaling,
    private val onMissedInvite: suspend (invite: CallInvite, reason: TerminalReason) -> Unit = { _, _ -> },
    private val onAnswerFailed: (invite: CallInvite, failure: ServerFailure) -> Unit = { _, _ -> }
) {
    constructor(
        scope: CoroutineScope,
        initialConfigGeneration: Long = 0L,
        ringingTimeoutMs: Long = DEFAULT_RINGING_TIMEOUT_MS,
        answerCall: suspend (String) -> FaceTimeSessionResult,
        leaveCall: suspend (String) -> Boolean,
        onMissedInvite: suspend (CallInvite, TerminalReason) -> Unit = { _, _ -> },
        onAnswerFailed: (CallInvite, ServerFailure) -> Unit = { _, _ -> }
    ) : this(scope, initialConfigGeneration, ringingTimeoutMs, object : CallSignaling {
        override suspend fun answer(callUuid: String, connectionRevision: Long) = answerCall(callUuid)
        override suspend fun leave(callUuid: String, connectionRevision: Long) = leaveCall(callUuid)
    }, onMissedInvite, onAnswerFailed)

    private val lock = StateMonitor()
    private class StateMonitor
    private val inviteCounter = AtomicLong(0L)
    private var pendingOpening: Long? = null
    private var currentConfigGeneration: Long = initialConfigGeneration
    private var currentMacGeneration = 0L
    private val terminalUuids = LinkedHashSet<String>()
    private var expiryJob: Job? = null
    private var answerJob: Job? = null

    private val _state = MutableStateFlow<CallLifecycleState>(CallLifecycleState.Idle)
    val state: StateFlow<CallLifecycleState> = _state.asStateFlow()

    val configGeneration: Long
        get() = synchronized(lock) { currentConfigGeneration }

    /** Handles a calling-mode incoming event carrying a non-null [callUuid] (`status_id == 4`). */
    fun onIncomingUuid(
        callUuid: String,
        caller: String,
        configGeneration: Long = this.configGeneration,
        source: InviteSource = InviteSource.SocketCalling
    ): Boolean {
        val cleanUuid = callUuid.trim()
        if (cleanUuid.isEmpty()) return false
        var displacedInvite: CallInvite? = null
        var displacedWasAnswering = false
        val accepted: CallInvite? = synchronized(lock) {
            if (configGeneration != currentConfigGeneration || cleanUuid in terminalUuids || roomOwnsLifecycleLocked()) return false
            val active = activeInviteLocked()
            if (active != null && active.callUuid == cleanUuid) return false
            if (active != null) {
                rememberTerminalUuidLocked(active.callUuid)
                displacedInvite = active
                displacedWasAnswering = _state.value is CallLifecycleState.Answering
                cancelActiveJobsLocked()
            }
            val next = CallInvite(
                callUuid = cleanUuid,
                faceTimeUrl = null,
                caller = caller.trim(),
                configGeneration = configGeneration,
                inviteGeneration = inviteCounter.incrementAndGet(),
                source = source
            )
            pendingOpening = null
            _state.value = CallLifecycleState.Ringing(next)
            startExpiryLocked(next)
            next
        }
        displacedInvite?.let { previous ->
            val prevUuid = previous.callUuid
            if (prevUuid != cleanUuid) leaveInvite(previous)
            val reason = if (displacedWasAnswering) TerminalReason.Disconnected else TerminalReason.Missed
            launchBestEffort { onMissedInvite(previous, reason) }
        }
        return accepted != null
    }

    /** Handles an NLS URL-only incoming invitation (`IncomingCallListenerService`), kept separate from socket UUIDs. */
    fun onIncomingNlsUrl(
        faceTimeUrl: String,
        caller: String,
        configGeneration: Long = this.configGeneration
    ): Boolean {
        val cleanUrl = UrlRouter.extractFaceTimeUrl(faceTimeUrl) ?: return false
        var displacedInvite: CallInvite? = null
        val accepted: CallInvite? = synchronized(lock) {
            if (configGeneration != currentConfigGeneration || roomOwnsLifecycleLocked()) return false
            val current = _state.value
            val active = activeInviteLocked()
            if (active != null && active.source == InviteSource.NlsUrl && active.faceTimeUrl == cleanUrl) return false
            if ((current is CallLifecycleState.Ringing || current is CallLifecycleState.Answering) && active?.callUuid != null) {
                return false
            }
            if (active != null) {
                rememberTerminalUuidLocked(active.callUuid)
                displacedInvite = active
                cancelActiveJobsLocked()
            }
            val next = CallInvite(
                callUuid = null,
                faceTimeUrl = cleanUrl,
                caller = caller.trim(),
                configGeneration = configGeneration,
                inviteGeneration = inviteCounter.incrementAndGet(),
                source = InviteSource.NlsUrl
            )
            pendingOpening = null
            _state.value = CallLifecycleState.Ringing(next)
            startExpiryLocked(next)
            next
        }
        displacedInvite?.let { previous ->
            leaveInvite(previous)
            launchBestEffort { onMissedInvite(previous, TerminalReason.Declined) }
        }
        return accepted != null
    }

    /** Handles a legacy `incoming-facetime` event (no `callUuid`, no `faceTimeUrl`); never calls `/facetime/answer`. */
    fun onIncomingLegacy(
        caller: String,
        timestampMs: Long?,
        configGeneration: Long = this.configGeneration,
        uiEnabled: Boolean = false
    ): Boolean {
        val cleanCaller = caller.trim()
        var displacedInvite: CallInvite? = null
        synchronized(lock) {
            if (configGeneration != currentConfigGeneration || roomOwnsLifecycleLocked()) return false
            val active = activeInviteLocked()
            if (active != null && active.source == InviteSource.SocketLegacy &&
                active.caller == cleanCaller && active.legacyTimestampMs == timestampMs
            ) {
                return false
            }
            if (!uiEnabled || _state.value is CallLifecycleState.Ringing || _state.value is CallLifecycleState.Answering) {
                return false
            }
            displacedInvite = active
            cancelActiveJobsLocked()
            val next = CallInvite(
                callUuid = null,
                faceTimeUrl = null,
                caller = cleanCaller,
                configGeneration = configGeneration,
                inviteGeneration = inviteCounter.incrementAndGet(),
                source = InviteSource.SocketLegacy,
                legacyTimestampMs = timestampMs
            )
            pendingOpening = null
            _state.value = CallLifecycleState.Ringing(next)
            startExpiryLocked(next)
        }
        displacedInvite?.let { previous ->
            leaveInvite(previous)
            launchBestEffort { onMissedInvite(previous, TerminalReason.Declined) }
        }
        return true
    }

    /** User tapped Accept on the currently ringing invitation. */
    @JvmOverloads
    fun accept(expectedInviteGeneration: Long? = null): Boolean {
        var legacyFailure: CallInvite? = null
        val invite: CallInvite? = synchronized(lock) {
            val current = _state.value
            if (current !is CallLifecycleState.Ringing) return false
            val ringing = current.invite
            if (expectedInviteGeneration != null && ringing.inviteGeneration != expectedInviteGeneration) return false
            if (ringing.configGeneration != currentConfigGeneration) return false
            expiryJob?.cancel()
            expiryJob = null
            when {
                ringing.callUuid != null -> {
                    _state.value = CallLifecycleState.Answering(ringing)
                    ringing
                }
                ringing.faceTimeUrl != null -> {
                    _state.value = CallLifecycleState.JoiningUrl(ringing, ringing.faceTimeUrl)
                    return true
                }
                else -> {
                    _state.value = CallLifecycleState.Terminal(ringing, TerminalReason.Failed, ServerFailure.EmptyLink)
                    legacyFailure = ringing
                    null
                }
            }
        }
        // Failure callback runs outside the lock: observers may re-enter the store.
        legacyFailure?.let { onAnswerFailed(it, ServerFailure.EmptyLink) }
        if (invite == null) return false
        val uuid = requireNotNull(invite.callUuid)
        val job = scope.launch {
            val outcome = signaling.answer(uuid, invite.configGeneration)
            handleAnswerResult(invite, outcome)
        }
        synchronized(lock) {
            val current = _state.value
            if (current is CallLifecycleState.Answering && current.invite.inviteGeneration == invite.inviteGeneration) {
                answerJob = job
            } else {
                job.cancel()
            }
        }
        return true
    }

    private fun handleAnswerResult(invite: CallInvite, result: FaceTimeSessionResult) {
        var shouldLeaveLateSuccess = false
        var notifyFailure: ServerFailure? = null
        synchronized(lock) {
            val current = _state.value
            val stillValid = current is CallLifecycleState.Answering &&
                current.invite.inviteGeneration == invite.inviteGeneration &&
                invite.configGeneration == currentConfigGeneration
            if (!stillValid) {
                if (result is FaceTimeSessionResult.Created && invite.callUuid != null) {
                    shouldLeaveLateSuccess = true
                }
                return@synchronized
            }
            answerJob = null
            when (result) {
                is FaceTimeSessionResult.Created -> {
                    val cleanLink = result.link.trim().takeIf { UrlRouter.isFaceTimeRoomUrl(it) }
                    if (cleanLink != null) {
                        _state.value = CallLifecycleState.Connecting(invite.copy(faceTimeUrl = cleanLink), cleanLink)
                    } else {
                        rememberTerminalUuidLocked(invite.callUuid)
                        _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Failed, ServerFailure.EmptyLink)
                        notifyFailure = ServerFailure.EmptyLink
                    }
                }
                is FaceTimeSessionResult.Failed -> {
                    if (result.failure == ServerFailure.ConnectionChanged) {
                        _state.value = CallLifecycleState.Terminal(invite, TerminalReason.CancelledByConfig)
                    } else {
                        rememberTerminalUuidLocked(invite.callUuid)
                        _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Failed, result.failure)
                        notifyFailure = result.failure
                    }
                }
            }
        }
        if (shouldLeaveLateSuccess && invite.callUuid != null) {
            leaveInvite(invite)
        }
        notifyFailure?.let { failure -> onAnswerFailed(invite, failure) }
    }

    /** Claims the current invitation's answer error once, even across Activity recreation. */
    fun claimAnswerFailure(inviteGeneration: Long): ServerFailure? = synchronized(lock) {
        val current = _state.value
        if (current !is CallLifecycleState.Terminal || current.invite.inviteGeneration != inviteGeneration ||
            current.invite.configGeneration != currentConfigGeneration || pendingOpening != null
        ) return@synchronized null
        val failure = current.answerFailure ?: return@synchronized null
        _state.value = current.copy(answerFailure = null)
        failure
    }

    /** User tapped Decline (or dismissed the incoming overlay). */
    @JvmOverloads
    fun decline(expectedInviteGeneration: Long? = null): Boolean {
        val declined: CallInvite = synchronized(lock) {
            val invite = when (val current = _state.value) {
                is CallLifecycleState.Ringing -> current.invite
                is CallLifecycleState.Answering -> current.invite
                else -> return false
            }
            if (expectedInviteGeneration != null && invite.inviteGeneration != expectedInviteGeneration) return false
            rememberTerminalUuidLocked(invite.callUuid)
            cancelActiveJobsLocked()
            // Terminal is emitted directly: a same-lock Declining assignment would be conflated
            // away by StateFlow before any collector could observe it.
            _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Declined)
            invite
        }
        leaveInvite(declined)
        launchBestEffort { onMissedInvite(declined, TerminalReason.Declined) }
        return true
    }

    /** Ends only a ringing/answering UUID; after answer, Mac admitAndLeave is independent of the browser room. */
    fun onCallDisconnected(callUuid: String, configGeneration: Long = this.configGeneration): Boolean {
        val cleanUuid = callUuid.trim()
        if (cleanUuid.isEmpty()) return false
        var wasAnswering = false
        val terminated: CallInvite = synchronized(lock) {
            if (configGeneration != currentConfigGeneration) return false
            rememberTerminalUuidLocked(cleanUuid)
            val invite = when (val current = _state.value) {
                is CallLifecycleState.Ringing -> current.invite
                is CallLifecycleState.Answering -> {
                    wasAnswering = true
                    current.invite
                }
                else -> return false
            }
            if (invite.callUuid != cleanUuid) return false
            cancelActiveJobsLocked()
            _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Disconnected)
            invite
        }
        if (wasAnswering) leaveInvite(terminated)
        launchBestEffort { onMissedInvite(terminated, TerminalReason.Disconnected) }
        return true
    }

    /** Invoked when `AppConfig.connectionRevision` changes after Settings save. */
    @JvmOverloads
    fun onConfigChanged(newConfigGeneration: Long, macGeneration: Long? = null) {
        synchronized(lock) {
            if (newConfigGeneration < currentConfigGeneration || (macGeneration != null && macGeneration < currentMacGeneration)) return
            if (macGeneration != null && macGeneration > currentMacGeneration) {
                currentMacGeneration = macGeneration
                terminalUuids.clear()
            }
            if (newConfigGeneration == currentConfigGeneration) return
            currentConfigGeneration = newConfigGeneration
            pendingOpening = null
            cancelActiveJobsLocked()
            discardAnswerFailureLocked()
            val active = activeInviteLocked()
            if (active != null && _state.value !is CallLifecycleState.InRoom && _state.value !is CallLifecycleState.Terminal) {
                _state.value = CallLifecycleState.Terminal(active, TerminalReason.CancelledByConfig)
            }
        }
    }

    /** Reserves the newest explicit opening, leaving an existing browser room alive until commit. */
    fun beginRoomOpening(): Long {
        var displaced: CallInvite? = null
        val generation = synchronized(lock) {
            val next = inviteCounter.incrementAndGet()
            pendingOpening = next
            discardAnswerFailureLocked()
            if (_state.value !is CallLifecycleState.InRoom) {
                displaced = activeInviteLocked()
                cancelActiveJobsLocked()
                displaced?.let {
                    rememberTerminalUuidLocked(it.callUuid)
                    _state.value = CallLifecycleState.Terminal(it, TerminalReason.Declined)
                }
            }
            next
        }
        displaced?.let { previous ->
            leaveInvite(previous)
            launchBestEffort { onMissedInvite(previous, TerminalReason.Declined) }
        }
        return generation
    }

    private fun discardAnswerFailureLocked() {
        val current = _state.value
        if (current is CallLifecycleState.Terminal && current.answerFailure != null) {
            _state.value = current.copy(answerFailure = null)
        }
    }

    fun isCurrentRoomOpening(generation: Long): Boolean = synchronized(lock) {
        pendingOpening == generation
    }

    /** Lets UI job ownership survive StateFlow conflation without guessing from an old state event. */
    fun hasRoomOpeningOrSession(): Boolean = synchronized(lock) {
        pendingOpening != null || _state.value is CallLifecycleState.InRoom
    }

    /** Releases only this opening; an older completion cannot release a newer request. */
    fun cancelRoomOpening(generation: Long) {
        synchronized(lock) {
            if (pendingOpening == generation) pendingOpening = null
        }
    }

    /** Whether an accepted invitation still owns this pending room after asynchronous work. */
    fun isPendingRoom(url: String, inviteGeneration: Long): Boolean = synchronized(lock) {
        pendingRoomInviteLocked(url, inviteGeneration) != null
    }

    /** Consumes the exact pending opening or accepted invitation; null is an immediate direct open. */
    @JvmOverloads
    fun onRoomOpened(
        url: String,
        recordId: String,
        inviteGeneration: Long? = null,
        openingJob: Job? = null
    ): Boolean {
        if (!UrlRouter.isFaceTimeRoomUrl(url) || recordId.isBlank()) return false
        var displaced: CallInvite? = null
        synchronized(lock) {
            val invite = if (inviteGeneration != null && pendingOpening != inviteGeneration) {
                pendingRoomInviteLocked(url, inviteGeneration) ?: return false
            } else null
            if (openingJob?.isActive == false) return false
            if (invite == null && _state.value !is CallLifecycleState.InRoom) {
                displaced = activeInviteLocked()
                rememberTerminalUuidLocked(displaced?.callUuid)
            }
            if (invite != null) rememberTerminalUuidLocked(invite.callUuid)
            cancelActiveJobsLocked()
            pendingOpening = null
            _state.value = CallLifecycleState.InRoom(invite, url, recordId)
        }
        displaced?.let { previous ->
            leaveInvite(previous)
            launchBestEffort { onMissedInvite(previous, TerminalReason.Declined) }
        }
        return true
    }

    private fun pendingRoomInviteLocked(url: String, inviteGeneration: Long): CallInvite? {
        val invite = when (val current = _state.value) {
            is CallLifecycleState.Connecting -> current.invite.takeIf { current.url == url }
            is CallLifecycleState.JoiningUrl -> current.invite.takeIf { current.url == url }
            else -> null
        }
        return invite?.takeIf {
            it.inviteGeneration == inviteGeneration && it.configGeneration == currentConfigGeneration
        }
    }

    /** A local opening failure can terminate only the invitation that still owns that room. */
    fun failPendingRoom(url: String, inviteGeneration: Long): Boolean {
        val failed = synchronized(lock) {
            val invite = pendingRoomInviteLocked(url, inviteGeneration) ?: return false
            rememberTerminalUuidLocked(invite.callUuid)
            cancelActiveJobsLocked()
            _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Failed)
            invite
        }
        leaveInvite(failed)
        return true
    }

    /** Completes only the local room whose record is being closed. */
    fun onRoomClosed(recordId: String) {
        synchronized(lock) {
            val current = _state.value
            if (current is CallLifecycleState.InRoom && current.recordId == recordId) {
                pendingOpening = null
                val invite = current.invite
                _state.value = if (invite != null) {
                    CallLifecycleState.Terminal(invite, TerminalReason.Completed)
                } else {
                    CallLifecycleState.Idle
                }
            }
        }
    }

    private fun leaveInvite(invite: CallInvite) {
        val uuid = invite.callUuid ?: return
        launchBestEffort { signaling.leave(uuid, invite.configGeneration) }
    }

    private fun startExpiryLocked(invite: CallInvite) {
        expiryJob?.cancel()
        if (ringingTimeoutMs <= 0L) return
        expiryJob = scope.launch {
            delay(ringingTimeoutMs)
            expireInvite(invite.inviteGeneration)
        }
    }

    private fun expireInvite(inviteGeneration: Long) {
        val expired: CallInvite = synchronized(lock) {
            val current = _state.value
            if (current !is CallLifecycleState.Ringing || current.invite.inviteGeneration != inviteGeneration) return
            val invite = current.invite
            rememberTerminalUuidLocked(invite.callUuid)
            cancelActiveJobsLocked()
            _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Expired)
            invite
        }
        leaveInvite(expired)
        launchBestEffort { onMissedInvite(expired, TerminalReason.Expired) }
    }

    private fun roomOwnsLifecycleLocked(): Boolean = _state.value is CallLifecycleState.InRoom

    private fun rememberTerminalUuidLocked(uuid: String?) {
        if (uuid == null || !terminalUuids.add(uuid)) return
        if (terminalUuids.size > MAX_TERMINAL_UUIDS) terminalUuids.remove(terminalUuids.first())
    }

    private fun activeInviteLocked(): CallInvite? = when (val current = _state.value) {
        is CallLifecycleState.Ringing -> current.invite
        is CallLifecycleState.Answering -> current.invite
        is CallLifecycleState.JoiningUrl -> current.invite
        is CallLifecycleState.Connecting -> current.invite
        is CallLifecycleState.InRoom -> current.invite
        is CallLifecycleState.Terminal, CallLifecycleState.Idle -> null
    }

    private fun cancelActiveJobsLocked() {
        expiryJob?.cancel()
        expiryJob = null
        answerJob?.cancel()
        answerJob = null
    }

    private fun launchBestEffort(action: suspend () -> Unit) {
        scope.launch {
            action()
        }
    }

    companion object {
        const val DEFAULT_RINGING_TIMEOUT_MS = 30_000L
        private const val MAX_TERMINAL_UUIDS = 128
    }
}
