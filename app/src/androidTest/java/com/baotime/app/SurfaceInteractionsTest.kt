package com.baotime.app

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baotime.app.call.SharedPreferencesPushStore
import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordRead
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceInteractionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val preferences = context.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val pushPreferences = context.getSharedPreferences(SharedPreferencesPushStore.PREFS_NAME, Context.MODE_PRIVATE)

    private var fixtureRetained = false
    private var originalScale = "1.0"
    private var originalRevision = 0L
    private var originalServerUrl = ""
    private var originalPassword: PasswordRead = PasswordRead.Missing
    private var originalRawPassword: String? = null
    private var originalPushToken: String? = null
    private var originalPushEndpoint: String? = null
    private var originalHadPushMac = false
    private var originalPushDistributor: String? = null

    @org.junit.After fun restoreScale() {
        if (!fixtureRetained) return
        val app = context.applicationContext
        require(app is BaoTimeApp)
        val store = app.pushStore
        require(store is SharedPreferencesPushStore)
        if (app.appConfig.connectionRevision() != originalRevision) {
            when (val stored = originalPassword) {
                is PasswordRead.Available -> app.appConfig.saveConnection(originalServerUrl, stored.value)
                PasswordRead.Missing -> {
                    app.appConfig.saveConnection(originalServerUrl, "")
                    if (originalRawPassword == null) {
                        preferences.edit(commit = true) { remove(AppConfig.KEY_SERVER_PASSWORD) }
                    }
                }
                is PasswordRead.Unavailable -> {
                    app.appConfig.saveConnection(originalServerUrl, "")
                    preferences.edit(commit = true) {
                        if (originalRawPassword == null) remove(AppConfig.KEY_SERVER_PASSWORD)
                        else putString(AppConfig.KEY_SERVER_PASSWORD, originalRawPassword)
                    }
                }
            }
            val targetRevision = app.appConfig.connectionRevision()
            val targetMac = app.appConfig.macBindingGeneration()
            val deadline = SystemClock.uptimeMillis() + 5000L
            while ((app.callLifecycle.configGeneration != targetRevision ||
                    pushPreferences.getLong("mac_generation", -1L) != targetMac) &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(25)
            }
            assertEquals(targetRevision, app.callLifecycle.configGeneration)
            assertEquals(targetMac, pushPreferences.getLong("mac_generation", -1L))
            store.clearEndpoint()
            pushPreferences.edit(commit = true) {
                clear()
                if (originalPushToken != null) putString("instance_token", originalPushToken)
                if (originalPushEndpoint != null) putString("endpoint", originalPushEndpoint)
                if (originalHadPushMac) putLong("mac_generation", targetMac)
                if (originalPushDistributor != null) putString("distributor_package", originalPushDistributor)
            }
            originalPushEndpoint?.let(store::saveEndpoint)
        }
        device.executeShellCommand("settings put system font_scale $originalScale")
    }

    @Before fun launch() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
        originalScale = device.executeShellCommand("settings get system font_scale").trim()
        val app = context.applicationContext
        require(app is BaoTimeApp)
        val snapshot = app.appConfig.connection()
        originalRevision = snapshot.revision
        originalServerUrl = snapshot.url
        originalPassword = snapshot.password
        originalRawPassword = preferences.getString(AppConfig.KEY_SERVER_PASSWORD, null)
        originalPushToken = pushPreferences.getString("instance_token", null)
        originalPushEndpoint = pushPreferences.getString("endpoint", null)
        originalHadPushMac = pushPreferences.contains("mac_generation")
        originalPushDistributor = pushPreferences.getString("distributor_package", null)
        fixtureRetained = true
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
    }

    @Test fun keyboardBackAndScrimHaveSeparateLifecycles() {
        device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
        val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
        input.click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.inputmethod.latin")), 5000))
        input.text = "audit-keyboard@example.invalid"
        capture("keyboard-visible")
        assertTrue(input.visibleBounds.bottom < device.displayHeight)
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.pkg("com.android.inputmethod.latin")), 5000))
        assertTrue(device.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
        assertTrue(device.wait(Until.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        device.click(device.displayWidth / 2, device.displayHeight / 4)
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        capture("scrim-dismissed")
    }

    @Test fun setupValidSaveRetainsDraftAndSettingsReturn() {
        device.findObject(By.desc(context.getString(R.string.desc_open_setup))).click()
        val editors = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5000)
        assertEquals(2, editors.size)
        editors[0].text = "http://127.0.0.1:1234"
        editors[1].text = "audit-disposable-password"
        device.findObject(By.text(context.getString(R.string.setup_save))).click()
        assertTrue(device.wait(Until.hasObject(By.textStartsWith(context.getString(R.string.setup_saved))), 5000))
        assertFalse(device.hasObject(By.text(context.getString(R.string.action_cancel))))
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.setup_saved_offline))), 5000))
        assertFalse(device.hasObject(By.text(context.getString(R.string.error_generic))))
        capture("setup-valid-save")
        device.findObject(By.textStartsWith(context.getString(R.string.setup_configure))).click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
        capture("system-default-link-settings")
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.setup_save))), 5000))
        assertEquals("http://127.0.0.1:1234", device.findObjects(By.clazz("android.widget.EditText"))[0].text)
        val listenerRow = device.findObjects(By.textStartsWith(context.getString(R.string.setup_incoming_configure))).last()
        listenerRow.click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
        capture("system-notification-listener-settings")
        device.pressBack()
        device.wait(Until.findObject(By.desc(context.getString(R.string.desc_close_sheet))), 5000).click()
    }

    @Test fun dragShortDistanceAndTextDropGestureStayRecoverable() {
        device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
        val handle = requireNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        val bounds = handle.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY() + 40, 20)
        assertTrue(device.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))))
        assertEquals(bounds, device.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))).visibleBounds)
        capture("sheet-short-drag-restored")
        device.pressBack()
        device.findObject(By.desc(context.getString(R.string.desc_open_setup))).click()
        val editors = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5000)
        editors[0].text = "https://audit.example.invalid"
        editors[1].text = ""
        editors[0].longClick()
        val target = editors[1].visibleBounds
        editors[0].drag(Point(target.centerX(), target.centerY()))
        capture("native-text-drop-attempt")
        android.util.Log.i("BaoTimeJourney", "Native password field accepted audit text drop: ${editors[1].text.isNotEmpty()}")
        assertTrue(device.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))))
        device.pressBack()
    }

    @Test fun tallSettingsHandleOwnsDragAndClose() {
        val scale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 2.0")
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        device.wait(Until.findObject(By.desc(context.getString(R.string.desc_open_setup))), 5000).click()
        val handle = requireNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        val bounds = handle.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY() + 40, 20)
        assertEquals(bounds, device.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))).visibleBounds)
        capture("tall-settings-short-drag-restored")
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY() + 250, 30)
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        device.findObject(By.desc(context.getString(R.string.desc_open_setup))).click()
        device.wait(Until.findObject(By.desc(context.getString(R.string.desc_close_sheet))), 5000).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        device.executeShellCommand("settings put system font_scale $scale")
    }

    @Test fun foregroundOwnershipRestoresAfterCoveredSurfaceRemoval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0)
                require(root is FrameLayout)
                val background = root.getChildAt(0)
                val original = background.importantForAccessibility
                val theme = com.baotime.app.ui.theme.BaoTimeTheme(activity)
                val first = com.baotime.app.ui.ModalSheetUi.build(theme, android.widget.TextView(activity)) { root.removeView(root.getChildAt(root.childCount - 1)) }
                root.addView(first)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, background.importantForAccessibility)
                val second = com.baotime.app.ui.ModalSheetUi.build(theme, android.widget.TextView(activity)) { root.removeView(root.getChildAt(root.childCount - 1)) }
                root.addView(second)
                root.removeView(first)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, background.importantForAccessibility)
                root.removeView(second)
                assertEquals(original, background.importantForAccessibility)
            }
        }
    }

    private fun capture(name: String) = AuditEvidence.capture(name, device)
}
