package dev.tonal.launcher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Moves items around home. A long press enters PENDING, where the context popup is showing and the
 * finger may simply lift; moving past the slop dismisses the popup and makes the drag ACTIVE.
 */
class DragController(private val activity: LauncherActivity, private val layer: DragLayer) {
    private enum class State { IDLE, PENDING, ACTIVE }

    private sealed class Target {
        object None : Target()
        object Remove : Target()
        object Uninstall : Target()
        class Cell(val cell: CellLayout, val x: Int, val y: Int) : Target()
    }

    private var state = State.IDLE
    private var startX = 0f
    private var startY = 0f
    private var existing: Item? = null
    private var newEntry: AppEntry? = null
    private var spanX = 1
    private var spanY = 1
    private var shadow: ImageView? = null
    private var shadowWidth = 0
    private var shadowHeight = 0
    private var target: Target = Target.None
    private var highlighted: CellLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFlip: Runnable? = null
    private var flipDirection = 0
    private val moveSlop = 12.dp

    val isTracking get() = state != State.IDLE
    val isActive get() = state == State.ACTIVE

    /** The item the home grid should leave out while it's in the air. */
    val draggedItem get() = if (isActive) existing else null

    fun begin(item: Item?, entry: AppEntry?, bitmap: Bitmap, width: Int, height: Int, spanX: Int, spanY: Int) {
        existing = item
        newEntry = entry
        this.spanX = spanX
        this.spanY = spanY
        shadowWidth = width
        shadowHeight = height
        startX = layer.lastX
        startY = layer.lastY
        state = State.PENDING
        shadow = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }
        layer.addView(shadow, FrameLayout.LayoutParams(width, height))
    }

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (state == State.PENDING && hypot(ev.x - startX, ev.y - startY) > moveSlop) activate()
                if (state == State.ACTIVE) move(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> if (state == State.ACTIVE) drop() else reset()
            MotionEvent.ACTION_CANCEL -> {
                if (state == State.ACTIVE) activity.bindAll()
                reset()
            }
        }
    }

    private fun activate() {
        state = State.ACTIVE
        shadow?.apply {
            visibility = View.VISIBLE
            scaleX = 1.08f
            scaleY = 1.08f
            alpha = 0.92f
        }
        layer.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        activity.onDragStarted(canUninstall = (existing?.type == ItemType.APP) || newEntry != null)
        shadow?.bringToFront()
    }

    private fun move(x: Float, y: Float) {
        shadow?.apply {
            translationX = x - shadowWidth / 2f
            translationY = y - shadowHeight / 2f
        }
        val next = activity.dropTargetAt(x, y)
        val resolved: Target = when (next) {
            LauncherActivity.DROP_REMOVE -> Target.Remove
            LauncherActivity.DROP_UNINSTALL -> Target.Uninstall
            else -> cellTarget(x, y)
        }
        target = resolved
        updateHighlight(resolved)
        activity.highlightDropBar(next)
        updateEdgeFlip(x, resolved)
    }

    private fun cellTarget(x: Float, y: Float): Target {
        val cell = activity.cellUnder(x, y, spanX == 1 && spanY == 1) ?: return Target.None
        val location = IntArray(2)
        cell.getLocationInWindow(location)
        val rootLocation = IntArray(2)
        layer.getLocationInWindow(rootLocation)
        val localX = x - (location[0] - rootLocation[0])
        val localY = y - (location[1] - rootLocation[1])
        val cx = ((localX - (spanX - 1) * cell.cellWidth / 2f) / cell.cellWidth).toInt()
        val cy = ((localY - (spanY - 1) * cell.cellHeight / 2f) / cell.cellHeight).toInt()
        return Target.Cell(cell, cx.coerceIn(0, cell.cols - spanX), cy.coerceIn(0, cell.rows - spanY))
    }

    private fun updateHighlight(t: Target) {
        val cell = (t as? Target.Cell)?.cell
        if (highlighted !== cell) highlighted?.clearHighlight()
        highlighted = cell
        if (t is Target.Cell) t.cell.setHighlight(t.x, t.y, spanX, spanY, canPlace(t))
    }

    private fun updateEdgeFlip(x: Float, t: Target) {
        val edge = 28.dp
        val direction = when {
            t !is Target.Cell || t.cell.container != CONTAINER_HOME -> 0
            x < edge -> -1
            x > layer.width - edge -> 1
            else -> 0
        }
        if (direction == flipDirection) return
        flipDirection = direction
        pendingFlip?.let { handler.removeCallbacks(it) }
        pendingFlip = null
        if (direction == 0) return
        val flip = object : Runnable {
            override fun run() {
                activity.flipPage(direction)
                handler.postDelayed(this, 800)
            }
        }
        pendingFlip = flip
        handler.postDelayed(flip, 500)
    }

    private fun canMerge(occupant: Item?): Boolean {
        val moving = existing
        if (occupant == null || occupant === moving) return false
        if (moving != null && moving.type != ItemType.APP) return false
        return occupant.type == ItemType.APP || occupant.type == ItemType.FOLDER
    }

    private fun canPlace(t: Target.Cell): Boolean {
        val model = activity.launcherApp.model
        val occupant = model.itemAt(t.cell.container, t.cell.page, t.x, t.y)
        if (spanX == 1 && spanY == 1 && canMerge(occupant)) return true
        return model.isFree(t.cell.container, t.cell.page, t.x, t.y, spanX, spanY, t.cell.cols, t.cell.rows, existing)
    }

    private fun drop() {
        val model = activity.launcherApp.model
        val item = existing
        when (val t = target) {
            Target.Remove -> item?.let { activity.removeItem(it) }
            Target.Uninstall -> {
                val entry = newEntry ?: activity.launcherApp.apps.find(item?.key)
                entry?.let { activity.uninstall(it) }
            }
            is Target.Cell -> if (canPlace(t)) place(t) else if (newEntry != null) {
                Toast.makeText(activity, R.string.no_room, Toast.LENGTH_SHORT).show()
            }
            Target.None -> Unit
        }
        model.pruneEmptyPages()
        reset()
        activity.onDragEnded()
    }

    private fun place(t: Target.Cell) {
        val model = activity.launcherApp.model
        // Looked up first: a new item starts at (0, 0) and would otherwise shadow what's really there.
        val occupant = model.itemAt(t.cell.container, t.cell.page, t.x, t.y)?.takeIf { it !== existing }
        val item = existing ?: Item(model.newId(), ItemType.APP, t.cell.container, key = newEntry!!.key).also { model.items += it }
        val oldContainer = item.container
        when {
            occupant == null || !canMergeInto(occupant, item) -> {
                item.container = t.cell.container
                item.page = t.cell.page
                item.x = t.x
                item.y = t.y
            }
            occupant.type == ItemType.FOLDER -> {
                item.container = occupant.id
                item.x = model.children(occupant.id).size
            }
            else -> {
                val folder = Item(
                    model.newId(), ItemType.FOLDER, occupant.container, occupant.page, occupant.x, occupant.y,
                    title = activity.getString(R.string.folder_default),
                )
                model.items += folder
                occupant.container = folder.id
                occupant.x = 0
                item.container = folder.id
                item.x = 1
            }
        }
        if (oldContainer > 0 && oldContainer != item.container) model.normalizeFolder(oldContainer)
    }

    private fun canMergeInto(occupant: Item, moving: Item) =
        moving.type == ItemType.APP && (occupant.type == ItemType.APP || occupant.type == ItemType.FOLDER)

    private fun reset() {
        pendingFlip?.let { handler.removeCallbacks(it) }
        pendingFlip = null
        flipDirection = 0
        highlighted?.clearHighlight()
        highlighted = null
        shadow?.let { layer.removeView(it) }
        shadow = null
        target = Target.None
        existing = null
        newEntry = null
        state = State.IDLE
    }
}
