package com.baotime.app.call

import android.view.View
import android.widget.FrameLayout
import com.baotime.app.R
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.routing.RoutedAction
import com.baotime.app.ui.IncomingCallUi
import com.baotime.app.ui.theme.BaoTimeTheme

/** Owns one unanswered incoming invitation and its one-shot accept/decline transition. */
class IncomingCallController(
    private val host: FrameLayout,
    private val theme: BaoTimeTheme,
    private val history: CallHistory,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onAccept: (RoutedAction.JoinFaceTimeWebLink) -> Unit,
    private val onHistoryChanged: () -> Unit
) {
    private var pending: Pending? = null
    val isShowing: Boolean get() = pending != null

    fun show(action: RoutedAction.JoinFaceTimeWebLink) {
        val previous = pending
        if (previous != null && previous.action.url != action.url) decline()
        else removePending()
        val caller = action.callerOrTitle?.takeIf { it.isNotBlank() }
            ?: theme.context.getString(R.string.default_call_label)
        val view = IncomingCallUi.build(theme, caller,
            onAccept = { removePending()?.let { onAccept(it.action) } }, onDecline = ::decline)
        pending = Pending(action, caller, view)
        host.addView(view)
    }

    fun decline() {
        val invitation = removePending() ?: return
        launch {
            history.recordCall(invitation.caller, invitation.caller,
                invitation.action.url, BaoCallRecord.TYPE_MISSED)
            onHistoryChanged()
        }
    }

    fun dispose() { removePending() }

    private fun removePending(): Pending? {
        val previous = pending ?: return null
        pending = null
        host.removeView(previous.view)
        return previous
    }

    private data class Pending(val action: RoutedAction.JoinFaceTimeWebLink, val caller: String, val view: View)
}
