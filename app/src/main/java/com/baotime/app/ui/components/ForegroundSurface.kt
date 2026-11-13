package com.baotime.app.ui.components

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import com.baotime.app.R

/** Per-host foreground ownership, including removal of a covered surface out of order. */
object ForegroundSurface {
    fun applyDefaultBars(window: Window) {
        val appearance = defaultBarAppearance(window)
        applyBars(window, appearance.lightStatus, appearance.lightNavigation)
    }

    internal fun refreshHomeAppearance(host: ViewGroup, home: View, fallbackFocus: View) {
        val stack = host.getTag(R.id.foreground_surface_state)
        if (stack is SurfaceStack) stack.refreshHomeAppearance(home, fallbackFocus)
        else {
            val context = host.context
            if (context is Activity) applyDefaultBars(context.window)
        }
    }

    fun isTop(root: ViewGroup): Boolean {
        val host = root.parent
        if (host !is ViewGroup) return false
        val stack = host.getTag(R.id.foreground_surface_state)
        return stack is SurfaceStack && stack.isTop(root)
    }

    fun bind(root: ViewGroup, entry: View, title: CharSequence, lightStatusBars: Boolean? = null,
        lightNavigationBars: Boolean? = null, systemBarColor: Int? = null, onTopChanged: ((Boolean) -> Unit)? = null
    ) {
        root.doOnAttach {
            val host = root.parent
            if (host is ViewGroup) {
                val tagged = host.getTag(R.id.foreground_surface_state)
                val stack = if (tagged is SurfaceStack) tagged else SurfaceStack(host)
                host.setTag(R.id.foreground_surface_state, stack)
                val surface = Surface(root, entry, lightStatusBars, lightNavigationBars, systemBarColor, onTopChanged)
                root.accessibilityPaneTitle = title
                stack.add(surface)
                root.doOnDetach { stack.remove(surface) }
            }
        }
    }

    private data class Surface(val root: ViewGroup, val entry: View, val lightStatusBars: Boolean?,
        val lightNavigationBars: Boolean?, val systemBarColor: Int?, val onTopChanged: ((Boolean) -> Unit)?)
    private data class BarAppearance(val lightStatus: Boolean, val lightNavigation: Boolean)
    private data class FittedBars(val status: Int, val navigation: Int, val flags: Int)
    private data class Original(val accessibility: Int, val focusability: Int?) {
        fun restore(view: View) {
            view.importantForAccessibility = accessibility
            if (view is ViewGroup && focusability != null) view.descendantFocusability = focusability
        }
    }

    private class SurfaceStack(private val host: ViewGroup) {
        private val originals = mutableMapOf<View, Original>()
        private val surfaces = mutableListOf<Surface>()
        private var previousFocus = host.findFocus()
        private val window = host.context.let { context -> if (context is Activity) context.window else null }
        private val navigationContrast = window?.isNavigationBarContrastEnforced
        private var barAppearance = window?.let(::readBars)
        private val fittedBars = if (window != null && Build.VERSION.SDK_INT < 35) FittedBars(
            window.statusBarColor, window.navigationBarColor, window.attributes.flags and BAR_BACKGROUND_FLAGS
        ) else null

        fun isTop(root: ViewGroup): Boolean = surfaces.lastOrNull()?.root === root

        fun refreshHomeAppearance(home: View, fallbackFocus: View) {
            previousFocus = previousFocus?.let { previous ->
                if (previous.isAttachedToWindow) previous else home.findViewById<View>(previous.id) ?: fallbackFocus
            }
            val hostWindow = window
            if (hostWindow != null) barAppearance = defaultBarAppearance(hostWindow)
            applyTop()
        }

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
            applyWindowBars(top)
        }

        private fun applyWindowBars(top: Surface?) {
            val appearance = barAppearance
            if (window != null && navigationContrast != null && appearance != null) {
                window.isNavigationBarContrastEnforced =
                    if (top?.lightStatusBars == false || top?.lightNavigationBars == false) false else navigationContrast
                applyBars(window, top?.lightStatusBars ?: appearance.lightStatus,
                    top?.lightNavigationBars ?: appearance.lightNavigation)
                if (fittedBars != null) {
                    val color = top?.systemBarColor
                    val flags = if (color == null) fittedBars.flags else WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                    window.setFlags(flags, BAR_BACKGROUND_FLAGS)
                    window.statusBarColor = color ?: fittedBars.status
                    window.navigationBarColor = color ?: fittedBars.navigation
                }
            }
        }
    }

    private const val BAR_BACKGROUND_FLAGS = WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION

    private fun defaultBarAppearance(window: Window): BarAppearance {
        val night = window.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val light = night != Configuration.UI_MODE_NIGHT_YES
        return BarAppearance(light, light)
    }

    private fun readBars(window: Window): BarAppearance {
        if (Build.VERSION.SDK_INT >= 35) {
            val appearance = window.insetsController?.systemBarsAppearance ?: 0
            return BarAppearance(
                appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0,
                appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0
            )
        }
        val flags = window.decorView.systemUiVisibility
        return BarAppearance(flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0,
            flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)
    }

    private fun applyBars(window: Window, lightStatus: Boolean, lightNavigation: Boolean) {
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 35) {
            val controller = window.insetsController ?: return
            val status = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val navigation = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (lightStatus) status else 0, status)
            controller.setSystemBarsAppearance(if (lightNavigation) navigation else 0, navigation)
            return
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && (lightStatus || lightNavigation)) {
            val translucentStatus = if (lightStatus) WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS else 0
            val translucentNavigation = if (lightNavigation) WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION else 0
            window.clearFlags(translucentStatus or translucentNavigation)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        val status = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val navigation = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val appearance = (if (lightStatus) status else 0) or (if (lightNavigation) navigation else 0)
        decor.systemUiVisibility = (decor.systemUiVisibility and (status or navigation).inv()) or appearance
    }
}
