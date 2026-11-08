package com.baotime.app.ui

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.baotime.app.R
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.GlassNavigation
import com.baotime.app.ui.theme.BaoTimeTheme

/** Canonical bounded sheet with separate drag and close controls. */
object ModalSheetUi {
    fun build(theme: BaoTimeTheme, content: View, onDismiss: () -> Unit): FrameLayout {
        val root = FrameLayout(theme.context).apply {
            setBackgroundColor(theme.colorScrim)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { onDismiss() }
        }
        val card = (content as? LinearLayout ?: contentColumn(theme).apply { addView(content) }).apply {
            setPadding(theme.spaceLg, 0, theme.spaceLg, theme.spaceLg)
            isClickable = true
            addView(sheetHeader(theme, this, onDismiss), 0)
        }
        val scroll = ScrollView(theme.context).apply {
            isFillViewport = false
            clipToPadding = false
            addView(card)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        root.addView(scroll)
        root.doOnAttach {
            val parent = root.parent as? ViewGroup ?: return@doOnAttach
            val source = (0 until parent.childCount).map(parent::getChildAt)
                .firstOrNull { it !== root && it.isShown } ?: return@doOnAttach
            GlassNavigation(theme, source, scroll, tintColor = theme.colorGlassSheet,
                radius = theme.radiusControl, interactive = false)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ForegroundSurface.bind(root, card, theme.context.getString(R.string.desc_sheet))
        return root
    }

    private fun sheetHeader(theme: BaoTimeTheme, card: View, onDismiss: () -> Unit): View =
        FrameLayout(theme.context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.minTouchTarget)
            addView(dragHandle(theme, card, onDismiss),
                FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget, Gravity.CENTER))
            val close = BaoTimeComponents.iconActionButton(theme,
                BaoTimeComponents.RowAction(R.drawable.ic_close, R.string.desc_close_sheet) { onDismiss() })
            addView(close, FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget, Gravity.END))
        }

    private fun dragHandle(theme: BaoTimeTheme, cardMotion: View, onDismiss: () -> Unit): View {
        var downY = 0f
        val threshold = theme.minTouchTarget.toFloat()
        val clear = theme.context.getColor(R.color.color_control_clear)
        return ImageButton(theme.context).apply {
            val grabber = theme.surfaceDrawable(theme.radiusControl, theme.colorTextHint).apply {
                setSize(resources.getDimensionPixelSize(R.dimen.sheet_grabber_width),
                    resources.getDimensionPixelSize(R.dimen.sheet_grabber_height))
            }
            setImageDrawable(grabber)
            imageTintList = theme.controlForeground(theme.colorTextHint)
            scaleType = ImageView.ScaleType.CENTER
            stateListAnimator = null
            background = theme.controlBackground(clear)
            contentDescription = theme.context.getString(R.string.desc_dismiss_sheet)
            setOnClickListener { onDismiss() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val distance = event.rawY - downY
                        cardMotion.translationY = distance.coerceAtLeast(0f)
                        if (kotlin.math.abs(distance) > theme.spaceSm) view.isPressed = false
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        val distance = event.rawY - downY
                        cardMotion.translationY = 0f
                        view.isPressed = false
                        if (distance > threshold) onDismiss()
                        else if (kotlin.math.abs(distance) < theme.spaceSm) view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        cardMotion.translationY = 0f
                        view.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    fun contentColumn(theme: BaoTimeTheme): LinearLayout = LinearLayout(theme.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

}
