package com.baotime.app

import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baotime.app.audio.CallAudioRouter
import com.baotime.app.call.CallLinkController
import com.baotime.app.call.CallSessionController
import com.baotime.app.call.IncomingCallController
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.CallOverlay
import com.baotime.app.ui.ContactsTabController
import com.baotime.app.ui.FaceTimeWebViewHelper
import com.baotime.app.ui.MainTabsController
import com.baotime.app.ui.SheetController
import com.baotime.app.ui.theme.BaoTimeTheme
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** These exercise native owner contracts, not a connected FaceTime media session. */
@RunWith(AndroidJUnit4::class)
class RoomOwnershipTest {
    private val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var originalNightMode: String? = null

    @Before fun requireAuditFixture() {
        assertEquals("true", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("auditFixture"))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.CAMERA))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
    }

    @After fun resetOrientation() {
        device.setOrientationNatural()
        device.unfreezeRotation()
        originalNightMode?.let { device.executeShellCommand("cmd uimode night $it") }
        originalNightMode = null
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

    @Test fun shippedRuntimeHasExactlyOneRoomWebViewOwnerClass() {
        Class.forName("com.baotime.app.ui.FaceTimeWebViewHelper")
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.baotime.app.ui.FaceTimeWebRoom")
        }
    }

    @Test fun extractionKeepsExactlyOneControllerOwnerOnMainActivity() {
        val activityMethods = MainActivity::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val linkMethods = CallLinkController::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val tabMethods = MainTabsController::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val sessionMethods = CallSessionController::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        for (name in setOf("startOutgoingCall", "cancelOutgoingCall", "submitHandleInput",
            "joinIncomingWebLink", "openRecordedWebLink", "openCallSession")) {
            assertTrue("CallLinkController owns $name", name in linkMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        for (name in setOf("selectTab", "renderTab", "refreshHistory", "refreshHistoryTimestamps", "renderContacts")) {
            assertTrue("MainTabsController owns $name", name in tabMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        for (name in setOf("openSession", "closeSession", "requestMediaConsent", "completeMediaConsent", "setPipMode")) {
            assertTrue("CallSessionController owns $name", name in sessionMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        val sheetMethods = SheetController::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val contactMethods = ContactsTabController::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val incomingMethods = IncomingCallController::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        for (name in setOf("showNew", "showRecord", "showContact", "showClearHistory", "showSetup", "refreshSetupStatus", "dismiss")) {
            assertTrue("SheetController owns $name", name in sheetMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        for (name in setOf("show", "refresh", "refreshFavorites")) {
            assertTrue("ContactsTabController owns $name", name in contactMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        for (name in setOf("bindProcessEvents", "show", "syncState", "decline", "dispose")) {
            assertTrue("IncomingCallController owns $name", name in incomingMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        assertTrue("Room WebView ownership stays with FaceTimeWebViewHelper, never an Activity field",
            MainActivity::class.java.declaredFields.none { WebView::class.java.isAssignableFrom(it.type) })
        assertTrue("Room overlay ownership stays with CallSessionController, never an Activity field",
            MainActivity::class.java.declaredFields.none { it.type == CallOverlay::class.java })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertSingleControllerOwners(activity) }
        }
    }

    @Test fun recreationKeepsOneControllerOwnerAndSharedApplicationSingletons() {
        val application = context.applicationContext
        require(application is BaoTimeApp)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertSingleControllerOwners(activity)
                assertSharedApplicationOwners(activity, application)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSingleControllerOwners(activity)
                assertSharedApplicationOwners(activity, application)
            }
        }
    }

    @Test fun serverBannerAndPermissionConsentStayApplicationOwned() {
        val application = context.applicationContext
        require(application is BaoTimeApp)
        val activityMethods = MainActivity::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val bannerMethods = BaoTimeApp.ServerBannerCoordinator::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val consentMethods = BaoTimeApp.PermissionConsent::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        assertTrue("ServerBannerCoordinator owns onConfigSaved", "onConfigSaved" in bannerMethods)
        assertFalse("MainActivity must not re-own onConfigSaved", "onConfigSaved" in activityMethods)
        for (name in setOf("restoreNotificationRequested", "notificationRequested", "onNotificationResult",
            "syncBlockedFlag", "requestNotification", "requestCallAlertConsent")) {
            assertTrue("PermissionConsent owns $name", name in consentMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
        assertTrue("Server-banner orchestration is never an Activity field",
            MainActivity::class.java.declaredFields.none { BaoTimeApp.ServerBannerCoordinator::class.java.isAssignableFrom(it.type) })
        assertTrue("Permission-consent orchestration is never an Activity field",
            MainActivity::class.java.declaredFields.none { BaoTimeApp.PermissionConsent::class.java.isAssignableFrom(it.type) })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var banner: BaoTimeApp.ServerBannerCoordinator? = null
            var consent: BaoTimeApp.PermissionConsent? = null
            scenario.onActivity { activity ->
                assertSame("MainActivity wires the Application singleton", application, delegateValue(activity, "baoTimeApp"))
                banner = application.serverBanner
                consent = application.permissionConsent
                assertSame("One server-banner owner per process", banner, application.serverBanner)
                assertSame("One permission-consent owner per process", consent, application.permissionConsent)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame("MainActivity wires the Application singleton", application, delegateValue(activity, "baoTimeApp"))
                assertSame("Server-banner owner survives recreation", requireNotNull(banner), application.serverBanner)
                assertSame("Permission-consent owner survives recreation", requireNotNull(consent), application.permissionConsent)
            }
        }
    }

    @Test fun mainActivityLeavesUrlClassificationToTheRouter() {
        val activityMethods = MainActivity::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        val routerMethods = UrlRouter::class.java.declaredMethods.mapTo(mutableSetOf()) { it.name }
        for (name in setOf("extractFaceTimeUrl", "wholeFaceTimeUrl", "isFaceTimeRoomUrl", "isAppleMediaUrl",
            "isFacetimeHost", "isAppleMediaHost", "isWebScheme", "webUrlParts", "routeIntent")) {
            assertTrue("UrlRouter owns $name", name in routerMethods)
            assertFalse("MainActivity must not re-own $name", name in activityMethods)
        }
    }

    @Test fun viewIntentRoomLinkOpensExactlyOneOwnedRoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("https://facetime.apple.com/join#audit-view-intent")
            })
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            scenario.onActivity { activity ->
                val view = soleRoomWebView(activity)
                assertTrue("View-intent room JavaScript is gated on by the owner", view.settings.javaScriptEnabled)
                assertTrue("View-intent room navigation client is wired by FaceTimeWebViewHelper",
                    requireNotNull(view.webViewClient).javaClass.name.startsWith("com.baotime.app.ui.FaceTimeWebViewHelper\$"))
            }
            closeRoom()
            scenario.onActivity { activity ->
                assertTrue("Closing the view-intent room releases every room WebView", roomWebViews(activity).isEmpty())
            }
        }
    }

    @Test fun urlExtraRoomLinkOpensExactlyOneOwnedRoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openIntent(Intent(context, MainActivity::class.java).apply {
                putExtra("url", "https://facetime.apple.com/join#audit-url-extra")
            })
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            scenario.onActivity { activity -> soleRoomWebView(activity) }
            closeRoom()
            scenario.onActivity { activity ->
                assertTrue("Closing the extra-link room releases every room WebView", roomWebViews(activity).isEmpty())
            }
        }
    }

    @Test fun facetimeSchemeIntentPrefillsDialerWithoutOpeningRoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("facetime://audit-scheme@example.invalid")
            })
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
            val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
            assertEquals("facetime: scheme prefills the dialer handle", "audit-scheme@example.invalid", input.text)
            scenario.onActivity { activity ->
                assertTrue("A dialer prefill never opens a room WebView", roomWebViews(activity).isEmpty())
            }
            dismissSheet()
        }
    }

    @Test fun telIntentPrefillsDialerWithoutOpeningRoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_DIAL
                data = Uri.parse("tel:+12025550123")
            })
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
            val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
            assertEquals("tel: scheme prefills the dialer number", "+12025550123", input.text)
            scenario.onActivity { activity ->
                assertTrue("A dialer prefill never opens a room WebView", roomWebViews(activity).isEmpty())
            }
            dismissSheet()
        }
    }

    @Test fun sharedTextAroundLinkPrefillsDialerInsteadOfOpeningRoom() {
        val shared = "Join here: https://facetime.apple.com/join#audit-shared-text"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shared)
            })
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
            val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000))
            assertEquals("Shared text around a link stays a dialer prefill", shared, input.text)
            scenario.onActivity { activity ->
                assertTrue("Text around a link never opens a room WebView", roomWebViews(activity).isEmpty())
            }
            dismissSheet()
        }
    }

    @Test fun blankShareIntentOpensNeitherDialerNorRoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "   ")
            })
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
            instrumentation.waitForIdleSync()
            assertFalse("A blank share never opens the dialer",
                device.hasObject(By.desc(context.getString(R.string.desc_dismiss_sheet))))
            scenario.onActivity { activity ->
                assertTrue("A blank share never opens a room WebView", roomWebViews(activity).isEmpty())
            }
        }
    }

    @Test fun reopenedRoomAfterCloseIsAgainTheSoleOwnedWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shareRoom("https://facetime.apple.com/join#audit-reopen-first")
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            var first: WebView? = null
            scenario.onActivity { activity -> first = soleRoomWebView(activity) }
            closeRoom()
            scenario.onActivity { activity ->
                assertTrue("Closing releases every room WebView before reopen", roomWebViews(activity).isEmpty())
            }
            shareRoom("https://facetime.apple.com/join#audit-reopen-second")
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            scenario.onActivity { activity ->
                val reopened = soleRoomWebView(activity)
                assertNotSame("Reopening builds a fresh room WebView, never reuses the disposed one", first, reopened)
                assertNull("The disposed first room stays detached", requireNotNull(first).parent)
            }
            closeRoom()
            scenario.onActivity { activity ->
                assertTrue("Closing the reopened room releases every room WebView", roomWebViews(activity).isEmpty())
            }
        }
    }

    @Test fun roomWebViewClientsBelongToTheHelperOwnerUntilClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shareRoom("https://facetime.apple.com/join#audit-wiring")
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            var opened: WebView? = null
            scenario.onActivity { activity ->
                val view = soleRoomWebView(activity)
                opened = view
                assertTrue("Room JavaScript is gated on by the owner", view.settings.javaScriptEnabled)
                assertTrue("Room chrome client is wired by FaceTimeWebViewHelper",
                    requireNotNull(view.webChromeClient).javaClass.name.startsWith("com.baotime.app.ui.FaceTimeWebViewHelper\$"))
                assertTrue("Room navigation client is wired by FaceTimeWebViewHelper",
                    requireNotNull(view.webViewClient).javaClass.name.startsWith("com.baotime.app.ui.FaceTimeWebViewHelper\$"))
                assertTrue("The room owner is never an Activity field",
                    MainActivity::class.java.declaredFields.none { FaceTimeWebViewHelper::class.java.isAssignableFrom(it.type) })
            }
            device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
            instrumentation.waitForIdleSync()
            val closed = requireNotNull(opened)
            scenario.onActivity { activity ->
                assertTrue("Closing the room releases every room WebView", roomWebViews(activity).isEmpty())
                assertNull("Disposal unbinds the helper chrome client", closed.webChromeClient)
                assertFalse("Disposal unbinds the helper navigation client",
                    closed.webViewClient.javaClass.name.startsWith("com.baotime.app.ui.FaceTimeWebViewHelper\$"))
                assertNull("Disposed room WebView is detached", closed.parent)
            }
        }
    }

    @Test fun navigationGateSeparatesMainFrameRoomsFromAppleMediaSubframes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = WebView(activity)
                var status = 0
                val room = FaceTimeWebViewHelper(view, { status = it }, {})
                val client = requireNotNull(view.webViewClient)
                assertTrue(room.loadRoom("https://facetime.apple.com/join#audit-nav"))
                assertFalse("Room main-frame navigation stays inside the owner",
                    client.shouldOverrideUrlLoading(view, NavigationRequest("https://facetime.apple.com/join#audit-nav-next", mainFrame = true)))
                assertTrue(view.settings.javaScriptEnabled)
                assertFalse("Apple media subframes load without owning the room",
                    client.shouldOverrideUrlLoading(view, NavigationRequest("https://www.apple.com/support/systemstatus/", mainFrame = false)))
                assertTrue(view.settings.javaScriptEnabled)
                assertTrue("Non-Apple subframes are blocked without failing the room",
                    client.shouldOverrideUrlLoading(view, NavigationRequest("https://evil.invalid/resource", mainFrame = false)))
                assertTrue(view.settings.javaScriptEnabled)
                assertTrue("Lookalike main-frame navigation is blocked",
                    client.shouldOverrideUrlLoading(view, NavigationRequest("https://facetime.apple.com.evil.invalid/join", mainFrame = true)))
                assertFalse(view.settings.javaScriptEnabled)
                assertEquals(R.string.call_status_navigation_blocked, status)
                val request = MediaRequest()
                requireNotNull(view.webChromeClient).onPermissionRequest(request)
                assertTrue("A failed main frame denies new media requests", request.denied)
                room.dispose()
            }
        }
    }

    @Test fun rendererDeathReleasesTheOwnedRoomAndHeldMedia() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = WebView(activity)
                val host = FrameLayout(activity)
                host.addView(view)
                var waiting = false
                var goneView: WebView? = null
                val room = FaceTimeWebViewHelper(view, {}, { waiting = it }) { goneView = it }
                assertTrue(room.loadRoom("https://facetime.apple.com/join#audit-renderer"))
                val request = MediaRequest()
                requireNotNull(view.webChromeClient).onPermissionRequest(request)
                assertTrue(waiting)
                assertTrue(room.hasPendingMedia)
                val client = requireNotNull(view.webViewClient)
                assertTrue("Renderer death is handled by the owner", client.onRenderProcessGone(view, RendererGoneDetail()))
                assertFalse(waiting)
                assertFalse(room.hasPendingMedia)
                assertFalse(room.grantHeldMedia())
                assertSame(view, goneView)
                assertNull("Renderer death detaches the owned room WebView", view.parent)
                assertFalse(room.loadRoom("https://facetime.apple.com/join#audit-renderer-stale"))
            }
        }
    }

    @Test fun permissionRequestsFromNonAppleOriginsOrWithoutMediaResourcesAreDenied() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = WebView(activity)
                var waiting = false
                val room = FaceTimeWebViewHelper(view, {}, { waiting = it })
                assertTrue(room.loadRoom("https://facetime.apple.com/join#audit-origin"))
                val chrome = requireNotNull(view.webChromeClient)
                val evilOrigin = MediaRequest(origin = "https://evil.invalid")
                chrome.onPermissionRequest(evilOrigin)
                assertTrue("Non-Apple permission origins are denied", evilOrigin.denied)
                val lookalikeOrigin = MediaRequest(origin = "https://facetime.apple.com.evil.invalid")
                chrome.onPermissionRequest(lookalikeOrigin)
                assertTrue("Lookalike Apple permission origins are denied", lookalikeOrigin.denied)
                val httpOrigin = MediaRequest(origin = "http://facetime.apple.com")
                chrome.onPermissionRequest(httpOrigin)
                assertTrue("Plain-HTTP permission origins are denied", httpOrigin.denied)
                val noMedia = MediaRequest(resources = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                chrome.onPermissionRequest(noMedia)
                assertTrue("Requests without camera or microphone resources are denied", noMedia.denied)
                assertFalse("Denied permission requests never wait for consent", waiting)
                assertFalse(room.hasPendingMedia)
                room.dispose()
            }
        }
    }

    @Test fun mainFrameLoadErrorsFailTheRoomAndDenyHeldMediaWhileSubframeErrorsDoNot() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = WebView(activity)
                var status = 0
                var waiting = false
                val room = FaceTimeWebViewHelper(view, { status = it }, { waiting = it })
                assertTrue(room.loadRoom("https://facetime.apple.com/join#audit-load-error"))
                val client = requireNotNull(view.webViewClient)
                val chrome = requireNotNull(view.webChromeClient)
                val held = MediaRequest()
                chrome.onPermissionRequest(held)
                assertTrue(waiting)
                client.onReceivedHttpError(view, NavigationRequest("https://www.apple.com/media", mainFrame = false),
                    WebResourceResponse("text/html", "utf-8", 500, "Server Error", emptyMap(), ByteArrayInputStream(ByteArray(0))))
                assertTrue("Subframe load errors never fail the room", view.settings.javaScriptEnabled)
                assertTrue("Subframe load errors never deny held media", waiting)
                client.onReceivedHttpError(view,
                    NavigationRequest("https://facetime.apple.com/join#audit-load-error", mainFrame = true),
                    WebResourceResponse("text/html", "utf-8", 500, "Server Error", emptyMap(), ByteArrayInputStream(ByteArray(0))))
                assertFalse("Main-frame HTTP failure disables room JavaScript", view.settings.javaScriptEnabled)
                assertEquals(R.string.call_status_failed, status)
                assertFalse("Main-frame failure denies held media", waiting)
                assertTrue(held.denied)
                assertFalse(room.hasPendingMedia)
                val stale = MediaRequest()
                chrome.onPermissionRequest(stale)
                assertTrue("A failed room denies new media requests", stale.denied)
                room.dispose()
            }
        }
    }

    @Test fun rejectedRoomUrlNeverCreatesOrDisplacesTheOwnedRoomWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sessions = requireNotNull(delegateValue(activity, "callSessions"))
                require(sessions is CallSessionController)
                val rejectedHost = FrameLayout(activity)
                sessions.openSession(activity, rejectedHost, BaoTimeTheme(activity),
                    "https://facetime.apple.com.evil.invalid/join", "Audit Rejected", "audit-rejected") {}
                assertFalse("A rejected room URL never opens a session", sessions.isActive)
                assertEquals("A rejected room URL never inflates a room host", 0, rejectedHost.childCount)
                assertTrue("A rejected room URL never creates a room WebView", roomWebViews(activity).isEmpty())
            }
            shareRoom("https://facetime.apple.com/join#audit-rejected-displace")
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            var opened: WebView? = null
            scenario.onActivity { activity -> opened = soleRoomWebView(activity) }
            scenario.onActivity { activity ->
                val sessions = requireNotNull(delegateValue(activity, "callSessions"))
                require(sessions is CallSessionController)
                sessions.openSession(activity, FrameLayout(activity), BaoTimeTheme(activity),
                    "https://user:secret@facetime.apple.com/join", "Audit Rejected", "audit-rejected-credentials") {}
                assertTrue("The owned room survives a rejected open", sessions.isActive)
                assertSame("A rejected open never displaces the owned room WebView", opened, soleRoomWebView(activity))
            }
            closeRoom()
            scenario.onActivity { activity ->
                assertTrue("Closing after a rejected open releases every room WebView", roomWebViews(activity).isEmpty())
            }
        }
    }

    @Test fun oneRoomWebViewSurvivesRotationAndIsReplacedNotStacked() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shareRoom("https://facetime.apple.com/join#audit-owner-first")
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            var opened: WebView? = null
            scenario.onActivity { activity -> opened = soleRoomWebView(activity) }
            val first = requireNotNull(opened)
            device.setOrientationLeft()
            instrumentation.waitForIdleSync()
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 5000))
            scenario.onActivity { activity ->
                assertSame("Configuration rebuild keeps the single room WebView", first, soleRoomWebView(activity))
            }
            shareRoom("https://facetime.apple.com/join#audit-owner-second")
            val replacement = awaitReplacementRoom(scenario, first)
            scenario.onActivity { activity ->
                assertSame(replacement, soleRoomWebView(activity))
                assertNull("Displaced room WebView is detached from the hierarchy", first.parent)
            }
            shareRoom("https://facetime.apple.com/join#audit-owner-third")
            val secondReplacement = awaitReplacementRoom(scenario, replacement)
            scenario.onActivity { activity ->
                assertSame(secondReplacement, soleRoomWebView(activity))
                assertNull("Repeated replacement never stacks room WebViews", replacement.parent)
            }
            device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue("Closing the room releases every room WebView", roomWebViews(activity).isEmpty())
            }
        }
    }

    @Test fun configurationRebuildKeepsTabAndSheetWiring() {
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
            device.setOrientationLeft()
            instrumentation.waitForIdleSync()
            assertNotNull(device.wait(Until.findObject(By.textStartsWith(context.getString(R.string.tab_recents))), 5000))
            device.findObject(By.desc(context.getString(R.string.desc_new_call))).click()
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
            device.findObject(By.desc(context.getString(R.string.desc_close_sheet))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
            device.setOrientationNatural()
            device.unfreezeRotation()
            instrumentation.waitForIdleSync()
            device.findObject(By.desc(context.getString(R.string.desc_open_setup))).click()
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.setup_server_url_label))), 5000))
            device.findObject(By.desc(context.getString(R.string.desc_close_sheet))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
        }
    }

    @Test fun themeRebuildKeepsSingleChromeAndCallLinkWiring() {
        val night = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) "yes" else "no"
        originalNightMode = night
        val target = if (night == "yes") "no" else "yes"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 10000))
            device.executeShellCommand("cmd uimode night $target")
            instrumentation.waitForIdleSync()
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_new_call))), 5000))
            assertEquals("Theme rebuild keeps exactly one chrome owner", 1,
                device.findObjects(By.desc(context.getString(R.string.desc_new_call))).size)
            device.findObject(By.desc(context.getString(R.string.desc_open_setup))).click()
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.setup_server_url_label))), 5000))
            device.findObject(By.desc(context.getString(R.string.desc_close_sheet))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
            shareRoom("https://facetime.apple.com/join#audit-theme-rebuild")
            assertNotNull(device.wait(Until.findObject(By.desc(context.getString(R.string.desc_end_call))), 15000))
            scenario.onActivity { activity ->
                assertEquals("Theme rebuild keeps the single room WebView owner", 1, roomWebViews(activity).size)
            }
            device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
            assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        }
    }

    private fun shareRoom(url: String) {
        openIntent(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        })
    }

    private fun openIntent(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dismissSheet() {
        device.findObject(By.desc(context.getString(R.string.desc_close_sheet))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_dismiss_sheet))), 5000))
    }

    private fun closeRoom() {
        device.findObject(By.desc(context.getString(R.string.desc_end_call))).click()
        assertTrue(device.wait(Until.gone(By.desc(context.getString(R.string.desc_end_call))), 5000))
        instrumentation.waitForIdleSync()
    }

    private fun awaitReplacementRoom(scenario: ActivityScenario<MainActivity>, previous: WebView): WebView {
        val deadline = SystemClock.uptimeMillis() + 10000L
        while (SystemClock.uptimeMillis() < deadline) {
            var candidate: WebView? = null
            scenario.onActivity { activity -> candidate = roomWebViews(activity).singleOrNull() }
            val found = candidate
            if (found != null && found !== previous) return found
            SystemClock.sleep(50)
        }
        throw AssertionError("Replacement room never became the single owned room WebView")
    }

    private fun soleRoomWebView(activity: MainActivity): WebView {
        val views = roomWebViews(activity)
        assertEquals("Exactly one room WebView is owned", 1, views.size)
        return views.single()
    }

    private fun roomWebViews(activity: MainActivity): List<WebView> {
        val found = mutableListOf<WebView>()
        collectWebViews(activity.window.decorView, found)
        return found
    }

    private fun collectWebViews(view: View, into: MutableList<WebView>) {
        if (view is WebView) into.add(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectWebViews(view.getChildAt(index), into)
        }
    }

    private fun assertSingleControllerOwners(activity: MainActivity) {
        val owned = MainActivity::class.java.declaredFields
            .filter { it.name.endsWith("\$delegate") }
            .map { field ->
                field.isAccessible = true
                val lazy = field.get(activity)
                lazy.javaClass.getMethod("getValue").invoke(lazy)
            }
        assertEquals("Exactly one CallLinkController owner", 1,
            owned.count { it?.javaClass == CallLinkController::class.java })
        assertEquals("Exactly one MainTabsController owner", 1,
            owned.count { it?.javaClass == MainTabsController::class.java })
        assertEquals("Exactly one CallSessionController owner", 1,
            owned.count { it?.javaClass == CallSessionController::class.java })
    }

    private fun assertSharedApplicationOwners(activity: MainActivity, application: BaoTimeApp) {
        assertSame("CallHistory stays owned by BaoTimeApp", application.callHistory, delegateValue(activity, "history"))
        assertSame("CallLifecycle stays owned by BaoTimeApp", application.callLifecycle, delegateValue(activity, "callLifecycle"))
        assertSame("BlueBubblesClient stays owned by BaoTimeApp", application.bbClient, delegateValue(activity, "bbClient"))
        assertSame("ContactsRepository stays owned by BaoTimeApp", application.contactsRepository, delegateValue(activity, "contacts"))
        assertSame("AppConfig stays owned by BaoTimeApp", application.appConfig, delegateValue(activity, "appConfig"))
    }

    private fun delegateValue(activity: MainActivity, property: String): Any? {
        val field = MainActivity::class.java.getDeclaredField("$property\$delegate")
        field.isAccessible = true
        val lazy = field.get(activity)
        return lazy.javaClass.getMethod("getValue").invoke(lazy)
    }

    private class MediaRequest(
        private val origin: String = "https://facetime.apple.com",
        private val resources: Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)
    ) : PermissionRequest() {
        var denied = false
        override fun getOrigin(): Uri = Uri.parse(origin)
        override fun getResources(): Array<String> = resources
        override fun grant(resources: Array<out String>) { error("Media was not granted in this isolated test") }
        override fun deny() { denied = true }
    }

    private class NavigationRequest(
        private val url: String,
        private val mainFrame: Boolean
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class RendererGoneDetail : RenderProcessGoneDetail() {
        override fun didCrash(): Boolean = true
        override fun rendererPriorityAtExit(): Int = 0
    }
}
