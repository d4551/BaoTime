package com.baotime.app.call

import android.widget.Toast
import com.baotime.app.R
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.FaceTimeAvailabilityResult
import com.baotime.app.network.FaceTimeSessionResult
import com.baotime.app.routing.RoutedAction
import com.baotime.app.routing.UrlRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Admits and opens call links: outgoing create, incoming web-link join, direct and recorded
 * links. Owns the single in-flight outgoing job; presentation is delegated to the [Host].
 */
class CallLinkController(
    private val host: Host,
    private val callLifecycle: CallLifecycle,
    private val history: CallHistory,
    private val appConfig: AppConfig,
    private val bbClient: BlueBubblesClient,
    private val contacts: ContactsRepository,
    private val scope: CoroutineScope,
    private val errorHandler: CoroutineExceptionHandler
) {
    /** Activity/UI surface the admission flow needs; keeps the controller free of chrome ownership. */
    interface Host {
        val isDead: Boolean
        val warningColor: Int
        fun string(resId: Int): String
        fun connectionState(text: String, color: Int)
        fun dismissSheets()
        fun openSession(url: String, title: String, recordId: String)
        fun toast(resId: Int, length: Int)
    }

    private val outgoingJob = AtomicReference<Job?>(null)

    val isOutgoingActive: Boolean get() = outgoingJob.get() != null

    fun submitHandleInput(raw: String) {
        val ftUrl = UrlRouter.wholeFaceTimeUrl(raw)
        if (ftUrl != null) openRecordedWebLink(ftUrl, host.string(R.string.link_label)) else startOutgoingCall(raw)
    }

    fun joinIncomingWebLink(action: RoutedAction.JoinFaceTimeWebLink) {
        if (!UrlRouter.isFaceTimeRoomUrl(action.url)) return
        val caller = action.callerOrTitle ?: host.string(R.string.default_call_label)
        val invite = action.acceptedInvite
        if (invite == null) {
            openRecordedWebLink(action.url, caller,
                if (action.isIncomingAnswer) BaoCallRecord.TYPE_INCOMING else BaoCallRecord.TYPE_OUTGOING)
            return
        }
        if (cancelOutgoingCall()) launchSafely { bbClient.checkServerStatus() }
        val joining = scope.launch(errorHandler) {
            val name = contacts.lookupContactName(caller) ?: caller
            val joiningJob = coroutineContext.job
            withContext(NonCancellable) {
                if (host.isDead || !callLifecycle.isPendingRoom(action.url, invite.inviteGeneration)) return@withContext
                val recordId = history.admitCall(caller, name, action.url, BaoCallRecord.TYPE_INCOMING) { recordId ->
                    appConfig.admitConnectionOperation(invite.configGeneration) {
                        callLifecycle.onRoomOpened(action.url, recordId, invite.inviteGeneration, joiningJob)
                    }
                } ?: return@withContext
                presentCallSession(action.url, name, recordId)
            }
        }
        joining.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                callLifecycle.failPendingRoom(action.url, invite.inviteGeneration)
            }
        }
    }

    /** Reserves a direct link before contact IO and records it only at atomic admission. */
    fun openRecordedWebLink(url: String, title: String, callType: Int = BaoCallRecord.TYPE_OUTGOING) {
        if (!UrlRouter.isFaceTimeRoomUrl(url)) return
        if (cancelOutgoingCall()) launchSafely { bbClient.checkServerStatus() }
        val revision = appConfig.connectionRevision()
        val opening = callLifecycle.beginRoomOpening()
        scope.launch(errorHandler) {
            val name = contacts.lookupContactName(title) ?: title
            val openingJob = coroutineContext.job
            withContext(NonCancellable) {
                if (host.isDead || !callLifecycle.isCurrentRoomOpening(opening)) return@withContext
                val recordId = history.admitCall(title, name, url, callType) { recordId ->
                    appConfig.admitConnectionOperation(revision) {
                        callLifecycle.onRoomOpened(url, recordId, opening, openingJob)
                    }
                } ?: return@withContext
                presentCallSession(url, name, recordId)
            }
        }.invokeOnCompletion {
            // Handles cancellation before this direct opening's coroutine body starts too.
            callLifecycle.cancelRoomOpening(opening)
        }
    }

    /**
     * Creates a FaceTime link, records it, and opens it. A failed create shows a toast
     * and does not open a page.
     *
     * Per `facetimeRouter.ts` @ `76d2b2e`, `POST /api/v1/facetime/session` takes `{}` with no
     * callee handle; `GET /api/v1/handle/availability/facetime` is strictly a non-blocking advisory.
     */
    fun startOutgoingCall(targetHandle: String, displayOverride: String? = null, expectedRevision: Long? = null) {
        val creating = scope.launch(errorHandler, start = CoroutineStart.LAZY) {
            val revision = appConfig.connectionRevision()
            if (expectedRevision != null && revision != expectedRevision) return@launch
            val opening = callLifecycle.beginRoomOpening()
            coroutineContext.job.invokeOnCompletion { callLifecycle.cancelRoomOpening(opening) }
            ensureActive()
            host.connectionState(host.string(R.string.call_generating), host.warningColor)
            createSession(targetHandle, displayOverride, opening, revision)
        }
        if (!outgoingJob.compareAndSet(null, creating)) {
            creating.cancel()
            return
        }
        creating.invokeOnCompletion {
            if (outgoingJob.compareAndSet(creating, null)) launchSafely { bbClient.checkServerStatus() }
        }
        creating.start()
    }

    fun cancelOutgoingCall(): Boolean {
        val creating = outgoingJob.getAndSet(null) ?: return false
        creating.cancel()
        host.connectionState(host.string(R.string.banner_checking), host.warningColor)
        return true
    }

    private suspend fun createSession(targetHandle: String, displayOverride: String?, opening: Long, revision: Long) {
        val trimmedHandle = targetHandle.trim()
        if (trimmedHandle.isNotEmpty() && UrlRouter.wholeFaceTimeUrl(trimmedHandle) == null) {
            val availability = bbClient.checkFaceTimeAvailability(trimmedHandle, revision)
            if (!callLifecycle.isCurrentRoomOpening(opening) || appConfig.connectionRevision() != revision) return
            if (availability == FaceTimeAvailabilityResult.Unavailable) {
                host.toast(R.string.call_advisory_unavailable, Toast.LENGTH_SHORT)
            }
        }
        val result = bbClient.createFaceTimeSession(revision)
        if (!callLifecycle.isCurrentRoomOpening(opening) || appConfig.connectionRevision() != revision) return
        when (result) {
            is FaceTimeSessionResult.Created -> {
                val label = resolveOutgoingLabel(targetHandle, displayOverride)
                val creatingJob = currentCoroutineContext().job
                withContext(NonCancellable) {
                    if (host.isDead || !callLifecycle.isCurrentRoomOpening(opening)) return@withContext
                    val recordId = history.admitCall(targetHandle, label, result.link,
                        BaoCallRecord.TYPE_OUTGOING) { recordId ->
                        appConfig.admitConnectionOperation(revision) {
                            callLifecycle.onRoomOpened(result.link, recordId, opening, creatingJob)
                        }
                    } ?: return@withContext
                    presentCallSession(result.link, label, recordId)
                }
            }
            is FaceTimeSessionResult.Failed -> {
                host.toast(R.string.call_create_failed, Toast.LENGTH_LONG)
            }
        }
    }

    private suspend fun resolveOutgoingLabel(targetHandle: String, displayOverride: String?): String {
        if (!displayOverride.isNullOrBlank()) return displayOverride
        if (targetHandle.isBlank()) return host.string(R.string.new_link_label)
        return contacts.lookupContactName(targetHandle) ?: targetHandle
    }

    fun openCallSession(url: String, title: String, recordId: String) {
        if (callLifecycle.onRoomOpened(url, recordId)) {
            if (cancelOutgoingCall()) launchSafely { bbClient.checkServerStatus() }
            presentCallSession(url, title, recordId)
        }
    }

    /** Presentation is allowed only for the record that owns the committed logical room. */
    private fun presentCallSession(url: String, title: String, recordId: String) {
        val current = callLifecycle.state.value
        if (host.isDead) {
            callLifecycle.onRoomClosed(recordId)
        } else if (current is CallLifecycleState.InRoom && current.recordId == recordId) {
            host.dismissSheets()
            host.openSession(url, title, recordId)
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch(errorHandler) { block() }
    }
}