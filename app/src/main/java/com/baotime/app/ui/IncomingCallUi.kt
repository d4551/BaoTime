package com.baotime.app.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/**
 * The incoming-call surface: caller identity with Accept and Decline controls.
 * Nothing joins and no media is requested until the user accepts — an incoming
 * link can never switch on the camera or microphone by itself.
 */
object IncomingCallUi {

    fun build(
        theme: BaoTimeTheme,
        caller: String,
        onAccept: () -> Unit,
        onDecline: () -> Unit
    ): FrameLayout {
        val context = theme.context
        return FrameLayout(context).apply {
            setBackgroundColor(theme.colorCallChrome)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = theme.spaceXl
                setPadding(pad, pad, pad, pad)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            column.addView(
                BaoTimeComponents.badgeView(theme, caller.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty())
            )
            column.addView(
                BaoTimeComponents.titleText(theme, caller).apply {
                    setTextColor(theme.colorCallText)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeTitlePx)
                    gravity = Gravity.CENTER
                }
            )
            column.addView(
                BaoTimeComponents.captionText(theme, context.getString(R.string.incoming_call_title)).apply {
                    setTextColor(theme.colorCallTextSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, theme.spaceSm, 0, 0)
                }
            )
            val controls = object : LinearLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                    val required = (0 until childCount).sumOf { index ->
                        val button = getChildAt(index)
                        require(button is android.widget.Button)
                        maxOf(button.minimumWidth, kotlin.math.ceil(button.paint.measureText(button.text.toString())).toInt() +
                            button.paddingLeft + button.paddingRight) + theme.spaceMd * 2
                    }
                    val direction = if (available > 0 && required > available) VERTICAL else HORIZONTAL
                    if (orientation != direction) orientation = direction
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, theme.spaceXl, 0, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            controls.addView(
                BaoTimeComponents.callControlButton(
                    theme,
                    R.drawable.ic_call_end,
                    R.string.action_decline,
                    R.string.desc_decline_call,
                    bgColor = theme.colorDanger,
                    captionColor = theme.colorCallText,
                    iconTint = theme.colorOnAccent
                ) { onDecline() }
            )
            controls.addView(
                BaoTimeComponents.callControlButton(
                    theme,
                    R.drawable.ic_call_accept,
                    R.string.action_accept,
                    R.string.desc_accept_call,
                    bgColor = theme.colorBaoGreen,
                    captionColor = theme.colorCallText,
                    iconTint = theme.colorOnAccent
                ) { onAccept() }
            )
            column.addView(controls)
            addView(column)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.background = theme.callSurfaceDrawable(insets.getInsets(WindowInsetsCompat.Type.navigationBars()), view.layoutDirection)
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            ForegroundSurface.bind(this, column, caller, lightStatusBars = false, lightNavigationBars = false)
        }
    }
}
