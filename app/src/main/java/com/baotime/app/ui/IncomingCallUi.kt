package com.baotime.app.ui

import android.content.Context
import android.graphics.Insets
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.components.WindowSafeArea
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlin.math.ceil

/**
 * The incoming-call surface: caller identity with Accept and Decline controls.
 * Nothing joins and no media is requested until the user accepts — an incoming
 * link can never switch on the camera or microphone by itself.
 */
object IncomingCallUi {
    private class IncomingFrame(context: Context) : FrameLayout(context) {
        lateinit var updateAppearance: (BaoTimeTheme) -> Unit
    }

    /** Control row that wraps vertically when the labels no longer fit side by side. */
    private class AdaptiveControlRow(context: Context, private val buttons: List<Button>, private val gap: Int) :
        LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val required = buttons.sumOf { button ->
                maxOf(button.minimumWidth, ceil(button.paint.measureText(button.text.toString())).toInt() +
                    button.paddingLeft + button.paddingRight) + gap * 2
            }
            val direction = if (available > 0 && required > available) VERTICAL else HORIZONTAL
            if (orientation != direction) orientation = direction
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    internal fun updateTheme(root: FrameLayout, theme: BaoTimeTheme) {
        if (root is IncomingFrame) root.updateAppearance(theme)
    }

    fun build(
        theme: BaoTimeTheme,
        caller: String,
        onAccept: () -> Unit,
        onDecline: () -> Unit
    ): FrameLayout {
        val root = IncomingFrame(theme.context).apply {
            setBackgroundColor(theme.colorCallChrome)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        buildIncomingContent(theme, caller, onAccept, onDecline, root)
        return root
    }

    private fun buildIncomingContent(
        theme: BaoTimeTheme,
        caller: String,
        onAccept: () -> Unit,
        onDecline: () -> Unit,
        root: IncomingFrame
    ): IncomingFrame {
        val context = theme.context
        var currentTheme = theme
        var navigationInsets = Insets.NONE
        root.apply {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = theme.spaceXl
                setPadding(pad, pad, pad, pad)
            }
            val badge = BaoTimeComponents.badgeView(theme, caller.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty())
            column.addView(badge)
            val heading = BaoTimeComponents.titleText(theme, caller).apply {
                isAccessibilityHeading = true
                BaoTimeComponents.styleTitle(theme, this, theme.colorCallText, theme.textSizeTitlePx)
                gravity = Gravity.CENTER
            }
            column.addView(heading)
            val caption = BaoTimeComponents.captionText(theme, context.getString(R.string.incoming_call_title)).apply {
                BaoTimeComponents.styleCaption(theme, this, theme.colorCallTextSecondary)
                gravity = Gravity.CENTER
                setPadding(0, theme.spaceSm, 0, 0)
            }
            column.addView(caption)
            val decline = BaoTimeComponents.callControlButton(
                theme, R.drawable.ic_call_end, R.string.action_decline, R.string.desc_decline_call,
                bgColor = theme.colorDanger, captionColor = theme.colorCallText, iconTint = theme.colorOnAccent
            ) { onDecline() }
            val accept = BaoTimeComponents.callControlButton(
                theme, R.drawable.ic_call_accept, R.string.action_accept, R.string.desc_accept_call,
                bgColor = theme.colorBaoGreen, captionColor = theme.colorCallText, iconTint = theme.colorOnAccent
            ) { onAccept() }
            val buttons = listOf(decline, accept)
            val controls = AdaptiveControlRow(context, buttons, theme.spaceMd).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, theme.spaceXl, 0, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            buttons.forEach(controls::addView)
            column.addView(controls)
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            WindowSafeArea.observe(this) { view, safe, navigation ->
                navigationInsets = navigation
                view.background = currentTheme.callSurfaceDrawable(navigation.left, navigation.right, navigation.bottom,
                    view.layoutDirection)
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            ForegroundSurface.bind(this, decline, caller, lightStatusBars = false, lightNavigationBars = false,
                systemBarColor = theme.colorCallChrome)
            updateAppearance = { updated ->
                currentTheme = updated
                root.background = updated.callSurfaceDrawable(navigationInsets.left, navigationInsets.right,
                    navigationInsets.bottom, root.layoutDirection)
                BaoTimeComponents.styleCaption(updated, badge)
                BaoTimeComponents.styleTitle(updated, heading, updated.colorCallText, updated.textSizeTitlePx)
                BaoTimeComponents.styleCaption(updated, caption, updated.colorCallTextSecondary)
                BaoTimeComponents.styleCallControl(updated, decline, R.drawable.ic_call_end, updated.colorDanger,
                    updated.colorCallText, updated.colorOnAccent)
                BaoTimeComponents.styleCallControl(updated, accept, R.drawable.ic_call_accept, updated.colorBaoGreen,
                    updated.colorCallText, updated.colorOnAccent)
            }
        }
        return root
    }
}
