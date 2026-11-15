package com.baotime.app.ui

import com.baotime.app.R
import com.baotime.app.call.CallLinkController
import com.baotime.app.data.CallHistory
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.RecentsRead
import com.baotime.app.ui.theme.BaoTimeMotion
import com.baotime.app.ui.theme.BaoTimeTheme

/** Owns tab selection and the recents/contacts content rendering. */
class MainTabsController(
    private var chrome: MainChrome,
    private var theme: BaoTimeTheme,
    private val contactsTab: ContactsTabController,
    private val sheets: SheetController,
    private val history: CallHistory,
    private val callLinks: CallLinkController,
    private val launchSafely: (suspend () -> Unit) -> Unit,
    private val requestContacts: () -> Unit
) {
    var currentTab = TAB_RECENTS
        private set
    private var historyGeneration = 0
    private var recentRows: List<RecentsTabUi.Row>? = null

    fun updateChromeAndTheme(chrome: MainChrome, theme: BaoTimeTheme) {
        this.chrome = chrome
        this.theme = theme
    }

    fun selectTab(index: Int) {
        if (currentTab == index) return
        currentTab = index
        BaoTimeMotion.tabIn(chrome.tabContentContainer)
        renderTab()
    }

    fun renderTab() {
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

    fun refreshHistory() {
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
                onJoin = { record -> record.faceTimeUrl?.let { url -> callLinks.openCallSession(url, record.displayName, record.id) } },
                onCall = { record -> callLinks.startOutgoingCall(record.handleOrNumber, record.displayName) },
                onOpenDetail = { record -> sheets.showRecord(record) }
            )
        }
    }

    fun refreshHistoryTimestamps() {
        recentRows?.forEach { it.caption.refresh() }
        sheets.refreshRecordTimestamp()
    }

    fun renderContacts(snapshot: ContactsSnapshot?) {
        if (snapshot != null) contactsTab.refresh(snapshot)
        sheets.refreshContacts(snapshot)
    }

    companion object {
        const val TAB_RECENTS = 0
        const val TAB_CONTACTS = 1
        private const val RECENTS_LIMIT = 40
    }
}