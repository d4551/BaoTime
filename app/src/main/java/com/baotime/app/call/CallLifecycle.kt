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
    private val answerCall: suspend (callUuid: String) -> FaceTimeSessionResult,
    private val leaveCall: suspend (callUuid: String) -> Boolean,
    private val onMissedInvite: suspend (invite: CallInvite, reason: TerminalReason) -> Unit = { _, _ -> },
    private val onAnswerFailed: (invite: CallInvite, failure: ServerFailure) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private val inviteCounter = AtomicLong(0L)
    private var currentConfigGeneration: Long = initialConfigGeneration
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
            if (configGeneration != currentConfigGeneration) return false
            val active = activeInviteLocked()
            if (active != null && active.callUuid == cleanUuid) return false
            if (_state.value is CallLifecycleState.Ringing || _state.value is CallLifecycleState.Answering) {
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
            _state.value = CallLifecycleState.Ringing(next)
            startExpiryLocked(next)
            next
        }
        displacedInvite?.let { previous ->
            val prevUuid = previous.callUuid
            if (prevUuid != null && prevUuid != cleanUuid) launchBestEffort { leaveCall(prevUuid) }
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
            if (configGeneration != currentConfigGeneration) return false
            val current = _state.value
            val active = activeInviteLocked()
            if (active != null && active.source == InviteSource.NlsUrl && active.faceTimeUrl == cleanUrl) return false
            if ((current is CallLifecycleState.Ringing || current is CallLifecycleState.Answering) && active?.callUuid != null) {
                return false
            }
            if (current is CallLifecycleState.Ringing || current is CallLifecycleState.Answering) {
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
            _state.value = CallLifecycleState.Ringing(next)
            startExpiryLocked(next)
            next
        }
        displacedInvite?.let { previous ->
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
        synchronized(lock) {
            if (configGeneration != currentConfigGeneration) return false
            val active = activeInviteLocked()
            if (active != null && active.source == InviteSource.SocketLegacy &&
                active.caller == cleanCaller && active.legacyTimestampMs == timestampMs
            ) {
                return false
            }
            if (!uiEnabled || _state.value is CallLifecycleState.Ringing || _state.value is CallLifecycleState.Answering) {
                return false
            }
            val next = CallInvite(
                callUuid = null,
                faceTimeUrl = null,
                caller = cleanCaller,
                configGeneration = configGeneration,
                inviteGeneration = inviteCounter.incrementAndGet(),
                source = InviteSource.SocketLegacy,
                legacyTimestampMs = timestampMs
            )
            _state.value = CallLifecycleState.Ringing(next)
            startExpiryLocked(next)
            return true
        }
    }

    /** User tapped Accept on the currently ringing invitation. */
    fun accept(): Boolean {
        val invite: CallInvite = synchronized(lock) {
            val current = _state.value
            if (current !is CallLifecycleState.Ringing) return false
            val ringing = current.invite
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
                    _state.value = CallLifecycleState.Terminal(ringing, TerminalReason.Failed)
                    onAnswerFailed(ringing, ServerFailure.EmptyLink)
                    return false
                }
            }
        }
        val uuid = requireNotNull(invite.callUuid)
        val job = scope.launch {
            val outcome = answerCall(uuid)
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
                        _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Failed)
                        notifyFailure = ServerFailure.EmptyLink
                    }
                }
                is FaceTimeSessionResult.Failed -> {
                    _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Failed)
                    notifyFailure = result.failure
                }
            }
        }
        if (shouldLeaveLateSuccess && invite.callUuid != null) {
            launchBestEffort { leaveCall(invite.callUuid) }
        }
        notifyFailure?.let { failure -> onAnswerFailed(invite, failure) }
    }

    /** User tapped Decline (or dismissed the incoming overlay). */
    fun decline(): Boolean {
        val declined: CallInvite = synchronized(lock) {
            val invite = when (val current = _state.value) {
                is CallLifecycleState.Ringing -> current.invite
                is CallLifecycleState.Answering -> current.invite
                else -> return false
            }
            cancelActiveJobsLocked()
            if (invite.callUuid != null) _state.value = CallLifecycleState.Declining(invite)
            _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Declined)
            invite
        }
        declined.callUuid?.let { uuid -> launchBestEffort { leaveCall(uuid) } }
        launchBestEffort { onMissedInvite(declined, TerminalReason.Declined) }
        return true
    }

    /** Handles `ft-call-status-changed` with `status_id == 6` (`DISCONNECTED`) for the active UUID only. */
    fun onCallDisconnected(callUuid: String, configGeneration: Long = this.configGeneration): Boolean {
        val cleanUuid = callUuid.trim()
        if (cleanUuid.isEmpty()) return false
        var wasAnswering = false
        val terminated: CallInvite = synchronized(lock) {
            if (configGeneration != currentConfigGeneration) return false
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
        if (wasAnswering) launchBestEffort { leaveCall(cleanUuid) }
        launchBestEffort { onMissedInvite(terminated, TerminalReason.Disconnected) }
        return true
    }

    /** Invoked when `AppConfig.connectionRevision` changes after Settings save. */
    fun onConfigChanged(newConfigGeneration: Long) {
        synchronized(lock) {
            if (newConfigGeneration == currentConfigGeneration) return
            currentConfigGeneration = newConfigGeneration
            cancelActiveJobsLocked()
            val active = activeInviteLocked()
            if (active != null && _state.value !is CallLifecycleState.InRoom && _state.value !is CallLifecycleState.Terminal) {
                _state.value = CallLifecycleState.Terminal(active, TerminalReason.CancelledByConfig)
            }
        }
    }

    /** Records that the WebView room overlay has opened for [url] and [recordId]. */
    fun onRoomOpened(url: String, recordId: String) {
        synchronized(lock) {
            _state.value = CallLifecycleState.InRoom(activeInviteLocked(), url, recordId)
        }
    }

    /** Records that the WebView room overlay has closed (`admitAndLeave` already left outgoing rooms on Mac). */
    fun onRoomClosed() {
        synchronized(lock) {
            val current = _state.value
            if (current is CallLifecycleState.InRoom) {
                val invite = current.invite
                _state.value = if (invite != null) {
                    CallLifecycleState.Terminal(invite, TerminalReason.Completed)
                } else {
                    CallLifecycleState.Idle
                }
            }
        }
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
            cancelActiveJobsLocked()
            _state.value = CallLifecycleState.Terminal(invite, TerminalReason.Expired)
            invite
        }
        expired.callUuid?.let { uuid -> launchBestEffort { leaveCall(uuid) } }
        launchBestEffort { onMissedInvite(expired, TerminalReason.Expired) }
    }

    private fun activeInviteLocked(): CallInvite? = when (val current = _state.value) {
        is CallLifecycleState.Ringing -> current.invite
        is CallLifecycleState.Answering -> current.invite
        is CallLifecycleState.JoiningUrl -> current.invite
        is CallLifecycleState.Connecting -> current.invite
        is CallLifecycleState.InRoom -> current.invite
        is CallLifecycleState.Declining -> current.invite
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
    }
}
