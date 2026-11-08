package com.baotime.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the real installed launcher entry without altering app records or configuration. */
@RunWith(AndroidJUnit4::class)
class InstalledLauncherTest {
    @Test fun installedLogoOpensTheUpdatedApp() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("installationCheck"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        if (!device.hasObject(By.text(context.getString(R.string.app_name)))) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 5, 30)
        }
        val icon = requireNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.app_name))), 5000))
        AuditEvidence.capture("installed-logo-launcher", device)
        icon.click()
        assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
        assertNotNull(device.findObject(By.res("com.baotime.app:id/screen_title")))
        AuditEvidence.capture("installed-app-opened", device)
    }
}
