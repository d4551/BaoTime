package com.baotime.app.ui.components

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import com.baotime.app.R

/** Per-host foreground ownership, including removal of a covered surface out of order. */
object ForegroundSurface {
    fun isTop(root: ViewGroup): Boolean {
        val host = root.parent
        if (host !is ViewGroup) return false
        val stack = host.getTag(R.id.foreground_surface_state)
        return stack is SurfaceStack && stack.isTop(root)
    }

    fun bind(root: ViewGroup, entry: View, title: CharSequence, lightStatusBars: Boolean? = null,
        lightNavigationBars: Boolean? = null, onTopChanged: ((Boolean) -> Unit)? = null
    ) {
        root.doOnAttach {
            val host = root.parent
            if (host is ViewGroup) {
                val tagged = host.getTag(R.id.foreground_surface_state)
                val stack = if (tagged is SurfaceStack) tagged else SurfaceStack(host)
                host.setTag(R.id.foreground_surface_state, stack)
                val surface = Surface(root, entry, lightStatusBars, lightNavigationBars, onTopChanged)
                ViewCompat.requestApplyInsets(root)
                ViewCompat.setAccessibilityPaneTitle(root, title)
                stack.add(surface)
                root.doOnDetach { stack.remove(surface) }
            }
        }
    }

    private data class Surface(val root: ViewGroup, val entry: View, val lightStatusBars: Boolean?,
        val lightNavigationBars: Boolean?, val onTopChanged: ((Boolean) -> Unit)?)
    private data class Original(val accessibility: Int, val focusability: Int?) {
        fun restore(view: View) {
            view.importantForAccessibility = accessibility
            if (view is ViewGroup && focusability != null) view.descendantFocusability = focusability
        }
    }

    private class SurfaceStack(private val host: ViewGroup) {
        private val originals = mutableMapOf<View, Original>()
        private val surfaces = mutableListOf<Surface>()
        private val previousFocus = host.findFocus()
        private val activity = host.context
        private val bars = if (activity is Activity) WindowCompat.getInsetsController(activity.window, activity.window.decorView) else null
        private val navigationContrast = if (activity is Activity) activity.window.isNavigationBarContrastEnforced else null
        private val lightStatus = bars?.isAppearanceLightStatusBars
        private val lightNavigation = bars?.isAppearanceLightNavigationBars

        fun isTop(root: ViewGroup): Boolean = surfaces.lastOrNull()?.root === root

        fun add(surface: Surface) {
            (0 until host.childCount).map(host::getChildAt).forEach { view ->
                originals.putIfAbsent(view, Original(view.importantForAccessibility,
                    if (view is ViewGroup) view.descendantFocusability else null))
            }
            surfaces.add(surface)
            applyTop()
            surface.entry.isFocusableInTouchMode = true
            surface.entry.requestFocus()
        }

        fun remove(surface: Surface) {
            surfaces.remove(surface)
            surface.onTopChanged?.invoke(false)
            originals.remove(surface.root)?.restore(surface.root)
            applyTop()
            if (surfaces.isEmpty()) {
                originals.forEach { (view, original) -> original.restore(view) }
                host.setTag(R.id.foreground_surface_state, null)
                if (previousFocus?.isAttachedToWindow == true) previousFocus.requestFocus()
            } else surfaces.last().entry.requestFocus()
        }

        private fun applyTop() {
            val top = surfaces.lastOrNull()
            surfaces.forEach { it.onTopChanged?.invoke(it === top) }
            originals.forEach { (view, original) ->
                if (top == null || view === top.root) original.restore(view)
                else {
                    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    if (view is ViewGroup) view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            }
            if (activity is Activity && navigationContrast != null) {
                activity.window.isNavigationBarContrastEnforced =
                    if (top?.lightStatusBars == false || top?.lightNavigationBars == false) false else navigationContrast
            }
            if (lightStatus != null && lightNavigation != null) {
                bars?.isAppearanceLightStatusBars = top?.lightStatusBars ?: lightStatus
                bars?.isAppearanceLightNavigationBars = top?.lightNavigationBars ?: lightNavigation
            }
        }
    }
}
