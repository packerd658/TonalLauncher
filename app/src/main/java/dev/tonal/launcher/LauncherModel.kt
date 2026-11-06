package dev.tonal.launcher

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class ItemType { APP, FOLDER, WIDGET, SHORTCUT }

/** A pinned shortcut's identity, stored in Item.key as "package|shortcutId#userSerial". */
data class ShortcutKey(val pkg: String, val id: String, val serial: Long) {
    override fun toString() = "$pkg|$id#$serial"

    companion object {
        fun parse(key: String?): ShortcutKey? {
            if (key == null) return null
            val bar = key.indexOf('|')
            val hash = key.lastIndexOf('#')
            if (bar <= 0 || hash < bar) return null
            val serial = key.substring(hash + 1).toLongOrNull() ?: return null
            return ShortcutKey(key.substring(0, bar), key.substring(bar + 1, hash), serial)
        }
    }
}

const val CONTAINER_HOME = -1L
const val CONTAINER_DOCK = -2L

class Item(
    val id: Long,
    val type: ItemType,
    var container: Long,
    var page: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var spanX: Int = 1,
    var spanY: Int = 1,
    var key: String? = null,
    var widgetId: Int = -1,
    var title: String? = null,
)

/** The layout of the home screen. Container is CONTAINER_HOME, CONTAINER_DOCK, or a folder's item id. */
class LauncherModel(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "layout.json"))
    private val writer = Executors.newSingleThreadExecutor()
    val items = ArrayList<Item>()
    private var nextId = 1L
    var isFirstRun = true
        private set

    /** Bumped by changes made outside the home screen, such as a pinned shortcut, so it knows to rebind. */
    var revision = 0
        private set

    fun addShortcut(key: ShortcutKey, title: String, cols: Int, rows: Int): Item {
        val (page, x, y) = findFree(0, 1, 1, cols, rows)
        val item = Item(newId(), ItemType.SHORTCUT, CONTAINER_HOME, page, x, y, key = key.toString(), title = title)
        items += item
        revision++
        save()
        return item
    }

    init {
        load()
    }

    fun newId() = nextId++

    private fun load() {
        val text = runCatching { String(file.readFully()) }.getOrNull() ?: return
        isFirstRun = false
        val array = JSONArray(text)
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            items += Item(
                o.getLong("id"), ItemType.valueOf(o.getString("type")), o.getLong("container"),
                o.getInt("page"), o.getInt("x"), o.getInt("y"), o.getInt("spanX"), o.getInt("spanY"),
                o.optString("key").ifEmpty { null }, o.optInt("widgetId", -1), o.optString("title").ifEmpty { null },
            )
        }
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }

    fun save() {
        isFirstRun = false
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("type", it.type.name).put("container", it.container)
                    .put("page", it.page).put("x", it.x).put("y", it.y)
                    .put("spanX", it.spanX).put("spanY", it.spanY)
                    .put("key", it.key ?: "").put("widgetId", it.widgetId).put("title", it.title ?: ""),
            )
        }
        val bytes = array.toString().toByteArray()
        writer.execute {
            val out = file.startWrite()
            try {
                out.write(bytes)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
            }
        }
    }

    fun children(folderId: Long) = items.filter { it.container == folderId }.sortedBy { it.x }

    val pageCount: Int
        get() = (items.filter { it.container == CONTAINER_HOME }.maxOfOrNull { it.page } ?: 0) + 1

    fun itemAt(container: Long, page: Int, x: Int, y: Int): Item? = items.firstOrNull {
        it.container == container && (container != CONTAINER_HOME || it.page == page) &&
            x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY
    }

    /** Row 0 of the first home page is reserved for the date header. */
    fun isFree(container: Long, page: Int, x: Int, y: Int, sx: Int, sy: Int, cols: Int, rows: Int, ignore: Item? = null): Boolean {
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false
        if (container == CONTAINER_HOME && page == 0 && y == 0) return false
        return items.none {
            it !== ignore && it.container == container && (container != CONTAINER_HOME || it.page == page) &&
                x < it.x + it.spanX && it.x < x + sx && y < it.y + it.spanY && it.y < y + sy
        }
    }

    fun findFree(startPage: Int, sx: Int, sy: Int, cols: Int, rows: Int, ignore: Item? = null): Triple<Int, Int, Int> {
        var page = startPage
        while (true) {
            for (y in 0..rows - sy) for (x in 0..cols - sx) {
                if (isFree(CONTAINER_HOME, page, x, y, sx, sy, cols, rows, ignore)) return Triple(page, x, y)
            }
            page++
        }
    }

    /** Folders never hold fewer than two apps: one left is promoted into the folder's cell, none deletes it. */
    fun normalizeFolder(folderId: Long) {
        val folder = items.firstOrNull { it.id == folderId && it.type == ItemType.FOLDER } ?: return
        val kids = children(folderId)
        if (kids.size >= 2) {
            kids.forEachIndexed { i, item -> item.x = i }
            return
        }
        kids.firstOrNull()?.apply {
            container = folder.container
            page = folder.page
            x = folder.x
            y = folder.y
        }
        items.remove(folder)
    }

    /** Pull items back inside the grid after the grid size changes. */
    fun fitToGrid(cols: Int, rows: Int) {
        items.filter { it.container == CONTAINER_DOCK && it.x >= cols }.forEach {
            val (page, x, y) = findFree(0, 1, 1, cols, rows, it)
            it.container = CONTAINER_HOME
            it.page = page
            it.x = x
            it.y = y
        }
        items.filter { it.container == CONTAINER_HOME }.forEach {
            it.spanX = it.spanX.coerceAtMost(cols)
            it.spanY = it.spanY.coerceAtMost(rows - 1)
            val outOfBounds = it.x + it.spanX > cols || it.y + it.spanY > rows
            if (outOfBounds) {
                val (page, x, y) = findFree(it.page, it.spanX, it.spanY, cols, rows, it)
                it.page = page
                it.x = x
                it.y = y
            }
        }
    }

    fun removePackage(pkg: String, serial: Long) {
        val gone = items.filter {
            val prefix = when (it.type) {
                ItemType.APP -> "$pkg/"
                ItemType.SHORTCUT -> "$pkg|"
                else -> return@filter false
            }
            it.key?.startsWith(prefix) == true && it.key?.endsWith("#$serial") == true
        }
        if (gone.isEmpty()) return
        items.removeAll(gone.toSet())
        revision++
        gone.map { it.container }.filter { it > 0 }.distinct().forEach { normalizeFolder(it) }
        save()
    }

    fun pruneEmptyPages() {
        val used = items.filter { it.container == CONTAINER_HOME }.map { it.page }.distinct().sorted()
        val remap = HashMap<Int, Int>()
        var next = 1
        used.forEach { if (it == 0) remap[0] = 0 else remap[it] = next++ }
        items.filter { it.container == CONTAINER_HOME }.forEach { it.page = remap[it.page] ?: 0 }
    }
}
