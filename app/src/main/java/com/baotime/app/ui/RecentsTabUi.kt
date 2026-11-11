package com.baotime.app.ui

import android.view.View
import android.widget.LinearLayout
import com.baotime.app.R
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders one row per history record, with one call action and a detail disclosure.
 * Sharing and record management stay in the detail sheet.
 */
object RecentsTabUi {

    private const val TIMESTAMP_FORMAT = "MMM d, HH:mm"

    fun populate(
        theme: BaoTimeTheme,
        container: LinearLayout,
        records: List<BaoCallRecord>,
        onJoin: (BaoCallRecord) -> Unit,
        onCall: (BaoCallRecord) -> Unit,
        onOpenDetail: (BaoCallRecord) -> Unit
    ) {
        if (records.isEmpty()) {
            container.addView(BaoTimeComponents.screenNote(theme, theme.context.getString(R.string.recents_empty)))
        } else {
            records.forEachIndexed { index, record ->
                val row = buildRow(theme, record, onJoin, onCall)
                row.setOnClickListener { onOpenDetail(record) }
                container.addView(row)
                BaoTimeComponents.animateRowIn(row, index)
            }
        }
    }

    fun loadingNote(theme: BaoTimeTheme): View =
        BaoTimeComponents.screenNote(theme, theme.context.getString(R.string.state_loading))

    private fun buildRow(
        theme: BaoTimeTheme,
        record: BaoCallRecord,
        onJoin: (BaoCallRecord) -> Unit,
        onCall: (BaoCallRecord) -> Unit
    ): View {
        val hasLink = record.faceTimeUrl != null
        val actions = listOf(
            BaoTimeComponents.RowAction(
                iconRes = R.drawable.ic_video_call,
                descriptionRes = if (hasLink) R.string.desc_join_call else R.string.desc_facetime_call,
                bgColor = theme.colorBaoGreen,
                iconTint = theme.colorOnAccent
            ) {
                if (hasLink) onJoin(record) else onCall(record)
            }
        )
        // Identity first: the handle is the title; a generic label never is.
        val title = record.handleOrNumber.ifBlank { record.displayName }
        val caption = if (record.displayName != title && record.displayName.isNotBlank()) {
            theme.context.getString(R.string.recents_caption_fmt, record.displayName,
                describe(theme, record, includeDuration = false))
        } else {
            describe(theme, record, includeDuration = false)
        }
        val (badgeIcon, badgeDesc) = when (record.callType) {
            BaoCallRecord.TYPE_OUTGOING -> R.drawable.ic_call_outgoing to R.string.call_type_outgoing
            BaoCallRecord.TYPE_MISSED -> R.drawable.ic_call_missed to R.string.call_type_missed
            else -> R.drawable.ic_call_incoming to R.string.call_type_incoming
        }
        return BaoTimeComponents.listRow(
            theme = theme,
            monogram = "",
            title = title,
            caption = caption,
            actions = actions,
            badgeIconRes = badgeIcon,
            badgeTint = if (record.callType == BaoCallRecord.TYPE_MISSED) {
                theme.colorDanger
            } else {
                theme.colorTextSecondary
            },
            badgeDescriptionRes = badgeDesc,
            showChevron = true
        )
    }

    fun describe(theme: BaoTimeTheme, record: BaoCallRecord, includeDuration: Boolean = true): String {
        val context = theme.context
        val direction = context.getString(
            when (record.callType) {
                BaoCallRecord.TYPE_OUTGOING -> R.string.call_type_outgoing
                BaoCallRecord.TYPE_MISSED -> R.string.call_type_missed
                else -> R.string.call_type_incoming
            }
        )
        val date = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.getDefault()).format(Date(record.timestampMs))
        val duration = if (includeDuration && record.durationSec > 0) {
            context.getString(R.string.call_duration_fmt, formatDuration(record.durationSec))
        } else ""
        return context.getString(R.string.recents_caption_fmt, direction, "$date$duration")
    }

    /** mm:ss, or h:mm:ss past an hour. */
    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
        }
    }
}
