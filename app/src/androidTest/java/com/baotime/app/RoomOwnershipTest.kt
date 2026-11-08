package com.baotime.app

import android.media.AudioManager
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baotime.app.audio.CallAudioRouter
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.FaceTimeWebViewHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** These exercise native owner contracts, not a connected FaceTime media session. */
@RunWith(AndroidJUnit4::class)
class RoomOwnershipTest {
    @Before fun requireAuditFixture() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assertEquals("true", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("auditFixture"))
        val context = instrumentation.targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.CAMERA))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
    }

    @Test fun roomUrlRejectsCredentialsPortsSchemesAndLookalikeHosts() {
        assertTrue(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com/join#test"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com.evil.invalid/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://user:secret@facetime.apple.com/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("https://facetime.apple.com:444/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("http://facetime.apple.com/join"))
        assertFalse(UrlRouter.isFaceTimeRoomUrl("javascript:alert(1)"))
    }

    @Test fun javaScriptBelongsOnlyToAnUndisposedTrustedRoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = WebView(activity)
                var status = 0
                val room = FaceTimeWebViewHelper(view, { status = it }, {})
                val client = requireNotNull(view.webViewClient)
                assertFalse(view.settings.javaScriptEnabled)
                assertFalse(room.loadRoom("https://facetime.apple.com.evil.invalid/join"))
                assertFalse(view.settings.javaScriptEnabled)
                assertEquals(R.string.call_status_navigation_blocked, status)
                assertTrue(room.loadRoom("https://facetime.apple.com/join#audit-js"))
                assertTrue(view.settings.javaScriptEnabled)
                client.onPageStarted(view, "https://example.invalid", null)
                assertFalse(view.settings.javaScriptEnabled)
                assertEquals(R.string.call_status_navigation_blocked, status)
                assertTrue(room.loadRoom("https://facetime.apple.com/join#audit-js-next"))
                assertTrue(view.settings.javaScriptEnabled)
                room.dispose()
                assertFalse(room.loadRoom("https://facetime.apple.com/join#audit-stale"))
                val afterDisposal = status
                client.onPageStarted(view, "https://facetime.apple.com/join#audit-callback", null)
                assertEquals(afterDisposal, status)
            }
        }
    }

    @Test fun permissionCancellationAndDisposalCannotLeakAcrossRooms() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var firstWaiting = false
                var secondWaiting = false
                var firstStatus = 0
                var secondStatus = 0
                val firstView = WebView(activity)
                val secondView = WebView(activity)
                val first = FaceTimeWebViewHelper(firstView, { firstStatus++ }, { firstWaiting = it })
                val second = FaceTimeWebViewHelper(secondView, { secondStatus++ }, { secondWaiting = it })
                val firstClient = requireNotNull(firstView.webChromeClient)
                val secondClient = requireNotNull(secondView.webChromeClient)
                val request = MediaRequest()
                firstClient.onPermissionRequest(request)
                assertTrue(firstWaiting)
                assertFalse(secondWaiting)
                firstClient.onPermissionRequestCanceled(request)
                assertFalse(firstWaiting)
                assertFalse(first.grantHeldMedia())
                val next = MediaRequest()
                secondClient.onPermissionRequest(next)
                assertTrue(secondWaiting)
                first.dispose()
                assertTrue(secondWaiting)
                val before = firstStatus
                val stale = MediaRequest()
                firstClient.onPermissionRequest(stale)
                assertTrue(stale.denied)
                assertEquals(before, firstStatus)
                second.dispose()
                assertTrue(next.denied)
                assertFalse(secondWaiting)
                assertFalse(second.grantHeldMedia())
                assertTrue(secondStatus > 0)
            }
        }
    }

    @Test fun speakerSelectionNeverMutatesModeOrMicrophone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val audio = requireNotNull(activity.getSystemService(AudioManager::class.java))
                val mode = audio.mode
                val muted = audio.isMicrophoneMute
                val owner = CallAudioRouter(activity)
                assertTrue(owner.enableSpeaker())
                assertEquals(mode, audio.mode)
                owner.release()
                assertEquals(mode, audio.mode)
                assertEquals(muted, audio.isMicrophoneMute)
                assertTrue(owner.enableSpeaker())
                assertEquals(mode, audio.mode)
                owner.release()
                assertEquals(mode, audio.mode)
            }
        }
    }

    @Test fun repeatedSpeakerRequestsAreIdempotentAndCancelWithoutChangingMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val audio = requireNotNull(activity.getSystemService(AudioManager::class.java))
                val mode = audio.mode
                val muted = audio.isMicrophoneMute
                val owner = CallAudioRouter(activity)
                repeat(12) {
                    assertTrue(owner.enableSpeaker())
                    assertTrue(owner.hasSpeakerSelection())
                    assertTrue(owner.enableSpeaker())
                    owner.release()
                    assertFalse(owner.hasSpeakerSelection())
                    assertEquals(CallAudioRouter.SpeakerState.Off, owner.speakerState())
                    assertEquals(mode, audio.mode)
                    assertEquals(muted, audio.isMicrophoneMute)
                }
            }
        }
    }

    @Test fun releasingSpeakerRemovesTheNativeClientRequest() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var owner: CallAudioRouter? = null
            scenario.onActivity { activity ->
                val audio = requireNotNull(activity.getSystemService(AudioManager::class.java))
                val mode = audio.mode
                val router = CallAudioRouter(activity)
                owner = router
                assertTrue(router.enableSpeaker())
                assertEquals(mode, audio.mode)
            }
            AuditEvidence.assertAudioClient("speaker-client-enabled", device, true)
            scenario.onActivity { requireNotNull(owner).release() }
            AuditEvidence.assertAudioClient("speaker-client-released", device, false)
            scenario.onActivity { requireNotNull(owner).release() }
        }
    }

    private class MediaRequest : PermissionRequest() {
        var denied = false
        override fun getOrigin(): Uri = Uri.parse("https://facetime.apple.com")
        override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)
        override fun grant(resources: Array<out String>) { error("Media was not granted in this isolated test") }
        override fun deny() { denied = true }
    }
}
