package com.baotime.app.call

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baotime.app.R
import com.baotime.app.routing.RoutedAction
import com.baotime.app.ui.IncomingCallUi
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.launch

/** Owns one unanswered incoming invitation and its one-shot accept/decline transition. */
class IncomingCallController(
    private val host: FrameLayout,
    theme: BaoTimeTheme,
    private val lifecycle: CallLifecycle,
    private val onAccept: (RoutedAction.JoinFaceTimeWebLink) -> Unit
) {
    private var theme = theme
    private var pending: Pending? = null
    private var lastJoinedGeneration: Long = -1L

    val isShowing: Boolean get() = pending != null

    internal fun updateTheme(theme: BaoTimeTheme) {
        this.theme = theme
        pending?.let { IncomingCallUi.updateTheme(it.view, theme) }
    }

    /** Collects process-wide call state while [activity] is STARTED. */
    fun bindProcessEvents(
        activity: ComponentActivity,
        onDismissSheets: () -> Unit,
        onLifecycleStateChanged: (CallLifecycleState) -> Unit = {},
        onAnswerFailed: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                lifecycle.state.collect { state ->
                    if (state is CallLifecycleState.Ringing || state is CallLifecycleState.Answering) {
                        onDismissSheets()
                    }
                    syncState(state)
                    onLifecycleStateChanged(state)
                    if (state is CallLifecycleState.Terminal && state.answerFailure != null &&
                        lifecycle.claimAnswerFailure(state.invite.inviteGeneration) != null
                    ) onAnswerFailed()
                }
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
            is CallLifecycleState.JoiningUrl -> joinOnce(state.invite, state.url)
            is CallLifecycleState.Connecting -> joinOnce(state.invite, state.url)
            is CallLifecycleState.InRoom,
            is CallLifecycleState.Terminal,
            CallLifecycleState.Idle -> removePending()
        }
    }

    /** Opens the accepted room exactly once per invite generation, whichever path produced the URL. */
    private fun joinOnce(invite: CallInvite, url: String) {
        removePending()
        if (lastJoinedGeneration == invite.inviteGeneration) return
        lastJoinedGeneration = invite.inviteGeneration
        // Accepting answers the invitation: the status-bar mirror alert must not linger.
        IncomingCallNotifications.cancelNlsUrlInvite(theme.context, url)
        val caller = invite.caller.takeIf { it.isNotBlank() }
            ?: theme.context.getString(R.string.default_call_label)
        onAccept(
            RoutedAction.JoinFaceTimeWebLink(
                url = url,
                callerOrTitle = caller,
                isIncomingAnswer = true,
                acceptedInvite = invite
            )
        )
    }

    /** Back acts on the invitation currently displayed by this controller. */
    fun decline() {
        val displayed = pending ?: return
        decline(displayed.inviteGeneration)
    }

    private fun decline(inviteGeneration: Long) {
        lifecycle.decline(inviteGeneration)
        syncState(lifecycle.state.value)
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
                lifecycle.accept(invite.inviteGeneration)
                syncState(lifecycle.state.value)
            },
            onDecline = { decline(invite.inviteGeneration) }
        )
        pending = Pending(invite.inviteGeneration, view)
        host.addView(view)
    }

    private fun removePending(): Pending? {
        val previous = pending ?: return null
        pending = null
        host.removeView(previous.view)
        return previous
    }

    private data class Pending(val inviteGeneration: Long, val view: FrameLayout)
}
