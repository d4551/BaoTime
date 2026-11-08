package com.baotime.app

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Intent
import android.provider.ContactsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform address-book fixtures; never inserts into private BaoTime state. */
@RunWith(AndroidJUnit4::class)
class ContactDirectoryJourneysTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val rawIds = mutableListOf<Long>()

    @Before fun installDisposableAddressBook() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("auditFixture"))
        assertEquals("baotime_audit_20261107", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
        val operations = arrayListOf<ContentProviderOperation>()
        repeat(60) { index ->
            val reference = operations.size
            operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, reference)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Bao Audit %02d".format(index)).build())
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, reference)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "202555%04d".format(100 + index)).build())
        }
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        for (index in 0 until 60) rawIds.add(ContentUris.parseId(requireNotNull(results[index * 3].uri)))
        android.util.Log.i("BaoTimeJourney", "Disposable contact raw IDs: $rawIds")
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
    }

    @After fun removeOnlyRecordedFixtures() {
        scenario?.close()
        scenario = null
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        if (rawIds.isEmpty()) return
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
        rawIds.forEach { id -> context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id), null, null) }
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        android.util.Log.i("BaoTimeJourney", "Removed only ${rawIds.size} owned contact fixtures; host restores original READ_CONTACTS after runner exits")
    }

    @Test fun completeAddressBookSearchFavoriteExpansionAndDial() {
        val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        scenario = launched
        launched.onActivity { assertEquals(MainActivity::class.java, it.javaClass) }
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, launched.state)
        assertNotNull(device.wait(Until.findObject(By.text("Recents").clazz("android.widget.TextView")), 10000))
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        capture("contact-launch-baseline")
        assertNotNull(device.wait(Until.findObject(By.text("Contacts").clazz("android.widget.Button")), 10000))
        device.findObject(By.text("Contacts").clazz("android.widget.Button")).click()
        val search = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
        search.text = "Bao Audit 59"
        assertNotNull(device.wait(Until.findObject(By.text("Bao Audit 59").clazz("android.widget.TextView")), 15000))
        capture("contacts-beyond-fifty")
        device.findObject(By.text("Bao Audit 59").clazz("android.widget.TextView")).click()
        device.wait(Until.findObject(By.text(context.getString(R.string.contact_action_favorite))), 5000).click()
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.contacts_favorites_header))), 5000))
        device.findObjects(By.text("Bao Audit 59").clazz("android.widget.TextView")).last().click()
        device.wait(Until.findObject(By.text(context.getString(R.string.contact_action_unfavorite))), 5000).click()
        assertTrue(device.wait(Until.gone(By.text(context.getString(R.string.contacts_favorites_header))), 5000))
        device.findObject(By.clazz("android.widget.EditText")).text = "audit-no-such-person"
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.contacts_no_matches))), 5000))
        device.findObject(By.clazz("android.widget.EditText")).text = ""
        assertNotNull(device.wait(Until.findObject(By.text("Bao Audit 00").clazz("android.widget.TextView")), 5000))
        val scroll = requireNotNull(device.wait(Until.findObject(By.scrollable(true)), 5000))
        var expand = requireNotNull(scroll.scrollUntil(Direction.DOWN, Until.findObject(By.textStartsWith("Show all"))))
        val bottom = device.findObject(By.text("Contacts").clazz("android.widget.Button")).visibleBounds.top
        if (expand.visibleBounds.centerY() >= bottom) {
            scroll.scroll(Direction.DOWN, 0.5f)
            expand = requireNotNull(device.findObject(By.textStartsWith("Show all")))
        }
        assertTrue(expand.visibleBounds.centerY() < bottom)
        capture("contacts-show-all-visible")
        expand.click()
        assertTrue(device.wait(Until.gone(By.textStartsWith("Show all")), 5000))
        val selector = By.text("Bao Audit 59").clazz("android.widget.TextView")
        var last = requireNotNull(scroll.scrollUntil(Direction.DOWN, Until.findObject(selector)))
        val navigation = device.findObject(By.text("Contacts").clazz("android.widget.Button")).visibleBounds
        if (last.visibleBounds.centerY() >= navigation.top) {
            scroll.scroll(Direction.DOWN, 0.5f)
            last = requireNotNull(device.findObject(selector))
        }
        device.waitForIdle()
        assertTrue(last.visibleBounds.centerY() < navigation.top)
        capture("contacts-expanded-bottom")
        last.click()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        capture("contact-expanded-actions")
        requireNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.contact_action_dial))), 5000)).click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.dialer")), 5000))
        capture("contact-public-dial-route")
        device.pressBack()
    }

    private fun capture(name: String) = AuditEvidence.capture(name, device)
}
