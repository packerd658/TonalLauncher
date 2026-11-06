package dev.tonal.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/** Icon above a one-line label, centered as a block in whatever cell it's given. */
class IconView(context: Context) : View(context) {
    var item: Item? = null
    var entry: AppEntry? = null
    var icon: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var label: String = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }
    var showLabel = true
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val iconRect = RectF()
    var iconSize = 52.dp

    init {
        isClickable = true
        isLongClickable = true
    }

    fun setLabelStyle(color: Int, shadow: Boolean) {
        textPaint.color = color
        if (shadow) textPaint.setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) else textPaint.clearShadowLayer()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.92f else 1f).scaleY(if (pressed) 0.92f else 1f).setDuration(100).start()
    }

    /** Where the icon sits within this view; used to line up popups and drag shadows. */
    fun iconBounds(): RectF {
        computeIconRect()
        return iconRect
    }

    private fun computeIconRect() {
        val size = iconSize.coerceAtMost(width).coerceAtMost(height).toFloat()
        val textHeight = if (showLabel) 6.dp + textPaint.fontSpacing else 0f
        val top = ((height - size - textHeight) / 2f).coerceAtLeast(0f)
        val left = (width - size) / 2f
        iconRect.set(left, top, left + size, top + size)
    }

    override fun onDraw(canvas: Canvas) {
        computeIconRect()
        icon?.let { canvas.drawBitmap(it, null, iconRect, bitmapPaint) }
        if (showLabel) {
            val text = TextUtils.ellipsize(label, textPaint, width - 4.dp.toFloat(), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, width / 2f, iconRect.bottom + 6.dp - textPaint.ascent(), textPaint)
        }
    }
}

/** A grid. Children carry their position in LayoutParams; occupancy rules live in LauncherModel. */
class CellLayout(context: Context, val cols: Int, val rows: Int, val container: Long, val page: Int) : ViewGroup(context) {
    class Params(val x: Int, val y: Int, val spanX: Int, val spanY: Int) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    val cellWidth get() = width / cols
    val cellHeight get() = height / rows
    private var highlight: RectF? = null
    private var highlightValid = true
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        isLongClickable = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        val cw = w / cols
        val ch = h / rows
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val p = child.layoutParams as Params
            child.measure(
                MeasureSpec.makeMeasureSpec(cw * p.spanX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * p.spanY, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l) / cols
        val ch = (b - t) / rows
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val p = child.layoutParams as Params
            child.layout(p.x * cw, p.y * ch, (p.x + p.spanX) * cw, (p.y + p.spanY) * ch)
        }
    }

    fun setHighlight(x: Int, y: Int, sx: Int, sy: Int, valid: Boolean) {
        highlight = RectF(
            x * cellWidth.toFloat(), y * cellHeight.toFloat(),
            (x + sx) * cellWidth.toFloat(), (y + sy) * cellHeight.toFloat(),
        )
        highlightValid = valid
        invalidate()
    }

    fun clearHighlight() {
        highlight = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        highlight?.let {
            highlightPaint.color = if (highlightValid) 0x44FFFFFF else 0x44FF5252
            val inset = 4.dp.toFloat()
            canvas.drawRoundRect(
                it.left + inset, it.top + inset, it.right - inset, it.bottom - inset,
                18.dp.toFloat(), 18.dp.toFloat(), highlightPaint,
            )
        }
    }
}

/** Dots for the home pages. */
class PageIndicator(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var pageCount = 1
        set(value) {
            field = value
            invalidate()
        }
    var position = 0f
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        if (pageCount < 2) return
        val spacing = 14.dp.toFloat()
        val radius = 3.dp.toFloat()
        val start = width / 2f - (pageCount - 1) * spacing / 2f
        for (i in 0 until pageCount) {
            val closeness = (1f - abs(position - i)).coerceIn(0f, 1f)
            paint.alpha = (90 + 165 * closeness).toInt()
            canvas.drawCircle(start + i * spacing, height / 2f, radius, paint)
        }
    }
}

class LauncherWidgetHost(context: Context, id: Int) : AppWidgetHost(context, id) {
    var onLongPress: ((LauncherWidgetView) -> Unit)? = null

    override fun onCreateView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo?): AppWidgetHostView =
        LauncherWidgetView(context, this)
}

/** Widgets swallow touches, so long press has to be spotted while intercepting rather than via a listener. */
class LauncherWidgetView(context: Context, private val host: LauncherWidgetHost) : AppWidgetHostView(context) {
    var item: Item? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private val longPress = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host.onLongPress?.invoke(this)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val density = resources.displayMetrics.density
        val wd = (w / density).toInt()
        val hd = (h / density).toInt()
        post { updateAppWidgetSize(Bundle(), wd, hd, wd, hd) }
    }
}

/** Finds whether something under this point can still scroll, so home gestures don't steal it. */
fun canScrollAt(view: View, x: Float, y: Float, vertical: Boolean, direction: Int): Boolean {
    if (view.visibility != View.VISIBLE) return false
    if (if (vertical) view.canScrollVertically(direction) else view.canScrollHorizontally(direction)) return true
    if (view is ViewGroup) {
        for (i in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(i)
            val cx = x + view.scrollX - child.left
            val cy = y + view.scrollY - child.top
            if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height &&
                canScrollAt(child, cx, cy, vertical, direction)
            ) return true
        }
    }
    return false
}

/** Root view. Owns the touch stream while dragging and dismisses transient UI on outside taps. */
class DragLayer(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    class Transient(val view: View, val inside: (Float, Float) -> Boolean, val dismiss: () -> Unit)

    var drag: DragController? = null
    var lastX = 0f
        private set
    var lastY = 0f
        private set
    private var childrenCancelled = false
    private val transients = ArrayList<Transient>()

    val hasTransient get() = transients.isNotEmpty()

    fun pushTransient(view: View, inside: (Float, Float) -> Boolean, dismiss: () -> Unit) {
        transients += Transient(view, inside, dismiss)
    }

    fun dismissTop() {
        transients.removeLastOrNull()?.dismiss?.invoke()
    }

    fun dismissAll() {
        while (transients.isNotEmpty()) dismissTop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.x
        lastY = ev.y
        val controller = drag
        if (controller != null && controller.isTracking) {
            if (!childrenCancelled) {
                childrenCancelled = true
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            controller.onTouch(ev)
            return true
        }
        childrenCancelled = false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            transients.lastOrNull()?.let {
                if (!it.inside(ev.x, ev.y)) {
                    dismissTop()
                    swallowing = true
                    return true
                }
            }
        }
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private var swallowing = false
}
