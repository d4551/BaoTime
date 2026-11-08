package com.baotime.app.ui

import android.content.Intent
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.baotime.app.R
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordRead
import com.baotime.app.network.ServerUrlPolicy
import com.baotime.app.system.DomainApproval
import com.baotime.app.system.OpenSettingsResult
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Facts for the two actionable phone integrations. */
data class SetupStatus(val domainApproval: DomainApproval, val incomingCallAccess: Boolean)

/** A flat settings surface: phone actions and the Mac connection, with no nested status cards. */
object SetupTabUi {
    fun populate(theme: BaoTimeTheme, container: LinearLayout, config: AppConfig,
        status: SetupStatus, launchWithErrorHandler: (suspend () -> Unit) -> Unit,
        onServerConfigSaved: suspend () -> com.baotime.app.network.BlueBubblesServerStatus
    ): (SetupStatus) -> Unit {
        val context = theme.context
        container.addView(BaoTimeComponents.titleText(theme, context.getString(R.string.tab_setup)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeTitlePx)
            setPadding(0, theme.spaceSm, 0, theme.spaceLg)
        })
        val links = settingAction(theme, R.drawable.ic_link) {
            val result = SystemIntegration.openDefaultLinkSettings(context)
            if (result is OpenSettingsResult.NotResolvable) settingsUnavailable(theme, result.reason)
        }
        val invitations = settingAction(theme, R.drawable.ic_bell) {
            val settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            if (settings.resolveActivity(context.packageManager) != null) context.startActivity(settings)
            else settingsUnavailable(theme, context.getString(R.string.setup_incoming_configure))
        }
        container.addView(links)
        container.addView(invitations)
        val updateStatus: (SetupStatus) -> Unit = { current ->
            val linkState = when (current.domainApproval) {
                DomainApproval.Approved -> R.string.setup_links_enabled
                DomainApproval.NotApproved -> R.string.setup_links_disabled
                is DomainApproval.Unavailable -> R.string.setup_status_unavailable
            }
            links.text = settingLabel(theme, R.string.setup_configure, linkState)
            invitations.text = settingLabel(theme, R.string.setup_incoming_configure,
                if (current.incomingCallAccess) R.string.setup_incoming_enabled else R.string.setup_incoming_disabled)
        }
        updateStatus(status)
        connectionFields(theme, container, config, launchWithErrorHandler, onServerConfigSaved)
        return updateStatus
    }

    private fun settingAction(theme: BaoTimeTheme, icon: Int, action: () -> Unit): Button =
        BaoTimeComponents.actionButton(theme, "", theme.colorSurface, theme.colorTextPrimary) { action() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = theme.listRowBackground()
            setPadding(0, theme.spaceLg, 0, theme.spaceLg)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, R.drawable.ic_chevron, 0)
            compoundDrawableTintList = theme.controlForeground(theme.colorTextSecondary)
            compoundDrawablePadding = theme.spaceMd
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun settingLabel(theme: BaoTimeTheme, titleRes: Int, stateRes: Int): CharSequence {
        val title = theme.context.getString(titleRes)
        val label = SpannableString("$title\n${theme.context.getString(stateRes)}")
        label.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(AbsoluteSizeSpan(theme.textSizeBodyPx.toInt()), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(AbsoluteSizeSpan(theme.textSizeCaptionPx.toInt()), title.length + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(ForegroundColorSpan(theme.colorTextSecondary), title.length + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return label
    }

    private fun connectionFields(theme: BaoTimeTheme, container: LinearLayout, config: AppConfig,
        launch: (suspend () -> Unit) -> Unit, onSaved: suspend () -> com.baotime.app.network.BlueBubblesServerStatus
    ) {
        val context = theme.context
        val connection = config.connection()
        container.addView(BaoTimeComponents.titleText(theme, context.getString(R.string.setup_config_title)).apply {
            setPadding(0, theme.spaceXl, 0, theme.spaceMd)
        })
        val url = editor(theme, connection.url, R.string.setup_server_url_hint, false)
        val password = editor(theme, when (val stored = connection.password) {
            is PasswordRead.Available -> stored.value
            PasswordRead.Missing, is PasswordRead.Unavailable -> ""
        }, R.string.setup_password_hint, true)
        container.addView(label(theme, R.string.setup_server_url_label))
        container.addView(url)
        container.addView(label(theme, R.string.setup_password_label))
        container.addView(password)
        val feedback = BaoTimeComponents.captionText(theme, context.getString(
            if (connection.password is PasswordRead.Unavailable) R.string.setup_password_unavailable else R.string.setup_password_note))
        feedback.setPadding(0, theme.spaceSm, 0, theme.spaceMd)
        if (connection.password is PasswordRead.Unavailable) feedback.setTextColor(theme.colorDanger)
        container.addView(feedback)
        container.addView(BaoTimeComponents.actionButton(theme, context.getString(R.string.setup_save),
            params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) {
            val candidate = url.text.toString().trim()
            val secret = password.text.toString()
            launch {
                when (withContext(Dispatchers.IO) { ServerUrlPolicy.check(candidate) }) {
                    is ServerUrlPolicy.Verdict.Invalid -> {
                        feedback.setTextColor(theme.colorDanger)
                        feedback.setText(R.string.setup_url_invalid)
                    }
                    is ServerUrlPolicy.Verdict.Valid -> {
                        withContext(Dispatchers.IO) { config.saveConnection(candidate, secret) }
                        feedback.setTextColor(theme.colorTextSecondary)
                        feedback.setText(R.string.setup_saved)
                        val server = onSaved()
                        feedback.setText(if (server.reachable) R.string.setup_saved_connected else R.string.setup_saved_offline)
                    }
                }
            }
        })
    }

    private fun editor(theme: BaoTimeTheme, value: String, hint: Int, secret: Boolean): EditText =
        EditText(theme.context).apply {
            setHint(hint)
            setHintTextColor(theme.colorTextHint)
            setTextColor(theme.colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
            background = theme.fieldBackground()
            setPadding(0, theme.spaceMd, 0, theme.spaceMd)
            minHeight = theme.minTouchTarget
            setSingleLine(true)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(value)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun label(theme: BaoTimeTheme, resource: Int): TextView =
        BaoTimeComponents.captionText(theme, theme.context.getString(resource)).apply {
            setPadding(0, theme.spaceMd, 0, theme.spaceXs)
        }

    private fun settingsUnavailable(theme: BaoTimeTheme, reason: String) {
        Toast.makeText(theme.context, theme.context.getString(R.string.setup_open_settings_failed_fmt, reason), Toast.LENGTH_SHORT).show()
    }

    fun readSetupStatus(context: android.content.Context): SetupStatus = SetupStatus(
        SystemIntegration.faceTimeDomainApproval(context),
        IncomingCallListenerService.hasNotificationAccess(context) && NotificationManagerCompat.from(context).areNotificationsEnabled())
}
