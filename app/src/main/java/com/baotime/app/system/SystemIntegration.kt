package com.baotime.app.system

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.net.toUri
import com.baotime.app.R
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.routing.UrlRouter

/** Verdict on whether BaoTime is the approved handler for facetime.apple.com links. */
sealed interface DomainApproval {
    data object Approved : DomainApproval
    data object NotApproved : DomainApproval

    /** Domain verification cannot be read on this device (pre-S or missing service). */
    data object Unavailable : DomainApproval
}

/** Outcome of jumping into the system settings screen for default-link approval. */
sealed interface OpenSettingsResult {
    data object Opened : OpenSettingsResult

    /** No activity can handle the settings intent on this device. */
    data object NotResolvable : OpenSettingsResult
}

/** Android settings, clipboard, and external activity launches. */
object SystemIntegration {

    /** Package names BaoTime cooperates with; the manifest queries block mirrors these. */
    const val PACKAGE_BLUEBUBBLES = "com.bluebubbles.messaging"
    const val PACKAGE_BEEPER = "com.beeper.android"

    internal fun shareLink(context: Context, url: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        return launchActivity(context, Intent.createChooser(send, context.getText(R.string.action_share)))
    }

    internal fun dial(context: Context, phoneNumber: String): Boolean {
        val intent = Intent(Intent.ACTION_DIAL, UrlRouter.dialUri(phoneNumber))
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }
        val (eligible, excluded) = matches.partition { activity ->
            activity.packageName != context.packageName && activity.exported &&
                (activity.permission == null || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
        }
        val external = eligible.map { it.packageName }.distinct()
        if (external.isEmpty()) return false

        val defaultPhone = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        val phone = defaultPhone?.takeIf { it in external } ?: external.singleOrNull()
        if (phone != null) return launchActivity(context, intent.setPackage(phone))

        val chooser = Intent.createChooser(intent, context.getText(R.string.contact_action_dial))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.map { ComponentName(it.packageName, it.name) }.toTypedArray())
        return launchActivity(context, chooser)
    }

    private fun launchActivity(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    internal fun copySensitiveText(context: Context, label: CharSequence, value: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        return true
    }

    /** Whether BaoTime is the user-selected handler for the FaceTime web host. */
    fun faceTimeDomainApproval(context: Context): DomainApproval {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return DomainApproval.Unavailable
        }
        val manager = context.getSystemService(DomainVerificationManager::class.java)
            ?: return DomainApproval.Unavailable
        val verification = manager.getDomainVerificationUserState(context.packageName)
        if (verification?.isLinkHandlingAllowed != true) return DomainApproval.NotApproved
        return when (verification.hostToStateMap[UrlRouter.FACETIME_HOST]) {
            DomainVerificationUserState.DOMAIN_STATE_SELECTED,
            DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> DomainApproval.Approved
            else -> DomainApproval.NotApproved
        }
    }

    /** Opens the system settings page for the FaceTime web-link handler. */
    fun openDefaultLinkSettings(context: Context): OpenSettingsResult {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri(context))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return OpenSettingsResult.NotResolvable
        }
        context.startActivity(intent)
        return OpenSettingsResult.Opened
    }

    /** Opens BaoTime's app-details settings page (permissions, notifications). */
    fun openAppSettings(context: Context): OpenSettingsResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        return if (launchActivity(context, intent)) OpenSettingsResult.Opened else OpenSettingsResult.NotResolvable
    }

    fun callAlertsEnabled(context: Context): Boolean {
        val channel = callAlertChannel(context)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun openCallAlertSettings(context: Context): OpenSettingsResult {
        val channelId = callAlertChannel(context)?.id
        val intent = Intent(if (channelId == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS
            else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (channelId != null) intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        if (intent.resolveActivity(context.packageManager) == null) return openAppSettings(context)
        context.startActivity(intent)
        return OpenSettingsResult.Opened
    }

    private fun callAlertChannel(context: Context) = context.getSystemService(NotificationManager::class.java)
        ?.takeIf { it.areNotificationsEnabled() }?.getNotificationChannel(IncomingCallListenerService.CHANNEL_ID)

    /** Whether Android allows BaoTime to raise full-screen incoming call notifications (API 34+ gate). */
    fun canUseFullScreenIncomingAlerts(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        return context.getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() == true
    }

    private fun packageUri(context: Context): Uri =
        "package:${context.packageName}".toUri()
}
