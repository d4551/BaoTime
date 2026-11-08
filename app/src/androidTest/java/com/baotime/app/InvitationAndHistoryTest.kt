package com.baotime.app

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baotime.app.call.IncomingCallListenerService
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public intent/UI routes; this does not claim live notification-service delivery. */
@RunWith(AndroidJUnit4::class)
class InvitationAndHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before fun openHome() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
        assertEquals("baotime_audit_20261107", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
    }

    @Test fun incomingDuplicateBackDeclineAcceptAndReplacement() {
        invitation("audit-incoming-one", "Audit One")
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_accept_call))), 5000))
        invitation("audit-incoming-one", "Audit One")
        assertEquals(1, device.findObjects(By.desc(context.getString(R.string.desc_accept_call))).size)
        capture("incoming-invitation")
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_accept_call))), 5000))
        invitation("audit-incoming-two", "Audit Two")
        device.wait(Until.findObject(By.desc(context.getString(R.string.desc_decline_call))), 5000).click()
        invitation("audit-incoming-three", "Audit Three")
        invitation("audit-incoming-four", "Audit Four")
        assertNotNull(device.wait(Until.findObject(By.text("Audit Four")), 5000))
        assertEquals(1, device.findObjects(By.desc(context.getString(R.string.desc_accept_call))).size)
        device.findObject(By.desc(context.getString(R.string.desc_accept_call))).click()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 10000))
        capture("invitation-accepted-local-room")
        device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
    }

    @Test fun recordCopyShareDeleteAndClearConfirmation() {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://facetime.apple.com/join#audit-detail")
            putExtra(Intent.EXTRA_TITLE, "Audit Detail")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 10000).click()
        device.wait(Until.findObject(By.text("Audit Detail")), 5000).click()
        device.wait(Until.findObject(By.desc(context.getString(R.string.action_copy))), 5000).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        device.findObject(By.text("Audit Detail")).click()
        device.findObject(By.desc(context.getString(R.string.desc_share_link))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        capture("record-share-chooser")
        device.pressBack()
        device.wait(Until.findObject(By.text("Audit Detail")), 5000).click()
        device.wait(Until.findObject(By.desc(context.getString(R.string.action_delete))), 5000).click()
        assertTrue(device.wait(Until.gone(By.text("Audit Detail")), 5000))
        device.findObject(By.desc(context.getString(R.string.action_clear_history))).click()
        device.wait(Until.findObject(By.text(context.getString(R.string.action_cancel))), 5000).click()
        assertTrue(device.findObject(By.desc(context.getString(R.string.action_clear_history))).isEnabled)
        device.findObject(By.desc(context.getString(R.string.action_clear_history))).click()
        device.wait(Until.findObject(By.text(context.getString(R.string.action_clear_history))), 5000).click()
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.recents_empty))), 5000))
        assertFalse(device.findObject(By.desc(context.getString(R.string.action_clear_history))).isEnabled)
        capture("history-cleared-fixture-only")
    }

    private fun invitation(id: String, title: String) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://facetime.apple.com/join#$id")
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(IncomingCallListenerService.EXTRA_INCOMING_CALL, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun capture(name: String) = AuditEvidence.capture(name, device)
}
