package com.baotime.app

import android.content.Intent
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baotime.app.call.CallSessionController
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualProfilesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var originalFont = "1.0"
    private var originalMotion = "1.0"
    private var originalNight = "no"

    @Before fun retainFixtureSettings() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
        originalFont = device.executeShellCommand("settings get system font_scale").trim()
        originalMotion = device.executeShellCommand("settings get global animator_duration_scale").trim()
        originalNight = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "yes" else "no"
    }

    @After fun restoreFixtureSettings() {
        device.executeShellCommand("settings put system font_scale $originalFont")
        device.executeShellCommand("settings put global animator_duration_scale $originalMotion")
        device.executeShellCommand("cmd uimode night $originalNight")
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    @Test fun lightDarkLargeTextAndReducedMotion() {
        for (mode in listOf("no", "yes")) {
            device.executeShellCommand("cmd uimode night $mode")
            device.executeShellCommand("settings put system font_scale 2.0")
            device.executeShellCommand("settings put global animator_duration_scale 0")
            launchHome()
            device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
            val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
            input.click()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.inputmethod.latin")), 5000))
            input.text = "audit-large-text@example.invalid"
            capture("profile-$mode-font200-keyboard")
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.pkg("com.android.inputmethod.latin")), 5000))
            assertTrue(device.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))))
            capture("profile-$mode-font200")
            device.pressBack()
            assertFalse(com.baotime.app.config.AppearancePreferences.animationsEnabled())
        }
    }

    @Test fun rotationActualWindowResizeAndControlFocus() {
        device.executeShellCommand("settings put system font_scale 1.0")
        launchHome()
        device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5000))
        device.setOrientationLeft()
        capture("profile-landscape-sheet")
        assertTrue(device.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))))
        device.setOrientationNatural()
        device.pressBack()
        for (scale in listOf("1.0", "2.0")) {
            device.executeShellCommand("settings put system font_scale $scale")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.window.setLayout(device.displayWidth * 2 / 3, device.displayHeight * 3 / 4) }
                assertTrue(device.wait(Until.hasObject(By.desc(context.getString(R.string.desc_new_call))), 5000))
                instrumentation.waitForIdleSync()
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_TAB)
                capture("profile-resized-window-font-$scale-keyboard-focus")
                verifyResizedHeading(scenario)
                device.findObject(By.text("Contacts").clazz("android.widget.Button")).click()
                device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), 1000)?.click()
                assertNotNull(device.wait(Until.findObject(By.res("com.baotime.app:id/screen_title").text("Contacts")), 5000))
                capture("profile-resized-contacts-font-$scale")
                verifyResizedHeading(scenario)
            }
        }
    }

    private fun verifyResizedHeading(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertTrue(root.width < device.displayWidth)
            val title = activity.findViewById<android.widget.TextView>(R.id.screen_title)
            assertEquals("Full heading remains on one line", 1, title.lineCount)
            assertEquals(title.text.length, requireNotNull(title.layout).getLineEnd(0))
            for (id in listOf(R.id.tab_recents, R.id.tab_contacts)) {
                val tab = activity.findViewById<android.widget.Button>(id)
                assertEquals("Full navigation label remains on one line", 1, tab.lineCount)
                assertEquals(tab.text.length, requireNotNull(tab.layout).getLineEnd(0))
            }
        }
        val target = device.findObject(By.desc(context.getString(R.string.desc_new_call))).visibleBounds
        val minimum = context.resources.getDimensionPixelSize(R.dimen.min_touch_target)
        assertTrue(target.width() >= minimum && target.height() >= minimum)
    }

    @Test fun populatedScrollSamplesOnlyBackdropAndKeepsControlsVisible() {
        launchHome()
        repeat(12) { index ->
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://facetime.apple.com/join#audit-scroll-$index")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            assertTrue(device.wait(Until.hasObject(By.desc(context.getString(R.string.desc_end_call))), 5000))
            device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        }
        capture("navigation-before-scroll")
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 40)
        assertTrue(device.hasObject(By.desc(context.getString(R.string.desc_new_call))))
        capture("navigation-content-under-blur")
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 40)
        capture("navigation-scroll-bottom")
    }

    @Test fun callBarsUseAndRestoreOwnedAppearance() {
        for (mode in listOf("no", "yes")) {
            device.executeShellCommand("cmd uimode night $mode")
            verifyRoomBars(mode)
        }
    }

    private fun verifyRoomBars(mode: String) {
        val history = CallHistory(context)
        val record = runBlocking { history.recordCall("audit-bars", "Audit system bars",
            "https://facetime.apple.com/join#audit-bars", BaoCallRecord.TYPE_OUTGOING) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var controller: CallSessionController? = null
            var initialStatus = false
            var initialNavigation = false
            scenario.onActivity { activity ->
                val host = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                require(host is FrameLayout)
                val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                initialStatus = bars.isAppearanceLightStatusBars
                initialNavigation = bars.isAppearanceLightNavigationBars
                val owner = CallSessionController(history) { activity.finish() }
                controller = owner
                owner.openSession(activity, host, BaoTimeTheme(activity), record.faceTimeUrl.orEmpty(),
                    record.displayName, record.id) { owner.denyHeldMedia() }
            }
            assertTrue(device.wait(Until.hasObject(By.desc(context.getString(R.string.desc_end_call))), 5000))
            device.wait(Until.hasObject(By.textContains("This call is not available")), 10000)
            capture("call-bars-$mode")
            AuditEvidence.recordText("call-bars-$mode-window", device.executeShellCommand("dumpsys window windows"))
            scenario.onActivity { activity ->
                val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertFalse(bars.isAppearanceLightStatusBars)
                assertFalse(bars.isAppearanceLightNavigationBars)
                requireNotNull(controller).closeSession()
                assertEquals(initialStatus, bars.isAppearanceLightStatusBars)
                assertEquals(initialNavigation, bars.isAppearanceLightNavigationBars)
            }
        }
        runBlocking { history.deleteRecord(record.id) }
    }

    @Test fun installedLauncherIconAndOpenRoute() {
        device.pressHome()
        if (!device.hasObject(By.text(context.getString(R.string.app_name)))) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 5, 30)
        }
        val icon = requireNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.app_name))), 5000))
        capture("installed-launcher-icon")
        icon.click()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
    }

    private fun launchHome() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
    }

    private fun capture(name: String) = AuditEvidence.capture(name, device)
}
