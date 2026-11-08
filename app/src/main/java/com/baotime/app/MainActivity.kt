package com.baotime.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallSessionController
import com.baotime.app.call.IncomingCallController
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.call.IncomingPushReceiver
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.BlueBubblesServerStatus
import com.baotime.app.network.FaceTimeAvailabilityResult
import com.baotime.app.network.FaceTimeSessionResult
import com.baotime.app.network.ServerCapability
import com.baotime.app.network.ServerFailure
import com.baotime.app.routing.RoutedAction
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.ContactsTabController
import com.baotime.app.ui.MainChrome
import com.baotime.app.ui.RecentsTabUi
import com.baotime.app.ui.RootUi
import com.baotime.app.ui.SheetController
import com.baotime.app.ui.theme.BaoTimeMotion
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Wires the scaffold, routing, permissions, tabs, and call sessions. */
class MainActivity : ComponentActivity() {

    private val baoTimeApp: BaoTimeApp by lazy { application as BaoTimeApp }
    private val appConfig: AppConfig by lazy { baoTimeApp.appConfig }
    private val history: CallHistory by lazy { baoTimeApp.callHistory }
    private val contacts: ContactsRepository by lazy { baoTimeApp.contactsRepository }
    private val bbClient: BlueBubblesClient by lazy { baoTimeApp.bbClient }
    private val callLifecycle: CallLifecycle by lazy { baoTimeApp.callLifecycle }
    private val theme by lazy { BaoTimeTheme(this) }
    private val currentTab = AtomicInteger(0)
    private val callPending = AtomicBoolean(false)
    private var renderGeneration = 0
    private val contactCache by lazy { ContactsCache(contacts, bbClient, appConfig) }
    private val contactsTab by lazy {
        ContactsTabController(theme, appConfig, contactCache, ::launchSafely, { sheets.showContact(it) })
    }

    private val chrome: MainChrome by lazy {
        RootUi.build(theme, { sheets.showNew() }, ::selectTab, { sheets.showClearHistory() }, { sheets.showSetup() })
    }

    private val callSessions by lazy {
        CallSessionController(history) {
            callLifecycle.onRoomClosed()
            refreshCurrentTab()
        }
    }

    private val sheets by lazy {
        SheetController(
            chrome.rootFrame, theme, appConfig, history, contactCache, ::launchSafely,
            ::startOutgoingCall, ::submitHandleInput, ::openCallSession, ::refreshCurrentTab, ::onServerConfigSaved
        )
    }

    private val incomingCalls by lazy {
        IncomingCallController(chrome.rootFrame, theme, callLifecycle, ::joinIncomingWebLink)
    }

    private val errorHandler = CoroutineExceptionHandler { _, failure ->
        Log.e("BaoTime", "Unexpected app failure: ${failure.javaClass.simpleName}")
        reportFailure()
    }

    private var notificationPermissionRequested = false
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        sheets.refreshSetupStatus()
    }

    private val callMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!callSessions.allowHeldMedia(this)) callSessions.denyHeldMedia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Native chrome follows the actual appearance; foreground surfaces own temporary changes.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightNavigationBars = isAppearanceLightStatusBars
        }
        setContentView(chrome.rootFrame)
        chrome.headerTitle.setText(R.string.tab_recents)
        RootUi.styleTabs(theme, chrome.tabButtons, currentTab.get())
        incomingCalls.bindProcessEvents(this, baoTimeApp, { sheets.dismiss() }, ::refreshCurrentTab) {
            Toast.makeText(this@MainActivity, R.string.call_create_failed, Toast.LENGTH_LONG).show()
            refreshServerBanner()
        }
        handleIncomingIntent(intent)
        baoTimeApp.socket.ensureConnected()
        launchSafely { refreshServerBanner() }
        refreshCurrentTab()
        IncomingCallListenerService.ensureChannel(this)
        onBackPressedDispatcher.addCallback(this) {
            if (!dismissTopSurface()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
        addOnPictureInPictureModeChangedListener { info ->
            callSessions.setPipMode(info.isInPictureInPictureMode)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        callSessions.releaseSpeakerSelection()
    }

    override fun onResume() {
        super.onResume()
        // Re-read system integration state: the user may have just changed it
        // in system settings (domain handler, notification access).
        sheets.refreshSetupStatus()
        incomingCalls.syncState(callLifecycle.state.value)
        if (currentTab.get() == TAB_CONTACTS) refreshCurrentTab()
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested &&
            IncomingCallListenerService.hasNotificationAccess(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun dismissTopSurface(): Boolean {
        when {
            incomingCalls.isShowing -> incomingCalls.decline()
            sheets.isShowing -> sheets.dismiss()
            callSessions.isActive -> callSessions.closeSession()
            else -> return false
        }
        return true
    }

    override fun onDestroy() {
        // Activity teardown unbinds UI only; process-wide Socket.IO and CallLifecycle stay alive.
        callSessions.closeSession()
        incomingCalls.dispose()
        sheets.dismiss()
        super.onDestroy()
    }

    // ---- Error channel ------------------------------------------------------------

    private fun launchSafely(block: suspend () -> Unit) {
        lifecycleScope.launch(errorHandler) { block() }
    }

    private fun reportFailure() {
        callPending.set(false)
        updateConnectionState(getString(R.string.error_generic), theme.colorDanger)
        Toast.makeText(this, R.string.error_generic, Toast.LENGTH_LONG).show()
    }

    // ---- Permissions ----------------------------------------------------------------

    private fun requestCallMediaThenAllow() {
        val needed = callSessions.requiredMediaPermissions()
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callSessions.allowHeldMedia(this)
        } else {
            callMediaPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---- Incoming links ---------------------------------------------------------------

    private fun handleIncomingIntent(incoming: Intent?) {
        val pushUuid = incoming?.getStringExtra(IncomingPushReceiver.EXTRA_PUSH_CALL_UUID)?.trim().orEmpty()
        if (pushUuid.isNotEmpty()) {
            IncomingPushReceiver.cancelCallNotification(this, pushUuid)
            sheets.dismiss()
            incomingCalls.syncState(callLifecycle.state.value)
            return
        }
        when (val action = UrlRouter.routeIntent(incoming)) {
            is RoutedAction.JoinFaceTimeWebLink -> {
                if (incoming?.getBooleanExtra(IncomingCallListenerService.EXTRA_INCOMING_CALL, false) == true) {
                    sheets.dismiss()
                    incomingCalls.show(action)
                } else {
                    joinIncomingWebLink(action)
                }
            }
            is RoutedAction.InitiateFaceTimeCall -> sheets.showNew(action.handle)
            is RoutedAction.OpenHome -> {
                if (!action.prefillHandle.isNullOrBlank()) {
                    sheets.showNew(action.prefillHandle)
                    UrlRouter.wholeFaceTimeUrl(action.prefillHandle)?.let { extracted ->
                        openRecordedWebLink(extracted, getString(R.string.link_label))
                    }
                }
            }
        }
    }

    private fun joinIncomingWebLink(action: RoutedAction.JoinFaceTimeWebLink) {
        val caller = action.callerOrTitle ?: getString(R.string.default_call_label)
        launchSafely {
            val name = contacts.lookupContactName(caller) ?: caller
            val record = history.recordCall(
                handleOrNumber = caller,
                displayName = name,
                faceTimeUrl = action.url,
                callType = if (action.isIncomingAnswer) BaoCallRecord.TYPE_INCOMING else BaoCallRecord.TYPE_OUTGOING
            )
            openCallSession(action.url, name, record.id)
        }
    }

    private fun submitHandleInput(raw: String) {
        val ftUrl = UrlRouter.wholeFaceTimeUrl(raw)
        if (ftUrl != null) openRecordedWebLink(ftUrl, getString(R.string.link_label)) else startOutgoingCall(raw)
    }

    /** Opens a FaceTime web link and records it into history. */
    private fun openRecordedWebLink(url: String, title: String) {
        launchSafely {
            val name = contacts.lookupContactName(title) ?: title
            val record = history.recordCall(title, name, url, BaoCallRecord.TYPE_OUTGOING)
            openCallSession(url, name, record.id)
        }
    }

    /**
     * Creates a FaceTime link, records it, and opens it. A failed create shows a toast
     * and does not open a page.
     *
     * Per `facetimeRouter.ts` @ `76d2b2e`, `POST /api/v1/facetime/session` takes `{}` with no
     * callee handle; `GET /api/v1/handle/availability/facetime` is strictly a non-blocking advisory.
     */
    private fun startOutgoingCall(targetHandle: String, displayOverride: String? = null) {
        // Debounce: one pending session creation at a time, with inline state
        // in the banner instead of stacked toasts.
        if (!callPending.compareAndSet(false, true)) return
        updateConnectionState(getString(R.string.call_generating), theme.colorWarning)
        launchSafely {
            val trimmedHandle = targetHandle.trim()
            if (trimmedHandle.isNotEmpty() && UrlRouter.wholeFaceTimeUrl(trimmedHandle) == null) {
                if (bbClient.checkFaceTimeAvailability(trimmedHandle) == FaceTimeAvailabilityResult.Unavailable) {
                    Toast.makeText(this@MainActivity, R.string.call_advisory_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
            when (val result = bbClient.createFaceTimeSession()) {
                is FaceTimeSessionResult.Created -> {
                    callPending.set(false)
                    val label = resolveOutgoingLabel(targetHandle, displayOverride)
                    val record = history.recordCall(targetHandle, label, result.link, BaoCallRecord.TYPE_OUTGOING)
                    openCallSession(result.link, label, record.id)
                    refreshServerBanner()
                }
                is FaceTimeSessionResult.Failed -> {
                    callPending.set(false)
                    Toast.makeText(this@MainActivity, R.string.call_create_failed, Toast.LENGTH_LONG).show()
                    refreshServerBanner()
                }
            }
        }
    }

    private suspend fun resolveOutgoingLabel(targetHandle: String, displayOverride: String?): String {
        if (!displayOverride.isNullOrBlank()) return displayOverride
        if (targetHandle.isBlank()) return getString(R.string.new_link_label)
        return contacts.lookupContactName(targetHandle) ?: targetHandle
    }

    private fun openCallSession(url: String, title: String, recordId: String) {
        // No upfront media prompt: media is granted automatically only when the
        // user already consented; otherwise Allow is the explicit consent path.
        sheets.dismiss()
        callLifecycle.onRoomOpened(url, recordId)
        callSessions.openSession(this, chrome.rootFrame, theme, url, title, recordId, ::requestCallMediaThenAllow)
    }

    // ---- Tabs ---------------------------------------------------------------------------

    private fun selectTab(index: Int) {
        currentTab.set(index)
        chrome.headerTitle.setText(if (index == TAB_RECENTS) R.string.tab_recents else R.string.tab_contacts)
        RootUi.styleTabs(theme, chrome.tabButtons, index)
        refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        renderGeneration++
        val container = chrome.tabContentContainer
        container.removeAllViews()
        BaoTimeMotion.tabIn(container)
        when (currentTab.get()) {
            TAB_RECENTS -> refreshRecentsTab(container)
            TAB_CONTACTS -> contactsTab.show(container)
        }
    }

    private fun refreshRecentsTab(container: LinearLayout) {
        val generation = renderGeneration
        container.addView(RecentsTabUi.loadingNote(theme))
        launchSafely {
            val records = history.history(RECENTS_LIMIT)
            if (generation != renderGeneration || currentTab.get() != TAB_RECENTS) return@launchSafely
            chrome.clearHistoryAction.isEnabled = records.isNotEmpty()
            val missed = records.count { it.callType == BaoCallRecord.TYPE_MISSED }
            chrome.tabButtons[TAB_RECENTS].text = if (missed > 0) {
                getString(R.string.tab_recents) + " ($missed)"
            } else {
                getString(R.string.tab_recents)
            }
            container.removeAllViews()
            RecentsTabUi.populate(
                theme, container, records,
                onJoin = { record -> record.faceTimeUrl?.let { url -> openCallSession(url, record.displayName, record.id) } },
                onCall = { record -> startOutgoingCall(record.handleOrNumber, record.displayName) },
                onOpenDetail = { record -> sheets.showRecord(record) }
            )
        }
    }

    // ---- Server banner ----------------------------------------------------------------------

    /** Fixed copy for each typed server failure. No diagnostic text is interpolated. */
    private fun bannerFor(failure: ServerFailure?): String =
        getString(ServerCapability.bannerResource(failure))

    private suspend fun onServerConfigSaved(): BlueBubblesServerStatus {
        contactCache.invalidate()
        baoTimeApp.socket.onConfigChanged(appConfig.connectionRevision())
        refreshCurrentTab()
        return refreshServerBanner()
    }

    private fun updateConnectionState(description: String, tint: Int) {
        chrome.setupAction.setColorFilter(tint)
        ViewCompat.setStateDescription(chrome.setupAction, description)
    }

    private suspend fun refreshServerBanner(): BlueBubblesServerStatus {
        val status = bbClient.checkServerStatus()
        val capable = status.isFaceTimeCapable
        updateConnectionState(
            if (capable) getString(R.string.banner_connected) else bannerFor(status.failure),
            if (capable) theme.colorBaoGreen else theme.colorDanger
        )
        return status
    }

    private companion object {
        const val TAB_RECENTS = 0
        const val TAB_CONTACTS = 1
        const val RECENTS_LIMIT = 40
    }
}
