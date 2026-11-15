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

    private class SheetScroll(context: Context) : ScrollView(context) {
        lateinit var header: SheetHeader
        private var headerTouch = false

        init {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                headerTouch = header.hitsControl(this, event)
                if (headerTouch) false else super.onInterceptTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val active = headerTouch
                headerTouch = false
                if (active) false else super.onInterceptTouchEvent(event)
            }
            else -> if (headerTouch) false else super.onInterceptTouchEvent(event)
        }
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
        val header = SheetHeader(theme, card, onDismiss)
        card.addView(header.view, 0)
        val sheetBounds = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        val scroll = SheetScroll(theme.context).apply {
            this.header = header
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
        ForegroundSurface.bind(root, header.close, theme.context.getString(R.string.desc_sheet))
        root.updateAppearance = { current ->
            scrim.setBackgroundColor(current.colorScrim)
            card.setPadding(current.spaceLg, 0, current.spaceLg, current.spaceLg)
            glass?.updateMaterial(current, current.colorGlassSheet, current.colorChromeFallback)
            header.updateTheme(current)
        }
        return root
    }

    private class SheetHeader(initialTheme: BaoTimeTheme, cardMotion: View, onDismiss: () -> Unit) {
        private var currentTheme = initialTheme
        private var downY = 0f

        private val drag = ImageButton(initialTheme.context).apply {
            scaleType = ImageView.ScaleType.CENTER
            stateListAnimator = null
            setOnClickListener { onDismiss() }
            setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        target.parent.requestDisallowInterceptTouchEvent(true)
                        target.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val distance = event.rawY - downY
                        cardMotion.translationY = distance.coerceAtLeast(0f)
                        if (abs(distance) > currentTheme.spaceSm) target.isPressed = false
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        target.parent.requestDisallowInterceptTouchEvent(false)
                        val distance = event.rawY - downY
                        cardMotion.translationY = 0f
                        target.isPressed = false
                        if (distance > currentTheme.minTouchTarget.toFloat()) onDismiss()
                        else if (abs(distance) < currentTheme.spaceSm) target.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        target.parent.requestDisallowInterceptTouchEvent(false)
                        cardMotion.translationY = 0f
                        target.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }

        val close = BaoTimeComponents.iconActionButton(
            initialTheme,
            BaoTimeComponents.RowAction(R.drawable.ic_close, R.string.desc_close_sheet) { onDismiss() }
        )

        val view = FrameLayout(initialTheme.context).apply {
            addView(drag)
            addView(close)
        }

        init {
            updateTheme(initialTheme)
        }

        fun updateTheme(theme: BaoTimeTheme) {
            currentTheme = theme
            view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.minTouchTarget)
            val grabber = theme.surfaceDrawable(theme.radiusControl, theme.colorTextHint).apply {
                setSize(drag.resources.getDimensionPixelSize(R.dimen.sheet_grabber_width),
                    drag.resources.getDimensionPixelSize(R.dimen.sheet_grabber_height))
            }
            drag.setImageDrawable(grabber)
            drag.imageTintList = theme.controlForeground(theme.colorTextHint)
            drag.background = theme.controlBackground(theme.colorControlClear)
            drag.contentDescription = theme.context.getString(R.string.desc_dismiss_sheet)
            drag.layoutParams = FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget, Gravity.CENTER)
            BaoTimeComponents.styleIconAction(theme, close, theme.colorControlClear, theme.colorTextPrimary)
            close.contentDescription = theme.context.getString(R.string.desc_close_sheet)
            close.layoutParams = FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget, Gravity.END)
        }

        fun hitsControl(scroll: ScrollView, event: MotionEvent): Boolean {
            if (!view.isShown) return false
            val card = scroll.getChildAt(0) ?: return false
            val x = event.x + scroll.scrollX - card.left - card.translationX - view.left
            val y = event.y + scroll.scrollY - card.top - card.translationY - view.top
            return hits(drag, x, y) || hits(close, x, y)
        }

        private fun hits(target: View, x: Float, y: Float): Boolean =
            target.isShown && x >= target.left && x < target.right && y >= target.top && y < target.bottom
    }

    fun contentColumn(theme: BaoTimeTheme): LinearLayout = LinearLayout(theme.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

}
