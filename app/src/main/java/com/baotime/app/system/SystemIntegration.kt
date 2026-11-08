package com.baotime.app.system

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.baotime.app.routing.UrlRouter

/** Verdict on whether BaoTime is the approved handler for facetime.apple.com links. */
sealed interface DomainApproval {
    data object Approved : DomainApproval
    data object NotApproved : DomainApproval

    /** Domain verification cannot be read on this device; [reason] says why. */
    data class Unavailable(val reason: String) : DomainApproval
}

/** Outcome of jumping into the system settings screen for default-link approval. */
sealed interface OpenSettingsResult {
    data object Opened : OpenSettingsResult

    /** No activity can handle the settings intent; [reason] names the resolved gap. */
    data class NotResolvable(val reason: String) : OpenSettingsResult
}

/** Reads of system integration state that the Setup screen displays. */
object SystemIntegration {

    /** Package names BaoTime cooperates with; the manifest queries block mirrors these. */
    const val PACKAGE_BLUEBUBBLES = "com.bluebubbles.messaging"
    const val PACKAGE_BEEPER = "com.beeper.android"

    /** Whether BaoTime is the user-selected handler for the FaceTime web host. */
    fun faceTimeDomainApproval(context: Context): DomainApproval {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return DomainApproval.Unavailable("unavailable")
        }
        val manager = context.getSystemService(DomainVerificationManager::class.java)
            ?: return DomainApproval.Unavailable("unavailable")
        val verification = manager.getDomainVerificationUserState(context.packageName)
        return when (verification?.hostToStateMap?.get(UrlRouter.FACETIME_HOST)) {
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
            return OpenSettingsResult.NotResolvable("no settings activity for this package")
        }
        context.startActivity(intent)
        return OpenSettingsResult.Opened
    }

    /** Opens BaoTime's app-details settings page (permissions, notifications). */
    fun openAppSettings(context: Context): OpenSettingsResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        if (intent.resolveActivity(context.packageManager) == null) {
            return OpenSettingsResult.NotResolvable("no app-details activity for this package")
        }
        context.startActivity(intent)
        return OpenSettingsResult.Opened
    }

    private fun packageUri(context: Context): Uri =
        "package:${context.packageName}".toUri()
}
