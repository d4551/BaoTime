package com.baotime.app.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import com.baotime.app.R

/** Canonical resolved colors, pixel typography, shapes and control states. */
class BaoTimeTheme(val context: Context) {
    val colorBg = context.getColor(R.color.color_bg)
    val colorBgTop = context.getColor(R.color.color_bg_top)
    val colorSurface = context.getColor(R.color.color_surface)
    val colorSurfaceHigh = context.getColor(R.color.color_surface_high)
    val colorStroke = context.getColor(R.color.color_stroke)
    val colorBaoGreen = context.getColor(R.color.bao_green)
    val colorBaoGreenDark = context.getColor(R.color.bao_green_dark)
    val colorOnAccent = context.getColor(R.color.color_on_accent)
    val colorCallText = context.getColor(R.color.color_call_text)
    val colorCallTextSecondary = context.getColor(R.color.color_call_text_secondary)
    val colorTextPrimary = context.getColor(R.color.color_text_primary)
    val colorTextSecondary = context.getColor(R.color.color_text_secondary)
    val colorTextHint = context.getColor(R.color.color_text_hint)
    val colorDanger = context.getColor(R.color.color_danger)
    val colorWarning = context.getColor(R.color.color_warning)
    val colorScrim = context.getColor(R.color.color_scrim)
    val colorCallChrome = context.getColor(R.color.color_call_chrome)
    val colorControlClear = context.getColor(R.color.color_control_clear)
    val colorGlassBar = context.getColor(R.color.color_glass_bar)
    val colorGlassSheet = context.getColor(R.color.color_glass_sheet)
    val colorGlassHighlight = context.getColor(R.color.color_glass_edge)
    val colorGlassCallBar = context.getColor(R.color.color_glass_call_bar)
    val colorChromeFallback = context.getColor(R.color.color_chrome_fallback)
    private val colorDisabledBackground = context.getColor(R.color.color_control_disabled_bg)
    private val colorDisabledText = context.getColor(R.color.color_control_disabled_text)
    private val colorFocus = context.getColor(R.color.color_control_focus)
    private val colorSelectedOutline = context.getColor(R.color.color_control_selected_outline)
    private val colorMask = context.getColor(R.color.color_control_mask)
    private val colorRipple = context.getColor(R.color.color_control_ripple)

    val textSizeTitlePx = context.resources.getDimension(R.dimen.text_size_title)
    val textSizeHeadingPx = context.resources.getDimension(R.dimen.text_size_heading)
    val textSizeBodyPx = context.resources.getDimension(R.dimen.text_size_body)
    val textSizeControlPx = context.resources.getDimension(R.dimen.text_size_control)
    val textSizeCaptionPx = context.resources.getDimension(R.dimen.text_size_caption)
    val typefaceControl: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val typefaceEmphasis: Typeface = Typeface.DEFAULT_BOLD

    val spaceXs = pixels(R.dimen.space_xs)
    val spaceSm = pixels(R.dimen.space_sm)
    val spaceMd = pixels(R.dimen.space_md)
    val spaceLg = pixels(R.dimen.space_lg)
    val spaceXl = pixels(R.dimen.space_xl)
    val iconSize = pixels(R.dimen.icon_size)
    val infoSize = pixels(R.dimen.info_size)
    val callControlSize = pixels(R.dimen.call_control_size)
    val callControlIconSize = pixels(R.dimen.call_control_icon_size)
    val minTouchTarget = pixels(R.dimen.min_touch_target)
    val radiusControl = pixels(R.dimen.radius_control)
    val hairline = pixels(R.dimen.hairline)
    val focusRing = pixels(R.dimen.focus_ring)
    val glassBlurRadius = context.resources.getDimension(R.dimen.glass_blur_radius)

    private fun pixels(resId: Int): Int = context.resources.getDimensionPixelSize(resId)

    fun surfaceDrawable(radius: Int, fillColor: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
            if (strokeColor != null) setStroke(hairline, strokeColor)
        }

    fun controlForeground(activeColor: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(colorDisabledText, activeColor)
    )

    fun controlBackground(fillColor: Int, radius: Int = radiusControl, oval: Boolean = false): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), controlShape(colorDisabledBackground, radius, oval))
            addState(intArrayOf(android.R.attr.state_focused), controlShape(fillColor, radius, oval, outlineFor(fillColor, colorFocus)))
            addState(intArrayOf(android.R.attr.state_selected), controlShape(fillColor, radius, oval, outlineFor(fillColor, colorSelectedOutline)))
            addState(intArrayOf(), controlShape(fillColor, radius, oval))
        }
        return RippleDrawable(ColorStateList.valueOf(colorRipple), states, controlShape(colorSurface, radius, oval))
    }

    fun glassControlForeground(radius: Int = radiusControl): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), controlShape(colorDisabledBackground, radius, false))
            addState(intArrayOf(android.R.attr.state_focused), controlShape(colorControlClear, radius, false, colorFocus))
            addState(intArrayOf(android.R.attr.state_selected), controlShape(colorControlClear, radius, false))
            addState(intArrayOf(), controlShape(colorControlClear, radius, false))
        }
        return RippleDrawable(ColorStateList.valueOf(colorRipple), states, controlShape(colorMask, radius, false))
    }

    private fun outlineFor(fillColor: Int, preferred: Int): Int {
        val surface = ColorUtils.compositeColors(fillColor, colorSurface)
        return if (ColorUtils.calculateContrast(preferred, surface) >= 3.0) preferred else colorOnAccent
    }

    private fun controlShape(fill: Int, radius: Int, oval: Boolean, outline: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) cornerRadius = radius.toFloat()
            setColor(fill)
            if (outline != null) setStroke(focusRing, outline)
        }

    fun fieldBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), bottomHairlineDrawable(colorFocus))
        addState(intArrayOf(), bottomHairlineDrawable(colorStroke))
    }

    fun listRowBackground(): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), controlShape(colorSurfaceHigh, radiusControl, false, colorFocus))
            addState(intArrayOf(android.R.attr.state_selected), controlShape(colorSurfaceHigh, radiusControl, false, colorSelectedOutline))
            addState(intArrayOf(), bottomHairlineDrawable(colorStroke))
        }
        return RippleDrawable(ColorStateList.valueOf(colorRipple), states, surfaceDrawable(radiusControl, colorSurface))
    }

    /** Native navigation retains its system-theme appearance over a matching inset surface. */
    fun callSurfaceDrawable(navigation: Insets, layoutDirection: Int): Drawable {
        val rtl = layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val start = if (rtl) navigation.right else navigation.left
        val end = if (rtl) navigation.left else navigation.right
        val edges = listOf(Gravity.BOTTOM to navigation.bottom, Gravity.START to start,
            Gravity.END to end).filter { it.second > 0 }
        val layers = listOf<Drawable>(surfaceDrawable(0, colorCallChrome)) +
            edges.map { surfaceDrawable(0, colorBg) }
        return LayerDrawable(layers.toTypedArray()).apply {
            edges.forEachIndexed { index, (edge, size) ->
                if (edge == Gravity.BOTTOM) {
                    setLayerGravity(index + 1, edge or Gravity.FILL_HORIZONTAL)
                    setLayerHeight(index + 1, size)
                } else {
                    setLayerGravity(index + 1, edge or Gravity.FILL_VERTICAL)
                    setLayerWidth(index + 1, size)
                }
            }
        }
    }

    fun bottomHairlineDrawable(color: Int): Drawable = LayerDrawable(arrayOf(surfaceDrawable(0, color))).apply {
        setLayerGravity(0, Gravity.BOTTOM)
        setLayerHeight(0, hairline)
        setLayerInsetLeft(0, spaceMd)
    }
}
