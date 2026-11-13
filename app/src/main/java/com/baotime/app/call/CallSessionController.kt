package com.baotime.app.call

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baotime.app.R
import com.baotime.app.audio.CallAudioRouter
import com.baotime.app.data.CallHistory
import com.baotime.app.routing.UrlRouter
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.CallOverlay
import com.baotime.app.ui.CallOverlayUi
import com.baotime.app.ui.FaceTimeWebViewHelper
import com.baotime.app.ui.theme.BaoTimeTheme
import com.baotime.app.ui.components.ForegroundSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Owns one opened room, its native audio route, permission request and elapsed session time. */
class CallSessionController(
    private val history: CallHistory,
    private val persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onSessionClosed: (String) -> Unit
) {
    private var session: Session? = null
    private var pendingConsent: Consent? = null
    val isActive: Boolean get() = session != null

    internal fun updateRoomTheme(theme: BaoTimeTheme) {
        session?.overlay?.updateTheme(theme)
    }

    fun openSession(activity: ComponentActivity, host: FrameLayout, theme: BaoTimeTheme,
        url: String, title: String, recordId: String, onAllowMedia: () -> Unit
    ) {
        if (!UrlRouter.isFaceTimeRoomUrl(url)) {
            Toast.makeText(activity, R.string.call_status_navigation_blocked, Toast.LENGTH_LONG).show()
            return
        }
        closeSession()
        val overlay = CallOverlayUi.build(theme, title,
            onAllowMedia = onAllowMedia,
            speakerState = { session?.audio?.speakerState() ?: CallAudioRouter.SpeakerState.Off },
            onSpeakerToggle = { toggleSpeaker(activity) },
            onShareLink = { shareSession(activity) },
            onEnterPip = { enterPipMode(activity) },
            onEndCall = ::closeSession,
            onRendererGone = { view ->
                if (session?.overlay?.webView === view) {
                    closeSession()
                    Toast.makeText(activity, R.string.call_status_failed, Toast.LENGTH_LONG).show()
                }
            },
            onTopChanged = { refreshPipEligibility() }
        )
        val current = Session(activity, overlay, host, url, recordId, CallAudioRouter(activity))
        session = current
        activity.lifecycle.addObserver(current.onLifecycle)
        overlay.webView.addOnLayoutChangeListener(current.onLayout)
        host.addView(overlay.rootView)
        if (!overlay.room.loadRoom(url)) {
            closeSession()
            Toast.makeText(activity, R.string.call_status_navigation_blocked, Toast.LENGTH_LONG).show()
        }
    }

    fun requestMediaConsent(launch: (Array<String>) -> Unit) {
        if (pendingConsent != null) return
        val current = session ?: return
        val request = current.overlay.room.captureMediaConsent() ?: return
        val missing = request.missingPermissions
        if (missing.isEmpty()) {
            if (request.complete(true)) showMediaGranted(current.activity)
        } else {
            startConsent(Consent.Media(request)) { launch(missing.toTypedArray()) }
        }
    }

    fun completeMediaConsent(grants: Map<String, Boolean>) {
        val media = pendingConsent
        if (media !is Consent.Media) return
        pendingConsent = null
        val request = media.request
        val current = session
        if (request != null && current != null && request.complete(request.missingPermissions.all { grants[it] == true })) {
            showMediaGranted(current.activity)
        }
        refreshPipEligibility()
    }

    fun requestNotificationConsent(launch: () -> Unit): Boolean = startConsent(Consent.Notification, launch)

    fun completeNotificationConsent() {
        if (pendingConsent != Consent.Notification) return
        pendingConsent = null
        refreshPipEligibility()
    }

    fun pendingConsentKind(): String? = pendingConsent?.kind?.name

    fun restorePendingConsent(kind: String?) {
        pendingConsent = when (kind) {
            ConsentKind.Notification.name -> Consent.Notification
            ConsentKind.Media.name -> Consent.Media(request = null)
            else -> null
        }
    }

    private fun startConsent(consent: Consent, launch: () -> Unit): Boolean {
        if (pendingConsent != null) return false
        pendingConsent = consent
        try {
            refreshPipEligibility()
            launch()
        } catch (failure: RuntimeException) {
            pendingConsent = null
            refreshPipEligibility()
            throw failure
        }
        return true
    }

    private fun showMediaGranted(activity: Activity) {
        Toast.makeText(activity, R.string.call_status_media_granted, Toast.LENGTH_SHORT).show()
    }

    fun denyHeldMedia() { session?.overlay?.room?.denyHeldMedia() }

    fun closeSession() {
        val current = session ?: return
        session = null
        if (pendingConsent is Consent.Media) pendingConsent = Consent.Media(request = null)
        disableAutoPip(current.activity)
        current.activity.lifecycle.removeObserver(current.onLifecycle)
        current.overlay.webView.removeOnLayoutChangeListener(current.onLayout)
        current.audio.release()
        current.host.removeView(current.overlay.rootView)
        current.overlay.room.dispose()
        val elapsed = (SystemClock.elapsedRealtime() - current.startedAt) / 1000L
        // Process scope: teardown must persist even when the Activity scope is already cancelled.
        persistScope.launch { history.setDuration(current.recordId, elapsed) }
        onSessionClosed(current.recordId)
    }

    fun setPipMode(inPip: Boolean) {
        val overlay = session?.overlay ?: return
        val visibility = if (inPip) View.GONE else View.VISIBLE
        overlay.topBar.visibility = visibility
        overlay.permissionButton.visibility = if (!inPip && overlay.room.hasPendingMedia) View.VISIBLE else View.GONE
    }

    fun refreshPipEligibility() {
        val current = session ?: return
        val activity = current.activity
        if (activity.isFinishing || activity.isDestroyed || activity.isInPictureInPictureMode) return
        if (current.share == ShareDeparture.Returned && activity.hasWindowFocus() &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            current.share = null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val visibleRoom = roomBounds(current)
        activity.setPictureInPictureParams(pictureInPictureParams(visibleRoom, autoPipEligible(current, visibleRoom)))
    }

    fun releaseSpeakerSelection() { session?.audio?.release() }

    fun onTopResumedActivityChanged(isTop: Boolean) {
        val current = session ?: return
        when (current.share) {
            ShareDeparture.Starting -> if (!isTop) current.share = ShareDeparture.Away
            ShareDeparture.Away -> if (isTop) {
                current.share = ShareDeparture.Returned
                refreshPipEligibility()
            }
            ShareDeparture.Returned -> if (!isTop) current.share = ShareDeparture.Away
            null -> Unit
        }
    }

    /**
     * On API 29 and 30, the user-leave callback is the automatic PiP entry point.
     */
    fun enterPipOnUserLeave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val current = session ?: return
        val visibleRoom = roomBounds(current)
        if (!autoPipEligible(current, visibleRoom)) return
        current.activity.enterPictureInPictureMode(pictureInPictureParams(visibleRoom))
    }

    private fun toggleSpeaker(activity: Activity) {
        val current = session ?: return
        if (current.audio.hasSpeakerSelection()) {
            current.audio.release()
        } else if (!current.audio.enableSpeaker()) {
            Toast.makeText(activity, R.string.call_speaker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterPipMode(activity: Activity) {
        val current = session ?: return
        val visibleRoom = roomBounds(current)
        val params = pictureInPictureParams(visibleRoom, autoPipEligible(current, visibleRoom))
        activity.setPictureInPictureParams(params)
        if (!activity.enterPictureInPictureMode(params)) {
            Toast.makeText(activity, R.string.call_pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun roomBounds(current: Session): Rect? {
        val rect = Rect()
        return rect.takeIf { current.overlay.webView.getGlobalVisibleRect(it) && !it.isEmpty }
    }

    private fun autoPipEligible(current: Session, visibleRoom: Rect?): Boolean {
        val activity = current.activity
        return visibleRoom != null && pendingConsent == null && current.share == null && !activity.isInPictureInPictureMode &&
            !activity.isFinishing && !activity.isDestroyed && ForegroundSurface.isTop(current.overlay.rootView) &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private fun pictureInPictureParams(visibleRoom: Rect?, autoEnter: Boolean = false): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (visibleRoom != null) builder.setSourceRectHint(visibleRoom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun disableAutoPip(activity: Activity) {
        if (!activity.isFinishing && !activity.isDestroyed &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }

    private fun shareSession(activity: Activity) {
        val current = session ?: return
        current.share = ShareDeparture.Starting
        disableAutoPip(activity)
        if (!SystemIntegration.shareLink(activity, current.url)) {
            current.share = null
            refreshPipEligibility()
            Toast.makeText(activity, R.string.share_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private inner class Session(val activity: ComponentActivity, val overlay: CallOverlay, val host: FrameLayout,
        val url: String, val recordId: String, val audio: CallAudioRouter
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var share: ShareDeparture? = null
        val onLifecycle = LifecycleEventObserver { _, event ->
            if (session === this && event == Lifecycle.Event.ON_RESUME) {
                refreshPipEligibility()
            }
        }
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (session === this) refreshPipEligibility()
        }
    }

    private enum class ShareDeparture { Starting, Away, Returned }

    private enum class ConsentKind { Notification, Media }

    private sealed class Consent(val kind: ConsentKind) {
        data object Notification : Consent(ConsentKind.Notification)
        class Media(val request: FaceTimeWebViewHelper.MediaConsent?) : Consent(ConsentKind.Media)
    }
}
