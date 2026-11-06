package dev.tonal.launcher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-layer overlay with handles around one widget. Handles move freely and the widget snaps to whole
 * cells on release; a touch anywhere away from a handle dismisses it.
 */
@SuppressLint("ViewConstructor")
class ResizeFrame(
    context: Context,
    private val item: Item,
    private val cell: CellLayout,
    info: AppWidgetProviderInfo,
    private val onResized: () -> Unit,
    private val onDismiss: () -> Unit,
) : View(context) {
    private val bounds = RectF()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp.toFloat()
        color = context.getColor(android.R.color.system_accent1_200)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var activeHandle = NONE
    private val canResizeX = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    private val canResizeY = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    private val minSpanX = spansFor(info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth, cell.cellWidth)
    private val minSpanY = spansFor(info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight, cell.cellHeight)

    init {
        syncBounds()
    }

    private fun spansFor(pixels: Int, cellSize: Int) = max(1, (pixels.toFloat() / cellSize).let { kotlin.math.ceil(it).toInt() })

    private fun cellOrigin(): Pair<Float, Float> {
        val location = IntArray(2)
        cell.getLocationInWindow(location)
        return location[0].toFloat() to location[1].toFloat()
    }

    private fun syncBounds() {
        val (ox, oy) = cellOrigin()
        bounds.set(
            ox + item.x * cell.cellWidth, oy + item.y * cell.cellHeight,
            ox + (item.x + item.spanX) * cell.cellWidth, oy + (item.y + item.spanY) * cell.cellHeight,
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(bounds, 16.dp.toFloat(), 16.dp.toFloat(), outline)
        val radius = 8.dp.toFloat()
        if (canResizeX) {
            canvas.drawCircle(bounds.left, bounds.centerY(), radius, handlePaint)
            canvas.drawCircle(bounds.right, bounds.centerY(), radius, handlePaint)
        }
        if (canResizeY) {
            canvas.drawCircle(bounds.centerX(), bounds.top, radius, handlePaint)
            canvas.drawCircle(bounds.centerX(), bounds.bottom, radius, handlePaint)
        }
    }

    private fun handleAt(x: Float, y: Float): Int {
        val reach = 32.dp
        return when {
            canResizeX && abs(x - bounds.left) < reach && abs(y - bounds.centerY()) < reach -> LEFT
            canResizeX && abs(x - bounds.right) < reach && abs(y - bounds.centerY()) < reach -> RIGHT
            canResizeY && abs(x - bounds.centerX()) < reach && abs(y - bounds.top) < reach -> TOP
            canResizeY && abs(x - bounds.centerX()) < reach && abs(y - bounds.bottom) < reach -> BOTTOM
            else -> NONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = handleAt(ev.x, ev.y)
                if (activeHandle == NONE) onDismiss()
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeHandle) {
                    LEFT -> bounds.left = ev.x.coerceAtMost(bounds.right - cell.cellWidth / 2f)
                    RIGHT -> bounds.right = ev.x.coerceAtLeast(bounds.left + cell.cellWidth / 2f)
                    TOP -> bounds.top = ev.y.coerceAtMost(bounds.bottom - cell.cellHeight / 2f)
                    BOTTOM -> bounds.bottom = ev.y.coerceAtLeast(bounds.top + cell.cellHeight / 2f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle != NONE) commit()
                activeHandle = NONE
            }
        }
        return true
    }

    private fun commit() {
        val (ox, oy) = cellOrigin()
        val left = ((bounds.left - ox) / cell.cellWidth).roundToInt().coerceAtLeast(0)
        val top = ((bounds.top - oy) / cell.cellHeight).roundToInt().coerceAtLeast(0)
        val right = ((bounds.right - ox) / cell.cellWidth).roundToInt().coerceAtMost(cell.cols)
        val bottom = ((bounds.bottom - oy) / cell.cellHeight).roundToInt().coerceAtMost(cell.rows)
        val sx = right - left
        val sy = bottom - top
        val model = context.launcherApp.model
        val fits = sx >= minSpanX.coerceAtMost(item.spanX) && sy >= minSpanY.coerceAtMost(item.spanY) &&
            model.isFree(item.container, item.page, left, top, sx, sy, cell.cols, cell.rows, item)
        if (fits) {
            item.x = left
            item.y = top
            item.spanX = sx
            item.spanY = sy
            model.save()
            onResized()
        } else {
            syncBounds()
            invalidate()
        }
    }

    private companion object {
        const val NONE = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val TOP = 3
        const val BOTTOM = 4
    }
}
