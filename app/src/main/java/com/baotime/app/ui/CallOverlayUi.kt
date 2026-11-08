package com.baotime.app.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baotime.app.R
import com.baotime.app.audio.CallAudioRouter
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.theme.BaoTimeTheme

class CallOverlay(val rootView: FrameLayout, val webView: WebView, val room: FaceTimeWebViewHelper,
    val topBar: View, val permissionButton: View, val statusText: TextView)

/** One compact app bar; Apple owns the entire room and its camera, mic and leave controls. */
object CallOverlayUi {
    fun build(theme: BaoTimeTheme, title: String, onStatusUpdate: (String) -> Unit,
        onAllowMedia: () -> Unit, speakerState: () -> CallAudioRouter.SpeakerState, onSpeakerToggle: () -> Unit,
        onShareLink: () -> Unit, onEnterPip: () -> Unit, onEndCall: () -> Unit,
        onTopChanged: (Boolean) -> Unit
    ): CallOverlay {
        val context = theme.context
        val root = FrameLayout(context).apply { setBackgroundColor(theme.colorCallChrome) }
        val web = WebView(context)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.spaceSm, theme.spaceSm, theme.spaceSm, theme.spaceSm)
        }
        toolbar.addView(BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_close, R.string.desc_end_call, iconTint = theme.colorCallText) { onEndCall() }))
        val identity = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.spaceMd, 0, theme.spaceMd, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        identity.addView(BaoTimeComponents.titleText(theme, title).apply { setTextColor(theme.colorCallText) })
        val status = TextView(context).apply {
            setTextColor(theme.colorCallTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeCaptionPx)
            setText(R.string.call_status_connecting)
        }
        identity.addView(status)
        toolbar.addView(identity)
        val more = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_more, R.string.call_more_actions, iconTint = theme.colorCallText) { anchor ->
            showOptions(theme, anchor, web, speakerState, onSpeakerToggle, onShareLink, onEnterPip)
        })
        toolbar.addView(more)
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        com.baotime.app.ui.components.GlassNavigation(theme, web, toolbar,
            tintColor = theme.colorGlassCallBar, fallbackColor = theme.colorCallChrome,
            radius = 0, interactive = false)
        val permission = BaoTimeComponents.actionButton(theme, context.getString(R.string.action_allow)) { onAllowMedia() }
        permission.visibility = View.GONE
        root.addView(permission, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                marginStart = theme.spaceSm
                marginEnd = theme.spaceSm
                topMargin = theme.minTouchTarget + theme.spaceSm * 2
            })
        val room = FaceTimeWebViewHelper(web, { resource -> onStatusUpdate(context.getString(resource)) }, { waiting ->
            val pip = context is android.app.Activity && context.isInPictureInPictureMode
            permission.visibility = if (waiting && !pip) View.VISIBLE else View.GONE
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.background = theme.callSurfaceDrawable(insets.getInsets(WindowInsetsCompat.Type.navigationBars()), view.layoutDirection)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ForegroundSurface.bind(root, status, title, lightStatusBars = false,
            lightNavigationBars = false, onTopChanged = onTopChanged)
        return CallOverlay(root, web, room, toolbar, permission, status)
    }
    private fun showOptions(theme: BaoTimeTheme, anchor: View, web: WebView,
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
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.room_share -> onShareLink()
                    R.id.room_speaker -> onSpeakerToggle()
                    R.id.room_pip -> onEnterPip()
                    R.id.room_reload -> web.reload()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

}
