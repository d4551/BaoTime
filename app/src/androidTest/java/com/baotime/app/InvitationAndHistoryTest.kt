package com.baotime.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.IncomingCallNotifications
import com.baotime.app.call.IncomingPushReceiver
import com.baotime.app.call.SharedPreferencesPushStore
import com.baotime.app.call.UnifiedPushRegistrar
import com.baotime.app.data.BaoCallRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public intent/UI routes; this does not claim live notification-service delivery. */
@RunWith(AndroidJUnit4::class)
class InvitationAndHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private var pushFixtureRetained = false
    private var originalPushToken: String? = null
    private var originalPushEndpoint: String? = null
    private var originalHadMacGeneration = false
    private var originalMacGeneration = -1L
    private var originalDistributor: String? = null

    @After fun restorePushFixture() {
        if (!pushFixtureRetained) return
        pushFixtureRetained = false
        val app = context.applicationContext
        require(app is BaoTimeApp)
        val store = app.pushStore
        require(store is SharedPreferencesPushStore)
        val pushPrefs = app.getSharedPreferences(SharedPreferencesPushStore.PREFS_NAME, Context.MODE_PRIVATE)
        store.clearEndpoint()
        pushPrefs.edit(commit = true) {
            clear()
            if (originalPushToken != null) putString("instance_token", originalPushToken)
            if (originalPushEndpoint != null) putString("endpoint", originalPushEndpoint)
            if (originalHadMacGeneration) putLong("mac_generation", originalMacGeneration)
            if (originalDistributor != null) putString("distributor_package", originalDistributor)
        }
        originalPushEndpoint?.let(store::saveEndpoint)
    }

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
        val app = context.applicationContext
        require(app is BaoTimeApp)
        val retainedTitle = "Audit Retained"
        runBlocking {
            app.callHistory.clearAll()
            app.callHistory.recordCall(retainedTitle, retainedTitle, null, BaoCallRecord.TYPE_OUTGOING)
        }
        val retainedRow = By.text(retainedTitle)
        assertNotNull(device.wait(Until.findObject(retainedRow), 5000))

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
        assertTrue(device.hasObject(retainedRow))
        device.findObject(By.desc(context.getString(R.string.action_clear_history))).click()
        device.wait(Until.findObject(By.text(context.getString(R.string.action_cancel))), 5000).click()
        assertNotNull(device.wait(Until.findObject(retainedRow), 5000))
        assertTrue(device.findObject(By.desc(context.getString(R.string.action_clear_history))).isEnabled)
        device.findObject(By.desc(context.getString(R.string.action_clear_history))).click()
        device.wait(Until.findObject(By.text(context.getString(R.string.action_clear_history))), 5000).click()
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.recents_empty))), 5000))
        assertFalse(device.hasObject(retainedRow))
        assertFalse(device.findObject(By.desc(context.getString(R.string.action_clear_history))).isEnabled)
        capture("history-cleared-fixture-only")
    }

    @Test fun unifiedPushIngressRoutesIntoCallLifecycle() {
        val app = context.applicationContext
        require(app is BaoTimeApp)
        val store = app.pushStore
        require(store is SharedPreferencesPushStore)
        val pushPrefs = app.getSharedPreferences(SharedPreferencesPushStore.PREFS_NAME, Context.MODE_PRIVATE)
        originalPushToken = pushPrefs.getString("instance_token", null)
        originalPushEndpoint = pushPrefs.getString("endpoint", null)
        originalHadMacGeneration = pushPrefs.contains("mac_generation")
        originalMacGeneration = pushPrefs.getLong("mac_generation", -1L)
        originalDistributor = pushPrefs.getString("distributor_package", null)
        pushFixtureRetained = true
        val payload = """{"type":"ft-call-status-changed","data":{"uuid":"audit-push-uuid-1","status_id":4,"status":"Incoming Call","handle":{"address":"Audit Push"}}}"""
        val macGeneration = app.appConfig.macBindingGeneration()
        store.bind(macGeneration, null)
        val binding = requireNotNull(store.bind(macGeneration, UnifiedPushRegistrar.NTFY_PACKAGE).current)
        val endpoint = "https://ntfy.sh/baotime-audit?up=1"
        deliverEndpoint(binding.token, "https://ntfy.sh:65536/baotime-audit?up=1")
        assertFalse(app.hasPushEndpoint())
        assertFalse(app.pushEndpointAvailable.value)
        deliverEndpoint(binding.token, endpoint)
        assertTrue(app.hasPushEndpoint())
        assertTrue(app.pushEndpointAvailable.value)
        deliverPushBroadcast(Intent(context, IncomingPushReceiver::class.java).apply {
            action = IncomingPushReceiver.ACTION_UNIFIED_PUSH_MESSAGE
            putExtra(IncomingPushReceiver.EXTRA_MESSAGE, payload)
        })
        assertFalse(app.callLifecycle.state.value is CallLifecycleState.Ringing)
        assertFalse(device.hasObject(By.text("Audit Push")))
        assertFalse(device.hasObject(By.desc(context.getString(R.string.desc_accept_call))))
        deliverPushBroadcast(Intent(context, IncomingPushReceiver::class.java).apply {
            action = IncomingPushReceiver.ACTION_UNIFIED_PUSH_MESSAGE
            putExtra(UnifiedPushRegistrar.EXTRA_TOKEN, "${binding.token}-rogue")
            putExtra(IncomingPushReceiver.EXTRA_MESSAGE, payload)
        })
        assertFalse(app.callLifecycle.state.value is CallLifecycleState.Ringing)
        assertFalse(device.hasObject(By.text("Audit Push")))
        assertFalse(device.hasObject(By.desc(context.getString(R.string.desc_accept_call))))
        deliverPushBroadcast(Intent(context, IncomingPushReceiver::class.java).apply {
            action = IncomingPushReceiver.ACTION_UNIFIED_PUSH_MESSAGE
            putExtra(UnifiedPushRegistrar.EXTRA_TOKEN, binding.token)
            putExtra(IncomingPushReceiver.EXTRA_MESSAGE, payload)
        })
        assertTrue(app.callLifecycle.state.value is CallLifecycleState.Ringing)
        assertNotNull(device.wait(Until.findObject(By.text("Audit Push")), 5000))
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_accept_call))), 5000))
        capture("incoming-unified-push")
        device.findObject(By.desc(context.getString(R.string.desc_decline_call))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_accept_call))), 5000))
        deliverPushBroadcast(Intent(IncomingPushReceiver.ACTION_UNIFIED_PUSH_UNREGISTERED)
            .setClass(context, IncomingPushReceiver::class.java)
            .putExtra(UnifiedPushRegistrar.EXTRA_TOKEN, binding.token))
        assertFalse(app.hasPushEndpoint())
        assertFalse(app.pushEndpointAvailable.value)
        deliverEndpoint(binding.token, endpoint)
        assertFalse(app.hasPushEndpoint())
        store.bind(macGeneration, null)
        assertFalse(app.hasPushEndpoint())
        assertFalse(app.pushEndpointAvailable.value)
        val unboundPayload = """{"type":"ft-call-status-changed","data":{"uuid":"audit-push-uuid-2","status_id":4,"status":"Incoming Call","handle":{"address":"Audit Push Unbound"}}}"""
        deliverPushBroadcast(Intent(context, IncomingPushReceiver::class.java).apply {
            action = IncomingPushReceiver.ACTION_UNIFIED_PUSH_MESSAGE
            putExtra(UnifiedPushRegistrar.EXTRA_TOKEN, binding.token)
            putExtra(IncomingPushReceiver.EXTRA_MESSAGE, unboundPayload)
        })
        assertFalse(app.callLifecycle.state.value is CallLifecycleState.Ringing)
        assertFalse(device.hasObject(By.text("Audit Push Unbound")))
    }

    private fun deliverEndpoint(token: String, endpoint: String) {
        deliverPushBroadcast(Intent(IncomingPushReceiver.ACTION_UNIFIED_PUSH_NEW_ENDPOINT)
            .setClass(context, IncomingPushReceiver::class.java)
            .putExtra(UnifiedPushRegistrar.EXTRA_TOKEN, token)
            .putExtra(IncomingPushReceiver.EXTRA_ENDPOINT, endpoint))
    }

    private fun deliverPushBroadcast(intent: Intent) {
        val delivered = CountDownLatch(1)
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    delivered.countDown()
                }
            },
            null,
            0,
            null,
            null
        )
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun invitation(id: String, title: String) {
        context.startActivity(IncomingCallNotifications.nlsOpenIntent(context,
            "https://facetime.apple.com/join#$id", title))
    }

    private fun capture(name: String) = AuditEvidence.capture(name, device)
}
