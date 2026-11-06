package dev.tonal.launcher

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherModelTest {
    private lateinit var model: LauncherModel

    @Before
    fun setUp() {
        model = LauncherModel(ApplicationProvider.getApplicationContext())
    }

    private fun app(container: Long = CONTAINER_HOME, page: Int = 0, x: Int = 0, y: Int = 1) =
        Item(model.newId(), ItemType.APP, container, page, x, y, key = "p/.A#0").also { model.items += it }

    @Test
    fun firstPageTopRowIsReservedForDateHeader() {
        assertFalse(model.isFree(CONTAINER_HOME, 0, 0, 0, 1, 1, 5, 6))
        assertTrue(model.isFree(CONTAINER_HOME, 1, 0, 0, 1, 1, 5, 6))
        assertTrue(model.isFree(CONTAINER_DOCK, 0, 0, 0, 1, 1, 5, 1))
    }

    @Test
    fun widgetsBlockEveryCellTheyCover() {
        model.items += Item(model.newId(), ItemType.WIDGET, CONTAINER_HOME, 0, 1, 1, 3, 2)
        assertFalse(model.isFree(CONTAINER_HOME, 0, 3, 2, 1, 1, 5, 6))
        assertFalse(model.isFree(CONTAINER_HOME, 0, 0, 1, 2, 1, 5, 6))
        assertTrue(model.isFree(CONTAINER_HOME, 0, 4, 1, 1, 2, 5, 6))
        assertEquals(ItemType.WIDGET, model.itemAt(CONTAINER_HOME, 0, 3, 2)?.type)
    }

    @Test
    fun isFreeRejectsOutOfBounds() {
        assertFalse(model.isFree(CONTAINER_HOME, 1, 4, 0, 2, 1, 5, 6))
        assertFalse(model.isFree(CONTAINER_HOME, 1, 0, 5, 1, 2, 5, 6))
    }

    @Test
    fun findFreeRollsOntoNextPageWhenFull() {
        for (y in 1 until 6) for (x in 0 until 5) app(x = x, y = y)
        assertEquals(Triple(1, 0, 0), model.findFree(0, 1, 1, 5, 6))
    }

    @Test
    fun folderWithOneChildPromotesItIntoTheFoldersCell() {
        val folder = Item(model.newId(), ItemType.FOLDER, CONTAINER_HOME, 2, 3, 4).also { model.items += it }
        val kid = app(container = folder.id, x = 0)
        model.normalizeFolder(folder.id)
        assertFalse(model.items.contains(folder))
        assertEquals(CONTAINER_HOME, kid.container)
        assertEquals(listOf(2, 3, 4), listOf(kid.page, kid.x, kid.y))
    }

    @Test
    fun folderWithTwoChildrenSurvivesAndReindexes() {
        val folder = Item(model.newId(), ItemType.FOLDER, CONTAINER_HOME, 0, 0, 1).also { model.items += it }
        val a = app(container = folder.id, x = 5)
        val b = app(container = folder.id, x = 9)
        model.normalizeFolder(folder.id)
        assertTrue(model.items.contains(folder))
        assertEquals(listOf(0, 1), listOf(a.x, b.x))
    }

    @Test
    fun removePackageOnlyTouchesThatProfile() {
        val mine = Item(model.newId(), ItemType.APP, CONTAINER_HOME, key = "com.a/.Main#0").also { model.items += it }
        val work = Item(model.newId(), ItemType.APP, CONTAINER_HOME, key = "com.a/.Main#10").also { model.items += it }
        model.removePackage("com.a", 0)
        assertFalse(model.items.contains(mine))
        assertTrue(model.items.contains(work))
    }

    @Test
    fun pruneClosesGapsButKeepsFirstPage() {
        val a = app(page = 2, x = 0)
        val b = app(page = 5, x = 0)
        model.pruneEmptyPages()
        assertEquals(listOf(1, 2), listOf(a.page, b.page))
    }

    @Test
    fun fitToGridMovesOverflowBackInside() {
        val a = app(page = 1, x = 4, y = 0)
        val dock = app(container = CONTAINER_DOCK, x = 4, y = 0)
        model.fitToGrid(4, 6)
        assertTrue(a.x < 4)
        assertEquals(CONTAINER_HOME, dock.container)
        assertNull(model.itemAt(CONTAINER_DOCK, 0, 4, 0))
    }

    @Test
    fun layoutRoundTripsThroughDisk() {
        val folder = Item(model.newId(), ItemType.FOLDER, CONTAINER_HOME, 1, 2, 3, title = "Games").also { model.items += it }
        model.items += Item(model.newId(), ItemType.WIDGET, CONTAINER_HOME, 0, 0, 1, 4, 2, widgetId = 77)
        model.save()
        Thread.sleep(300)
        val reloaded = LauncherModel(ApplicationProvider.getApplicationContext())
        assertEquals(2, reloaded.items.size)
        assertEquals("Games", reloaded.items.first { it.id == folder.id }.title)
        assertEquals(77, reloaded.items.first { it.type == ItemType.WIDGET }.widgetId)
        assertFalse(reloaded.isFirstRun)
    }
}
