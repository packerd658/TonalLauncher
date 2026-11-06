package dev.tonal.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Bottom sheet listing every widget, grouped by the app that provides it. */
class WidgetPicker(
    private val context: Context,
    private val onPick: (AppWidgetProviderInfo) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val onSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, 0)
    private val onSurfaceVariant = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, 0)
    private val card = MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainerHigh, 0)

    fun show(cellWidth: Int, cellHeight: Int) {
        val dialog = BottomSheetDialog(context)
        val manager = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 32.dp)
        }
        column.addView(TextView(context).apply {
            setText(R.string.widgets)
            textSize = 22f
            setTextColor(onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 16.dp)
        })

        val providers = context.launcherApp.apps.launcherApps.profiles.flatMap { manager.getInstalledProvidersForProfile(it) }
        val groups = providers.groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                label to list
            }
            .sortedBy { it.first.lowercase() }

        if (groups.isEmpty()) {
            column.addView(TextView(context).apply {
                setText(R.string.no_widgets)
                setTextColor(onSurfaceVariant)
                gravity = Gravity.CENTER
            })
        }

        groups.forEach { (label, list) ->
            column.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(onSurfaceVariant)
                setPadding(4.dp, 16.dp, 4.dp, 8.dp)
            })
            list.forEach { info ->
                column.addView(row(info, cellWidth, cellHeight) {
                    dialog.dismiss()
                    onPick(info)
                })
            }
        }

        dialog.setContentView(NestedScrollView(context).apply { addView(column) })
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { worker.shutdown() }
        dialog.show()
    }

    private fun row(info: AppWidgetProviderInfo, cellWidth: Int, cellHeight: Int, click: () -> Unit): LinearLayout {
        val density = context.resources.displayMetrics.densityDpi
        val (sx, sy) = spansFor(info, cellWidth, cellHeight, 5, 6)
        val preview = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        worker.execute {
            val drawable: Drawable? = runCatching {
                info.loadPreviewImage(context, density) ?: info.loadIcon(context, density)
            }.getOrNull()
            main.post { preview.setImageDrawable(drawable) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = 20.dp.toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
            }
            setOnClickListener { click() }
            addView(preview, LinearLayout.LayoutParams(96.dp, 72.dp))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 0, 0, 0)
                addView(TextView(context).apply {
                    text = info.loadLabel(context.packageManager)
                    textSize = 15f
                    setTextColor(onSurface)
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.widget_size, sx, sy)
                    textSize = 13f
                    setTextColor(onSurfaceVariant)
                })
            })
        }
    }

    companion object {
        fun spansFor(info: AppWidgetProviderInfo, cellWidth: Int, cellHeight: Int, cols: Int, rows: Int): Pair<Int, Int> {
            val sx = info.targetCellWidth.takeIf { it > 0 } ?: ceil(info.minWidth.toFloat() / cellWidth).toInt()
            val sy = info.targetCellHeight.takeIf { it > 0 } ?: ceil(info.minHeight.toFloat() / cellHeight).toInt()
            return sx.coerceIn(1, cols) to sy.coerceIn(1, rows - 1)
        }
    }
}
