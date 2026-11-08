package com.baotime.app.call

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baotime.app.BaoTimeApp
import com.baotime.app.R
import com.baotime.app.routing.RoutedAction
import com.baotime.app.ui.IncomingCallUi
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.launch

/** Owns one unanswered incoming invitation and its one-shot accept/decline transition. */
class IncomingCallController(
    private val host: FrameLayout,
    private val theme: BaoTimeTheme,
    private val lifecycle: CallLifecycle,
    private val onAccept: (RoutedAction.JoinFaceTimeWebLink) -> Unit
) {
    private var pending: Pending? = null
    private var lastJoinedGeneration: Long = -1L

    val isShowing: Boolean get() = pending != null

    /** Collects process-wide [CallLifecycle] and [BaoTimeApp] streams while [activity] is STARTED. */
    fun bindProcessEvents(
        activity: ComponentActivity,
        app: BaoTimeApp,
        onDismissSheets: () -> Unit,
        onHistoryUpdated: () -> Unit,
        onAnswerFailed: suspend () -> Unit
    ) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    lifecycle.state.collect { state ->
                        if (state is CallLifecycleState.Ringing || state is CallLifecycleState.Answering) {
                            onDismissSheets()
                        } else {
                            IncomingPushReceiver.cancelAllCallNotifications(activity)
                        }
                        syncState(state)
                    }
                }
                launch { app.historyUpdated.collect { onHistoryUpdated() } }
                launch { app.answerFailures.collect { onAnswerFailed() } }
            }
        }
    }

    /** Entry point for NLS URL-only invitations delivered via Activity intent. */
    fun show(action: RoutedAction.JoinFaceTimeWebLink) {
        val caller = action.callerOrTitle?.takeIf { it.isNotBlank() }
            ?: theme.context.getString(R.string.default_call_label)
        lifecycle.onIncomingNlsUrl(action.url, caller)
        syncState(lifecycle.state.value)
    }

    /** Synchronizes the Activity view hierarchy with the current [CallLifecycleState]. */
    fun syncState(state: CallLifecycleState) {
        when (state) {
            is CallLifecycleState.Ringing -> renderInvite(state.invite)
            is CallLifecycleState.Answering -> renderInvite(state.invite)
            is CallLifecycleState.JoiningUrl -> {
                removePending()
                if (lastJoinedGeneration != state.invite.inviteGeneration) {
                    lastJoinedGeneration = state.invite.inviteGeneration
                    val caller = state.invite.caller.takeIf { it.isNotBlank() }
                        ?: theme.context.getString(R.string.default_call_label)
                    onAccept(
                        RoutedAction.JoinFaceTimeWebLink(
                            url = state.url,
                            callerOrTitle = caller,
                            isIncomingAnswer = true
                        )
                    )
                }
            }
            is CallLifecycleState.Connecting -> {
                removePending()
                if (lastJoinedGeneration != state.invite.inviteGeneration) {
                    lastJoinedGeneration = state.invite.inviteGeneration
                    val caller = state.invite.caller.takeIf { it.isNotBlank() }
                        ?: theme.context.getString(R.string.default_call_label)
                    onAccept(
                        RoutedAction.JoinFaceTimeWebLink(
                            url = state.url,
                            callerOrTitle = caller,
                            isIncomingAnswer = true
                        )
                    )
                }
            }
            is CallLifecycleState.Declining,
            is CallLifecycleState.InRoom,
            is CallLifecycleState.Terminal,
            CallLifecycleState.Idle -> removePending()
        }
    }

    /** Declines the active invitation and removes the overlay view. */
    fun decline() {
        removePending()
        lifecycle.decline()
    }

    /** Unbinds the view on Activity destruction without terminating the [CallLifecycle] invite. */
    fun dispose() {
        removePending()
    }

    private fun renderInvite(invite: CallInvite) {
        val existing = pending
        if (existing != null && existing.inviteGeneration == invite.inviteGeneration) {
            return
        }
        removePending()
        val caller = invite.caller.takeIf { it.isNotBlank() }
            ?: theme.context.getString(R.string.default_call_label)
        val view = IncomingCallUi.build(
            theme = theme,
            caller = caller,
            onAccept = {
                lifecycle.accept()
                syncState(lifecycle.state.value)
            },
            onDecline = ::decline
        )
        pending = Pending(invite.inviteGeneration, caller, view)
        host.addView(view)
    }

    private fun removePending(): Pending? {
        val previous = pending ?: return null
        pending = null
        host.removeView(previous.view)
        return previous
    }

    private data class Pending(val inviteGeneration: Long, val caller: String, val view: View)
}
