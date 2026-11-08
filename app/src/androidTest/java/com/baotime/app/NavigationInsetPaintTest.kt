package com.baotime.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baotime.app.ui.theme.BaoTimeTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationInsetPaintTest {
    @Test fun physicalNavigationEdgesStayOnTheirOwnedSideInBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val theme = BaoTimeTheme(instrumentation.targetContext)
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (navigation in listOf(Insets.of(12, 0, 0, 0), Insets.of(0, 0, 12, 0), Insets.of(0, 0, 0, 12))) {
                    val view = FrameLayout(theme.context)
                    view.layoutDirection = direction
                    view.background = theme.callSurfaceDrawable(navigation, view.layoutDirection)
                    view.layout(0, 0, 100, 100)
                    val image = createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(image))
                    assertEquals(theme.colorCallChrome, image.getPixel(50, 50))
                    assertEquals(if (navigation.left > 0) theme.colorBg else theme.colorCallChrome, image.getPixel(2, 50))
                    assertEquals(if (navigation.right > 0) theme.colorBg else theme.colorCallChrome, image.getPixel(98, 50))
                    assertEquals(if (navigation.bottom > 0) theme.colorBg else theme.colorCallChrome, image.getPixel(50, 98))
                    image.recycle()
                }
            }
        }
    }
}
