package com.baotime.app.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** Record detail sheet: identity, call status, and flat actions. */
object RecordDetailUi {

    fun build(
        theme: BaoTimeTheme,
        title: String,
        subtitle: String,
        link: String?,
        onJoin: () -> Unit,
        onShare: () -> Unit,
        onCopy: () -> Unit,
        onDelete: () -> Unit,
        onDismiss: () -> Unit
    ): FrameLayout {
        val content = ModalSheetUi.contentColumn(theme)
        content.addView(BaoTimeComponents.titleText(theme, title))
        content.addView(BaoTimeComponents.captionText(theme, subtitle))
        if (link != null) {
            content.addView(BaoTimeComponents.actionButton(
                theme,
                theme.context.getString(R.string.action_join_link),
                params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = theme.spaceLg }
            ) { onJoin() }.apply {
                contentDescription = theme.context.getString(R.string.desc_join_call)
            })
            content.addView(BaoTimeComponents.actionRow(
                theme, R.drawable.ic_copy, R.string.action_copy
            ) { onCopy() })
            content.addView(BaoTimeComponents.actionRow(
                theme, R.drawable.ic_share, R.string.desc_share_link
            ) { onShare() })
        }
        content.addView(BaoTimeComponents.actionRow(
            theme, R.drawable.ic_delete, R.string.action_delete, theme.colorDanger
        ) { onDelete() })
        return ModalSheetUi.build(theme, content, onDismiss)
    }
}
