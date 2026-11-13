package com.baotime.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baotime.app.call.CallLifecycle
import com.baotime.app.call.CallLifecycleState
import com.baotime.app.call.CallSessionController
import com.baotime.app.call.IncomingCallController
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.call.IncomingCallNotifications
import com.baotime.app.call.IncomingPushReceiver
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsRepository
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.RecentsRead
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.BlueBubblesServerStatus
import com.baotime.app.network.FaceTimeAvailabilityResult
import com.baotime.app.network.FaceTimeSessionResult
import com.baotime.app.network.ServerCapability
import com.baotime.app.routing.RoutedAction
import com.baotime.app.routing.UrlRouter
import com.baotime.app.system.OpenSettingsResult
import com.baotime.app.system.SystemIntegration
import com.baotime.app.ui.ContactsTabController
import com.baotime.app.ui.MainChrome
import com.baotime.app.ui.RecentsTabUi
import com.baotime.app.ui.RootUi
import com.baotime.app.ui.SheetController
import com.baotime.app.ui.components.ForegroundSurface
import com.baotime.app.ui.theme.BaoTimeMotion
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

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
    private var currentTab = TAB_RECENTS
    private val outgoingJob = AtomicReference<Job?>(null)
    private var historyGeneration = 0
    private var recentRows: List<RecentsTabUi.Row>? = null
    private val timeZoneChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshHistoryTimestamps()
    }
    private val clockFormatChanges = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshHistoryTimestamps()
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

    private val sheets by lazy {
        SheetController(
            chrome.rootFrame, theme, appConfig, history, contactCache, ::launchSafely,
            ::startOutgoingCall, ::submitHandleInput,
            { url, title, recordId -> openCallSession(url, title, recordId) },
            { contactsTab.refreshFavorites() }, ::requestContacts, ::openCallAlertControls,
            baoTimeApp::hasPushEndpoint, baoTimeApp::copyPushWebhook, ::onServerConfigSaved
        )
    }

    private val incomingCalls by lazy {
        IncomingCallController(chrome.rootFrame, theme, callLifecycle, ::joinIncomingWebLink)
    }

    private val errorHandler = CoroutineExceptionHandler { _, failure ->
        Log.e(LOG_TAG, "Unexpected app failure: ${failure.javaClass.simpleName}")
        reportFailure()
    }

    private var notificationPermissionRequested = false
    private var resumedOnce = false
    private var showingOverLockscreen = false
    private var presentedUiMode = Configuration.UI_MODE_TYPE_UNDEFINED
    private var presentedFontScale = 1f
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callSessions.completeNotificationConsent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appConfig.setNotificationPermissionBlocked(!granted &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        }
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
        chrome = RootUi.build(theme, { sheets.showNew() }, ::selectTab, { sheets.showClearHistory() }, { sheets.showSetup() })
        notificationPermissionRequested = savedInstanceState?.getBoolean(STATE_NOTIFICATION_REQUESTED) == true
        callSessions.restorePendingConsent(savedInstanceState?.getString(STATE_PENDING_CONSENT))
        ForegroundSurface.applyDefaultBars(window)
        setContentView(chrome.rootFrame)
        renderTab()
        incomingCalls.bindProcessEvents(
            activity = this,
            onDismissSheets = { sheets.dismiss() },
            onLifecycleStateChanged = { state ->
                val superseded = state is CallLifecycleState.Ringing ||
                    (state is CallLifecycleState.Terminal && !callLifecycle.hasRoomOpeningOrSession())
                if (superseded && cancelOutgoingCall()) {
                    launchSafely { bbClient.checkServerStatus() }
                }
                syncLockscreenPresentation(state)
            }
        ) {
            Toast.makeText(this@MainActivity, R.string.call_answer_failed, Toast.LENGTH_LONG).show()
            launchSafely { bbClient.checkServerStatus() }
        }
        if (savedInstanceState == null) handleIncomingIntent(intent)
        syncLockscreenPresentation()
        baoTimeApp.socket.ensureConnected()
        launchSafely { bbClient.checkServerStatus() }
        lifecycleScope.launch(errorHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                history.changes.collect { refreshHistory() }
            }
        }
        lifecycleScope.launch(errorHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { baoTimeApp.pushEndpointAvailable.collect { sheets.refreshPushStatus() } }
                launch {
                    appConfig.connectionRevisions.combine(bbClient.serverStatuses) { _, _ -> Unit }
                        .collect {
                            val current = bbClient.serverStatuses.value?.takeIf { it.revision == appConfig.connectionRevision() }
                            renderServerBanner(current?.status)
                        }
                }
                launch {
                    appConfig.connectionRevisions.collect {
                        renderContacts(contactCache.snapshot)
                        sheets.refreshPushStatus()
                    }
                }
                appConfig.connectionRevisions.combine(contactRefreshes) { revision, _ -> revision }
                    .collectLatest { revision ->
                        if (contactsTab.isShowing || sheets.isShowingNewCall) {
                            try {
                                contactCache.refresh(revision, ::renderContacts)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                errorHandler.handleException(currentCoroutineContext(), failure)
                            }
                        }
                    }
            }
        }
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
        chrome = RootUi.build(theme, { sheets.showNew() }, ::selectTab, { sheets.showClearHistory() },
            { sheets.showSetup() }, root = host, home = previous.home)
        contactsTab.updateTheme(theme)
        sheets.updateTheme(theme)
        incomingCalls.updateTheme(theme)
        callSessions.updateRoomTheme(theme)
        renderTab()
        ForegroundSurface.refreshHomeAppearance(host, chrome.home, chrome.tabButtons[currentTab])
        if (previousFocus != null) {
            val target = chrome.home.findViewById<View>(previousFocus.id)
            (target ?: chrome.tabButtons[currentTab]).requestFocus()
        }
        val status = bbClient.serverStatuses.value?.takeIf { it.revision == appConfig.connectionRevision() }?.status
        if (outgoingJob.get() != null) chrome.updateConnectionState(getString(R.string.call_generating), theme.colorWarning)
        else renderServerBanner(status)
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
        refreshHistoryTimestamps()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            appConfig.notificationPermissionBlocked()) appConfig.setNotificationPermissionBlocked(false)
        requestNotificationPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_NOTIFICATION_REQUESTED, notificationPermissionRequested)
        outState.putString(STATE_PENDING_CONSENT, callSessions.pendingConsentKind())
        super.onSaveInstanceState(outState)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionRequested &&
            canRequestNotificationPermission() &&
            IncomingCallListenerService.hasNotificationAccess(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launchNotificationPermission()
        }
    }

    private fun launchNotificationPermission() {
        if (callSessions.requestNotificationConsent { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            notificationPermissionRequested = true
        }
    }

    private fun canRequestNotificationPermission() = !appConfig.notificationPermissionBlocked() ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))

    private fun openCallAlertControls() {
        val permissionDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (permissionDenied && canRequestNotificationPermission()) {
            launchNotificationPermission()
            return
        }
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
        val pushUuid = incoming?.getStringExtra(IncomingPushReceiver.EXTRA_PUSH_CALL_UUID)?.trim().orEmpty()
        if (pushUuid.isNotEmpty()) {
            IncomingCallNotifications.cancelUuidInvite(this, pushUuid)
            sheets.dismiss()
            incomingCalls.syncState(callLifecycle.state.value)
            return
        }
        when (val action = UrlRouter.routeIntent(incoming)) {
            is RoutedAction.JoinFaceTimeWebLink -> {
                if (incoming?.getBooleanExtra(IncomingCallListenerService.EXTRA_INCOMING_CALL, false) == true) {
                    IncomingCallNotifications.cancelNlsUrlInvite(this, action.url)
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
        if (!UrlRouter.isFaceTimeRoomUrl(action.url)) return
        val caller = action.callerOrTitle ?: getString(R.string.default_call_label)
        val invite = action.acceptedInvite
        if (invite == null) {
            openRecordedWebLink(action.url, caller,
                if (action.isIncomingAnswer) BaoCallRecord.TYPE_INCOMING else BaoCallRecord.TYPE_OUTGOING)
            return
        }
        if (cancelOutgoingCall()) launchSafely { bbClient.checkServerStatus() }
        val joining = lifecycleScope.launch(errorHandler) {
            val name = contacts.lookupContactName(caller) ?: caller
            val joiningJob = coroutineContext.job
            withContext(NonCancellable) {
                if (isFinishing || isDestroyed || !callLifecycle.isPendingRoom(action.url, invite.inviteGeneration)) return@withContext
                val recordId = history.admitCall(caller, name, action.url, BaoCallRecord.TYPE_INCOMING) { recordId ->
                    appConfig.admitConnectionOperation(invite.configGeneration) {
                        callLifecycle.onRoomOpened(action.url, recordId, invite.inviteGeneration, joiningJob)
                    }
                } ?: return@withContext
                presentCallSession(action.url, name, recordId)
            }
        }
        joining.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                callLifecycle.failPendingRoom(action.url, invite.inviteGeneration)
            }
        }
    }

    private fun submitHandleInput(raw: String) {
        val ftUrl = UrlRouter.wholeFaceTimeUrl(raw)
        if (ftUrl != null) openRecordedWebLink(ftUrl, getString(R.string.link_label)) else startOutgoingCall(raw)
    }

    /** Reserves a direct link before contact IO and records it only at atomic admission. */
    private fun openRecordedWebLink(url: String, title: String, callType: Int = BaoCallRecord.TYPE_OUTGOING) {
        if (!UrlRouter.isFaceTimeRoomUrl(url)) return
        if (cancelOutgoingCall()) launchSafely { bbClient.checkServerStatus() }
        val revision = appConfig.connectionRevision()
        val opening = callLifecycle.beginRoomOpening()
        lifecycleScope.launch(errorHandler) {
            val name = contacts.lookupContactName(title) ?: title
            val openingJob = coroutineContext.job
            withContext(NonCancellable) {
                if (isFinishing || isDestroyed || !callLifecycle.isCurrentRoomOpening(opening)) return@withContext
                val recordId = history.admitCall(title, name, url, callType) { recordId ->
                    appConfig.admitConnectionOperation(revision) {
                        callLifecycle.onRoomOpened(url, recordId, opening, openingJob)
                    }
                } ?: return@withContext
                presentCallSession(url, name, recordId)
            }
        }.invokeOnCompletion {
            // Handles cancellation before this direct opening's coroutine body starts too.
            callLifecycle.cancelRoomOpening(opening)
        }
    }

    /**
     * Creates a FaceTime link, records it, and opens it. A failed create shows a toast
     * and does not open a page.
     *
     * Per `facetimeRouter.ts` @ `76d2b2e`, `POST /api/v1/facetime/session` takes `{}` with no
     * callee handle; `GET /api/v1/handle/availability/facetime` is strictly a non-blocking advisory.
     */
    private fun startOutgoingCall(targetHandle: String, displayOverride: String? = null, expectedRevision: Long? = null) {
        val creating = lifecycleScope.launch(errorHandler, start = CoroutineStart.LAZY) {
            val revision = appConfig.connectionRevision()
            if (expectedRevision != null && revision != expectedRevision) return@launch
            val opening = callLifecycle.beginRoomOpening()
            coroutineContext.job.invokeOnCompletion { callLifecycle.cancelRoomOpening(opening) }
            ensureActive()
            chrome.updateConnectionState(getString(R.string.call_generating), theme.colorWarning)
            createSession(targetHandle, displayOverride, opening, revision)
        }
        if (!outgoingJob.compareAndSet(null, creating)) {
            creating.cancel()
            return
        }
        creating.invokeOnCompletion {
            if (outgoingJob.compareAndSet(creating, null)) launchSafely { bbClient.checkServerStatus() }
        }
        creating.start()
    }

    private fun cancelOutgoingCall(): Boolean {
        val creating = outgoingJob.getAndSet(null) ?: return false
        creating.cancel()
        chrome.updateConnectionState(getString(R.string.banner_checking), theme.colorWarning)
        return true
    }

    private suspend fun createSession(targetHandle: String, displayOverride: String?, opening: Long, revision: Long) {
        val trimmedHandle = targetHandle.trim()
        if (trimmedHandle.isNotEmpty() && UrlRouter.wholeFaceTimeUrl(trimmedHandle) == null) {
            val availability = bbClient.checkFaceTimeAvailability(trimmedHandle, revision)
            if (!callLifecycle.isCurrentRoomOpening(opening) || appConfig.connectionRevision() != revision) return
            if (availability == FaceTimeAvailabilityResult.Unavailable) {
                Toast.makeText(this@MainActivity, R.string.call_advisory_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        val result = bbClient.createFaceTimeSession(revision)
        if (!callLifecycle.isCurrentRoomOpening(opening) || appConfig.connectionRevision() != revision) return
        when (result) {
            is FaceTimeSessionResult.Created -> {
                val label = resolveOutgoingLabel(targetHandle, displayOverride)
                val creatingJob = currentCoroutineContext().job
                withContext(NonCancellable) {
                    if (isFinishing || isDestroyed || !callLifecycle.isCurrentRoomOpening(opening)) return@withContext
                    val recordId = history.admitCall(targetHandle, label, result.link,
                        BaoCallRecord.TYPE_OUTGOING) { recordId ->
                        appConfig.admitConnectionOperation(revision) {
                            callLifecycle.onRoomOpened(result.link, recordId, opening, creatingJob)
                        }
                    } ?: return@withContext
                    presentCallSession(result.link, label, recordId)
                }
            }
            is FaceTimeSessionResult.Failed -> {
                Toast.makeText(this@MainActivity, R.string.call_create_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun resolveOutgoingLabel(targetHandle: String, displayOverride: String?): String {
        if (!displayOverride.isNullOrBlank()) return displayOverride
        if (targetHandle.isBlank()) return getString(R.string.new_link_label)
        return contacts.lookupContactName(targetHandle) ?: targetHandle
    }

    private fun openCallSession(url: String, title: String, recordId: String) {
        if (callLifecycle.onRoomOpened(url, recordId)) {
            if (cancelOutgoingCall()) launchSafely { bbClient.checkServerStatus() }
            presentCallSession(url, title, recordId)
        }
    }

    /** Presentation is allowed only for the record that owns the committed logical room. */
    private fun presentCallSession(url: String, title: String, recordId: String) {
        val current = callLifecycle.state.value
        if (isFinishing || isDestroyed) {
            callLifecycle.onRoomClosed(recordId)
        } else if (current is CallLifecycleState.InRoom && current.recordId == recordId) {
            sheets.dismiss()
            callSessions.openSession(this, chrome.rootFrame, theme, url, title, recordId, ::requestCallMediaThenAllow)
        }
    }

    // ---- Tabs ---------------------------------------------------------------------------

    private fun selectTab(index: Int) {
        if (currentTab == index) return
        currentTab = index
        BaoTimeMotion.tabIn(chrome.tabContentContainer)
        renderTab()
    }

    private fun renderTab() {
        chrome.headerTitle.setText(if (currentTab == TAB_RECENTS) R.string.tab_recents else R.string.tab_contacts)
        RootUi.styleTabs(theme, chrome.tabButtons, currentTab)
        val container = chrome.tabContentContainer
        container.removeAllViews()
        recentRows = null
        when (currentTab) {
            TAB_RECENTS -> {
                container.addView(RecentsTabUi.loadingNote(theme))
                refreshHistory()
            }
            TAB_CONTACTS -> {
                contactsTab.show(container)
                requestContacts()
            }
        }
    }

    private fun refreshHistory() {
        val generation = ++historyGeneration
        launchSafely {
            val result = history.readRecents(RECENTS_LIMIT)
            if (generation != historyGeneration) return@launchSafely
            val recent = when (result) {
                is RecentsRead.Available -> result.recent
                RecentsRead.Unreadable -> null
            }
            chrome.clearHistoryAction.isEnabled = recent == null || recent.records.isNotEmpty()
            RootUi.updateRecentsLabel(chrome.tabButtons[TAB_RECENTS], recent?.missedCount ?: 0)
            sheets.refreshRecord(recent)
            if (currentTab != TAB_RECENTS) return@launchSafely
            if (recent == null) {
                recentRows = null
                RecentsTabUi.showUnreadable(theme, chrome.tabContentContainer)
                return@launchSafely
            }
            recentRows = RecentsTabUi.render(
                theme, chrome.tabContentContainer, recentRows, recent.records,
                onJoin = { record -> record.faceTimeUrl?.let { url -> openCallSession(url, record.displayName, record.id) } },
                onCall = { record -> startOutgoingCall(record.handleOrNumber, record.displayName) },
                onOpenDetail = { record -> sheets.showRecord(record) }
            )
        }
    }

    private fun refreshHistoryTimestamps() {
        recentRows?.forEach { it.caption.refresh() }
        sheets.refreshRecordTimestamp()
    }

    private fun requestContacts() = contactRefreshes.update { it + 1L }

    private fun renderContacts(snapshot: ContactsSnapshot?) {
        if (snapshot != null) contactsTab.refresh(snapshot)
        sheets.refreshContacts(snapshot)
    }

    // ---- Server banner ----------------------------------------------------------------------

    private suspend fun onServerConfigSaved(): BlueBubblesServerStatus {
        cancelOutgoingCall()
        val status = bbClient.checkServerStatus()
        val observation = bbClient.serverStatuses.value
        return observation?.status?.takeIf { observation.revision == appConfig.connectionRevision() } ?: status
    }

    private fun renderServerBanner(status: BlueBubblesServerStatus?) {
        if (outgoingJob.get() != null || isFinishing || isDestroyed) return
        if (status == null) {
            chrome.updateConnectionState(getString(R.string.banner_checking), theme.colorWarning)
            return
        }
        val capable = status.isFaceTimeCapable
        chrome.updateConnectionState(
            getString(if (capable) R.string.banner_connected else ServerCapability.bannerResource(status.failure)),
            if (capable) theme.colorBaoGreen else theme.colorDanger
        )
    }

    private companion object {
        const val STATE_NOTIFICATION_REQUESTED = "notification_permission_requested"
        const val STATE_PENDING_CONSENT = "pending_permission_consent"
        const val LOG_TAG = "BaoTime"
        const val TAB_RECENTS = 0
        const val TAB_CONTACTS = 1
        const val RECENTS_LIMIT = 40
    }
}
