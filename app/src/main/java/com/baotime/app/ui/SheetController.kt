package com.baotime.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.baotime.app.R
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.DeviceContact
import com.baotime.app.data.RecentCalls
import com.baotime.app.network.BlueBubblesServerStatus
import com.baotime.app.network.ServerUrlPolicy
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the single active sheet, its drafts and async completion identity. */
class SheetController(
    private val host: FrameLayout,
    theme: BaoTimeTheme,
    private val config: AppConfig,
    private val history: CallHistory,
    private val contacts: ContactsCache,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onCall: (String, String?, Long?) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onJoin: (String, String, String) -> Unit,
    private val onFavoritesChanged: () -> Unit,
    private val onContactsRequested: () -> Unit,
    private val onCallAlerts: () -> Unit,
    private val hasPushEndpoint: () -> Boolean,
    private val copyPushWebhook: ((String) -> Boolean) -> Boolean,
    private val onServerSaved: suspend () -> BlueBubblesServerStatus
) {
    private var theme = theme
    private var active: Sheet? = null
    private var draft = ""
    private var setupSave: SetupSave? = null
    val isShowing: Boolean get() = active != null
    val isShowingNewCall: Boolean get() = active is Sheet.NewCall
    private val context get() = theme.context

    internal fun updateTheme(theme: BaoTimeTheme) {
        this.theme = theme
        val sheet = active ?: return
        ModalSheetUi.updateTheme(sheet.root, theme)
        when (sheet) {
            is Sheet.NewCall -> sheet.editor.updateTheme(theme)
            is Sheet.Setup -> sheet.controls?.updateTheme(theme)
            is Sheet.RecordDetail -> sheet.detail.updateTheme(theme)
            is Sheet.Contact -> sheet.updateTheme(theme)
            is Sheet.ClearHistory -> sheet.updateTheme(theme)
        }
    }

    fun dismiss() {
        val sheet = active ?: return
        active = null
        host.removeView(sheet.root)
    }

    fun showNew(prefill: String? = null) {
        if (prefill != null) draft = prefill
        if (active is Sheet.NewCall && prefill == null) return
        dismiss()
        val editor = NewCallSheetUi(theme, draft, contacts,
            onFaceTime = { contact, revision ->
                if (revision == null || revision == config.connectionRevision()) {
                    dismiss()
                    onCall(contact.primaryAddress, contact.displayName, revision)
                }
            },
            onFaceTimeNumber = { value -> dismiss(); onSubmit(value) },
            onDraftChanged = { draft = it }, onDismiss = ::dismiss)
        val sheet = Sheet.NewCall(editor)
        activate(sheet)
        contacts.snapshot?.let(::refreshContacts)
        onContactsRequested()
    }

    fun refreshContacts(snapshot: ContactsSnapshot?) {
        when (val sheet = active) {
            is Sheet.NewCall -> if (snapshot != null) sheet.editor.updateContacts(snapshot)
            is Sheet.Contact -> if (!sheet.canUse()) dismiss()
            else -> Unit
        }
    }

    fun showRecord(record: BaoCallRecord) {
        dismiss()
        val title = record.handleOrNumber.ifBlank { record.displayName }
        val subtitle = RecentsTabUi.Caption(theme, record, detail = true)
        val detail = RecordDetailUi(theme, title, subtitle,
            onJoin = { record.faceTimeUrl?.let { dismiss(); onJoin(it, record.displayName, record.id) } },
            onShare = {
                val url = record.faceTimeUrl
                val opened = url != null && SystemIntegration.shareLink(context, url)
                if (opened) dismiss()
                opened
            },
            onCopy = { record.faceTimeUrl?.let(::copyLink); dismiss() },
            onDelete = { dismiss(); launch { history.deleteRecord(record.id) } },
            onDismiss = ::dismiss)
        activate(Sheet.RecordDetail(detail))
    }

    fun refreshRecordTimestamp() {
        val sheet = active
        if (sheet is Sheet.RecordDetail) sheet.detail.subtitle.refresh()
    }

    fun refreshRecord(recent: RecentCalls?) {
        val sheet = active
        if (sheet is Sheet.RecordDetail) {
            val record = recent?.record(sheet.detail.subtitle.record.id)
            if (record == null) dismiss() else sheet.detail.subtitle.update(record)
        }
    }

    private fun copyLink(url: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.link_label), url))
        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    fun showContact(contact: DeviceContact, revision: Long?) {
        val canUse = { revision == null || revision == config.connectionRevision() }
        if (!canUse()) return
        dismiss()
        val title = BaoTimeComponents.titleText(theme, contact.displayName).apply { isAccessibilityHeading = true }
        val address = BaoTimeComponents.captionText(theme, contact.primaryAddress)
        val faceTime = BaoTimeComponents.ActionRow(theme, R.drawable.ic_video_call, R.string.contact_action_facetime) {
            if (canUse()) { dismiss(); onCall(contact.primaryAddress, contact.displayName, revision) }
        }
        val dialFeedback = if (contact.isEmail) null else {
            BaoTimeComponents.actionFailure(theme, R.string.contact_dial_unavailable)
        }
        val dial = dialFeedback?.let { feedback ->
            BaoTimeComponents.ActionRow(theme, R.drawable.ic_call_accept, R.string.contact_action_dial) {
                if (canUse()) {
                    if (SystemIntegration.dial(context, contact.primaryAddress)) dismiss()
                    else feedback.visibility = View.VISIBLE
                }
            }
        }
        val key = AppConfig.favoriteKey(contact.primaryAddress)
        val favorite = BaoTimeComponents.ActionRow(theme, R.drawable.ic_star,
            if (key in config.favorites()) R.string.contact_action_unfavorite else R.string.contact_action_favorite) {
            if (canUse()) { dismiss(); config.toggleFavorite(key); onFavoritesChanged() }
        }
        val content = ModalSheetUi.contentColumn(theme).apply {
            addView(title)
            addView(address)
            addView(faceTime.view)
            dial?.let { addView(it.view) }
            dialFeedback?.let(::addView)
            addView(favorite.view)
        }
        val root = ModalSheetUi.build(theme, content, ::dismiss)
        activate(Sheet.Contact(root, canUse, title, address, listOfNotNull(faceTime, dial, favorite), dialFeedback))
    }

    fun showClearHistory() {
        dismiss()
        val title = BaoTimeComponents.titleText(theme, context.getString(R.string.clear_history_title)).apply {
            isAccessibilityHeading = true
        }
        val message = BaoTimeComponents.captionText(theme, context.getString(R.string.clear_history_body))
        val cancel = BaoTimeComponents.actionButton(theme, context.getString(R.string.action_cancel),
            theme.colorControlClear, theme.colorTextPrimary,
            params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = theme.spaceXs
            }) { dismiss() }
        val confirm = BaoTimeComponents.actionButton(theme, context.getString(R.string.action_clear_history),
            theme.colorDanger, theme.colorOnAccent,
            params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = theme.spaceXs
            }) {
            dismiss(); launch { history.clearAll() }
        }
        val content = ModalSheetUi.contentColumn(theme).apply {
            addView(title)
            addView(message)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(cancel)
                addView(confirm)
            })
        }
        activate(Sheet.ClearHistory(ModalSheetUi.build(theme, content, ::dismiss), title, message, cancel, confirm))
    }

    fun showSetup() {
        if (active is Sheet.Setup) return
        dismiss()
        val content = ModalSheetUi.contentColumn(theme)
        val sheet = Sheet.Setup(ModalSheetUi.build(theme, content, ::dismiss), content)
        activate(sheet)
        refreshSetupStatus()
    }

    fun refreshSetupStatus() {
        val sheet = active
        if (sheet !is Sheet.Setup || setupSave == SetupSave.Persisting) return
        val request = ++sheet.statusRequest
        launch {
            val status = withContext(Dispatchers.IO) { SetupTabUi.readSetupStatus(context, config) }
            if (active !== sheet || request != sheet.statusRequest || setupSave == SetupSave.Persisting) return@launch
            if (status.connection.revision != config.connectionRevision()) {
                refreshSetupStatus()
                return@launch
            }
            val controls = sheet.controls
            if (controls == null) {
                sheet.controls = SetupTabUi.Controls(theme, sheet.content, status, onCallAlerts,
                    copyWebhook = ::copyWebhook) { url, password, feedback ->
                        saveConnection(sheet, url, password, feedback)
                    }.apply { setSaving(setupSave != null) }
            } else controls.updateStatus(status)
            refreshPushStatus()
        }
    }

    internal fun refreshPushStatus() {
        val sheet = active
        if (sheet is Sheet.Setup) sheet.controls?.updateWebhook(config.serverUrl().isNotEmpty(), hasPushEndpoint())
    }

    private fun copyWebhook(): Boolean = copyPushWebhook { url ->
        SystemIntegration.copySensitiveText(context, context.getString(R.string.setup_webhook_title), url)
    }

    private fun saveConnection(sheet: Sheet.Setup, url: String, password: String,
        feedback: (SetupTabUi.ConnectionFeedback) -> Unit) {
        if (active !== sheet || setupSave != null) return
        if (ServerUrlPolicy.check(url) is ServerUrlPolicy.Verdict.Invalid) {
            feedback(SetupTabUi.ConnectionFeedback.InvalidUrl)
            return
        }

        val request = config.beginConnectionSave()
        setupSave = SetupSave.Persisting
        sheet.controls?.setSaving(true)
        launch {
            try {
                val revision = withContext(Dispatchers.IO) { config.saveConnection(request, url, password) }
                    ?: return@launch
                setupSave = SetupSave.Verifying
                if (active === sheet) feedback(SetupTabUi.ConnectionFeedback.Saved) else refreshSetupStatus()
                val server = onServerSaved()
                if (active === sheet && config.connectionRevision() == revision) {
                    feedback(if (server.reachable) SetupTabUi.ConnectionFeedback.Connected else SetupTabUi.ConnectionFeedback.Offline)
                }
            } finally {
                setupSave = null
                val current = active
                if (current is Sheet.Setup) {
                    val controls = current.controls
                    if (controls == null) refreshSetupStatus() else controls.setSaving(false)
                }
            }
        }
    }

    private enum class SetupSave { Persisting, Verifying }

    private fun activate(sheet: Sheet) { active = sheet; host.addView(sheet.root) }

    private sealed class Sheet(val root: View) {
        class NewCall(val editor: NewCallSheetUi) : Sheet(editor.rootView)
        class RecordDetail(val detail: RecordDetailUi) : Sheet(detail.root)
        class Contact(
            root: View, val canUse: () -> Boolean, private val title: TextView, private val address: TextView,
            private val actions: List<BaoTimeComponents.ActionRow>, private val dialFeedback: TextView?
        ) : Sheet(root) {
            fun updateTheme(theme: BaoTimeTheme) {
                BaoTimeComponents.styleTitle(theme, title)
                BaoTimeComponents.styleCaption(theme, address)
                actions.forEach { it.updateTheme(theme) }
                dialFeedback?.let { BaoTimeComponents.styleCaption(theme, it, theme.colorDanger) }
            }
        }
        class ClearHistory(
            root: View, private val title: TextView, private val message: TextView,
            private val cancel: Button, private val confirm: Button
        ) : Sheet(root) {
            fun updateTheme(theme: BaoTimeTheme) {
                BaoTimeComponents.styleTitle(theme, title)
                BaoTimeComponents.styleCaption(theme, message)
                BaoTimeComponents.styleActionButton(theme, cancel, theme.colorControlClear, theme.colorTextPrimary)
                BaoTimeComponents.styleActionButton(theme, confirm, theme.colorDanger, theme.colorOnAccent)
            }
        }
        class Setup(root: View, val content: LinearLayout) : Sheet(root) {
            var controls: SetupTabUi.Controls? = null
            var statusRequest = 0L
        }
    }
}
