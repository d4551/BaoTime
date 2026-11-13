package com.baotime.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.baotime.app.R
import com.baotime.app.data.ContactMatch
import com.baotime.app.data.DeviceContact
import com.baotime.app.data.MacContactsState
import com.baotime.app.system.OpenSettingsResult
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

object ContactsTabUi {

    internal class Section(
        theme: BaoTimeTheme,
        title: Int,
        onContact: (DeviceContact, Long?) -> Unit,
        onExpand: () -> Unit
    ) {
        private val header = BaoTimeComponents.captionText(theme, theme.context.getString(title)).apply {
            setTextColor(theme.colorTextPrimary)
            setPadding(0, theme.spaceMd, 0, theme.spaceXs)
            isAccessibilityHeading = true
        }
        private val rows = ContactRows(theme, showAddressKind = true, onContact = onContact)
        private val more = BaoTimeComponents.actionButton(theme, "", theme.colorSurfaceHigh, theme.colorTextPrimary) {
            onExpand()
        }
        val root = LinearLayout(theme.context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(header)
            addView(rows.root)
            addView(more)
        }

        fun render(contacts: List<ContactMatch>, expanded: Boolean, animateFrom: Int?): Int {
            val visible = if (expanded) contacts else contacts.take(SECTION_INITIAL_ROWS)
            rows.render(visible, animateFrom)
            val remaining = contacts.size - visible.size
            root.visibility = if (contacts.isNotEmpty()) View.VISIBLE else View.GONE
            more.visibility = if (remaining > 0) View.VISIBLE else View.GONE
            if (remaining > 0) {
                more.text = more.resources.getQuantityString(R.plurals.contacts_show_all, remaining, remaining)
            }
            return visible.size
        }
    }

    internal class ContactRows(
        theme: BaoTimeTheme,
        private val showAddressKind: Boolean,
        private val onContact: (DeviceContact, Long?) -> Unit
    ) {
        private var theme = theme
        val root = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        private var mounted = emptyList<Row>()

        fun updateTheme(theme: BaoTimeTheme) {
            this.theme = theme
            mounted.forEach { it.updateTheme(theme) }
        }

        private class Row(
            theme: BaoTimeTheme,
            showAddressKind: Boolean,
            initialMatch: ContactMatch,
            onContact: (DeviceContact, Long?) -> Unit
        ) {
            var match = initialMatch
            private val contact = initialMatch.contact
            private val kind = if (contact.isEmail) R.string.contact_kind_email_fmt else R.string.contact_kind_phone_fmt
            private val address = if (showAddressKind) theme.context.getString(kind, contact.primaryAddress)
                else contact.primaryAddress
            private val row = BaoTimeComponents.listRow(theme,
                contact.displayName.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                contact.displayName, BaoTimeComponents.captionText(theme, address), emptyList(), showChevron = true
            )
            val view = row.root.apply {
                setOnClickListener { onContact(match.contact, match.macRevision) }
            }

            fun updateTheme(theme: BaoTimeTheme) = row.updateTheme(theme)
        }

        fun render(contacts: List<ContactMatch>, animateFrom: Int? = null) {
            val available = mounted.groupBy { it.match.identity }.mapValues { (_, rows) -> ArrayDeque(rows) }
            val next = contacts.map { match ->
                val previous = available[match.identity]?.removeFirstOrNull()
                if (previous == null) Row(theme, showAddressKind, match, onContact)
                else previous.apply { this.match = match }
            }
            available.values.forEach { unused -> unused.forEach { root.removeView(it.view) } }
            next.forEachIndexed { index, row ->
                val current = root.indexOfChild(row.view)
                if (current == index) return@forEachIndexed
                if (current >= 0) root.removeView(row.view)
                root.addView(row.view, index)
                if (current < 0 && animateFrom != null && animateFrom + index < ANIMATED_ROWS) {
                    BaoTimeComponents.animateRowIn(row.view, animateFrom + index)
                }
            }
            mounted = next
        }
    }

    internal class Status(theme: BaoTimeTheme) {
        val view = BaoTimeComponents.screenNote(theme, "").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }

        fun show(message: Int?) {
            val text = message?.let(view.context::getString).orEmpty()
            view.visibility = if (message == null) View.GONE else View.VISIBLE
            if (view.text != text) view.text = text
        }
    }

    internal fun macStatus(state: MacContactsState?): Int? = when (state) {
        null, MacContactsState.Loading -> R.string.state_loading
        MacContactsState.Unavailable -> R.string.contacts_sync_offline
        MacContactsState.NotConfigured, MacContactsState.Ready -> null
    }

    internal fun emptyStatus(query: String, deviceOnly: Boolean = false): Int = when {
        deviceOnly && query.isBlank() -> R.string.contacts_device_empty
        deviceOnly -> R.string.contacts_device_no_matches
        query.isBlank() -> R.string.contacts_empty
        else -> R.string.contacts_no_matches
    }

    internal class PermissionRecovery(theme: BaoTimeTheme) {
        private val feedback = BaoTimeComponents.actionFailure(theme, R.string.setup_open_settings_failed)
        val root = LinearLayout(theme.context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(BaoTimeComponents.screenNote(theme, context.getString(R.string.contacts_denied)))
            addView(BaoTimeComponents.actionButton(theme, context.getString(R.string.contacts_grant),
                params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.spaceMd }) {
                val result = SystemIntegration.openAppSettings(context)
                feedback.visibility = if (result == OpenSettingsResult.NotResolvable) View.VISIBLE else View.GONE
            })
            addView(feedback)
        }

        fun update(denied: Boolean) {
            root.visibility = if (denied) View.VISIBLE else View.GONE
            if (!denied) feedback.visibility = View.GONE
        }
    }

    private const val SECTION_INITIAL_ROWS = 30
    private const val ANIMATED_ROWS = 8

}
