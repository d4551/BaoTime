package com.baotime.app.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.baotime.app.R
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme
import java.util.Locale

/**
 * Renders one row per history record, with one call action and a detail disclosure.
 * Sharing and record management stay in the detail sheet.
 */
object RecentsTabUi {

    class Caption(theme: BaoTimeTheme, record: BaoCallRecord, private val detail: Boolean = false) {
        var record = record
            private set
        val view = BaoTimeComponents.captionText(theme, describe(theme.context, record, detail))

        fun update(record: BaoCallRecord) {
            if (this.record == record) return
            this.record = record
            refresh()
        }

        internal fun updateTheme(theme: BaoTimeTheme) {
            BaoTimeComponents.styleCaption(theme, view)
            refresh()
        }

        fun refresh() {
            val text = describe(view.context, record, detail)
            if (view.text != text) view.text = text
        }
    }

    internal class Row(val view: View, val caption: Caption)

    internal fun render(
        theme: BaoTimeTheme,
        container: LinearLayout,
        mounted: List<Row>?,
        records: List<BaoCallRecord>,
        onJoin: (BaoCallRecord) -> Unit,
        onCall: (BaoCallRecord) -> Unit,
        onOpenDetail: (BaoCallRecord) -> Unit
    ): List<Row> {
        if (records.isEmpty()) {
            if (mounted?.isEmpty() != true) {
                container.removeAllViews()
                container.addView(BaoTimeComponents.screenNote(theme, theme.context.getString(R.string.recents_empty)))
            }
            return emptyList()
        }
        if (mounted.isNullOrEmpty()) container.removeAllViews()
        val available = mounted.orEmpty().groupBy { it.caption.record.copy(durationSec = 0L) }
            .mapValues { (_, rows) -> ArrayDeque(rows) }
        val rows = records.map { record ->
            val existing = available[record.copy(durationSec = 0L)]?.removeFirstOrNull()
            if (existing != null) {
                existing.caption.update(record)
                existing
            } else {
                val caption = Caption(theme, record)
                val row = buildRow(theme, caption, onJoin, onCall)
                row.primary.apply {
                    isFocusable = true
                    setOnClickListener { onOpenDetail(caption.record) }
                }
                Row(row.root, caption)
            }
        }
        available.values.forEach { unused -> unused.forEach { container.removeView(it.view) } }
        rows.forEachIndexed { index, row ->
            val currentIndex = container.indexOfChild(row.view)
            if (currentIndex == index) return@forEachIndexed
            if (currentIndex >= 0) container.removeView(row.view)
            container.addView(row.view, index)
            if (currentIndex < 0) BaoTimeComponents.animateRowIn(row.view, index)
        }
        return rows
    }

    fun loadingNote(theme: BaoTimeTheme): TextView =
        BaoTimeComponents.screenNote(theme, theme.context.getString(R.string.state_loading)).apply {
            id = R.id.recents_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

    fun showUnreadable(theme: BaoTimeTheme, container: LinearLayout) {
        val existing = container.findViewById<TextView>(R.id.recents_status)
        val status = existing ?: loadingNote(theme).also {
            container.removeAllViews()
            container.addView(it)
        }
        val message = theme.context.getString(R.string.recents_unreadable)
        status.setTextColor(theme.colorDanger)
        if (status.text != message) status.text = message
    }

    private fun buildRow(
        theme: BaoTimeTheme,
        caption: Caption,
        onJoin: (BaoCallRecord) -> Unit,
        onCall: (BaoCallRecord) -> Unit
    ): BaoTimeComponents.ListRow {
        val record = caption.record
        val hasLink = record.faceTimeUrl != null
        val actions = listOf(
            BaoTimeComponents.RowAction(
                iconRes = R.drawable.ic_video_call,
                descriptionRes = if (hasLink) R.string.desc_join_call else R.string.desc_facetime_call,
                bgColor = theme.colorBaoGreen,
                iconTint = theme.colorOnAccent
            ) {
                if (hasLink) onJoin(caption.record) else onCall(caption.record)
            }
        )
        val title = record.handleOrNumber.ifBlank { record.displayName }
        val badgeIcon = when (record.callType) {
            BaoCallRecord.TYPE_OUTGOING -> R.drawable.ic_call_outgoing
            BaoCallRecord.TYPE_MISSED -> R.drawable.ic_call_missed
            else -> R.drawable.ic_call_incoming
        }
        return BaoTimeComponents.listRow(
            theme = theme,
            monogram = "",
            title = title,
            caption = caption.view,
            actions = actions,
            badgeIconRes = badgeIcon,
            badgeTint = if (record.callType == BaoCallRecord.TYPE_MISSED) {
                theme.colorDanger
            } else {
                theme.colorTextSecondary
            },
            showChevron = true
        )
    }

    private fun describe(context: Context, record: BaoCallRecord, detail: Boolean): String {
        val direction = context.getString(
            when (record.callType) {
                BaoCallRecord.TYPE_OUTGOING -> R.string.call_type_outgoing
                BaoCallRecord.TYPE_MISSED -> R.string.call_type_missed
                else -> R.string.call_type_incoming
            }
        )
        val date = DateUtils.formatDateTime(context, record.timestampMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_NO_YEAR)
        val duration = if (detail && record.durationSec > 0) {
            context.getString(R.string.call_duration_fmt,
                formatDuration(record.durationSec, context.resources.configuration.locales[0]))
        } else ""
        val summary = context.getString(R.string.recents_caption_fmt, direction, "$date$duration")
        val showName = detail || (record.handleOrNumber.isNotBlank() && record.displayName.isNotBlank() &&
            record.displayName != record.handleOrNumber)
        return if (showName) context.getString(R.string.recents_caption_fmt, record.displayName, summary) else summary
    }

    /** mm:ss, or h:mm:ss past an hour. */
    private fun formatDuration(seconds: Long, locale: Locale): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(locale, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(locale, "%d:%02d", minutes, secs)
        }
    }
}
