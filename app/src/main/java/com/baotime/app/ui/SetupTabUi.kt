package com.baotime.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.baotime.app.R
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordRead
import com.baotime.app.config.ServerConnection
import com.baotime.app.system.DomainApproval
import com.baotime.app.system.OpenSettingsResult
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** Platform status and the stored connection shown in Settings. */
data class SetupStatus(
    val domainApproval: DomainApproval,
    val incomingCallAccess: Boolean,
    val callAlertsEnabled: Boolean,
    val connection: ServerConnection
)

/** A flat settings surface: phone actions and the Mac connection, with no nested status cards. */
object SetupTabUi {
    internal enum class ConnectionFeedback(val message: Int) {
        InvalidUrl(R.string.setup_url_invalid), Saved(R.string.setup_saved),
        Connected(R.string.setup_saved_connected), Offline(R.string.setup_saved_offline)
    }

    internal class Controls(
        theme: BaoTimeTheme,
        container: LinearLayout,
        status: SetupStatus,
        onCallAlerts: () -> Unit,
        copyWebhook: () -> Boolean,
        onSave: (String, String, (ConnectionFeedback) -> Unit) -> Unit
    ) {
        private var theme = theme
        private val context = theme.context
        private val title = BaoTimeComponents.titleText(theme, context.getString(R.string.tab_setup)).apply {
            isAccessibilityHeading = true
            setPadding(0, theme.spaceSm, 0, theme.spaceLg)
        }
        private val links = settingAction(theme, R.drawable.ic_link) {
            val result = SystemIntegration.openDefaultLinkSettings(context)
            if (result is OpenSettingsResult.NotResolvable) settingsUnavailable(context)
        }
        private val invitations = settingAction(theme, R.drawable.ic_bell) {
            val settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            if (settings.resolveActivity(context.packageManager) != null) context.startActivity(settings)
            else settingsUnavailable(context)
        }
        private val alerts = settingAction(theme, R.drawable.ic_bell, onCallAlerts)
        private val webhookTitle = BaoTimeComponents.titleText(theme, context.getString(R.string.setup_webhook_title)).apply {
            isAccessibilityHeading = true
            setPadding(0, theme.spaceXl, 0, theme.spaceSm)
        }
        private val webhookStatus = BaoTimeComponents.captionText(theme, "").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val copy = settingAction(theme, R.drawable.ic_copy) {
            Toast.makeText(context, if (copyWebhook()) R.string.link_copied else R.string.setup_webhook_unavailable,
                Toast.LENGTH_SHORT).show()
        }.apply { setText(R.string.setup_webhook_copy) }
        private val connectionTitle = BaoTimeComponents.titleText(theme, context.getString(R.string.setup_config_title)).apply {
            isAccessibilityHeading = true
            setPadding(0, theme.spaceXl, 0, theme.spaceMd)
        }
        private val url = BaoTimeComponents.textField(theme, R.string.setup_server_url_hint, status.connection.url)
        private val password = BaoTimeComponents.textField(theme, R.string.setup_password_hint, when (val stored = status.connection.password) {
            is PasswordRead.Available -> stored.value
            PasswordRead.Missing, is PasswordRead.Unavailable -> ""
        }, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        private val urlLabel = BaoTimeComponents.fieldLabel(theme, R.string.setup_server_url_label, url)
        private val passwordLabel = BaoTimeComponents.fieldLabel(theme, R.string.setup_password_label, password)
        private var feedbackIsError = status.connection.password is PasswordRead.Unavailable
        private val feedback = BaoTimeComponents.captionText(theme, context.getString(
            if (feedbackIsError) R.string.setup_password_unavailable else R.string.setup_password_note)).apply {
            setPadding(0, theme.spaceSm, 0, theme.spaceMd)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val save = BaoTimeComponents.actionButton(theme, context.getString(R.string.setup_save),
            params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ) {
            onSave(url.text.toString().trim(), password.text.toString(), ::showFeedback)
        }
        private var domainApproval = status.domainApproval
        private var incomingCallAccess = status.incomingCallAccess
        private var callAlertsEnabled = status.callAlertsEnabled

        init {
            for (label in listOf(urlLabel, passwordLabel)) {
                label.setPadding(0, theme.spaceMd, 0, theme.spaceXs)
            }
            for (view in listOf(title, links, invitations, alerts, webhookTitle, webhookStatus, copy,
                connectionTitle, urlLabel, url, passwordLabel, password, feedback, save)) {
                container.addView(view)
            }
            updateTheme(theme)
        }

        fun updateTheme(theme: BaoTimeTheme) {
            this.theme = theme
            BaoTimeComponents.styleTitle(theme, title, size = theme.textSizeTitlePx)
            BaoTimeComponents.styleTitle(theme, webhookTitle)
            BaoTimeComponents.styleTitle(theme, connectionTitle)
            BaoTimeComponents.styleCaption(theme, webhookStatus)
            BaoTimeComponents.styleCaption(theme, urlLabel)
            BaoTimeComponents.styleCaption(theme, passwordLabel)
            BaoTimeComponents.styleCaption(theme, feedback, feedbackColor())
            BaoTimeComponents.styleTextField(theme, url)
            BaoTimeComponents.styleTextField(theme, password)
            BaoTimeComponents.styleActionButton(theme, save)
            for (button in listOf(links, invitations, alerts, copy)) styleSettingAction(theme, button)
            renderStatus()
        }

        fun updateStatus(status: SetupStatus) {
            domainApproval = status.domainApproval
            incomingCallAccess = status.incomingCallAccess
            callAlertsEnabled = status.callAlertsEnabled
            renderStatus()
        }

        fun updateWebhook(macConfigured: Boolean, available: Boolean) {
            val message = when {
                available -> R.string.setup_webhook_configure
                macConfigured -> R.string.setup_webhook_pending
                else -> R.string.setup_webhook_no_mac
            }
            val label = context.getText(message)
            if (webhookStatus.text != label) webhookStatus.text = label
            copy.visibility = if (available) View.VISIBLE else View.GONE
        }

        fun setSaving(saving: Boolean) {
            url.isEnabled = !saving
            password.isEnabled = !saving
            save.isEnabled = !saving
        }

        private fun renderStatus() {
            val linkState = when (domainApproval) {
                DomainApproval.Approved -> R.string.setup_links_enabled
                DomainApproval.NotApproved -> R.string.setup_links_disabled
                is DomainApproval.Unavailable -> R.string.setup_status_unavailable
            }
            links.text = settingLabel(theme, R.string.setup_configure, linkState)
            invitations.text = settingLabel(theme, R.string.setup_incoming_configure,
                if (incomingCallAccess) R.string.setup_incoming_enabled else R.string.setup_incoming_disabled)
            alerts.text = settingLabel(theme, R.string.setup_call_alerts,
                if (callAlertsEnabled) R.string.setup_incoming_enabled else R.string.setup_incoming_disabled)
        }

        private fun feedbackColor(): Int = if (feedbackIsError) theme.colorDanger else theme.colorTextSecondary

        private fun showFeedback(state: ConnectionFeedback) {
            feedbackIsError = state == ConnectionFeedback.InvalidUrl
            feedback.setTextColor(feedbackColor())
            feedback.setText(state.message)
        }
    }

    private fun settingAction(theme: BaoTimeTheme, icon: Int, action: () -> Unit): Button =
        BaoTimeComponents.actionButton(theme, "", theme.colorSurface, theme.colorTextPrimary) { action() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(0, theme.spaceLg, 0, theme.spaceLg)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, R.drawable.ic_chevron, 0)
            compoundDrawablePadding = theme.spaceMd
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun styleSettingAction(theme: BaoTimeTheme, button: Button) {
        BaoTimeComponents.styleActionButton(theme, button, theme.colorSurface, theme.colorTextPrimary)
        button.background = theme.listRowBackground()
        button.compoundDrawableTintList = theme.controlForeground(theme.colorTextSecondary)
    }

    private fun settingLabel(theme: BaoTimeTheme, titleRes: Int, stateRes: Int): CharSequence {
        val title = theme.context.getString(titleRes)
        val label = SpannableString("$title\n${theme.context.getString(stateRes)}")
        label.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(AbsoluteSizeSpan(theme.textSizeBodyPx.toInt()), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(AbsoluteSizeSpan(theme.textSizeCaptionPx.toInt()), title.length + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(ForegroundColorSpan(theme.colorTextSecondary), title.length + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return label
    }

    private fun settingsUnavailable(context: Context) {
        Toast.makeText(context, R.string.setup_open_settings_failed, Toast.LENGTH_SHORT).show()
    }

    fun readSetupStatus(context: Context, config: AppConfig): SetupStatus {
        return SetupStatus(SystemIntegration.faceTimeDomainApproval(context),
            IncomingCallListenerService.hasNotificationAccess(context),
            SystemIntegration.callAlertsEnabled(context),
            config.connection())
    }
}
