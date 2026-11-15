package com.baotime.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** Mounted record identity, call status, and actions. */
internal class RecordDetailUi(
    theme: BaoTimeTheme,
    title: String,
    val subtitle: RecentsTabUi.Caption,
    onJoin: () -> Unit,
    onShare: () -> Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    private val heading = BaoTimeComponents.titleText(theme, title).apply { isAccessibilityHeading = true }
    private val linkActions = subtitle.record.faceTimeUrl?.let { LinkActions(theme, onJoin, onShare, onCopy) }
    private val delete = BaoTimeComponents.ActionRow(theme, R.drawable.ic_delete, R.string.action_delete,
        destructive = true, onClick = onDelete)

    val root: FrameLayout = ModalSheetUi.build(theme, ModalSheetUi.contentColumn(theme).apply {
        addView(heading)
        addView(subtitle.view)
        linkActions?.let {
            addView(it.join)
            addView(it.copy.view)
            addView(it.share.view)
            addView(it.feedback)
        }
        addView(delete.view)
    }, onDismiss)

    fun updateTheme(theme: BaoTimeTheme) {
        BaoTimeComponents.styleTitle(theme, heading)
        subtitle.updateTheme(theme)
        linkActions?.updateTheme(theme)
        delete.updateTheme(theme)
    }

    private class LinkActions(theme: BaoTimeTheme, onJoin: () -> Unit, onShare: () -> Boolean, onCopy: () -> Unit) {
        val join = BaoTimeComponents.actionButton(theme, theme.context.getString(R.string.action_join_link),
            params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = theme.spaceLg }) { onJoin() }
        val copy = BaoTimeComponents.ActionRow(theme, R.drawable.ic_copy, R.string.action_copy, onClick = onCopy)
        val feedback = BaoTimeComponents.actionFailure(theme, R.string.share_unavailable)
        val share = BaoTimeComponents.ActionRow(theme, R.drawable.ic_share, R.string.desc_share_link) {
            if (!onShare()) feedback.visibility = View.VISIBLE
        }

        fun updateTheme(theme: BaoTimeTheme) {
            BaoTimeComponents.styleActionButton(theme, join)
            copy.updateTheme(theme)
            share.updateTheme(theme)
            BaoTimeComponents.styleCaption(theme, feedback, theme.colorDanger)
        }
    }
}
