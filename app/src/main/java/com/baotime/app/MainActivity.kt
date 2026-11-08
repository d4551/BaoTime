package com.baotime.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.widget.doAfterTextChanged
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.baotime.app.call.IncomingCallController
import com.baotime.app.call.CallSessionController
import com.baotime.app.call.IncomingCallListenerService
import com.baotime.app.config.AppConfig
import com.baotime.app.data.BaoCallRecord
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsRepository
import com.baotime.app.network.BlueBubblesClient
import com.baotime.app.network.FaceTimeSessionResult
import com.baotime.app.network.ServerFailure
import com.baotime.app.routing.RoutedAction
import com.baotime.app.routing.UrlRouter
import com.baotime.app.ui.ContactsTabController
import com.baotime.app.ui.IncomingCallUi
import com.baotime.app.ui.MainChrome
import com.baotime.app.ui.ModalSheetUi
import com.baotime.app.ui.NewCallSheetUi
import com.baotime.app.ui.RecordDetailUi
import com.baotime.app.ui.RecentsTabUi
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.RootUi
import com.baotime.app.ui.SheetController
import com.baotime.app.ui.SetupTabUi
import com.baotime.app.ui.theme.BaoTimeMotion
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Wires the scaffold, routing, permissions, tabs, and call sessions. */
class MainActivity : ComponentActivity() {

    private val appConfig by lazy { AppConfig(this) }
    private val history by lazy { CallHistory(this) }
    private val contacts by lazy { ContactsRepository(this) }
    private val bbClient by lazy { BlueBubblesClient(appConfig) }
    private val theme by lazy { BaoTimeTheme(this) }
    private val currentTab = AtomicInteger(0)
    private val callPending = AtomicBoolean(false)
    private var renderGeneration = 0
    private val contactCache by lazy { ContactsCache(contacts, bbClient, appConfig) }
    private val contactsTab by lazy { ContactsTabController(theme, appConfig, contactCache, ::launchSafely, { sheets.showContact(it) }) }

    private val chrome: MainChrome by lazy {
        RootUi.build(
            theme = theme,
            onNewLink = { sheets.showNew() },
            onTabSelected = ::selectTab,
            onClearHistory = { sheets.showClearHistory() },
            onOpenSetup = { sheets.showSetup() }
        )
    }

    private val callSessions by lazy {
        CallSessionController(
            history = history,
            onSessionClosed = { refreshCurrentTab() }
        )
    }

    private val sheets by lazy {
        SheetController(chrome.rootFrame, theme, appConfig, history, contactCache, ::launchSafely,
            ::startOutgoingCall, ::submitHandleInput, ::openCallSession, ::refreshCurrentTab, ::onServerConfigSaved)
    }

    private val incomingCalls by lazy {
        IncomingCallController(chrome.rootFrame, theme, history, ::launchSafely,
            ::joinIncomingWebLink, ::refreshCurrentTab)
    }

    private val errorHandler = CoroutineExceptionHandler { _, failure ->
        android.util.Log.e("BaoTime", "Unexpected app failure: ${failure.javaClass.simpleName}")
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
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightNavigationBars = isAppearanceLightStatusBars
        }
        setContentView(chrome.rootFrame)
        chrome.headerTitle.setText(R.string.tab_recents)
        RootUi.styleTabs(theme, chrome.tabButtons, currentTab.get())
        handleIncomingIntent(intent)
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
        if (currentTab.get() == TAB_CONTACTS) refreshCurrentTab()
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested &&
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
        when (val action = UrlRouter.routeIntent(incoming)) {
            is RoutedAction.JoinFaceTimeWebLink -> {
                if (incoming?.getBooleanExtra(IncomingCallListenerService.EXTRA_INCOMING_CALL, false) == true) {
                    sheets.dismiss()
                    incomingCalls.show(action)
                } else {
                    joinIncomingWebLink(action)
                }
            }
            is RoutedAction.InitiateFaceTimeCall -> {
                sheets.showNew(action.handle)
            }
            is RoutedAction.OpenHome -> {
                if (!action.prefillHandle.isNullOrBlank()) {
                    sheets.showNew(action.prefillHandle)
                    val extracted = UrlRouter.wholeFaceTimeUrl(action.prefillHandle)
                    if (extracted != null) {
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
        if (ftUrl != null) {
            openRecordedWebLink(ftUrl, getString(R.string.link_label))
        } else {
            startOutgoingCall(raw)
        }
    }

    /** Opens a FaceTime web link and records it into history. */
    private fun openRecordedWebLink(url: String, title: String) {
        launchSafely {
            val name = contacts.lookupContactName(title) ?: title
            val record = history.recordCall(
                handleOrNumber = title,
                displayName = name,
                faceTimeUrl = url,
                callType = BaoCallRecord.TYPE_OUTGOING
            )
            openCallSession(url, name, record.id)
        }
    }

    /**
     * Creates a FaceTime link, records it, and opens it. A failed create shows a toast
     * and does not open a page.
     */
    private fun startOutgoingCall(targetHandle: String, displayOverride: String? = null) {
        // Debounce: one pending session creation at a time, with inline state
        // in the banner instead of stacked toasts.
        if (!callPending.compareAndSet(false, true)) return
        updateConnectionState(getString(R.string.call_generating), theme.colorWarning)
        launchSafely {
            when (val result = bbClient.createFaceTimeSession()) {
                is FaceTimeSessionResult.Created -> {
                    callPending.set(false)
                    val label = resolveOutgoingLabel(targetHandle, displayOverride)
                    val record = history.recordCall(
                        handleOrNumber = targetHandle,
                        displayName = label,
                        faceTimeUrl = result.link,
                        callType = BaoCallRecord.TYPE_OUTGOING
                    )
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

    private fun openCallSession(url: String, title: String, recordId: String) {        // No upfront media prompt: media is granted automatically only when the
        // user already consented; otherwise Allow is the explicit consent path.
        sheets.dismiss()
        callSessions.openSession(
            activity = this,
            host = chrome.rootFrame,
            theme = theme,
            url = url,
            title = title,
            recordId = recordId,
            onAllowMedia = ::requestCallMediaThenAllow
        )
    }

    // ---- Tabs ---------------------------------------------------------------------------

    private fun selectTab(index: Int) {
        currentTab.set(index)
        chrome.headerTitle.setText(
            if (index == TAB_RECENTS) R.string.tab_recents else R.string.tab_contacts
        )
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
                onJoin = { record ->
                    record.faceTimeUrl?.let { url -> openCallSession(url, record.displayName, record.id) }
                },
                onCall = { record -> startOutgoingCall(record.handleOrNumber, record.displayName) },
                onOpenDetail = { record -> sheets.showRecord(record) }
            )
        }
    }

    // ---- Server banner ----------------------------------------------------------------------

    /** Fixed copy for each typed server failure. No diagnostic text is interpolated. */
    private fun bannerFor(failure: ServerFailure?): String {
        val res = when (failure) {
            ServerFailure.PasswordMissing -> R.string.banner_unreachable_password
            ServerFailure.CredentialsUnavailable -> R.string.banner_credentials_unavailable
            ServerFailure.UrlMissing -> R.string.banner_unreachable_url
            ServerFailure.UrlRejected -> R.string.banner_unreachable_rejected
            ServerFailure.HttpFailed, ServerFailure.EmptyLink -> R.string.banner_unreachable_http
            null -> R.string.banner_unreachable_unknown
        }
        return getString(res)
    }

    private suspend fun onServerConfigSaved(): com.baotime.app.network.BlueBubblesServerStatus {
        contactCache.invalidate()
        refreshCurrentTab()
        return refreshServerBanner()
    }

    private fun updateConnectionState(description: String, tint: Int) {
        chrome.setupAction.setColorFilter(tint)
        androidx.core.view.ViewCompat.setStateDescription(chrome.setupAction, description)
    }

    private suspend fun refreshServerBanner(): com.baotime.app.network.BlueBubblesServerStatus {
        val status = bbClient.checkServerStatus()
        updateConnectionState(if (status.reachable) getString(R.string.banner_connected) else bannerFor(status.failure),
            if (status.reachable) theme.colorBaoGreen else theme.colorDanger)
        return status
    }

    private companion object {
        const val TAB_RECENTS = 0
        const val TAB_CONTACTS = 1
        const val RECENTS_LIMIT = 40
    }
}
