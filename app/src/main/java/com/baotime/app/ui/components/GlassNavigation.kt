package com.baotime.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.createBitmap
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import com.baotime.app.config.AppearancePreferences
import com.baotime.app.ui.theme.BaoTimeTheme

/** Attaches a blurred backdrop directly to a control without adding a container view. */
class GlassNavigation(
    private val theme: BaoTimeTheme,
    private val source: View,
    target: View,
    tintColor: Int = theme.colorGlassBar,
    fallbackColor: Int = theme.colorChromeFallback,
    radius: Int = theme.radiusControl,
    interactive: Boolean = true
) {
    private val glass = GlassSurfaceDrawable(theme, tintColor, fallbackColor, radius)
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { glass.refresh(source, target) }
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { glass.refresh(source, target) }
    private val onTargetLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        glass.refresh(source, target)
    }

    init {
        target.background = glass
        if (interactive) target.foreground = theme.glassControlForeground(radius)
        target.clipToOutline = true
        target.doOnAttach {
            source.viewTreeObserver.addOnScrollChangedListener(onScroll)
            source.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
            target.addOnLayoutChangeListener(onTargetLayout)
            glass.refresh(source, target)
        }
        target.doOnDetach {
            if (source.viewTreeObserver.isAlive) {
                source.viewTreeObserver.removeOnScrollChangedListener(onScroll)
                source.viewTreeObserver.removeOnGlobalLayoutListener(onLayout)
            }
            target.removeOnLayoutChangeListener(onTargetLayout)
            glass.clear()
        }
    }
}

private class GlassSurfaceDrawable(
    private val theme: BaoTimeTheme,
    private val tintColor: Int,
    private val fallbackColor: Int,
    private val radius: Int
) : Drawable() {
    private val edgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorGlassHighlight
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = theme.hairline.toFloat()
    }
    private val selectionPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorBaoGreen
        style = android.graphics.Paint.Style.FILL
    }
    private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val edgeRect = android.graphics.RectF()
    private var blurExecutor: ExecutorService? = null
    private var refreshGeneration = 0
    private var sample: Bitmap? = null
    private var blurred = false
    private var focused = false
    private var selected = false

    fun refresh(source: View, target: View) {
        blurred = false
        if (!target.isAttachedToWindow || bounds.isEmpty) return
        if (Build.VERSION.SDK_INT < 31 || !AppearancePreferences.backdropEnabled(theme.context)) {
            clear()
            invalidateSelf()
            return
        }
        val generation = ++refreshGeneration
        val sampleScale = minOf(1f, 512f / maxOf(bounds.width(), bounds.height()).toFloat())
        val bitmap = createBitmap((bounds.width() * sampleScale).toInt().coerceAtLeast(1),
            (bounds.height() * sampleScale).toInt().coerceAtLeast(1))
        bitmap.eraseColor(fallbackColor)
        val sourcePosition = IntArray(2)
        val targetPosition = IntArray(2)
        source.getLocationInWindow(sourcePosition)
        target.getLocationInWindow(targetPosition)
        val canvas = Canvas(bitmap)
        canvas.scale(sampleScale, sampleScale)
        canvas.translate((sourcePosition[0] - targetPosition[0]).toFloat(),
            (sourcePosition[1] - targetPosition[1]).toFloat())
        source.draw(canvas)
        val executor = blurExecutor ?: Executors.newSingleThreadExecutor().also { blurExecutor = it }
        executor.execute {
            if (generation != refreshGeneration) {
                bitmap.recycle()
                return@execute
            }
            BoxBlur.blur(bitmap, (theme.glassBlurRadius * sampleScale).toInt().coerceIn(1, 25))
            target.post {
                if (generation == refreshGeneration) {
                    sample = bitmap
                    blurred = true
                    invalidateSelf()
                } else {
                    bitmap.recycle()
                }
            }
        }
    }

    fun clear() {
        refreshGeneration++
        blurExecutor?.shutdownNow()
        blurExecutor = null
        sample = null
        blurred = false
    }

    override fun draw(canvas: Canvas) {
        val bitmap = sample
        if (blurred && bitmap != null) {
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(bounds), bitmapPaint)
        } else {
            canvas.drawColor(fallbackColor)
        }
        canvas.drawColor(tintColor)
        if (!focused && radius > 0) {
            val inset = theme.hairline / 2f
            edgeRect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
            canvas.drawRoundRect(edgeRect, radius - inset, radius - inset, edgePaint)
        }
        if (selected && radius > 0) {
            val indicatorWidth = theme.iconSize.toFloat()
            val indicatorHeight = theme.spaceXs.toFloat()
            val bottom = bounds.bottom - theme.spaceSm.toFloat()
            edgeRect.set(bounds.centerX() - indicatorWidth / 2f, bottom - indicatorHeight,
                bounds.centerX() + indicatorWidth / 2f, bottom)
            canvas.drawRoundRect(edgeRect, indicatorHeight / 2f, indicatorHeight / 2f, selectionPaint)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        blurred = false
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius.toFloat())
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(stateSet: IntArray): Boolean {
        val nextFocused = stateSet.contains(android.R.attr.state_focused)
        val nextSelected = stateSet.contains(android.R.attr.state_selected)
        val changed = focused != nextFocused || selected != nextSelected
        focused = nextFocused
        selected = nextSelected
        if (changed) invalidateSelf()
        return changed
    }

    override fun setAlpha(alpha: Int) {
        edgePaint.alpha = alpha
        selectionPaint.alpha = alpha
        bitmapPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        edgePaint.colorFilter = colorFilter
        bitmapPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private object BoxBlur {
    fun blur(bitmap: Bitmap, radius: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val workspace = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val normalizedRadius = radius.coerceIn(1, 25)
        repeat(3) {
            pass(pixels, workspace, width, height, normalizedRadius, true)
            pass(workspace, pixels, width, height, normalizedRadius, false)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun pass(source: IntArray, target: IntArray, width: Int, height: Int,
        radius: Int, horizontal: Boolean
    ) {
        val outer = if (horizontal) height else width
        val inner = if (horizontal) width else height
        val samples = radius * 2 + 1
        for (outerIndex in 0 until outer) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            fun index(innerIndex: Int): Int = if (horizontal) {
                outerIndex * width + innerIndex.coerceIn(0, inner - 1)
            } else {
                outerIndex + innerIndex.coerceIn(0, inner - 1) * width
            }
            for (offset in -radius..radius) {
                val color = source[index(offset)]
                alpha += color ushr 24
                red += color shr 16 and 0xff
                green += color shr 8 and 0xff
                blue += color and 0xff
            }
            for (innerIndex in 0 until inner) {
                if (innerIndex > 0) {
                    val added = source[index(innerIndex + radius)]
                    val removed = source[index(innerIndex - radius - 1)]
                    alpha += (added ushr 24) - (removed ushr 24)
                    red += (added shr 16 and 0xff) - (removed shr 16 and 0xff)
                    green += (added shr 8 and 0xff) - (removed shr 8 and 0xff)
                    blue += (added and 0xff) - (removed and 0xff)
                }
                target[index(innerIndex)] = (alpha / samples shl 24) or
                    (red / samples shl 16) or (green / samples shl 8) or blue / samples
            }
        }
    }
}
