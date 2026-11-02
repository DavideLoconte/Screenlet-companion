package com.davideloconte.screenlet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.roundToInt
import com.google.android.material.R as M

/**
 * Programmatic Material 3 layout helpers, typography scales, dynamic color resolution, and segmented lists.
 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

/** Resolves a theme color attribute from the active contextual theme. */
fun Context.themeColor(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

private fun Context.resourceOf(attr: Int): Int =
    TypedValue().also { theme.resolveAttribute(attr, it, true) }.resourceId

/** Constructs a TextView styled according to the specified typography appearance and theme color. */
fun Context.label(text: CharSequence?, appearance: Int, color: Int = M.attr.colorOnSurface) = TextView(this).apply {
    setTextAppearance(resourceOf(appearance))
    setTextColor(themeColor(color))
    this.text = text
}

enum class Look(val style: Int) {
    FILLED(M.attr.materialButtonStyle),
    TONAL(M.attr.materialButtonTonalStyle),
    TEXT(R.attr.screenletTextButtonStyle),
}

fun Context.button(label: CharSequence, look: Look = Look.FILLED, icon: Int = 0, onClick: (View) -> Unit) =
    MaterialButton(this, null, look.style).apply {
        text = label
        isSingleLine = true
        if (icon != 0) {
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START  // beside the label, however wide
        }
        setOnClickListener(onClick)
    }

/** Constructs an icon-only MaterialButton configured with accessibility labels and tooltips. */
fun Context.iconButton(icon: Int, description: Int, style: Int = M.attr.materialIconButtonStyle,
                       onClick: (View) -> Unit) =
    MaterialButton(this, null, style).apply {
        setIconResource(icon)
        contentDescription = getString(description)
        tooltipText = contentDescription
        setOnClickListener(onClick)
    }

/** Generates a MaterialShapeDrawable with rounded corners filled with the theme surface color. */
fun Context.surface(color: Int = M.attr.colorSurfaceContainer, radius: Float = dp(28f)) =
    MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(radius).build()).apply {
        fillColor = ColorStateList.valueOf(themeColor(color))
    }

/** Constructs an ImageView for a drawable resource tinted according to theme colors. */
fun Context.icon(drawable: Int, color: Int = M.attr.colorOnSurfaceVariant, size: Int = 24) = ImageView(this).apply {
    setImageResource(drawable)
    imageTintList = ColorStateList.valueOf(themeColor(color))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

/** Constructs a styled section title header view. */
fun Context.sectionTitle(text: CharSequence) = label(text, M.attr.textAppearanceTitleSmall, androidx.appcompat.R.attr.colorPrimary).apply {
    setPadding(dp(16), dp(28), dp(16), dp(10))
}

/** Resolves the platform selectable item background ripple drawable. */
fun Context.ripple(borderless: Boolean = false) = AppCompatResources.getDrawable(this, resourceOf(
    if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground))

/** Constructs a standard list row container with leading icon, labels, and trailing controls. */
fun Context.row(icon: Int, title: CharSequence, supporting: CharSequence? = null, trailing: View? = null,
                oneLine: Boolean = false) =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        if (icon != 0) addView(icon(icon).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, M.attr.textAppearanceBodyLarge).apply {
                if (oneLine) {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
            })
            if (supporting != null) addView(label(supporting, M.attr.textAppearanceBodyMedium, M.attr.colorOnSurfaceVariant))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
    }

/** Assembles a list of rows into a segmented Material card container. */
fun Context.group(rows: List<Row>) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    rows.forEachIndexed { i, (content, onClick) ->
        val card = ListItemCardView(context, null, M.attr.listItemCardViewSegmentedStyle).apply {
            addView(content)
            if (onClick != null) setOnClickListener(onClick) else isClickable = false
        }
        val item = ListItemLayout(context).apply {
            addView(card)
            updateAppearance(i, rows.size)
        }
        addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(2) })
    }
}

data class Row(val view: View, val onClick: ((View) -> Unit)? = null)


/** Tracks whether any application activity currently resides in the foreground. */
object Front {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    val shown get() = count.get() > 0
    fun started() = count.incrementAndGet()
    fun stopped() = count.decrementAndGet()
}

/** Computes insets occupied by system bars and display cutouts. */
fun systemBars(insets: android.view.WindowInsets): android.graphics.Insets =
    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
