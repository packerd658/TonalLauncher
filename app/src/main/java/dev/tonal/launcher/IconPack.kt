package dev.tonal.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Calendar

/** Reads the ADW/Nova appfilter.xml format that icon packs on the Play Store ship. */
class IconPack private constructor(private val pkg: String, private val res: Resources) {
    private val icons = HashMap<String, String>()
    private val calendars = HashMap<String, String>()
    private val backs = ArrayList<String>()
    private var mask: String? = null
    private var upon: String? = null
    private var scale = 1f

    @SuppressLint("DiscouragedApi")
    private fun drawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    /** The pack's drawable for this exact activity, if it has one. */
    fun iconFor(component: ComponentName): Drawable? {
        val key = component.flattenToString()
        calendars[key]?.let { prefix ->
            drawable(prefix + Calendar.getInstance().get(Calendar.DAY_OF_MONTH))?.let { return it }
        }
        return icons[key]?.let { drawable(it) }
    }

    val hasMasking get() = backs.isNotEmpty() || mask != null || upon != null

    /** Fits an icon the pack doesn't cover into the pack's look using iconback/iconmask/iconupon. */
    fun masked(icon: Bitmap, component: ComponentName, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bounds = Rect(0, 0, size, size)
        if (backs.isNotEmpty()) {
            val back = backs[Math.floorMod(component.hashCode(), backs.size)]
            drawable(back)?.apply { setBounds(bounds); draw(canvas) }
        }
        val inset = ((1f - scale) * size / 2).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        layerCanvas.drawBitmap(icon, null, Rect(inset, inset, size - inset, size - inset), paint)
        mask?.let { name ->
            drawable(name)?.let { maskDrawable ->
                val maskBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                maskDrawable.setBounds(bounds)
                maskDrawable.draw(Canvas(maskBitmap))
                val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
                layerCanvas.drawBitmap(maskBitmap, 0f, 0f, clear)
            }
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
        upon?.let { name -> drawable(name)?.apply { setBounds(bounds); draw(canvas) } }
        return out
    }

    @SuppressLint("DiscouragedApi")
    private fun parse(context: Context): Boolean {
        val parser = openAppFilter(context) ?: return false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = normalize(parser.getAttributeValue(null, "component"))
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) icons[component] = drawable
                    }
                    "calendar" -> {
                        val component = normalize(parser.getAttributeValue(null, "component"))
                        val prefix = parser.getAttributeValue(null, "prefix")
                        if (component != null && prefix != null) calendars[component] = prefix
                    }
                    "iconback" -> for (i in 0 until parser.attributeCount) backs += parser.getAttributeValue(i)
                    "iconmask" -> mask = parser.getAttributeValue(null, "img1")
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1")
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            event = parser.next()
        }
        return true
    }

    @SuppressLint("DiscouragedApi")
    private fun openAppFilter(context: Context): XmlPullParser? {
        val id = res.getIdentifier("appfilter", "xml", pkg)
        if (id != 0) return res.getXml(id)
        return runCatching {
            val assets = context.packageManager.getResourcesForApplication(pkg).assets
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(assets.open("appfilter.xml"), "UTF-8")
            }
        }.getOrNull()
    }

    private fun normalize(raw: String?): String? {
        val inner = raw?.substringAfter("ComponentInfo{", "")?.substringBefore("}")
        if (inner.isNullOrEmpty()) return null
        return ComponentName.unflattenFromString(inner)?.flattenToString()
    }

    companion object {
        private val ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme",
        )
        private val CATEGORIES = listOf(
            "com.fede.launcher.THEME_ICONPACK",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.novalauncher.category.CUSTOM_ICON_PICKER",
        )

        /** Installed icon packs as packageName to label. */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val intents = ACTIONS.map { Intent(it) } + CATEGORIES.map { Intent(Intent.ACTION_MAIN).addCategory(it) }
            return intents.flatMap { pm.queryIntentActivities(it, PackageManager.MATCH_ALL) }
                .distinctBy { it.activityInfo.packageName }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase() }
        }

        fun load(context: Context, pkg: String): IconPack? = runCatching {
            val res = context.packageManager.getResourcesForApplication(pkg)
            IconPack(pkg, res).takeIf { it.parse(context) }
        }.getOrNull()
    }
}
