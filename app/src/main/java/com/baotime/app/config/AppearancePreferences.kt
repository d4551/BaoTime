package com.baotime.app.config

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Platform appearance capabilities and accessibility preferences, read at the configuration boundary. */
object AppearancePreferences {
    fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun backdropEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        if (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return false
        if (Build.VERSION.SDK_INT >= 34 &&
            (context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f) > 0f
        ) return false
        return true
    }
}
