package com.baotime.app.ui.theme

import com.baotime.app.config.AppearancePreferences
import android.view.View
import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp

/**
 * Canonical motion tokens and helpers. Every animation in BaoTime runs through
 * this damped spring — no ad-hoc durations, scales, or linear snaps at call sites.
 */
object BaoTimeMotion {

    const val FADE_FROM_ALPHA = 0.4f
    const val DURATION_ROW_MS = 180L
    const val DURATION_TAB_MS = 150L
    const val DURATION_STAGGER_MS = 35L
    const val MAX_STAGGER_DELAY_MS = 240L

    /** Damped harmonic spring: slight overshoot, then a smooth settle. */
    private class LiquidSpring : Interpolator {
        private val damping = 0.75
        private val stiffness = 9.0
        override fun getInterpolation(t: Float): Float {
            val x = t * stiffness
            return (1f - exp(-damping * x) * cos(x)).toFloat()
        }
    }

    private val spring = LiquidSpring()

    fun rowIn(view: View, index: Int, offsetPx: Float) {
        if (!AppearancePreferences.animationsEnabled()) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f
        view.translationY = offsetPx
        view.animate().alpha(1f).translationY(0f)
            .setDuration(DURATION_ROW_MS)
            .setStartDelay((index * DURATION_STAGGER_MS).coerceAtMost(MAX_STAGGER_DELAY_MS))
            .setInterpolator(spring)
            .start()
    }

    fun tabIn(view: View) {
        if (!AppearancePreferences.animationsEnabled()) { view.alpha = 1f; return }
        view.alpha = FADE_FROM_ALPHA
        view.animate().alpha(1f).setDuration(DURATION_TAB_MS).setInterpolator(spring).start()
    }

}
