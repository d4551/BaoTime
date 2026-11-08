package com.baotime.app.ui.components

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        setTextColor(theme.controlForeground(textColor))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeControlPx)
        typeface = theme.typefaceControl
        background = theme.controlBackground(bgColor)
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

    fun screenNote(theme: BaoTimeTheme, text: String, textColor: Int = theme.colorTextSecondary): TextView =
        TextView(theme.context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
            setPadding(0, theme.spaceXl, 0, 0)
        }

    /** Decorative monogram; the adjacent title conveys the name. */
    fun badgeView(theme: BaoTimeTheme, text: String): TextView = TextView(theme.context).apply {
        this.text = text
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setTextColor(theme.colorTextSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeCaptionPx)
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
        setTextColor(theme.colorTextPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
        typeface = theme.typefaceEmphasis
    }

    fun captionText(theme: BaoTimeTheme, text: String): TextView = TextView(theme.context).apply {
        this.text = text
        setTextColor(theme.colorTextSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeCaptionPx)
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

    fun listRow(theme: BaoTimeTheme, monogram: String, title: String, caption: String,
        actions: List<RowAction>, badgeIconRes: Int? = null, badgeTint: Int? = null,
        badgeDescriptionRes: Int? = null, showChevron: Boolean = false
    ): LinearLayout {
        val row = LinearLayout(theme.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.spaceMd, 0, theme.spaceMd)
            minimumHeight = theme.minTouchTarget
            background = theme.listRowBackground()
        }
        if (badgeIconRes != null && badgeTint != null && badgeDescriptionRes != null) {
            row.addView(iconBadge(theme, badgeIconRes, badgeTint, badgeDescriptionRes))
        } else if (monogram.isNotEmpty()) row.addView(badgeView(theme, monogram))
        val info = infoColumn(theme)
        info.addView(titleText(theme, title))
        info.addView(captionText(theme, caption))
        row.addView(info)
        actions.forEach { row.addView(iconActionButton(theme, it)) }
        if (showChevron) row.addView(ImageView(theme.context).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = theme.controlForeground(theme.colorTextHint)
            layoutParams = LinearLayout.LayoutParams(theme.infoSize, theme.infoSize)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return row
    }

    /** A native actionable target with centered artwork, keyboard focus and native ripple feedback. */
    fun iconActionButton(theme: BaoTimeTheme, action: RowAction): ImageButton = ImageButton(theme.context).apply {
        setImageResource(action.iconRes)
        contentDescription = context.getString(action.descriptionRes)
        background = theme.controlBackground(action.bgColor ?: context.getColor(R.color.color_control_clear), oval = true)
        imageTintList = theme.controlForeground(action.iconTint ?: theme.colorTextPrimary)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(theme.minTouchTarget, theme.minTouchTarget).apply { marginStart = theme.spaceXs }
        scaleType = ImageView.ScaleType.CENTER
        val inset = (theme.minTouchTarget - theme.iconSize) / 2
        setPadding(inset, inset, inset, inset)
        setOnClickListener { action.onClick(it) }
    }

    fun animateRowIn(view: View, index: Int) {
        BaoTimeMotion.rowIn(view, index, view.resources.getDimension(R.dimen.space_md))
    }

    fun actionRow(theme: BaoTimeTheme, iconRes: Int, labelRes: Int,
        contentColor: Int = theme.colorTextPrimary, onClick: () -> Unit
    ): LinearLayout = LinearLayout(theme.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.minTouchTarget
        setPadding(theme.spaceMd, theme.spaceSm, theme.spaceMd, theme.spaceSm)
        background = theme.controlBackground(theme.colorControlClear)
        contentDescription = context.getString(labelRes)
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = theme.spaceXs }
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = theme.controlForeground(contentColor)
            layoutParams = LinearLayout.LayoutParams(theme.iconSize, theme.iconSize)
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(TextView(context).apply {
            text = context.getString(labelRes)
            isDuplicateParentStateEnabled = true
            setTextColor(theme.controlForeground(contentColor))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeBodyPx)
            setPadding(theme.spaceMd, 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
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
        val icon = requireNotNull(context.getDrawable(iconRes)).mutate()
        icon.setTintList(theme.controlForeground(iconTint))
        val circle = android.graphics.drawable.LayerDrawable(arrayOf(theme.controlBackground(bgColor, oval = true), icon)).apply {
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(1, theme.callControlIconSize, theme.callControlIconSize)
            setBounds(0, 0, theme.callControlSize, theme.callControlSize)
        }
        setCompoundDrawables(null, circle, null, null)
        compoundDrawablePadding = theme.spaceSm
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.textSizeControlPx)
    }
}
