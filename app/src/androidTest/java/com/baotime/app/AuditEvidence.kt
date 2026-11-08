package com.baotime.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue

/** Capture destination is unique per executed runner invocation, never a reused screenshot pool. */
object AuditEvidence {
    private fun directory(): File {
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(Regex("[a-z0-9-]+").matches(run))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "journeys/$run").apply { mkdirs() }
    }

    fun recordText(name: String, value: String) {
        File(directory(), "$name.txt").writeText(value)
    }

    fun assertAudioClient(name: String, device: UiDevice, present: Boolean) {
        val uid = android.os.Process.myUid().toString()
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var diagnostic = device.executeShellCommand("dumpsys audio")
        recordText("$name-initial", diagnostic)
        while (audioClients(diagnostic).contains(uid) != present && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(50)
            diagnostic = device.executeShellCommand("dumpsys audio")
        }
        recordText(name, diagnostic)
        org.junit.Assert.assertEquals("Native client ownership for UID $uid", present, audioClients(diagnostic).contains(uid))
    }

    private fun audioClients(diagnostic: String): String {
        val marker = "Communication route clients:"
        require(diagnostic.contains(marker))
        return diagnostic.substringAfter(marker).substringBefore("Computed Preferred communication device:")
    }

    fun capturePipFrame(name: String, device: UiDevice, bounds: android.graphics.Rect): Boolean {
        capture(name, device)
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(File(directory(), "$name.png").path))
        val area = android.graphics.Rect(bounds)
        require(area.intersect(0, 0, bitmap.width, bitmap.height))
        val colors = mutableSetOf<Int>()
        var greenPixels = 0
        for (row in 2..18) for (column in 2..18) {
            val color = bitmap.getPixel(area.left + area.width() * column / 20,
                area.top + area.height() * row / 20)
            colors.add(color and 0x00F0F0F0)
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            if (green > red + 20 && green > blue + 20) greenPixels++
        }
        bitmap.recycle()
        recordText("$name-pixels", "bounds=${area.toShortString()} quantizedInteriorColors=${colors.size} greenLogoSamples=$greenPixels")
        return colors.size >= 8 && greenPixels >= 3
    }

    fun capture(name: String, device: UiDevice) {
        val directory = directory()
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
