package com.baotime.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.createBitmap
import com.baotime.app.config.AppearancePreferences
import com.baotime.app.ui.theme.BaoTimeTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private val glass = GlassSurfaceDrawable(theme, source, target, tintColor, fallbackColor, radius)
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { glass.refresh() }
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { glass.refresh() }
    private val onTargetLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        glass.refresh()
    }
    private var observedTree: ViewTreeObserver? = null

    internal fun sampleTopEdge() = glass.sampleTopEdge()

    internal fun updateMaterial(theme: BaoTimeTheme, tintColor: Int, fallbackColor: Int) {
        glass.updateMaterial(theme, tintColor, fallbackColor)
    }

    init {
        target.background = glass
        if (interactive) target.foreground = theme.glassControlForeground(radius)
        target.clipToOutline = true
        // Persistent attach observer: every reattach rebinds sampling, every detach releases it.
        val attachObserver = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                observedTree = target.viewTreeObserver.apply {
                    addOnScrollChangedListener(onScroll)
                    addOnGlobalLayoutListener(onLayout)
                }
                target.addOnLayoutChangeListener(onTargetLayout)
                glass.refresh()
            }

            override fun onViewDetachedFromWindow(view: View) {
                observedTree?.takeIf { it.isAlive }?.apply {
                    removeOnScrollChangedListener(onScroll)
                    removeOnGlobalLayoutListener(onLayout)
                }
                observedTree = null
                target.removeOnLayoutChangeListener(onTargetLayout)
                glass.clear()
            }
        }
        target.addOnAttachStateChangeListener(attachObserver)
        if (target.isAttachedToWindow) attachObserver.onViewAttachedToWindow(target)
    }
}

private class GlassSurfaceDrawable(
    private var theme: BaoTimeTheme,
    private val source: View,
    private val target: View,
    private var tintColor: Int,
    private var fallbackColor: Int,
    private val radius: Int
) : Drawable() {
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorGlassHighlight
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = theme.hairline.toFloat()
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colorBaoGreen
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgeRect = RectF()
    private val indicatorRect = RectF()
    private val sampleRect = RectF()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureFrame = Runnable(::capture)
    private val sourcePosition = IntArray(2)
    private val targetPosition = IntArray(2)
    private var blurExecutor: ExecutorService? = null
    private var blurScratch: BoxBlur.Scratch? = null
    private var captureScheduled = false
    private var captureNeeded = false
    private var blurRunning = false
    private var geometryGeneration = 0
    private var sample: Bitmap? = null
    private var blurred = false
    private var focused = false
    private var selected = false
    private var sampleTop = false

    fun updateMaterial(theme: BaoTimeTheme, tintColor: Int, fallbackColor: Int) {
        this.theme = theme
        this.tintColor = tintColor
        this.fallbackColor = fallbackColor
        edgePaint.color = theme.colorGlassHighlight
        edgePaint.strokeWidth = theme.hairline.toFloat()
        selectionPaint.color = theme.colorBaoGreen
        clear()
        refresh()
    }

    fun sampleTopEdge() {
        sampleTop = true
        clear()
        refresh()
    }

    fun refresh() {
        if (Build.VERSION.SDK_INT < 31 || !AppearancePreferences.backdropEnabled(theme.context)) {
            clear()
            return
        }
        captureNeeded = true
        scheduleCapture()
    }

    private fun scheduleCapture() {
        if (!captureNeeded || captureScheduled || blurRunning || bounds.isEmpty || !target.isAttachedToWindow) return
        captureScheduled = true
        target.postOnAnimation(captureFrame)
    }

    private fun capture() {
        captureScheduled = false
        if (!target.isAttachedToWindow || bounds.isEmpty) return
        if (!source.isAttachedToWindow || source.windowToken != target.windowToken ||
            !AppearancePreferences.backdropEnabled(theme.context)
        ) {
            clear()
            return
        }
        captureNeeded = false
        val generation = geometryGeneration
        val sampleScale = minOf(1f, 512f / maxOf(bounds.width(), bounds.height()).toFloat())
        val bitmap = createBitmap((bounds.width() * sampleScale).toInt().coerceAtLeast(1),
            (bounds.height() * sampleScale).toInt().coerceAtLeast(1))
        try {
            bitmap.eraseColor(fallbackColor)
            source.getLocationInWindow(sourcePosition)
            target.getLocationInWindow(targetPosition)
            val canvas = Canvas(bitmap)
            canvas.scale(sampleScale, sampleScale)
            val sampleY = if (sampleTop) 0 else sourcePosition[1] - targetPosition[1]
            canvas.translate((sourcePosition[0] - targetPosition[0]).toFloat(),
                sampleY.toFloat())
            source.draw(canvas)
            val executor = blurExecutor ?: Executors.newSingleThreadExecutor().also { blurExecutor = it }
            val scratch = blurScratch ?: BoxBlur.Scratch().also { blurScratch = it }
            val radius = (theme.glassBlurRadius * sampleScale).toInt().coerceIn(1, 25)
            blurRunning = true
            executor.execute {
                val failure = try {
                    BoxBlur.blur(bitmap, radius, scratch)
                    null
                } catch (failure: RuntimeException) {
                    failure
                }
                val complete = Runnable { finishCapture(bitmap, generation, failure) }
                if (!mainHandler.post {
                        if (!target.isAttachedToWindow || !target.post(complete)) complete.run()
                    }
                ) {
                    bitmap.recycle()
                    executor.shutdown()
                }
            }
        } catch (failure: RuntimeException) {
            finishCapture(bitmap, generation, failure)
        }
    }

    private fun finishCapture(bitmap: Bitmap, generation: Int, failure: RuntimeException?) {
        blurRunning = false
        if (failure != null) {
            releaseWorker()
            Log.w(TAG, "Backdrop capture failed", failure)
        }
        val eligible = target.isAttachedToWindow && source.isAttachedToWindow && source.windowToken == target.windowToken &&
            AppearancePreferences.backdropEnabled(theme.context)
        if (generation == geometryGeneration && eligible) {
            sample = bitmap.takeIf { failure == null }
            blurred = failure == null
            invalidateSelf()
        }
        if (sample !== bitmap) bitmap.recycle()
        if (!eligible) clear()
        else scheduleCapture()
    }

    fun clear() {
        geometryGeneration++
        target.removeCallbacks(captureFrame)
        captureScheduled = false
        captureNeeded = false
        if (!blurRunning) releaseWorker()
        sample = null
        blurred = false
        invalidateSelf()
    }

    private fun releaseWorker() {
        blurExecutor?.shutdownNow()
        blurExecutor = null
        blurScratch = null
    }

    override fun draw(canvas: Canvas) {
        val bitmap = sample
        // Offscreen drawing uses the same stable fallback as unsupported display surfaces.
        if (blurred && bitmap != null && canvas.isHardwareAccelerated) {
            sampleRect.set(bounds)
            canvas.drawBitmap(bitmap, null, sampleRect, bitmapPaint)
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
            indicatorRect.set(bounds.centerX() - indicatorWidth / 2f, bottom - indicatorHeight,
                bounds.centerX() + indicatorWidth / 2f, bottom)
            canvas.drawRoundRect(indicatorRect, indicatorHeight / 2f, indicatorHeight / 2f, selectionPaint)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        // The previous sample no longer matches the surface geometry; drop it until a fresh blur lands.
        geometryGeneration++
        sample = null
        blurred = false
        refresh()
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

    private companion object {
        const val TAG = "BaoTimeGlass"
    }
}

private object BoxBlur {
    // Buffers belong to one executor instance and grow once for it, then are reused across that
    // worker's refreshes instead of allocating two full pixel arrays per scroll frame. A
    // re-attach creates a new executor and therefore a new Scratch.
    class Scratch {
        var pixels = IntArray(0)
        var workspace = IntArray(0)
    }

    fun blur(bitmap: Bitmap, radius: Int, scratch: Scratch) {
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height
        if (scratch.pixels.size < size) {
            scratch.pixels = IntArray(size)
            scratch.workspace = IntArray(size)
        }
        val pixels = scratch.pixels
        val workspace = scratch.workspace
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
