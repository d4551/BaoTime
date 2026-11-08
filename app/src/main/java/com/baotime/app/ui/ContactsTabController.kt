package com.baotime.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.doOnDetach
import androidx.core.widget.doAfterTextChanged
import com.baotime.app.R
import com.baotime.app.config.AppConfig
import com.baotime.app.data.ContactsCache
import com.baotime.app.data.ContactsSnapshot
import com.baotime.app.data.DeviceContact
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.theme.BaoTimeTheme

/** Owns the Contacts tab's query, expansion and transient view lifecycle. */
class ContactsTabController(
    private val theme: BaoTimeTheme,
    private val config: AppConfig,
    private val cache: ContactsCache,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onOpenActions: (DeviceContact) -> Unit
) {
    private var query = ""
    private var expanded = false
    private var current: Tab? = null

    fun show(container: LinearLayout) {
        val tab = Tab()
        current = tab
        container.addView(tab.root)
        tab.root.doOnDetach {
            tab.search.removeCallbacks(tab.debounce)
            if (current === tab) current = null
        }
        val existing = cache.snapshot
        if (existing != null) tab.render(existing, true)
        else tab.sections.addView(ContactsTabUi.loadingNote(theme))
        launch {
            val fresh = cache.refresh()
            if (current === tab && tab.root.isAttachedToWindow) tab.render(fresh, true)
        }
    }

    private inner class Tab {
        val root = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        val sections = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        val debounce = Runnable { cache.snapshot?.let { render(it, false) } }
        val search = EditText(theme.context).apply {
            hint = context.getString(R.string.contacts_search_hint)
            setTextColor(theme.colorTextPrimary)
            setHintTextColor(theme.colorTextHint)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
            background = theme.fieldBackground()
            setPadding(0, theme.spaceMd, 0, theme.spaceMd)
            setSingleLine(true)
            setText(query)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            doAfterTextChanged { value ->
                query = value.toString()
                expanded = false
                removeCallbacks(debounce)
                postDelayed(debounce, SEARCH_DEBOUNCE_MS)
            }
        }
        init {
            root.addView(BaoTimeComponents.captionText(theme, theme.context.getString(R.string.contacts_search_label)))
            root.addView(search)
            root.addView(sections)
        }

        fun render(snapshot: ContactsSnapshot, animate: Boolean) {
            if (current !== this) return
            sections.removeAllViews()
            val favorites = snapshot.allContacts.filter { AppConfig.favoriteKey(it.primaryAddress) in config.favorites() }
            ContactsTabUi.renderSections(theme, sections, snapshot.device, snapshot.iMessage,
                snapshot.serverReachable, favorites, query, expanded, onOpenActions,
                { expanded = true; render(snapshot, false) }, animate)
        }
    }

    private companion object { const val SEARCH_DEBOUNCE_MS = 250L }
}
