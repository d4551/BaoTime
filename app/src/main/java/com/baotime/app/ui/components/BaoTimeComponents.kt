package com.baotime.app.ui.components

import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.baotime.app.R
import com.baotime.app.ui.theme.BaoTimeMotion
import com.baotime.app.ui.theme.BaoTimeTheme

/** Shared native view primitives. Theme owns all dimensions, materials and interaction states. */
object BaoTimeComponents {
    fun actionButton(theme: BaoTimeTheme, label: String, bgColor: Int = theme.colorBaoGreen,
        textColor: Int = theme.colorOnAccent, params: LinearLayout.LayoutParams? = null,
        compact: Boolean = false, onClick: (Button) -> Unit
    ): Button = Button(theme.context).apply {
        text = label
        isAllCaps = false
        styleActionButton(theme, this, bgColor, textColor)
        typeface = theme.typefaceControl
        stateListAnimator = null
        minHeight = theme.minTouchTarget
        minWidth = theme.minTouchTarget
        minimumHeight = theme.minTouchTarget
        minimumWidth = theme.minTouchTarget
        val horizontal = if (compact) theme.spaceSm else theme.spaceMd
        setPadding(horizontal, theme.spaceSm, horizontal, theme.spaceSm)
        layoutParams = params ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick(this) }
    }

    internal fun styleActionButton(theme: BaoTimeTheme, button: Button, fill: Int = theme.colorBaoGreen,
        text: Int = theme.colorOnAccent) {
        button.background = theme.controlBackground(fill)
        button.setTextColor(theme.controlForeground(text))
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeControlPx)
    }

    /** Canonical single-line text field: theme-owned typography, underline focus state and touch height. */
    fun textField(theme: BaoTimeTheme, hintRes: Int, value: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    ): EditText = EditText(theme.context).apply {
        id = View.generateViewId()
        isSaveEnabled = false
        setHint(hintRes)
        styleTextField(theme, this)
        setPadding(0, theme.spaceMd, 0, theme.spaceMd)
        setSingleLine(true)
        this.inputType = inputType
        setText(value)
        minHeight = theme.minTouchTarget
        minimumHeight = theme.minTouchTarget
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    internal fun styleTextField(theme: BaoTimeTheme, field: EditText) {
        field.setHintTextColor(theme.colorTextHint)
        field.setTextColor(theme.colorTextPrimary)
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
        field.background = theme.fieldBackground()
    }

    fun screenNote(theme: BaoTimeTheme, text: String, textColor: Int = theme.colorTextSecondary): TextView =
        TextView(theme.context).apply {
            this.text = text
            styleTitle(theme, this, textColor)
            setPadding(0, theme.spaceXl, 0, 0)
        }

    /** Decorative monogram; the adjacent title conveys the name. */
    fun badgeView(theme: BaoTimeTheme, text: String): TextView = TextView(theme.context).apply {
        this.text = text
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        styleCaption(theme, this)
        typeface = theme.typefaceEmphasis
        gravity = Gravity.CENTER
        minimumWidth = theme.infoSize
        minimumHeight = theme.infoSize
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = theme.spaceMd }
    }

    fun infoColumn(theme: BaoTimeTheme): LinearLayout = LinearLayout(theme.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    fun titleText(theme: BaoTimeTheme, text: String): TextView = TextView(theme.context).apply {
        this.text = text
        styleTitle(theme, this)
        typeface = theme.typefaceEmphasis
    }

    fun captionText(theme: BaoTimeTheme, text: String): TextView = TextView(theme.context).apply {
        this.text = text
        styleCaption(theme, this)
    }

    internal fun styleTitle(theme: BaoTimeTheme, view: TextView, color: Int = theme.colorTextPrimary,
        size: Float = theme.textSizeBodyPx) {
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    internal fun styleCaption(theme: BaoTimeTheme, view: TextView, color: Int = theme.colorTextSecondary) {
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeCaptionPx)
    }

    internal fun fieldLabel(theme: BaoTimeTheme, @StringRes text: Int, field: EditText): TextView =
        captionText(theme, theme.context.getString(text)).apply { labelFor = field.id }

    internal fun actionFailure(theme: BaoTimeTheme, message: Int): TextView =
        captionText(theme, theme.context.getString(message)).apply {
            setTextColor(theme.colorDanger)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }

    class RowAction(val iconRes: Int, val descriptionRes: Int, val bgColor: Int? = null,
        val iconTint: Int? = null, val onClick: (View) -> Unit)

    /** Informational direction glyph: its visual size is independent of action hit targets. */
    fun iconBadge(theme: BaoTimeTheme, iconRes: Int, tint: Int, descriptionRes: Int): ImageView =
        ImageView(theme.context).apply {
            setImageResource(iconRes)
            imageTintList = theme.controlForeground(tint)
            contentDescription = context.getString(descriptionRes)
            layoutParams = LinearLayout.LayoutParams(theme.infoSize, theme.infoSize).apply { marginEnd = theme.spaceMd }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

    internal class ListRow(
        val root: LinearLayout,
        private val monogram: TextView?,
        private val title: TextView,
        private val caption: TextView,
        private val chevron: ImageView?
    ) {
        fun updateTheme(theme: BaoTimeTheme) {
            root.background = theme.listRowBackground()
            monogram?.let { styleCaption(theme, it) }
            styleTitle(theme, title)
            styleCaption(theme, caption)
            chevron?.imageTintList = theme.controlForeground(theme.colorTextHint)
        }
    }

    internal fun listRow(theme: BaoTimeTheme, monogram: String, title: String, caption: TextView,
        actions: List<RowAction>, badgeIconRes: Int? = null, badgeTint: Int? = null,
        badgeDescriptionRes: Int? = null, showChevron: Boolean = false
    ): ListRow {
        val row = LinearLayout(theme.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.spaceMd, 0, theme.spaceMd)
            minimumHeight = theme.minTouchTarget
            background = theme.listRowBackground()
        }
        val monogramView = if (badgeIconRes != null && badgeTint != null && badgeDescriptionRes != null) {
            row.addView(iconBadge(theme, badgeIconRes, badgeTint, badgeDescriptionRes))
            null
        } else if (monogram.isNotEmpty()) badgeView(theme, monogram).also(row::addView)
        else null
        val info = infoColumn(theme)
        val heading = titleText(theme, title)
        info.addView(heading)
        info.addView(caption)
        row.addView(info)
        actions.forEach { row.addView(iconActionButton(theme, it)) }
        val chevron = if (showChevron) ImageView(theme.context).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = theme.controlForeground(theme.colorTextHint)
            layoutParams = LinearLayout.LayoutParams(theme.infoSize, theme.infoSize)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also(row::addView) else null
        return ListRow(row, monogramView, heading, caption, chevron)
    }

    /** A native actionable target with centered artwork, keyboard focus and native ripple feedback. */
    fun iconActionButton(theme: BaoTimeTheme, action: RowAction): ImageButton = ImageButton(theme.context).apply {
        setImageResource(action.iconRes)
        contentDescription = context.getString(action.descriptionRes)
        styleIconAction(theme, this, action.bgColor ?: theme.colorControlClear, action.iconTint ?: theme.colorTextPrimary)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget).apply { marginStart = theme.spaceXs }
        scaleType = ImageView.ScaleType.CENTER
        val inset = (theme.minTouchTarget - theme.iconSize) / 2
        setPadding(inset, inset, inset, inset)
        setOnClickListener { action.onClick(it) }
    }

    internal fun styleIconAction(theme: BaoTimeTheme, button: ImageButton, fill: Int, tint: Int) {
        button.background = theme.controlBackground(fill, oval = true)
        button.imageTintList = theme.controlForeground(tint)
    }

    fun animateRowIn(view: View, index: Int) {
        BaoTimeMotion.rowIn(view, index, view.resources.getDimension(R.dimen.space_md))
    }

    internal class ActionRow(
        theme: BaoTimeTheme,
        iconRes: Int,
        labelRes: Int,
        private val destructive: Boolean = false,
        onClick: () -> Unit
    ) {
        private val icon = ImageView(theme.context).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(theme.iconSize, theme.iconSize)
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val label = TextView(theme.context).apply {
            setText(labelRes)
            isDuplicateParentStateEnabled = true
            setPadding(theme.spaceMd, 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val view = LinearLayout(theme.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = theme.minTouchTarget
            setPadding(theme.spaceMd, theme.spaceSm, theme.spaceMd, theme.spaceSm)
            contentDescription = context.getString(labelRes)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.spaceXs }
            setOnClickListener { onClick() }
            addView(icon)
            addView(label)
        }

        init {
            updateTheme(theme)
        }

        fun updateTheme(theme: BaoTimeTheme) {
            val foreground = theme.controlForeground(if (destructive) theme.colorDanger else theme.colorTextPrimary)
            view.background = theme.controlBackground(theme.colorControlClear)
            icon.imageTintList = foreground
            label.setTextColor(foreground)
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
        }
    }

    /** One accessible native target owns the circular call action and its separate caption. */
    fun callControlButton(theme: BaoTimeTheme, iconRes: Int, captionRes: Int, descriptionRes: Int,
        bgColor: Int = theme.colorSurfaceHigh, captionColor: Int = theme.colorCallText,
        iconTint: Int = theme.colorTextPrimary, onClick: () -> Unit
    ): Button = actionButton(theme, theme.context.getString(captionRes), theme.colorControlClear, captionColor,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = theme.spaceMd
            marginEnd = theme.spaceMd
        }) { onClick() }.apply {
        contentDescription = context.getString(descriptionRes)
        minWidth = theme.callControlSize + theme.spaceMd * 2
        minimumWidth = minWidth
        styleCallControl(theme, this, iconRes, bgColor, captionColor, iconTint)
        compoundDrawablePadding = theme.spaceSm
    }

    internal fun styleCallControl(theme: BaoTimeTheme, button: Button, iconRes: Int, fill: Int,
        captionColor: Int, iconTint: Int) {
        styleActionButton(theme, button, theme.colorControlClear, captionColor)
        val icon = requireNotNull(button.context.getDrawable(iconRes)).mutate()
        icon.setTintList(theme.controlForeground(iconTint))
        val circle = LayerDrawable(arrayOf(theme.controlBackground(fill, oval = true), icon)).apply {
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(1, theme.callControlIconSize, theme.callControlIconSize)
            setBounds(0, 0, theme.callControlSize, theme.callControlSize)
        }
        button.setCompoundDrawables(null, circle, null, null)
    }
}
