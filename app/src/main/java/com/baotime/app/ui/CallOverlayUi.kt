package com.baotime.app.ui

import android.app.Activity
import android.graphics.Insets
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import com.baotime.app.R
import com.baotime.app.audio.CallAudioRouter
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.components.GlassNavigation
import com.baotime.app.ui.components.WindowSafeArea
import com.baotime.app.ui.theme.BaoTimeTheme

class CallOverlay(val rootView: FrameLayout, val webView: WebView, val room: FaceTimeWebViewHelper,
    val topBar: View, val permissionButton: View, internal val updateTheme: (BaoTimeTheme) -> Unit)

/** One compact app bar; Apple owns the entire room and its camera, mic and leave controls. */
object CallOverlayUi {
    fun build(theme: BaoTimeTheme, title: String,
        onAllowMedia: () -> Unit, speakerState: () -> CallAudioRouter.SpeakerState, onSpeakerToggle: () -> Unit,
        onShareLink: () -> Unit, onEnterPip: () -> Unit, onEndCall: () -> Unit,
        onRendererGone: (WebView) -> Unit,
        onTopChanged: (Boolean) -> Unit
    ): CallOverlay {
        val context = theme.context
        var currentTheme = theme
        val root = FrameLayout(context).apply { setBackgroundColor(theme.colorCallChrome) }
        val web = WebView(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.spaceSm, theme.spaceSm, theme.spaceSm, theme.spaceSm)
        }
        val close = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_close, R.string.desc_end_call, iconTint = theme.colorCallText) { onEndCall() })
        toolbar.addView(close)
        val identity = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.spaceMd, 0, theme.spaceMd, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val heading = BaoTimeComponents.titleText(theme, title).apply {
            isAccessibilityHeading = true
            BaoTimeComponents.styleTitle(theme, this, theme.colorCallText)
        }
        identity.addView(heading)
        val status = BaoTimeComponents.captionText(theme, context.getString(R.string.call_status_connecting)).apply {
            BaoTimeComponents.styleCaption(theme, this, theme.colorCallTextSecondary)
        }
        identity.addView(status)
        toolbar.addView(identity)
        val permission = BaoTimeComponents.actionButton(theme, context.getString(R.string.action_allow)) { onAllowMedia() }
        permission.visibility = View.GONE
        val room = FaceTimeWebViewHelper(web, { resource ->
            val message = context.getString(resource)
            if (status.text != message) status.text = message
        }, { waiting ->
            val pip = context is Activity && context.isInPictureInPictureMode
            permission.visibility = if (waiting && !pip) View.VISIBLE else View.GONE
        }, onRendererGone)
        val more = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_more, R.string.call_more_actions, iconTint = theme.colorCallText) { anchor ->
            showOptions(currentTheme, anchor, room, speakerState, onSpeakerToggle, onShareLink, onEnterPip)
        })
        toolbar.addView(more)
        column.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        val glass = GlassNavigation(theme, web, toolbar,
            tintColor = theme.colorGlassCallBar, fallbackColor = theme.colorCallChrome,
            radius = 0, interactive = false)
        glass.sampleTopEdge()
        column.addView(permission, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = theme.spaceSm
                marginEnd = theme.spaceSm
                topMargin = theme.spaceSm
                bottomMargin = theme.spaceSm
            })
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var lastNavigation = Insets.NONE
        WindowSafeArea.observe(root, includeKeyboard = true) { view, safe, navigation ->
            lastNavigation = navigation
            view.background = currentTheme.callSurfaceDrawable(navigation.left, navigation.right, navigation.bottom,
                view.layoutDirection)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        }
        ForegroundSurface.bind(root, close, title, lightStatusBars = false,
            lightNavigationBars = false, systemBarColor = theme.colorCallChrome) { top ->
            status.accessibilityLiveRegion = if (top) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
            onTopChanged(top)
        }
        return CallOverlay(root, web, room, toolbar, permission) { updated ->
            currentTheme = updated
            BaoTimeComponents.styleTitle(updated, heading, updated.colorCallText)
            BaoTimeComponents.styleCaption(updated, status, updated.colorCallTextSecondary)
            for (button in listOf(close, more)) {
                BaoTimeComponents.styleIconAction(updated, button, updated.colorControlClear, updated.colorCallText)
            }
            BaoTimeComponents.styleActionButton(updated, permission)
            glass.updateMaterial(updated, updated.colorGlassCallBar, updated.colorCallChrome)
            root.background = updated.callSurfaceDrawable(lastNavigation.left, lastNavigation.right,
                lastNavigation.bottom, root.layoutDirection)
        }
    }
    private fun showOptions(theme: BaoTimeTheme, anchor: View, room: FaceTimeWebViewHelper,
        speakerState: () -> CallAudioRouter.SpeakerState, onSpeakerToggle: () -> Unit,
        onShareLink: () -> Unit, onEnterPip: () -> Unit
    ) {
        PopupMenu(theme.context, anchor).apply {
            menu.add(Menu.NONE, R.id.room_share, 0, R.string.action_share)
            val speaker = speakerState()
            menu.add(Menu.NONE, R.id.room_speaker, 1,
                if (speaker == CallAudioRouter.SpeakerState.Switching) R.string.action_cancel_speaker_switch
                else R.string.action_speaker).apply {
                isCheckable = true
                isChecked = speaker == CallAudioRouter.SpeakerState.Active
            }
            menu.add(Menu.NONE, R.id.room_pip, 2, R.string.desc_pip)
            menu.add(Menu.NONE, R.id.room_reload, 3, R.string.desc_retry_call)
            val attachment = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) = dismiss()
            }
            anchor.addOnAttachStateChangeListener(attachment)
            setOnDismissListener { anchor.removeOnAttachStateChangeListener(attachment) }
            setOnMenuItemClickListener { item ->
                if (!anchor.isAttachedToWindow) return@setOnMenuItemClickListener false
                when (item.itemId) {
                    R.id.room_share -> onShareLink()
                    R.id.room_speaker -> onSpeakerToggle()
                    R.id.room_pip -> onEnterPip()
                    R.id.room_reload -> room.reloadRoom()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }
}
