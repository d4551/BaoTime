package com.baotime.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baotime.app.config.AppConfig
import com.baotime.app.config.PasswordRead
import com.baotime.app.network.BlueBubblesClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit deployment check; uses the app's real encrypted config and real HTTP client. */
@RunWith(AndroidJUnit4::class)
class DeviceConnectionCheckTest {
    @Test fun connectInstalledXiaomiToTailnetServer() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("deploymentCheck"))
        assertTrue(Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "deployment-connection-input.json")
        val document = input.readText()
        check(input.delete())
        val requested = JSONObject(document)
        val endpoint = requested.getString("url")
        val secret = requested.getString("password")
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedServer"), endpoint)
        val config = AppConfig(context)
        config.saveConnection(endpoint, secret)
        assertEquals(endpoint, config.serverUrl())
        assertTrue(config.connection().password is PasswordRead.Available)
        val status = runBlocking { BlueBubblesClient(config).checkServerStatus() }
        assertTrue("Phone must authenticate to BlueBubbles through Tailscale HTTPS", status.reachable)
        assertTrue("BlueBubbles Private API is available", status.privateApiEnabled)
        assertTrue("BlueBubbles helper is connected", status.helperConnected)
        File(context.filesDir, "deployment-connection-receipt.json").writeText(JSONObject().apply {
            put("server", endpoint)
            put("reachable", status.reachable)
            put("serverVersion", status.serverVersion)
            put("privateApiEnabled", status.privateApiEnabled)
            put("helperConnected", status.helperConnected)
            put("checkedAtMillis", System.currentTimeMillis())
        }.toString(2))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)))
        val device = UiDevice.getInstance(instrumentation)
        requireNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_open_setup))), 10000)).click()
        assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").text(endpoint)), 5000))
        requireNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.setup_save))), 5000)).click()
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.setup_saved_connected))), 35000))
    }
}
