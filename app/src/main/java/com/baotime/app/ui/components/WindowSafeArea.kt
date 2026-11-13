package com.baotime.app.ui.components

import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.core.view.doOnAttach

internal object WindowSafeArea {
    fun observe(view: View, includeKeyboard: Boolean = false, apply: (View, Insets, Insets) -> Unit) {
        apply(view, Insets.NONE, Insets.NONE)
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val navigation = insets.getInsets(WindowInsets.Type.navigationBars())
                val safeTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val types = if (includeKeyboard) safeTypes or WindowInsets.Type.ime() else safeTypes
                apply(target, insets.getInsets(types), navigation)
            } else {
                applyAndroid10(target, insets, includeKeyboard, apply)
            }
            insets
        }
        view.doOnAttach { it.requestApplyInsets() }
    }

    private fun applyAndroid10(view: View, insets: WindowInsets, includeKeyboard: Boolean,
        apply: (View, Insets, Insets) -> Unit) {
        val rootInsets = view.rootWindowInsets
        val system = insets.systemWindowInsets
        val stableBottom = rootInsets?.stableInsetBottom
        val flags = view.windowSystemUiVisibility
        val navigation = if (flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
            Insets.of(system.left, 0, system.right, minOf(system.bottom, stableBottom ?: system.bottom))
        } else Insets.NONE
        val statusTop = if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) system.top else 0
        val bottom = if (includeKeyboard && system.bottom > (stableBottom ?: 0)) system.bottom else navigation.bottom
        val cutout = (rootInsets ?: insets).displayCutout
        val safe = Insets.of(
            maxOf(navigation.left, cutout?.safeInsetLeft ?: 0),
            maxOf(statusTop, cutout?.safeInsetTop ?: 0),
            maxOf(navigation.right, cutout?.safeInsetRight ?: 0),
            maxOf(bottom, cutout?.safeInsetBottom ?: 0)
        )
        apply(view, safe, navigation)
    }
}
