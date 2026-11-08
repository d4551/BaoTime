package com.baotime.app.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.baotime.app.R
import com.baotime.app.data.ContactsOutcome
import com.baotime.app.data.DeviceContact
import com.baotime.app.routing.UrlRouter
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/**
 * Renders the Contacts tab: a New call card with one fully editable handle
 * field (number, Apple ID, or FaceTime link) and FaceTime/system-dial icon
 * actions, then iMessage (BlueBubbles) and device contact sections.
 * There is deliberately no numeric keypad — the full keyboard covers emails.
 */
object ContactsTabUi {

    /** Contact sections: favorites, iMessage (with sync state), and device. */
    fun renderSections(
        theme: BaoTimeTheme,
        container: LinearLayout,
        outcome: ContactsOutcome,
        iMessageContacts: List<DeviceContact>,
        iMessageReachable: Boolean?,
        favorites: List<DeviceContact>,
        query: String,
        expandAll: Boolean,
        onOpenActions: (DeviceContact) -> Unit,
        onExpandAll: () -> Unit,
        animate: Boolean = true
    ) {
        val context = theme.context
        val normalizedQuery = query.trim().lowercase()
        fun matches(contact: DeviceContact): Boolean =
            normalizedQuery.isEmpty() ||
                contact.displayName.lowercase().contains(normalizedQuery) ||
                contact.primaryAddress.lowercase().contains(normalizedQuery)

        var rowIndex = 0
        fun addSection(titleRes: Int, contacts: List<DeviceContact>) {
            if (contacts.isEmpty()) return
            container.addView(sectionHeader(theme, titleRes))
            val visible = if (expandAll) contacts else contacts.take(SECTION_INITIAL_ROWS)
            for (contact in visible) {
                val row = buildRow(theme, contact, onOpenActions)
                container.addView(row)
                if (animate && rowIndex < ANIMATED_ROWS) BaoTimeComponents.animateRowIn(row, rowIndex)
                rowIndex++
            }
            if (!expandAll && contacts.size > SECTION_INITIAL_ROWS) {
                val remaining = contacts.size - SECTION_INITIAL_ROWS
                container.addView(
                    BaoTimeComponents.actionButton(
                        theme,
                        context.resources.getQuantityString(R.plurals.contacts_show_all, remaining, remaining),
                        theme.colorSurfaceHigh,
                        theme.colorTextPrimary
                    ) { onExpandAll() }
                )
            }
        }

        val matchingMac = iMessageContacts.filter(::matches)
        val matchingDevice = when (outcome) {
            is ContactsOutcome.Records -> outcome.contacts.filter(::matches)
            ContactsOutcome.Denied -> emptyList()
        }
        addSection(R.string.contacts_favorites_header, favorites.filter(::matches))
        if (iMessageReachable == false) {
            container.addView(
                BaoTimeComponents.captionText(theme, context.getString(R.string.contacts_sync_offline))
            )
        }
        addSection(R.string.contacts_imessage_header, matchingMac)

        when (outcome) {
            ContactsOutcome.Denied -> {
                container.addView(BaoTimeComponents.screenNote(theme, context.getString(R.string.contacts_denied)))
                container.addView(
                    BaoTimeComponents.actionButton(
                        theme,
                        context.getString(R.string.contacts_grant),
                        params = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = theme.spaceMd }
                    ) { SystemIntegration.openAppSettings(context) }
                )
            }
            is ContactsOutcome.Records -> {
                addSection(R.string.contacts_device_header, matchingDevice)
                if (matchingDevice.isEmpty() && matchingMac.isEmpty()) {
                    container.addView(BaoTimeComponents.screenNote(theme, context.getString(
                        if (query.isNotBlank()) R.string.contacts_no_matches else R.string.contacts_empty)))
                }
            }
        }
    }

    private const val SECTION_INITIAL_ROWS = 30
    private const val ANIMATED_ROWS = 8

    fun loadingNote(theme: BaoTimeTheme): View =
        BaoTimeComponents.screenNote(theme, theme.context.getString(R.string.state_loading))

    private fun sectionHeader(theme: BaoTimeTheme, titleRes: Int): View =
        BaoTimeComponents.captionText(theme, theme.context.getString(titleRes)).apply {
            setTextColor(theme.colorTextPrimary)
            setPadding(0, theme.spaceMd, 0, theme.spaceXs)
        }

    private fun buildRow(
        theme: BaoTimeTheme,
        contact: DeviceContact,
        onOpenActions: (DeviceContact) -> Unit
    ): View {
        val context = theme.context
        val kindRes = if (contact.isEmail) R.string.contact_kind_email_fmt else R.string.contact_kind_phone_fmt
        return BaoTimeComponents.listRow(
            theme = theme,
            monogram = contact.displayName.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
            title = contact.displayName,
            caption = context.getString(kindRes, contact.primaryAddress),
            actions = emptyList(),
            showChevron = true
        ).apply {
            setOnClickListener {
                onOpenActions(contact)
            }
        }
    }
}
