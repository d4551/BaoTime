package com.baotime.app.call

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.baotime.app.R
import com.baotime.app.audio.CallAudioRouter
import com.baotime.app.data.CallHistory
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.CallOverlay
import com.baotime.app.ui.CallOverlayUi
import com.baotime.app.ui.theme.BaoTimeTheme
import com.baotime.app.ui.components.ForegroundSurface

/** Owns one opened room, its native audio route, permission request and elapsed session time. */
class CallSessionController(
    private val history: CallHistory,
    private val onSessionClosed: () -> Unit
) {
    private var session: Session? = null
    val isActive: Boolean get() = session != null

    fun openSession(activity: ComponentActivity, host: FrameLayout, theme: BaoTimeTheme,
        url: String, title: String, recordId: String, onAllowMedia: () -> Unit
    ) {
        if (!UrlRouter.isFaceTimeRoomUrl(url)) {
            Toast.makeText(activity, R.string.call_status_navigation_blocked, Toast.LENGTH_LONG).show()
            return
        }
        closeSession()
        val overlay = CallOverlayUi.build(theme, title,
            onStatusUpdate = { message -> session?.overlay?.statusText?.text = message },
            onAllowMedia = onAllowMedia,
            speakerState = { session?.audio?.speakerState() ?: com.baotime.app.audio.CallAudioRouter.SpeakerState.Off },
            onSpeakerToggle = { toggleSpeaker(activity) },
            onShareLink = { shareSession(activity) },
            onEnterPip = { enterPipMode(activity) },
            onEndCall = { closeSession(); onSessionClosed() },
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
            onSessionClosed()
        }
    }

    fun requiredMediaPermissions(): Array<String> = session?.overlay?.room?.pendingAndroidPermissions() ?: emptyArray()

    fun allowHeldMedia(activity: Activity): Boolean {
        if (session?.overlay?.room?.grantHeldMedia() == true) {
            Toast.makeText(activity, R.string.call_status_media_granted, Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    fun denyHeldMedia() { session?.overlay?.room?.denyHeldMedia() }

    fun closeSession() {
        val current = session ?: return
        session = null
        disableAutoPip(current.activity)
        current.activity.lifecycle.removeObserver(current.onLifecycle)
        current.overlay.webView.removeOnLayoutChangeListener(current.onLayout)
        current.audio.release()
        current.host.removeView(current.overlay.rootView)
        current.overlay.room.dispose()
        val elapsed = (SystemClock.elapsedRealtime() - current.startedAt) / 1000L
        history.setDuration(current.recordId, elapsed)
    }

    fun setPipMode(inPip: Boolean) {
        val overlay = session?.overlay ?: return
        val visibility = if (inPip) View.GONE else View.VISIBLE
        overlay.topBar.visibility = visibility
        overlay.permissionButton.visibility = if (!inPip && overlay.room.hasPendingMedia) View.VISIBLE else View.GONE
    }

    fun refreshPipEligibility() {
        val current = session ?: return
        if (!ForegroundSurface.isTop(current.overlay.rootView)) {
            disableAutoPip(current.activity)
        } else if (!current.activity.isInPictureInPictureMode &&
            current.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            current.activity.setPictureInPictureParams(pictureInPictureParams(current))
        }
    }

    fun releaseSpeakerSelection() { session?.audio?.release() }

    private fun toggleSpeaker(activity: Activity) {
        val current = session ?: return
        if (current.audio.hasSpeakerSelection()) {
            current.audio.release()
        } else if (!current.audio.enableSpeaker()) {
            Toast.makeText(activity, R.string.call_speaker_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

    }

    private fun enterPipMode(activity: Activity) {
        val current = session ?: return
        val params = pictureInPictureParams(current)
        activity.setPictureInPictureParams(params)
        if (!activity.enterPictureInPictureMode(params)) {
            Toast.makeText(activity, R.string.call_pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pictureInPictureParams(current: Session): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(9, 16))
        val sourceRect = android.graphics.Rect()
        val visible = current.overlay.webView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty
        if (visible) {
            builder.setSourceRectHint(sourceRect)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(visible && ForegroundSurface.isTop(current.overlay.rootView) &&
                current.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        return builder.build()
    }

    private fun disableAutoPip(activity: Activity) {
        if (!activity.isFinishing && !activity.isDestroyed &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }

    private fun shareSession(activity: Activity) {
        val current = session ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, current.url)
        }
        disableAutoPip(activity)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.action_share)))
    }

    private inner class Session(val activity: ComponentActivity, val overlay: CallOverlay, val host: FrameLayout,
        val url: String, val recordId: String, val audio: CallAudioRouter
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val onLifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (session === this && event == Lifecycle.Event.ON_RESUME) refreshPipEligibility()
        }
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (session === this) refreshPipEligibility()
        }
    }
}
