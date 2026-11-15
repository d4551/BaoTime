package com.baotime.app.ui

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.GlassNavigation
import com.baotime.app.ui.components.WindowSafeArea
import com.baotime.app.ui.theme.BaoTimeTheme
import kotlin.math.ceil

class MainChrome(private val theme: BaoTimeTheme, val rootFrame: FrameLayout, private val setupAction: ImageView,
    internal val home: FrameLayout, private val connectionStatus: TextView, val clearHistoryAction: ImageView, val headerTitle: TextView,
    val tabButtons: List<Button>, val tabContentContainer: LinearLayout) {
    fun updateConnectionState(description: String, tint: Int) {
        setupAction.imageTintList = theme.controlForeground(tint)
        if (connectionStatus.text != description) connectionStatus.text = description
    }
}

/** Floating navigation above a real scrolling content layer; all geometry uses Android project tokens. */
object RootUi {
    fun build(theme: BaoTimeTheme, onNewLink: () -> Unit, onTabSelected: (Int) -> Unit,
        onClearHistory: () -> Unit, onOpenSetup: () -> Unit,
        root: FrameLayout = FrameLayout(theme.context), home: FrameLayout = FrameLayout(theme.context)
    ): MainChrome {
        root.background = theme.chromeBackground()
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        if (home.parent == null) root.addView(home, FrameLayout.LayoutParams(fill, fill))
        val host = FrameLayout(theme.context)
        home.removeAllViews()
        home.addView(host, FrameLayout.LayoutParams(fill, fill))
        val scroll = ScrollView(theme.context).apply { clipToPadding = false }
        val rows = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val top = buildTopBar(theme, onNewLink, onClearHistory, onOpenSetup)
        host.addView(top.bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val tabs = buildTabs(theme, scroll, onTabSelected)
        val buttons = tabs.second
        host.addView(tabs.first, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bindChromeLayout(host, top, tabs.first, scroll, theme)
        WindowSafeArea.observe(host) { view, safe, _ ->
            view.setPadding(theme.spaceLg + safe.left, theme.spaceSm + safe.top,
                theme.spaceLg + safe.right, safe.bottom + tabs.first.paddingBottom * 2)
        }
        return MainChrome(theme, root, top.setup, home, top.status, top.clearHistory, top.title, buttons, rows)
    }

    private class TopBar(val bar: LinearLayout, val titleRow: LinearLayout, val title: TextView,
        val actions: LinearLayout, val status: TextView, val setup: ImageView, val clearHistory: ImageView)

    // One floating top bar owns the title and actions so scrolled rows never collide
    // with bare glyphs.
    private fun buildTopBar(theme: BaoTimeTheme, onNewLink: () -> Unit, onClearHistory: () -> Unit, onOpenSetup: () -> Unit): TopBar {
        val topBar = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(theme.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(theme.context).apply {
            id = R.id.screen_title
            isAccessibilityHeading = true
            setTextColor(theme.colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeTitlePx)
            typeface = theme.typefaceEmphasis
            gravity = Gravity.CENTER_VERTICAL
            minHeight = theme.minTouchTarget
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(title)
        val actions = LinearLayout(theme.context).apply { orientation = LinearLayout.HORIZONTAL }
        val newCall = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_add, R.string.desc_new_call, iconTint = theme.colorBaoGreen) { onNewLink() }).apply { id = R.id.new_call_action }
        actions.addView(newCall)
        val clearHistory = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_delete, R.string.action_clear_history, iconTint = theme.colorDanger) { onClearHistory() }).apply { id = R.id.clear_history_action }
        actions.addView(clearHistory)
        val setup = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_settings, R.string.desc_open_setup) { onOpenSetup() }).apply { id = R.id.setup_action }
        actions.addView(setup)
        titleRow.addView(actions)
        topBar.addView(titleRow)
        val connectionStatus = BaoTimeComponents.captionText(theme, theme.context.getString(R.string.banner_checking)).apply {
            setPadding(0, 0, 0, theme.spaceSm)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        topBar.addView(connectionStatus)
        // The canonical opaque chrome fallback keeps scrolled rows readable under the title and
        // actions; the live sampled glass material stays owned by the tab bar alone.
        topBar.background = theme.surfaceDrawable(0, theme.colorChromeFallback)
        return TopBar(topBar, titleRow, title, actions, connectionStatus, setup, clearHistory)
    }

    private fun buildTabs(theme: BaoTimeTheme, scroll: ScrollView, onTabSelected: (Int) -> Unit): Pair<LinearLayout, List<Button>> {
        val tabs = LinearLayout(theme.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.spaceSm, 0, theme.spaceSm)
        }
        val buttons = listOf(R.string.tab_recents, R.string.tab_contacts).mapIndexed { index, resource ->
            BaoTimeComponents.actionButton(theme, theme.context.getString(resource), theme.colorControlClear,
                theme.colorTextPrimary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (index == 0) 0 else theme.spaceXs
                    marginEnd = if (index == 1) 0 else theme.spaceXs
                }) { onTabSelected(index) }.apply {
                    id = if (index == 0) R.id.tab_recents else R.id.tab_contacts
                    labelTab(this, text)
                    GlassNavigation(theme, scroll, this)
                }.also(tabs::addView)
        }
        tabs.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left - tabs.paddingLeft - tabs.paddingRight
            val required = buttons.size * buttons.maxOf { button ->
                maxOf(theme.minTouchTarget, ceil(button.paint.measureText(button.text.toString())).toInt() +
                    button.paddingLeft + button.paddingRight) + theme.spaceXs * 2
            }
            val stacked = required > available
            val direction = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (tabs.orientation != direction) {
                tabs.orientation = direction
                buttons.forEachIndexed { index, button ->
                    button.layoutParams = LinearLayout.LayoutParams(
                        if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, if (stacked) 0f else 1f).apply {
                        marginStart = if (index == 0) 0 else theme.spaceXs
                        marginEnd = if (index == buttons.lastIndex) 0 else theme.spaceXs
                        if (stacked) { topMargin = theme.spaceXs; bottomMargin = theme.spaceXs }
                    }
                }
            }
        }
        return tabs to buttons
    }

    private fun bindChromeLayout(host: FrameLayout, top: TopBar, tabs: LinearLayout, scroll: ScrollView, theme: BaoTimeTheme) {
        var stackTopActions = false
        val actionWidth = 3 * (theme.minTouchTarget + theme.spaceXs)
        fun insetContent() {
            val topPadding = top.bar.height + theme.spaceMd
            val bottomPadding = tabs.height + theme.spaceMd
            if (scroll.paddingTop != topPadding || scroll.paddingBottom != bottomPadding) scroll.setPadding(0, topPadding, 0, bottomPadding)
        }
        fun layoutTopChrome() {
            if (stackTopActions && top.actions.parent === top.titleRow) {
                top.titleRow.removeView(top.actions)
                top.bar.addView(top.actions, 1, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.END
                    })
            } else if (!stackTopActions && top.actions.parent === top.bar) {
                top.bar.removeView(top.actions)
                top.titleRow.addView(top.actions)
            }
            insetContent()
        }
        host.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left - host.paddingLeft - host.paddingRight
            val required = ceil(top.title.paint.measureText(top.title.text.toString())).toInt() +
                top.title.paddingLeft + top.title.paddingRight + actionWidth
            val stacked = required > available
            if (stackTopActions != stacked) {
                stackTopActions = stacked
                layoutTopChrome()
            }
            insetContent()
        }
        top.bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> insetContent() }
        tabs.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> insetContent() }
        layoutTopChrome()
    }

    fun updateRecentsLabel(button: Button, missed: Int) {
        val context = button.context
        val title = context.getString(R.string.tab_recents)
        labelTab(button, if (missed > 0) context.getString(R.string.tab_recents_badge_fmt, title, missed) else title)
    }

    private fun labelTab(button: Button, label: CharSequence) {
        button.text = label
        button.contentDescription = button.context.getString(R.string.desc_navigation_tab, label)
    }

    fun styleTabs(theme: BaoTimeTheme, tabButtons: List<Button>, selectedIndex: Int) {
        tabButtons.forEachIndexed { index, button ->
            button.isSelected = index == selectedIndex
            button.setTextColor(theme.controlForeground(if (button.isSelected) theme.colorBaoGreen else theme.colorTextPrimary))
            button.typeface = if (button.isSelected) theme.typefaceEmphasis else theme.typefaceControl
        }
    }
}
