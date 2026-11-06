package dev.tonal.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Horizontally paged home screens. Dragging right past the first page drives the Google feed. */
class Workspace(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val flingVelocity = config.scaledMinimumFlingVelocity * 4
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var feedDragging = false
    private var feedDistance = 0f

    var feed: FeedClient? = null
    var feedEnabled = false
    var onPositionChanged: ((Float) -> Unit)? = null
    var dragInProgress: () -> Boolean = { false }

    var currentPage = 0
        private set

    val pageCount get() = childCount
    val currentCell get() = getChildAt(currentPage) as CellLayout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        currentPage = currentPage.coerceIn(0, max(0, childCount - 1))
        if (scroller.isFinished && !dragging) scrollTo(currentPage * w, 0)
    }

    fun clampCurrentPage() {
        currentPage = currentPage.coerceIn(0, max(0, childCount - 1))
    }

    fun snapTo(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, max(0, childCount - 1))
        currentPage = target
        val dx = target * width - scrollX
        scroller.forceFinished(true)
        if (animate && dx != 0) {
            scroller.startScroll(scrollX, 0, dx, 0, 320)
            postInvalidateOnAnimation()
        } else {
            scrollTo(target * width, 0)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) onPositionChanged?.invoke(l.toFloat() / width)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragInProgress()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.rawX
                dragging = false
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && shouldStartDrag(ev)) beginDrag(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) recycleTracker()
        }
        track(ev)
        return dragging
    }

    private fun shouldStartDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) <= slop || abs(dx) <= abs(dy)) return false
        return !canScrollAt(currentCell, downX + scrollX - currentCell.left, downY, false, if (dx < 0) 1 else -1)
    }

    private fun beginDrag(ev: MotionEvent) {
        dragging = true
        lastX = ev.rawX
        parent.requestDisallowInterceptTouchEvent(true)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.rawX
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && shouldStartDrag(ev)) beginDrag(ev)
                if (dragging) {
                    // Raw coordinates: the feed slides this whole view sideways under the finger.
                    moveBy(lastX - ev.rawX)
                    lastX = ev.rawX
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag()
        }
        return true
    }

    private fun moveBy(dx: Float) {
        if (feedDragging) {
            feedDistance -= dx
            if (feedDistance <= 0f) {
                feed?.onScroll(0f)
                feed?.endScroll()
                feedDragging = false
                return
            }
            feed?.onScroll((feedDistance / width).coerceAtMost(1f))
            return
        }
        val next = scrollX + dx
        if (next < 0 && currentPageIsFirstReachable() && feedEnabled && feed?.attached == true) {
            scrollTo(0, 0)
            feedDragging = true
            feedDistance = -next
            feed?.startScroll()
            feed?.onScroll((feedDistance / width).coerceAtMost(1f))
            return
        }
        scrollTo(next.coerceIn(0f, ((childCount - 1) * width).toFloat()).roundToInt(), 0)
    }

    private fun currentPageIsFirstReachable() = scrollX <= width / 2

    private fun finishDrag() {
        if (feedDragging) {
            feed?.endScroll()
            feedDragging = false
        } else if (dragging) {
            val velocity = tracker?.let {
                it.computeCurrentVelocity(1000)
                it.xVelocity
            } ?: 0f
            val page = when {
                velocity < -flingVelocity -> min(currentPage + 1, childCount - 1)
                velocity > flingVelocity -> max(currentPage - 1, 0)
                else -> (scrollX.toFloat() / width).roundToInt()
            }
            snapTo(page)
        } else if (!scroller.isFinished || scrollX != currentPage * width) {
            snapTo(currentPage)
        }
        dragging = false
        recycleTracker()
    }

    private fun track(ev: MotionEvent) {
        val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
        t.addMovement(ev)
    }

    private fun recycleTracker() {
        tracker?.recycle()
        tracker = null
    }
}

/** Sends vertical swipes on home to the drawer (up) and the notification shade (down). */
class HomeLayout(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var mode = 0
    private var tracker: VelocityTracker? = null

    var onDrawerDrag: ((Float) -> Unit)? = null
    var onDrawerRelease: ((Float) -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var dragInProgress: () -> Boolean = { false }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragInProgress()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                mode = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                    val direction = if (dy < 0) 1 else -1
                    if (!canScrollAt(this, downX, downY, true, direction)) {
                        mode = if (dy < 0) MODE_DRAWER else MODE_SHADE
                        lastY = ev.y
                        tracker = VelocityTracker.obtain()
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_DRAWER) {
                    onDrawerDrag?.invoke(lastY - ev.y)
                    lastY = ev.y
                } else if (mode == MODE_SHADE) {
                    onSwipeDown?.invoke()
                    mode = MODE_DONE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_DRAWER) {
                    tracker?.computeCurrentVelocity(1000)
                    onDrawerRelease?.invoke(-(tracker?.yVelocity ?: 0f))
                }
                tracker?.recycle()
                tracker = null
                mode = 0
            }
        }
        return true
    }

    private companion object {
        const val MODE_DRAWER = 1
        const val MODE_SHADE = 2
        const val MODE_DONE = 3
    }
}
