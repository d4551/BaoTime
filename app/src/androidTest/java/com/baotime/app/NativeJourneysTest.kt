package com.baotime.app

import android.content.Intent
import android.app.Activity
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the dedicated audit AVD; actions use ordinary rendered controls and public intents. */
@RunWith(AndroidJUnit4::class)
class NativeJourneysTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val owned = mutableSetOf<Activity>()
    private val destroyed = mutableSetOf<Activity>()
    private var destruction: CountDownLatch? = null
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity) {
            owned.add(activity)
            if (stage == Stage.DESTROYED) destroyed.add(activity)
            if (owned.all { it in destroyed }) destruction?.countDown()
        }
    }

    @org.junit.After fun finishOwnedActivities() {
        device.setOrientationNatural()
        device.unfreezeRotation()
        val barrier = CountDownLatch(1)
        destruction = barrier
        instrumentation.runOnMainSync {
            owned.filter { it !in destroyed }.toList().forEach(Activity::finish)
            if (owned.all { it in destroyed }) barrier.countDown()
        }
        val completed = barrier.await(10, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { monitor.removeLifecycleCallback(lifecycle) }
        assertTrue("Every owned MainActivity reaches DESTROYED", completed)
    }

    @Before fun openHome() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(lifecycle) }
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val opened = instrumentation.startActivitySync(launch)
        assertTrue(opened is MainActivity)
        instrumentation.runOnMainSync {
            owned.add(opened)
            assertEquals(Stage.RESUMED, monitor.getLifecycleStageOf(opened))
        }
        assertNotNull(device.wait(Until.findObject(By.desc("New Call")), 10000))
    }

    @Test fun repeatedSheetsEmptySearchBackCloseAndDrag() {
        repeat(3) {
            device.findObject(By.desc("New Call")).click()
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.action_create_link))), 5000))
            val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
            input.text = "audit-no-match@example.invalid"
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.contacts_no_matches))), 10000))
            capture("new-call-no-match-$it")
            device.findObject(By.desc(context.getString(R.string.desc_close_sheet))).click()
            assertTrue(device.wait(Until.gone(By.text("New Call")), 5000))
        }
        device.findObject(By.desc("New Call")).click()
        val handle = requireNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        val bounds = handle.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY() + 250, 30)
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        capture("sheet-drag-dismissed")
    }

    @Test fun tabsDeniedContactsAndClearHistoryCancel() {
        shareTestLink()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 10000))
        device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        repeat(4) {
            requireNotNull(device.wait(Until.findObject(By.text("Contacts").clazz("android.widget.Button")), 5000)).click()
            val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), 1000)
            deny?.click()
            assertNotNull(device.wait(Until.findObject(By.text("Search contacts")), 5000))
            capture("contacts-$it")
            requireNotNull(device.wait(Until.findObject(By.textStartsWith("Recents").clazz("android.widget.Button")), 5000)).click()
            assertNotNull(device.wait(Until.findObject(By.desc("Clear History")), 5000))
        }
        device.findObject(By.desc("Clear History")).click()
        assertNotNull(device.wait(Until.findObject(By.text("Cancel")), 5000))
        capture("clear-confirmation")
        device.findObject(By.text("Cancel")).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
    }

    @Test fun setupKeyboardInvalidSaveScrollAndClose() {
        device.findObject(By.desc(context.getString(R.string.desc_open_setup))).click()
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.setup_server_url_label))), 5000))
        val editors = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(2, editors.size)
        editors[0].text = "ftp://invalid.example"
        scrollToText(context.getString(R.string.setup_save))
        device.findObject(By.text(context.getString(R.string.setup_save))).click()
        assertNotNull(device.wait(Until.findObject(By.textContains("Enter a valid")), 5000))
        capture("setup-invalid-save")
        device.findObject(By.desc(context.getString(R.string.desc_close_sheet))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
    }

    @Test fun localSessionErrorReloadShareEndAndBack() {
        shareTestLink()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
        assertFalse(device.hasObject(By.textContains("Live")))
        capture("room-native-controls")
        val audio = requireNotNull(context.getSystemService(android.media.AudioManager::class.java))
        val speakerBefore = audio.mode
        val speakerItem = overflowAction(By.text(context.getString(R.string.action_speaker)))
        capture("room-overflow")
        speakerItem.click()
        assertEquals(speakerBefore, audio.mode)
        val speakerAction = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(context.getString(R.string.action_speaker)) + "|" +
                java.util.regex.Pattern.quote(context.getString(R.string.action_cancel_speaker_switch)))
        val speakerToggle = overflowAction(By.text(speakerAction))
        capture("room-speaker-selected-or-pending")
        speakerToggle.click()
        assertTrue(device.wait(Until.gone(By.text(speakerAction)), 5000))
        instrumentation.waitForIdleSync()
        assertEquals(speakerBefore, audio.mode)
        AuditEvidence.assertAudioClient("room-speaker-off-audio", device, false)
        overflowAction(By.text(context.getString(R.string.action_speaker))).click()
        assertTrue(device.wait(Until.gone(By.text(speakerAction)), 5000))
        AuditEvidence.assertAudioClient("room-before-pip-audio", device, true)
        overflowAction(By.text(context.getString(R.string.desc_pip))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        awaitPipContent("manual-pip")
        capture("room-picture-in-picture")
        AuditEvidence.assertAudioClient("room-visible-pip-audio", device, true)
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 5000))
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        assertPip(true)
        capture("room-home-auto-picture-in-picture")
        AuditEvidence.assertAudioClient("room-home-pip-audio", device, true)
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 5000))
        overflowAction(By.text(context.getString(R.string.desc_retry_call))).click()
        overflowAction(By.text(context.getString(R.string.action_share))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        assertPip(false)
        capture("room-share-chooser")
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 5000))
        device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        shareTestLink()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 10000))
        overflowAction(By.text(context.getString(R.string.action_speaker))).click()
        instrumentation.waitForIdleSync()
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        AuditEvidence.assertAudioClient("room-back-closed-audio", device, false)
        capture("room-back-closed")
    }

    @Test fun onlyTheVisibleRoomAutoEntersAfterRotationAndResize() {
        shareTestLink()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 10000))
        var caller: android.view.View? = null
        withResumedActivity { activity ->
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            require(root is android.widget.FrameLayout)
            val incoming = com.baotime.app.ui.IncomingCallUi.build(
                com.baotime.app.ui.theme.BaoTimeTheme(activity), "Covered room",
                onAccept = { root.removeView(caller) }, onDecline = { root.removeView(caller) })
            caller = incoming
            root.addView(incoming)
        }
        assertNotNull(device.wait(Until.findObject(By.text("Covered room")), 5000))
        device.pressHome()
        assertHome()
        assertPip(false)
        capture("incoming-covered-room-home")
        returnToApp()
        withResumedActivity {
            val parent = caller?.parent
            if (parent is android.view.ViewGroup) parent.removeView(caller)
        }
        overflowAction(By.text(context.getString(R.string.action_share))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        instrumentation.runOnMainSync {
            owned.last { it !in destroyed }.window.setLayout(device.displayWidth * 3 / 4, device.displayHeight * 3 / 4)
        }
        instrumentation.waitForIdleSync()
        device.pressHome()
        assertHome()
        assertPip(false)
        capture("share-late-layout-home")
        returnToApp()
        withResumedActivity { it.window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) }
        device.setOrientationLeft()
        instrumentation.waitForIdleSync()
        capture("room-rotated-source")
        withResumedActivity { it.window.setLayout(device.displayWidth * 2 / 3, device.displayHeight * 3 / 4) }
        instrumentation.waitForIdleSync()
        withResumedActivity { activity ->
            val room = requireNotNull(findWebView(activity.findViewById(android.R.id.content)))
            assertTrue(room.width > 0 && room.height > 0 && room.width < device.displayWidth)
            val rect = android.graphics.Rect()
            assertTrue(room.getGlobalVisibleRect(rect))
            AuditEvidence.recordText("resized-room-source-rect", rect.toShortString())
        }
        capture("room-resized-source")
        // Release the test's fixed window dimensions before the system sizes the PiP window.
        withResumedActivity { it.window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) }
        instrumentation.waitForIdleSync()
        device.pressHome()
        assertPip(true)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000L
        var settled = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                settled = owned.any { it !in destroyed && it.isInPictureInPictureMode &&
                    it.window.decorView.width < device.displayWidth / 2 }
            }
            if (settled) break
            android.os.SystemClock.sleep(50)
        }
        AuditEvidence.recordText("settled-pip-window", device.executeShellCommand("dumpsys window windows"))
        assertTrue("Actual PiP decor has resized", settled)
        awaitPipContent("automatic-pip")
        capture("resized-room-auto-pip")
        returnToApp()
        assertPip(false)
        device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.pressHome()
        assertHome()
        assertPip(false)
        capture("closed-room-home")
        returnToApp()
        device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        device.pressHome()
        assertHome()
        assertPip(false)
        capture("ordinary-sheet-home")
        returnToApp()
        device.pressBack()
    }

    private fun withResumedActivity(block: (Activity) -> Unit) {
        instrumentation.runOnMainSync {
            block(owned.last { it !in destroyed && monitor.getLifecycleStageOf(it) == Stage.RESUMED })
        }
    }

    private fun returnToApp() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 5000))
        instrumentation.waitForIdleSync()
    }

    private fun assertHome() {
        val launcher = device.executeShellCommand(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        ).lineSequence().last { it.contains('/') }.trim().substringBefore('/')
        val deadline = android.os.SystemClock.uptimeMillis() + 5000L
        var resumed = ""
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resumed = device.executeShellCommand("dumpsys activity activities").lineSequence()
                .firstOrNull { it.contains("topResumedActivity=") }.orEmpty()
            if (resumed.contains("$launcher/")) break
            android.os.SystemClock.sleep(50)
        }
        assertTrue("System resumed launcher: $resumed", resumed.contains("$launcher/"))
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findWebView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun shareTestLink() {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://facetime.apple.com/join#audit-invalid")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun scrollToText(text: String) {
        repeat(8) {
            if (device.hasObject(By.text(text))) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        assertTrue("Missing $text after scrolling", device.hasObject(By.text(text)))
    }

    private fun awaitPipContent(name: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8000L
        var frame = 0
        var rendered = false
        while (android.os.SystemClock.uptimeMillis() < deadline && !rendered) {
            val bounds = android.graphics.Rect()
            instrumentation.runOnMainSync {
                owned.lastOrNull { it !in destroyed && it.isInPictureInPictureMode }?.window?.decorView?.let { view ->
                    if (view.width < device.displayWidth / 2 && view.height < device.displayHeight) {
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
                    }
                }
            }
            if (!bounds.isEmpty) rendered = AuditEvidence.capturePipFrame("$name-frame-${frame++}", device, bounds)
            if (!rendered) android.os.SystemClock.sleep(250)
        }
        AuditEvidence.recordText("$name-window", device.executeShellCommand("dumpsys window windows"))
        assertTrue("Loaded room pixels survive $name entry after the bounded transition", rendered)
    }

    private fun assertPip(expected: Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000L
        var actual = !expected
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { actual = owned.any { it !in destroyed && it.isInPictureInPictureMode } }
            if (actual == expected) break
            android.os.SystemClock.sleep(50)
        }
        assertEquals("Actual activity PiP state", expected, actual)
    }

    /** A dismissing PopupMenu keeps its window briefly and can swallow the next overflow tap. */
    private fun overflowAction(item: BySelector): UiObject2 {
        val overflow = By.desc(context.getString(R.string.call_more_actions))
        val deadline = android.os.SystemClock.uptimeMillis() + 5000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (device.hasObject(item)) {
                device.wait(Until.gone(item), 1000)
                continue
            }
            device.findObject(overflow)?.click()
            val action = device.wait(Until.findObject(item), 1000)
            if (action != null) return action
        }
        throw AssertionError("Call overflow menu never presented the requested action")
    }

    private fun capture(name: String) = AuditEvidence.capture(name, device)
}
