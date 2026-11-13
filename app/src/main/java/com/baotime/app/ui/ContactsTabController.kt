package com.baotime.app.ui

import android.text.InputType
import android.widget.LinearLayout
import androidx.core.view.doOnDetach
import androidx.core.widget.doAfterTextChanged
import com.baotime.app.R
import com.baotime.app.config.AppConfig
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsOutcome
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.DeviceContact
import com.baotime.app.data.MacContactsState
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** Owns the Contacts tab's query, expansion and transient view lifecycle. */
class ContactsTabController(
    theme: BaoTimeTheme,
    private val config: AppConfig,
    private val cache: ContactsCache,
    private val onOpenActions: (DeviceContact, Long?) -> Unit
) {
    private var theme = theme
    private var query = ""
    private var expanded = false
    private var current: Tab? = null
    private var selection: Pair<Int, Int>? = null
    val isShowing: Boolean get() = current != null

    internal fun updateTheme(theme: BaoTimeTheme) {
        this.theme = theme
    }

    fun show(container: LinearLayout) {
        val tab = Tab()
        current = tab
        container.addView(tab.root)
        tab.root.doOnDetach {
            tab.search.removeCallbacks(tab.debounce)
            if (current === tab) {
                selection = tab.search.selectionStart to tab.search.selectionEnd
                current = null
            }
        }
        cache.snapshot?.let { tab.render(it, true) }
    }

    fun refresh(snapshot: ContactsSnapshot) {
        current?.render(snapshot, true)
    }

    fun refreshFavorites() {
        val snapshot = cache.snapshot ?: return
        current?.render(snapshot, false)
    }

    private inner class Tab {
        val root = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        private val permissionRecovery = ContactsTabUi.PermissionRecovery(theme)
        private val macStatus = ContactsTabUi.Status(theme)
        private val empty = ContactsTabUi.Status(theme)
        private val favorites = ContactsTabUi.Section(theme, R.string.contacts_favorites_header, onOpenActions, ::expand)
        private val mac = ContactsTabUi.Section(theme, R.string.contacts_imessage_header, onOpenActions, ::expand)
        private val device = ContactsTabUi.Section(theme, R.string.contacts_device_header, onOpenActions, ::expand)
        val debounce = Runnable { cache.snapshot?.let { render(it, false) } }
        val search = BaoTimeComponents.textField(theme, R.string.contacts_search_hint, query,
            InputType.TYPE_CLASS_TEXT).apply {
            id = R.id.contacts_search
            selection?.let { (start, end) ->
                if (start >= 0 && end >= 0) setSelection(start.coerceAtMost(length()), end.coerceAtMost(length()))
            }
            doAfterTextChanged { value ->
                query = value.toString()
                expanded = false
                removeCallbacks(debounce)
                postDelayed(debounce, SEARCH_DEBOUNCE_MS)
            }
        }
        private var displayed: ContactsSnapshot? = null
        init {
            root.addView(BaoTimeComponents.fieldLabel(theme, R.string.contacts_search_label, search))
            root.addView(search)
            root.addView(favorites.root)
            root.addView(macStatus.view)
            root.addView(mac.root)
            root.addView(device.root)
            root.addView(empty.view)
            root.addView(permissionRecovery.root)
            macStatus.show(ContactsTabUi.macStatus(null))
        }

        private fun expand() {
            expanded = true
            cache.snapshot?.let { render(it, false) }
        }

        fun render(snapshot: ContactsSnapshot, animate: Boolean) {
            if (current !== this) return
            val latest = cache.current(snapshot)
            if (animate && displayed == latest) return
            displayed = latest
            val denied = latest.device == ContactsOutcome.Denied
            permissionRecovery.update(denied)
            val matches = latest.search(query)
            val favoriteKeys = config.favorites()
            val favoriteMatches = matches.all.filter { AppConfig.favoriteKey(it.contact.primaryAddress) in favoriteKeys }
            val animationStart = 0.takeIf { animate }
            val favoritesCount = favorites.render(favoriteMatches, expanded, animationStart)
            macStatus.show(ContactsTabUi.macStatus(latest.macState))
            val macCount = mac.render(matches.iMessage, expanded, animationStart?.plus(favoritesCount))
            device.render(matches.device, expanded, animationStart?.plus(favoritesCount + macCount))
            val noMatches = !denied && latest.macState != MacContactsState.Loading && matches.all.isEmpty()
            empty.show(ContactsTabUi.emptyStatus(query, deviceOnly = latest.macState == MacContactsState.Unavailable)
                .takeIf { noMatches })
        }
    }

    private companion object { const val SEARCH_DEBOUNCE_MS = 250L }
}
