package dev.tonal.launcher

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Runs the real activity and view tree; what it can't see is the Google app and real widgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LauncherActivityTest {
    private fun launch(): ActivityController<LauncherActivity> {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).setup()
        layout(controller.get())
        return controller
    }

    private fun layout(activity: LauncherActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2340)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private inline fun <reified T : View> find(view: View): List<T> {
        val out = ArrayList<T>()
        val pending = ArrayDeque(listOf(view))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next is T) out += next
            if (next is ViewGroup) for (i in 0 until next.childCount) pending += next.getChildAt(i)
        }
        return out
    }

    private fun touch(root: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        root.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun dragLayer(activity: LauncherActivity) = find<DragLayer>(activity.window.decorView).first()

    @Test
    fun startsAndShowsHomeGrid() {
        val activity = launch().get()
        val workspace = find<Workspace>(activity.window.decorView).single()
        assertTrue(workspace.pageCount >= 1)
        assertNotNull(find<AppDrawer>(activity.window.decorView).single())
    }

    @Test
    fun survivesFullLifecycle() {
        val controller = launch()
        controller.pause().stop().start().resume()
        controller.newIntent(Intent(Intent.ACTION_MAIN))
        controller.pause().stop().destroy()
    }

    @Test
    fun swipeUpOpensDrawerAndBackClosesIt() {
        val activity = launch().get()
        val root = dragLayer(activity)
        val drawer = find<AppDrawer>(root).single()
        touch(root, MotionEvent.ACTION_DOWN, 500f, 1800f)
        for (y in listOf(1700f, 1400f, 1000f, 600f)) touch(root, MotionEvent.ACTION_MOVE, 500f, y)
        assertTrue("drawer should follow the finger", drawer.progress > 0f)
        touch(root, MotionEvent.ACTION_UP, 500f, 600f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1f, drawer.progress, 0.01f)

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertFalse(drawer.isOpen)
    }

    @Test
    fun draggingAnAppOntoAnotherCreatesAFolder() {
        val activity = launch().get()
        val app = activity.launcherApp
        val root = dragLayer(activity)
        val startCount = app.model.items.size
        val bitmap = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
        val drag = root.drag!!
        val item = Item(app.model.newId(), ItemType.APP, CONTAINER_HOME, 0, 0, 1, key = "x/.Y#0")
        val other = Item(app.model.newId(), ItemType.APP, CONTAINER_HOME, 0, 2, 2, key = "x/.Z#0")
        app.model.items += item
        app.model.items += other
        activity.bindAll()
        layout(activity)

        touch(root, MotionEvent.ACTION_DOWN, 100f, 500f)
        drag.begin(item, null, bitmap, 100, 100, 1, 1)
        assertTrue(drag.isTracking)
        touch(root, MotionEvent.ACTION_MOVE, 400f, 900f)
        assertTrue(drag.isActive)
        layout(activity)
        val cell = find<Workspace>(root).single().currentCell
        val location = IntArray(2)
        cell.getLocationInWindow(location)
        // Drop on `other`'s cell: two apps should merge into a folder.
        val targetX = location[0] + (other.x + 0.5f) * cell.cellWidth
        val targetY = location[1] + (other.y + 0.5f) * cell.cellHeight
        touch(root, MotionEvent.ACTION_MOVE, targetX, targetY)
        touch(root, MotionEvent.ACTION_UP, targetX, targetY)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(drag.isTracking)
        assertEquals(startCount + 3, app.model.items.size)
        val folder = app.model.items.single { it.type == ItemType.FOLDER }
        assertEquals(setOf(item, other), app.model.children(folder.id).toSet())
    }

    @Test
    fun settingsScreenOpens() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(controller.get().supportFragmentManager.findFragmentById(R.id.settings_container))
    }
}
