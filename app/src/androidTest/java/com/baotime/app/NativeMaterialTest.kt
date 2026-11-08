package com.baotime.app

import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baotime.app.ui.RootUi
import com.baotime.app.ui.components.GlassNavigation
import com.baotime.app.ui.theme.BaoTimeTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMaterialTest {
    @org.junit.Before fun requireAuditFixture() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
    }

    @Test fun callCirclesAndCaptionsFitAtNormalAndLargeNarrowSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        for ((fontScale, widthDp) in listOf(1f to 411, 2f to 260)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var controls = emptyList<Button>()
                var surface: android.view.View? = null
                scenario.onActivity { activity ->
                    val configuration = android.content.res.Configuration(activity.resources.configuration)
                    configuration.fontScale = fontScale
                    configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (fontScale == 2f) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                    val theme = BaoTimeTheme(activity.createConfigurationContext(configuration))
                    val host = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    require(host is FrameLayout)
                    val incoming = com.baotime.app.ui.IncomingCallUi.build(theme, "Call controls",
                        onAccept = { host.removeView(surface) }, onDecline = { host.removeView(surface) })
                    surface = incoming
                    host.addView(incoming)
                    activity.window.setLayout((widthDp * activity.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                    controls = allButtons(incoming)
                    assertEquals(2, controls.size)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    for (button in controls) {
                        val circle = requireNotNull(button.compoundDrawables[1])
                        assertEquals(circle.bounds.width(), circle.bounds.height())
                        assertEquals(activity.resources.getDimensionPixelSize(R.dimen.call_control_size), circle.bounds.width())
                        assertTrue(button.height > circle.bounds.height())
                        assertTrue(button.width >= circle.bounds.width())
                        val layout = requireNotNull(button.layout)
                        assertEquals(button.text.length, layout.getLineEnd(layout.lineCount - 1))
                        assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                        assertEquals(activity.getString(if (button.text == activity.getString(R.string.action_accept)) R.string.desc_accept_call else R.string.desc_decline_call), button.contentDescription)
                    }
                    val first = controls[0]
                    val second = controls[1]
                    val a = android.graphics.Rect()
                    val b = android.graphics.Rect()
                    assertTrue(first.getGlobalVisibleRect(a))
                    assertTrue(second.getGlobalVisibleRect(b))
                    assertFalse(android.graphics.Rect.intersects(a, b))
                    if (fontScale == 2f) assertTrue("Narrow controls reflow vertically", b.top >= a.bottom)
                }
                AuditEvidence.capture("call-controls-font-$fontScale-width-$widthDp", device)
                instrumentation.runOnMainSync { controls[0].performClick() }
            }
        }
    }

    private fun allButtons(view: android.view.View): List<Button> {
        if (view is Button) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { allButtons(view.getChildAt(it)) }
    }

    @Test fun softwarePaintAndTabStatesPreserveTheInstalledGlassMaterial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var button: Button? = null
            scenario.onActivity { activity ->
                val theme = BaoTimeTheme(activity)
                val root = FrameLayout(activity)
                val source = ScrollView(activity)
                source.addView(TextView(activity).apply { text = activity.getString(R.string.app_name) })
                root.addView(source, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val control = Button(activity)
                button = control
                GlassNavigation(theme, source, control)
                root.addView(control, FrameLayout.LayoutParams(240, 120))
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val theme = BaoTimeTheme(activity)
                val control = requireNotNull(button)
                val background = control.background
                val foreground = control.foreground
                assertNotNull(background)
                assertNotNull(foreground)
                val bitmap = createBitmap(control.width, control.height)
                control.draw(Canvas(bitmap))
                val normal = bitmap.getPixel(control.width / 2, control.height / 2)
                assertEquals(ColorUtils.compositeColors(theme.colorGlassBar, theme.colorChromeFallback), normal)
                RootUi.styleTabs(theme, listOf(control), 0)
                assertTrue(control.isSelected)
                assertSame(background, control.background)
                assertSame(foreground, control.foreground)
                assertEquals(theme.colorBaoGreen, control.currentTextColor)
                control.isEnabled = false
                control.draw(Canvas(bitmap))
                assertNotEquals(normal, bitmap.getPixel(control.width / 2, control.height / 2))
                assertEquals(activity.getColor(R.color.color_control_disabled_text), control.currentTextColor)
                control.isEnabled = true
                control.isSelected = false
                control.isFocusableInTouchMode = true
                assertTrue(control.requestFocus())
                assertTrue(control.isFocused)
                control.draw(Canvas(bitmap))
                assertSame(background, control.background)
                bitmap.recycle()
            }
        }
    }
}
