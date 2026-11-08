package com.baotime.app.ui

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.baotime.app.R
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.ContactsOutcome
import com.baotime.app.data.DeviceContact
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** One sheet owns its input, listener and contact rows for its entire lifetime. */
class NewCallSheetUi(
    private val theme: BaoTimeTheme,
    initialHandle: String,
    private val onFaceTime: (DeviceContact) -> Unit,
    onFaceTimeNumber: (String) -> Unit,
    onDraftChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    private var snapshot: ContactsSnapshot? = null
    private val content = ModalSheetUi.contentColumn(theme)
    private val input = EditText(theme.context).apply {
        hint = context.getString(R.string.input_hint)
        setTextColor(theme.colorTextPrimary)
        setHintTextColor(theme.colorTextHint)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
        background = theme.fieldBackground()
        setPadding(0, theme.spaceMd, 0, theme.spaceMd)
        setSingleLine(true)
        setText(initialHandle)
        doAfterTextChanged { value -> onDraftChanged(value.toString()); renderRows() }
    }
    private val callButton = BaoTimeComponents.actionButton(theme,
        theme.context.getString(R.string.action_create_link),
        params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.spaceMd }) {
        onFaceTimeNumber(input.text.toString().trim())
    }
    private var staticRowCount = 0
    val rootView: View

    init {
        content.addView(BaoTimeComponents.titleText(theme, theme.context.getString(R.string.dialer_title)))
        content.addView(BaoTimeComponents.captionText(theme, theme.context.getString(R.string.new_call_explanation)))
        content.addView(BaoTimeComponents.captionText(theme, theme.context.getString(R.string.input_label)))
        content.addView(input)
        content.addView(callButton)
        rootView = ModalSheetUi.build(theme, content, onDismiss)
        staticRowCount = content.childCount
        renderRows()
    }

    fun updateContacts(value: ContactsSnapshot) {
        snapshot = value
        renderRows()
    }

    private fun renderRows() {
        val query = input.text.toString().trim()
        callButton.text = theme.context.getString(if (com.baotime.app.routing.UrlRouter.wholeFaceTimeUrl(query) != null)
            R.string.action_join_link else R.string.action_create_link)
        val filtered = snapshot?.allContacts.orEmpty().filter {
            it.displayName.contains(query, ignoreCase = true) || it.primaryAddress.contains(query, ignoreCase = true)
        }
        while (content.childCount > staticRowCount) content.removeViewAt(content.childCount - 1)
        if (filtered.isEmpty()) {
            val state = when {
                snapshot == null -> R.string.state_loading
                query.isNotEmpty() -> R.string.contacts_no_matches
                snapshot?.device == ContactsOutcome.Denied -> R.string.contacts_denied
                else -> R.string.contacts_empty
            }
            content.addView(BaoTimeComponents.screenNote(theme, theme.context.getString(state)))
        }
        filtered.forEach { contact ->
            content.addView(BaoTimeComponents.listRow(theme,
                contact.displayName.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                contact.displayName, contact.primaryAddress, emptyList(), showChevron = true
            ).apply { setOnClickListener { onFaceTime(contact) } })
        }
    }
}
