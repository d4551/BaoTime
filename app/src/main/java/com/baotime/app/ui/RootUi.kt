package com.baotime.app.ui

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baotime.app.R
import com.baotime.app.ui.components.BaoTimeComponents
import com.baotime.app.ui.components.GlassNavigation
import com.baotime.app.ui.theme.BaoTimeTheme

class MainChrome(val rootFrame: FrameLayout, val setupAction: ImageView, val clearHistoryAction: ImageView,
    val headerTitle: TextView, val tabButtons: List<Button>, val tabContentContainer: LinearLayout)

/** Floating navigation above a real scrolling content layer; all geometry uses Android project tokens. */
object RootUi {
    fun build(theme: BaoTimeTheme, onNewLink: () -> Unit, onTabSelected: (Int) -> Unit,
        onClearHistory: () -> Unit, onOpenSetup: () -> Unit
    ): MainChrome {
        val root = FrameLayout(theme.context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.colorBgTop, theme.colorBg))
        }
        val host = FrameLayout(theme.context)
        root.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val scroll = ScrollView(theme.context).apply { clipToPadding = false }
        val rows = LinearLayout(theme.context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val title = TextView(theme.context).apply {
            id = R.id.screen_title
            setTextColor(theme.colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeTitlePx)
            typeface = theme.typefaceEmphasis
            gravity = Gravity.CENTER_VERTICAL
            minHeight = theme.minTouchTarget
            setPadding(0, 0, 0, 0)
        }
        host.addView(title)
        val newCall = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_add, R.string.desc_new_call, iconTint = theme.colorBaoGreen) { onNewLink() })
        host.addView(newCall)
        val clearHistory = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_delete, R.string.action_clear_history, iconTint = theme.colorDanger) { onClearHistory() })
        host.addView(clearHistory)
        val setup = BaoTimeComponents.iconActionButton(theme, BaoTimeComponents.RowAction(
            R.drawable.ic_settings, R.string.desc_open_setup) { onOpenSetup() })
        ViewCompat.setStateDescription(setup, theme.context.getString(R.string.banner_checking))
        host.addView(setup)
        var stackTopActions = false
        val actionWidth = 3 * (theme.minTouchTarget + theme.spaceXs)
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
                    GlassNavigation(theme, scroll, this)
                }.also(tabs::addView)
        }
        tabs.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left - tabs.paddingLeft - tabs.paddingRight
            val required = buttons.size * buttons.maxOf { button ->
                maxOf(theme.minTouchTarget, kotlin.math.ceil(button.paint.measureText(button.text.toString())).toInt() +
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
        host.addView(tabs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        fun insetContent() {
            val topPadding = maxOf(title.height, theme.minTouchTarget) +
                (if (stackTopActions) theme.minTouchTarget else 0) + theme.spaceSm * 2 + theme.spaceMd
            val bottomPadding = tabs.height + theme.spaceMd
            if (scroll.paddingTop != topPadding || scroll.paddingBottom != bottomPadding) scroll.setPadding(0, topPadding, 0, bottomPadding)
        }
        fun layoutTopChrome() {
            title.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                marginEnd = if (stackTopActions) 0 else actionWidth
                topMargin = theme.spaceSm
            }
            newCall.layoutParams = FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget,
                Gravity.TOP or Gravity.END).apply {
                marginEnd = (theme.minTouchTarget + theme.spaceXs) * 2
                topMargin = theme.spaceSm + if (stackTopActions) title.height else 0
            }
            clearHistory.layoutParams = FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget,
                Gravity.TOP or Gravity.END).apply {
                marginEnd = theme.minTouchTarget + theme.spaceXs
                topMargin = theme.spaceSm + if (stackTopActions) title.height else 0
            }
            setup.layoutParams = FrameLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget,
                Gravity.TOP or Gravity.END).apply {
                topMargin = theme.spaceSm + if (stackTopActions) title.height else 0
            }
            insetContent()
        }
        host.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left - host.paddingLeft - host.paddingRight
            val required = kotlin.math.ceil(title.paint.measureText(title.text.toString())).toInt() +
                title.paddingLeft + title.paddingRight + actionWidth
            val stacked = required > available
            if (stackTopActions != stacked) {
                stackTopActions = stacked
                layoutTopChrome()
            }
            insetContent()
        }
        tabs.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> insetContent() }
        layoutTopChrome()
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(theme.spaceLg + safe.left, theme.spaceSm + safe.top,
                theme.spaceLg + safe.right, safe.bottom + tabs.paddingBottom * 2)
            insets
        }
        return MainChrome(root, setup, clearHistory, title, buttons, rows)
    }

    fun styleTabs(theme: BaoTimeTheme, tabButtons: List<Button>, selectedIndex: Int) {
        tabButtons.forEachIndexed { index, button ->
            button.isSelected = index == selectedIndex
            button.setTextColor(theme.controlForeground(if (button.isSelected) theme.colorBaoGreen else theme.colorTextPrimary))
            button.typeface = if (button.isSelected) theme.typefaceEmphasis else theme.typefaceControl
        }
    }
}
