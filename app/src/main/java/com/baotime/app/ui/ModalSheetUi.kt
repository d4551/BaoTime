package com.baotime.app.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnAttach
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.components.GlassNavigation
import com.baotime.app.ui.components.WindowSafeArea
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlin.math.abs

/** Canonical bounded sheet with separate drag and close controls. */
object ModalSheetUi {
    private class SheetFrame(context: Context) : FrameLayout(context) {
        lateinit var updateAppearance: (BaoTimeTheme) -> Unit
    }

    internal fun updateTheme(root: View, theme: BaoTimeTheme) {
        if (root is SheetFrame) root.updateAppearance(theme)
    }

    fun build(theme: BaoTimeTheme, content: View, onDismiss: () -> Unit): FrameLayout {
        val root = SheetFrame(theme.context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scrim = View(theme.context).apply {
            setBackgroundColor(theme.colorScrim)
            setOnClickListener { onDismiss() }
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val card = if (content is LinearLayout) content else contentColumn(theme).apply { addView(content) }
        card.setPadding(theme.spaceLg, 0, theme.spaceLg, theme.spaceLg)
        var header = sheetHeader(theme, card, onDismiss)
        card.addView(header, 0)
        val sheetBounds = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        val scroll = ScrollView(theme.context).apply {
            isFillViewport = false
            clipToPadding = false
            addView(card)
        }
        root.addView(scroll, sheetBounds)
        var glass: GlassNavigation? = null
        root.doOnAttach {
            val parent = root.parent
            if (parent !is ViewGroup) return@doOnAttach
            val source = (0 until parent.childCount).map(parent::getChildAt)
                .firstOrNull { it !== root && it.isShown } ?: return@doOnAttach
            glass = GlassNavigation(theme, source, scroll, tintColor = theme.colorGlassSheet,
                radius = theme.radiusControl, interactive = false)
        }
        WindowSafeArea.observe(root, includeKeyboard = true) { _, safe, _ ->
            sheetBounds.setMargins(safe.left, safe.top, safe.right, safe.bottom)
            scroll.requestLayout()
        }
        ForegroundSurface.bind(root, card, theme.context.getString(R.string.desc_sheet))
        root.updateAppearance = { current ->
            scrim.setBackgroundColor(current.colorScrim)
            card.setPadding(current.spaceLg, 0, current.spaceLg, current.spaceLg)
            glass?.updateMaterial(current, current.colorGlassSheet, current.colorChromeFallback)
            val focusIndex = (0 until header.childCount).firstOrNull { header.getChildAt(it).hasFocus() }
            card.removeView(header)
            header = sheetHeader(current, card, onDismiss)
            card.addView(header, 0)
            if (focusIndex != null) header.getChildAt(focusIndex).requestFocus()
        }
        return root
    }

    private fun sheetHeader(theme: BaoTimeTheme, card: View, onDismiss: () -> Unit): FrameLayout =
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
        return ImageButton(theme.context).apply {
            val grabber = theme.surfaceDrawable(theme.radiusControl, theme.colorTextHint).apply {
                setSize(resources.getDimensionPixelSize(R.dimen.sheet_grabber_width),
                    resources.getDimensionPixelSize(R.dimen.sheet_grabber_height))
            }
            setImageDrawable(grabber)
            imageTintList = theme.controlForeground(theme.colorTextHint)
            scaleType = ImageView.ScaleType.CENTER
            stateListAnimator = null
            background = theme.controlBackground(theme.colorControlClear)
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
                        if (abs(distance) > theme.spaceSm) view.isPressed = false
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        val distance = event.rawY - downY
                        cardMotion.translationY = 0f
                        view.isPressed = false
                        if (distance > threshold) onDismiss()
                        else if (abs(distance) < theme.spaceSm) view.performClick()
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
