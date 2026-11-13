package com.baotime.app.ui

import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.baotime.app.R
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.ContactsOutcome
import com.baotime.app.data.DeviceContact
import com.baotime.app.data.MacContactsState
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** One sheet owns its input, listener and contact rows for its entire lifetime. */
class NewCallSheetUi(
    theme: BaoTimeTheme,
    initialHandle: String,
    private val contacts: ContactsCache,
    private val onFaceTime: (DeviceContact, Long?) -> Unit,
    onFaceTimeNumber: (String) -> Unit,
    onDraftChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    private var theme = theme
    private var snapshot: ContactsSnapshot? = null
    private val content = ModalSheetUi.contentColumn(theme)
    private val permissionStatus = ContactsTabUi.Status(theme)
    private val macStatus = ContactsTabUi.Status(theme)
    private val empty = ContactsTabUi.Status(theme)
    private val rows = ContactsTabUi.ContactRows(theme, showAddressKind = false, onContact = onFaceTime)
    private val title = BaoTimeComponents.titleText(theme, theme.context.getString(R.string.dialer_title))
    private val explanation = BaoTimeComponents.captionText(theme, theme.context.getString(R.string.new_call_explanation))
    private val input = BaoTimeComponents.textField(theme, R.string.input_hint, initialHandle,
        InputType.TYPE_CLASS_TEXT).apply {
        doAfterTextChanged { value -> onDraftChanged(value.toString()); renderRows() }
    }
    private val callButton = BaoTimeComponents.actionButton(theme,
        theme.context.getString(R.string.action_create_link),
        params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.spaceMd }) {
        onFaceTimeNumber(input.text.toString().trim())
    }
    private val label = BaoTimeComponents.fieldLabel(theme, R.string.input_label, input)
    val rootView: FrameLayout

    init {
        content.addView(title)
        content.addView(explanation)
        content.addView(label)
        content.addView(input)
        content.addView(callButton)
        content.addView(permissionStatus.view)
        content.addView(macStatus.view)
        content.addView(empty.view)
        content.addView(rows.root)
        rootView = ModalSheetUi.build(theme, content, onDismiss)
        renderRows()
    }

    internal fun updateTheme(theme: BaoTimeTheme) {
        this.theme = theme
        BaoTimeComponents.styleTitle(theme, title)
        BaoTimeComponents.styleCaption(theme, explanation)
        BaoTimeComponents.styleCaption(theme, label)
        BaoTimeComponents.styleTextField(theme, input)
        BaoTimeComponents.styleActionButton(theme, callButton)
        BaoTimeComponents.styleTitle(theme, permissionStatus.view, theme.colorTextSecondary)
        BaoTimeComponents.styleTitle(theme, macStatus.view, theme.colorTextSecondary)
        BaoTimeComponents.styleTitle(theme, empty.view, theme.colorTextSecondary)
        rows.updateTheme(theme)
    }

    fun updateContacts(value: ContactsSnapshot) {
        if (snapshot == value) return
        snapshot = value
        renderRows()
    }

    private fun renderRows() {
        val query = input.text.toString().trim()
        callButton.text = theme.context.getString(if (UrlRouter.wholeFaceTimeUrl(query) != null)
            R.string.action_join_link else R.string.action_create_link)
        val current = snapshot?.let(contacts::current)
        val matches = current?.search(query)?.all.orEmpty()
        val denied = current?.device == ContactsOutcome.Denied
        val complete = current?.macState == MacContactsState.Ready || current?.macState == MacContactsState.NotConfigured
        permissionStatus.show(R.string.contacts_denied.takeIf { denied })
        macStatus.show(ContactsTabUi.macStatus(current?.macState))
        empty.show(ContactsTabUi.emptyStatus(query).takeIf { complete && matches.isEmpty() && (!denied || query.isNotEmpty()) })
        rows.render(matches)
    }
}
