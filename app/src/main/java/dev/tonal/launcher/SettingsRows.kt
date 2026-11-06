package dev.tonal.launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * Rows draw as cards that join into one rounded block per category: big corners at the block's
 * ends, small ones between rows, with a ripple clipped to the same shape.
 */
private fun styleRow(holder: PreferenceViewHolder, preference: Preference) {
    val context = preference.context
    val group = preference.parent ?: return
    val visible = (0 until group.preferenceCount).map { group.getPreference(it) }.filter { it.isVisible }
    val index = visible.indexOf(preference)
    val outer = 24.dp.toFloat()
    val inner = 4.dp.toFloat()
    val top = if (index == 0) outer else inner
    val bottom = if (index == visible.lastIndex) outer else inner
    val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)

    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val card = MaterialColors.getColor(
        context, if (night) MaterialR.attr.colorSurfaceContainerHigh else MaterialR.attr.colorSurfaceBright, Color.WHITE,
    )
    val shape = GradientDrawable().apply {
        setColor(card)
        cornerRadii = radii
    }
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadii = radii
    }
    val ripple = MaterialColors.getColor(context, android.R.attr.colorControlHighlight, Color.LTGRAY)
    holder.itemView.background = RippleDrawable(ColorStateList.valueOf(ripple), shape, mask)
    (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
        marginStart = 16.dp
        marginEnd = 16.dp
        topMargin = if (index == 0) 0 else 2.dp
    }
    holder.itemView.minimumHeight = 72.dp
}

private fun tintedIcon(context: Context, res: Int, color: Int? = null) = context.getDrawable(res)?.mutate()?.apply {
    setTint(color ?: MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLACK))
}

class RowPreference(context: Context, icon: Int? = null) : Preference(context) {
    init {
        icon?.let { this.icon = tintedIcon(context, it) }
    }

    fun setIconTint(res: Int, color: Int) {
        icon = tintedIcon(context, res, color)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        styleRow(holder, this)
    }
}

class RowSwitch(context: Context, icon: Int) : SwitchPreferenceCompat(context) {
    init {
        this.icon = tintedIcon(context, icon)
        widgetLayoutResource = R.layout.pref_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        styleRow(holder, this)
    }
}

class RowEditText(context: Context, icon: Int) : EditTextPreference(context) {
    init {
        this.icon = tintedIcon(context, icon)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        styleRow(holder, this)
    }
}

/** Section title lined up with the text inside the cards below it. */
class SectionHeader(context: Context, title: Int) : PreferenceCategory(context) {
    init {
        setTitle(title)
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            marginStart = 16.dp
            marginEnd = 16.dp
        }
        holder.itemView.setPadding(holder.itemView.paddingLeft, 20.dp, holder.itemView.paddingRight, 8.dp)
    }
}

/** A small home screen in the wallpaper's colors, drawn with the current icon settings. */
class PreviewPreference(context: Context) : Preference(context) {
    init {
        layoutResource = R.layout.pref_preview
        isSelectable = false
    }

    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val frame = holder.itemView as FrameLayout
        frame.removeAllViews()
        frame.addView(
            build(context),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 220.dp).apply { setMargins(16.dp, 4.dp, 16.dp, 4.dp) },
        )
    }

    private fun build(context: Context): View {
        val app = context.launcherApp
        val colors: WallpaperColors? = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val fallback = MaterialColors.getColor(context, MaterialR.attr.colorPrimaryContainer, Color.GRAY)
        val primary = colors?.primaryColor?.toArgb() ?: fallback
        val secondary = colors?.secondaryColor?.toArgb() ?: primary
        val darkText = colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        val textColor = if (darkText) 0xDE000000.toInt() else Color.WHITE

        val dock = app.model.items.filter { it.container == CONTAINER_DOCK }.sortedBy { it.x }.mapNotNull { app.apps.find(it.key) }
        val entries = (dock + app.apps.apps).distinctBy { it.key }.take(5)

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 16.dp)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(primary, secondary)).apply {
                cornerRadius = 28.dp.toFloat()
            }
            clipToOutline = true
            addView(TextClock(context).apply {
                format12Hour = "EEEE, MMM d"
                format24Hour = "EEEE, MMM d"
                textSize = 18f
                setTextColor(textColor)
                if (!darkText) setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            })
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                entries.forEach { entry ->
                    addView(ImageView(context).apply {
                        setImageBitmap(app.icons.icon(entry))
                        contentDescription = entry.label
                    }, LinearLayout.LayoutParams(0, 48.dp, 1f))
                }
            })
            if (app.prefs.searchBar) {
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainerHigh, Color.WHITE))
                        cornerRadius = 20.dp.toFloat()
                    }
                    addView(ImageView(context).apply {
                        setImageDrawable(tintedIcon(context, R.drawable.ic_search,
                            MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)))
                        scaleType = ImageView.ScaleType.CENTER
                    }, LinearLayout.LayoutParams(40.dp, 40.dp))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp).apply { topMargin = 14.dp })
            }
        }
    }
}
