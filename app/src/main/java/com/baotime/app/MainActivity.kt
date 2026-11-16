package com.baotime.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.CallLinkController
import com.baotime.app.call.CallSessionController
import com.baotime.app.call.IncomingCallController
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.call.IncomingCallNotifications
import com.baotime.app.config.AppConfig
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.routing.RoutedAction
import com.baotime.app.routing.UrlRouter
import com.baotime.app.system.OpenSettingsResult
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.ContactsTabController
import com.baotime.app.ui.MainChrome
import com.baotime.app.ui.MainTabsController
import com.baotime.app.ui.RootUi
import com.baotime.app.ui.SheetController
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Wires the scaffold, routing, permissions, tabs, and call sessions. */
class MainActivity : ComponentActivity() {

    private val baoTimeApp: BaoTimeApp by lazy {
        val app = application
        check(app is BaoTimeApp)
        app
    }
    private val appConfig: AppConfig by lazy { baoTimeApp.appConfig }
    private val history: CallHistory by lazy { baoTimeApp.callHistory }
    private val contacts: ContactsRepository by lazy { baoTimeApp.contactsRepository }
    private val bbClient: BlueBubblesClient by lazy { baoTimeApp.bbClient }
    private val callLifecycle: CallLifecycle by lazy { baoTimeApp.callLifecycle }
    private lateinit var theme: BaoTimeTheme
    private val timeZoneChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = tabs.refreshHistoryTimestamps()
    }
    private val clockFormatChanges = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = tabs.refreshHistoryTimestamps()
    }
    private val contactCache by lazy { ContactsCache(contacts, bbClient, appConfig) }
    private val contactRefreshes = MutableStateFlow(0L)
    private val contactsTab: ContactsTabController by lazy {
        ContactsTabController(theme, appConfig, contactCache) { contact, revision -> sheets.showContact(contact, revision) }
    }

    private lateinit var chrome: MainChrome

    private val callSessions by lazy {
        CallSessionController(history, baoTimeApp.applicationScope, callLifecycle::onRoomClosed)
    }

    private val callLinks: CallLinkController by lazy {
        CallLinkController(
            object : CallLinkController.Host {
                override val isDead: Boolean get() = isFinishing || isDestroyed
                override val warningColor: Int get() = theme.colorWarning
                override fun string(resId: Int): String = getString(resId)
                override fun connectionState(text: String, color: Int) {
                    chrome.updateConnectionState(text, color)
                }
                override fun dismissSheets() {
                    sheets.dismiss()
                }
                override fun openSession(url: String, title: String, recordId: String) {
                    callSessions.openSession(this@MainActivity, chrome.rootFrame, theme, url, title, recordId,
                        ::requestCallMediaThenAllow)
                }
                override fun toast(resId: Int, length: Int) {
                    Toast.makeText(this@MainActivity, resId, length).show()
                }
            },
            callLifecycle, history, appConfig, bbClient, contacts, lifecycleScope, errorHandler
        )
    }

    private val sheets by lazy {
        SheetController(
            chrome.rootFrame, theme, appConfig, history, contactCache, ::launchSafely,
            callLinks::startOutgoingCall, callLinks::submitHandleInput,
            { url, title, recordId -> callLinks.openCallSession(url, title, recordId) },
            { contactsTab.refreshFavorites() }, ::requestContacts, ::openCallAlertControls,
            baoTimeApp::hasPushEndpoint, baoTimeApp::copyPushWebhook,
            { baoTimeApp.serverBanner.onConfigSaved(callLinks::cancelOutgoingCall) }
        )
    }

    private val incomingCalls by lazy {
        IncomingCallController(chrome.rootFrame, theme, callLifecycle, callLinks::joinIncomingWebLink)
    }

    private val tabs: MainTabsController by lazy {
        MainTabsController(chrome, theme, contactsTab, sheets, history, callLinks, ::launchSafely, ::requestContacts)
    }

    private val errorHandler = CoroutineExceptionHandler { _, failure ->
        Log.e(LOG_TAG, "Unexpected app failure: ${failure.javaClass.simpleName}")
        reportFailure()
    }

    private var resumedOnce = false
    private var showingOverLockscreen = false
    private var presentedUiMode = Configuration.UI_MODE_TYPE_UNDEFINED
    private var presentedFontScale = 1f
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callSessions.completeNotificationConsent()
        baoTimeApp.permissionConsent.onNotificationResult(granted, ::notificationRationale)
        sheets.refreshSetupStatus()
    }

    private val callMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        callSessions.completeMediaConsent(grants)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) requestNotificationPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presentedUiMode = resources.configuration.uiMode
        presentedFontScale = resources.configuration.fontScale
        theme = BaoTimeTheme(this)
        chrome = RootUi.build(theme, { sheets.showNew() }, { index -> tabs.selectTab(index) }, { sheets.showClearHistory() }, { sheets.showSetup() })
        baoTimeApp.permissionConsent.restoreNotificationRequested(
            savedInstanceState?.getBoolean(STATE_NOTIFICATION_REQUESTED) == true)
        callSessions.restorePendingConsent(savedInstanceState?.getString(STATE_PENDING_CONSENT))
        ForegroundSurface.applyDefaultBars(window)
        setContentView(chrome.rootFrame)
        tabs.renderTab()
        bindIncomingCallEvents()
        if (savedInstanceState == null) handleIncomingIntent(intent)
        syncLockscreenPresentation()
        baoTimeApp.socket.ensureConnected()
        launchSafely { bbClient.checkServerStatus() }
        observeAppFlows()
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

    private fun bindIncomingCallEvents() {
        incomingCalls.bindProcessEvents(
            activity = this,
            onDismissSheets = { sheets.dismiss() },
            onLifecycleStateChanged = { state ->
                val superseded = state is CallLifecycleState.Ringing ||
                    (state is CallLifecycleState.Terminal && !callLifecycle.hasRoomOpeningOrSession())
                if (superseded && callLinks.cancelOutgoingCall()) {
                    launchSafely { bbClient.checkServerStatus() }
                }
                syncLockscreenPresentation(state)
            }
        ) {
            Toast.makeText(this@MainActivity, R.string.call_answer_failed, Toast.LENGTH_LONG).show()
            launchSafely { bbClient.checkServerStatus() }
        }
    }

    private fun observeAppFlows() {
        lifecycleScope.launch(errorHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                history.changes.collect { tabs.refreshHistory() }
            }
        }
        lifecycleScope.launch(errorHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { baoTimeApp.pushEndpointAvailable.collect { sheets.refreshPushStatus() } }
                launch {
                    baoTimeApp.serverBanner.state.collect {
                        if (!callLinks.isOutgoingActive) applyServerBanner()
                    }
                }
                launch {
                    appConfig.connectionRevisions.collect {
                        tabs.renderContacts(contactCache.snapshot)
                        sheets.refreshPushStatus()
                    }
                }
                appConfig.connectionRevisions.combine(contactRefreshes) { revision, _ -> revision }
                    .collectLatest(::refreshVisibleContacts)
            }
        }
    }

    private suspend fun refreshVisibleContacts(revision: Long) {
        if (!contactsTab.isShowing && !sheets.isShowingNewCall) return
        runCatching { contactCache.refresh(revision, tabs::renderContacts) }.onFailure { failure ->
            if (failure !is Exception) throw failure
            if (failure is CancellationException) throw failure
            errorHandler.handleException(currentCoroutineContext(), failure)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (presentedUiMode == newConfig.uiMode && presentedFontScale == newConfig.fontScale) return
        presentedUiMode = newConfig.uiMode
        presentedFontScale = newConfig.fontScale
        super.getTheme().rebase()
        theme = BaoTimeTheme(this)
        val previous = chrome
        val host = previous.rootFrame
        val previousFocus = previous.home.findFocus()
        chrome = RootUi.build(theme, { sheets.showNew() }, { index -> tabs.selectTab(index) }, { sheets.showClearHistory() },
            { sheets.showSetup() }, root = host, home = previous.home)
        contactsTab.updateTheme(theme)
        sheets.updateTheme(theme)
        incomingCalls.updateTheme(theme)
        callSessions.updateRoomTheme(theme)
        tabs.updateChromeAndTheme(chrome, theme)
        tabs.renderTab()
        ForegroundSurface.refreshHomeAppearance(host, chrome.home, chrome.tabButtons[tabs.currentTab])
        if (previousFocus != null) {
            val target = chrome.home.findViewById<View>(previousFocus.id)
            (target ?: chrome.tabButtons[tabs.currentTab]).requestFocus()
        }
        if (callLinks.isOutgoingActive) chrome.updateConnectionState(getString(R.string.call_generating), theme.colorWarning)
        else applyServerBanner()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        syncLockscreenPresentation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) callSessions.refreshPipEligibility()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        callSessions.onTopResumedActivityChanged(isTopResumedActivity)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ auto-enters through setAutoEnterEnabled; older supported versions need this hook.
        callSessions.enterPipOnUserLeave()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeZoneChanges, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24), false,
            clockFormatChanges)
        tabs.refreshHistoryTimestamps()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeZoneChanges)
        contentResolver.unregisterContentObserver(clockFormatChanges)
        callSessions.releaseSpeakerSelection()
    }

    override fun onResume() {
        super.onResume()
        baoTimeApp.syncPushRegistration()
        // Re-read system integration state: the user may have just changed it
        // in system settings (domain handler, notification access).
        sheets.refreshSetupStatus()
        incomingCalls.syncState(callLifecycle.state.value)
        syncLockscreenPresentation()
        if (resumedOnce) {
            baoTimeApp.socket.ensureConnected()
            launchSafely { bbClient.checkServerStatus() }
        } else {
            resumedOnce = true
        }
        if (contactsTab.isShowing || sheets.isShowingNewCall) requestContacts()
        baoTimeApp.permissionConsent.syncBlockedFlag()
        requestNotificationPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_NOTIFICATION_REQUESTED, baoTimeApp.permissionConsent.notificationRequested())
        outState.putString(STATE_PENDING_CONSENT, callSessions.pendingConsentKind())
        super.onSaveInstanceState(outState)
    }

    private fun requestNotificationPermission() {
        baoTimeApp.permissionConsent.requestNotification(::notificationRationale,
            callSessions::requestNotificationConsent, ::launchNotificationPermission)
    }

    private fun launchNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationRationale(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    private fun openCallAlertControls() {
        val consentHandled = baoTimeApp.permissionConsent.requestCallAlertConsent(::notificationRationale,
            callSessions::requestNotificationConsent, ::launchNotificationPermission)
        if (consentHandled) return
        // Settings jump stays on the Activity: SystemIntegration starts it without NEW_TASK.
        if (SystemIntegration.openCallAlertSettings(this) == OpenSettingsResult.NotResolvable) {
            Toast.makeText(this, R.string.setup_open_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncLockscreenPresentation(state: CallLifecycleState = callLifecycle.state.value) {
        val activeCall = state !is CallLifecycleState.Idle && state !is CallLifecycleState.Terminal
        if (showingOverLockscreen == activeCall) return
        showingOverLockscreen = activeCall
        setShowWhenLocked(activeCall)
        setTurnScreenOn(activeCall)
    }

    private fun hasTopSurface(): Boolean = incomingCalls.isShowing || sheets.isShowing || callSessions.isActive

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hasTopSurface()) {
            if (event.repeatCount == 0) event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCanceled && event.isTracking && dismissTopSurface()) {
            return true
        }
        return super.onKeyUp(keyCode, event)
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
        chrome.updateConnectionState(getString(R.string.error_generic), theme.colorDanger)
        Toast.makeText(this, R.string.error_generic, Toast.LENGTH_LONG).show()
    }

    // ---- Permissions ----------------------------------------------------------------

    private fun requestCallMediaThenAllow() {
        callSessions.requestMediaConsent(callMediaPermissionLauncher::launch)
    }

    // ---- Incoming links ---------------------------------------------------------------

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming != null && incoming.component == IncomingCallNotifications.notificationEntry(this)) {
            handleCallNotificationIntent(incoming)
            return
        }
        when (val action = UrlRouter.routeIntent(incoming)) {
            is RoutedAction.JoinFaceTimeWebLink -> callLinks.joinIncomingWebLink(action)
            is RoutedAction.InitiateFaceTimeCall -> sheets.showNew(action.handle)
            is RoutedAction.OpenHome -> {
                if (!action.prefillHandle.isNullOrBlank()) {
                    sheets.showNew(action.prefillHandle)
                    UrlRouter.wholeFaceTimeUrl(action.prefillHandle)?.let { extracted ->
                        callLinks.openRecordedWebLink(extracted, getString(R.string.link_label))
                    }
                }
            }
        }
    }

    private fun handleCallNotificationIntent(incoming: Intent) {
        when (incoming.action) {
            Intent.ACTION_MAIN -> {
                val uuid = incoming.getStringExtra(IncomingCallNotifications.EXTRA_CALL_UUID)
                    ?.trim()?.takeIf(String::isNotEmpty) ?: return
                IncomingCallNotifications.cancelUuidInvite(this, uuid)
                sheets.dismiss()
                incomingCalls.syncState(callLifecycle.state.value)
            }
            Intent.ACTION_VIEW -> {
                val action = UrlRouter.routeIntent(incoming)
                if (action is RoutedAction.JoinFaceTimeWebLink) {
                    IncomingCallNotifications.cancelNlsUrlInvite(this, action.url)
                    sheets.dismiss()
                    incomingCalls.show(action)
                }
            }
        }
    }

    private fun requestContacts() = contactRefreshes.update { it + 1L }

    // ---- Server banner ----------------------------------------------------------------------

    private fun applyServerBanner() {
        if (isFinishing || isDestroyed) return
        val banner = baoTimeApp.serverBanner.state.value
        chrome.updateConnectionState(getString(banner.messageRes), bannerColor(banner.tone))
    }

    private fun bannerColor(tone: ServerBanner.Tone): Int = when (tone) {
        ServerBanner.Tone.Checking -> theme.colorWarning
        ServerBanner.Tone.Connected -> theme.colorBaoGreen
        ServerBanner.Tone.Fault -> theme.colorDanger
    }

    private companion object {
        const val STATE_NOTIFICATION_REQUESTED = "notification_permission_requested"
        const val STATE_PENDING_CONSENT = "pending_permission_consent"
        const val LOG_TAG = "BaoTime"
    }
}
