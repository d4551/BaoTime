package com.baotime.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.baotime.app.R
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.DeviceContact
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the single active sheet, its drafts and async completion identity. */
class SheetController(
    private val host: FrameLayout,
    private val theme: BaoTimeTheme,
    private val config: AppConfig,
    private val history: CallHistory,
    private val contacts: ContactsCache,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onCall: (String, String?) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onJoin: (String, String, String) -> Unit,
    private val onHistoryChanged: () -> Unit,
    private val onServerSaved: suspend () -> com.baotime.app.network.BlueBubblesServerStatus
) {
    private var active: Sheet? = null
    private var draft = ""
    val isShowing: Boolean get() = active != null
    private val context get() = theme.context

    fun dismiss() {
        val sheet = active ?: return
        active = null
        host.removeView(sheet.root)
    }

    fun showNew(prefill: String? = null) {
        if (prefill != null) draft = prefill
        if (active is Sheet.NewCall && prefill == null) return
        dismiss()
        val editor = NewCallSheetUi(theme, draft,
            onFaceTime = { contact -> dismiss(); onCall(contact.primaryAddress, contact.displayName) },
            onFaceTimeNumber = { value -> dismiss(); onSubmit(value) },
            onDraftChanged = { draft = it }, onDismiss = ::dismiss)
        val sheet = Sheet.NewCall(editor)
        activate(sheet)
        contacts.snapshot?.let { editor.updateContacts(it) }
        launch {
            val result = contacts.refresh()
            if (active === sheet) editor.updateContacts(result)
        }
    }

    fun showRecord(record: BaoCallRecord) {
        dismiss()
        val title = record.handleOrNumber.ifBlank { record.displayName }
        val subtitle = context.getString(R.string.recents_caption_fmt, record.displayName,
            RecentsTabUi.describe(theme, record))
        val root = RecordDetailUi.build(theme, title, subtitle, record.faceTimeUrl,
            onJoin = { record.faceTimeUrl?.let { dismiss(); onJoin(it, record.displayName, record.id) } },
            onShare = { dismiss(); shareRecord(record) },
            onCopy = { record.faceTimeUrl?.let(::copyLink); dismiss() },
            onDelete = { dismiss(); launch { history.deleteRecord(record.id); onHistoryChanged() } },
            onDismiss = ::dismiss)
        activate(Sheet.General(root))
    }

    fun shareRecord(record: BaoCallRecord) {
        val url = record.faceTimeUrl ?: return
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share)))
    }

    private fun copyLink(url: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.link_label), url))
        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    fun showContact(contact: DeviceContact) {
        dismiss()
        val content = ModalSheetUi.contentColumn(theme)
        content.addView(BaoTimeComponents.titleText(theme, contact.displayName))
        content.addView(BaoTimeComponents.captionText(theme, contact.primaryAddress))
        content.addView(BaoTimeComponents.actionRow(theme, R.drawable.ic_video_call, R.string.contact_action_facetime) {
            dismiss(); onCall(contact.primaryAddress, contact.displayName)
        })
        if (!contact.isEmail) content.addView(BaoTimeComponents.actionRow(theme, R.drawable.ic_call_accept, R.string.contact_action_dial) {
            dismiss(); context.startActivity(Intent(Intent.ACTION_DIAL, UrlRouter.dialUri(contact.primaryAddress)))
        })
        val key = AppConfig.favoriteKey(contact.primaryAddress)
        val favorite = key in config.favorites()
        content.addView(BaoTimeComponents.actionRow(theme, R.drawable.ic_star,
            if (favorite) R.string.contact_action_unfavorite else R.string.contact_action_favorite) {
            dismiss(); config.toggleFavorite(key); onHistoryChanged()
        })
        activate(Sheet.General(ModalSheetUi.build(theme, content, ::dismiss)))
    }

    fun showClearHistory() {
        dismiss()
        val content = ModalSheetUi.contentColumn(theme)
        content.addView(BaoTimeComponents.titleText(theme, context.getString(R.string.clear_history_title)))
        content.addView(BaoTimeComponents.captionText(theme, context.getString(R.string.clear_history_body)))
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        actions.addView(BaoTimeComponents.actionButton(theme, context.getString(R.string.action_cancel),
            theme.colorControlClear, theme.colorTextPrimary,
            params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = theme.spaceXs
            }) { dismiss() })
        actions.addView(BaoTimeComponents.actionButton(theme, context.getString(R.string.action_clear_history),
            theme.colorDanger, theme.colorOnAccent,
            params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = theme.spaceXs
            }) {
            dismiss(); launch { history.clearAll(); onHistoryChanged() }
        })
        content.addView(actions)
        activate(Sheet.General(ModalSheetUi.build(theme, content, ::dismiss)))
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
        if (sheet !is Sheet.Setup) return
        launch {
            val status = withContext(Dispatchers.IO) { SetupTabUi.readSetupStatus(context) }
            if (active !== sheet) return@launch
            val update = sheet.updateStatus
            if (update == null) {
                sheet.updateStatus = SetupTabUi.populate(theme, sheet.content, config, status, launch, onServerSaved)
            } else update(status)
        }
    }

    private fun activate(sheet: Sheet) { active = sheet; host.addView(sheet.root) }

    private sealed class Sheet(val root: View) {
        class NewCall(val editor: NewCallSheetUi) : Sheet(editor.rootView)
        class General(root: View) : Sheet(root)
        class Setup(root: View, val content: LinearLayout) : Sheet(root) {
            var updateStatus: ((SetupStatus) -> Unit)? = null
        }
    }
}
