package dev.tonal.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Process
import android.util.LruCache
import kotlin.math.abs

/** Resolves an app's icon: icon pack first, then Material You themed, then the app's own. */
class IconProvider(private val context: Context, private val prefs: Prefs) {
    val sizePx = 56.dp
    private val cache = LruCache<String, Bitmap>(512)
    private var pack: IconPack? = null
    private var packName: String? = null
    private var colorSignature = 0

    private val night get() =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val themedBackground get() = context.getColor(
        if (night) android.R.color.system_neutral2_800 else android.R.color.system_accent1_100,
    )
    private val themedForeground get() = context.getColor(
        if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_700,
    )

    /** Drops cached bitmaps when the wallpaper palette or day/night mode has moved on. */
    fun refreshIfColorsChanged() {
        val signature = themedBackground * 31 + themedForeground
        if (signature != colorSignature) {
            colorSignature = signature
            cache.evictAll()
        }
    }

    fun clear() {
        cache.evictAll()
        packName = null
        pack = null
    }

    fun icon(entry: AppEntry): Bitmap {
        cache.get(entry.key)?.let { return it }
        val rendered = badge(render(entry), entry)
        cache.put(entry.key, rendered)
        return rendered
    }

    private fun currentPack(): IconPack? {
        val name = prefs.iconPack
        if (name != packName) {
            packName = name
            pack = if (name.isEmpty()) null else IconPack.load(context, name)
        }
        return pack
    }

    private fun render(entry: AppEntry): Bitmap {
        val density = context.resources.displayMetrics.densityDpi
        val base = entry.info.getIcon(density)
        currentPack()?.let { p ->
            p.iconFor(entry.component)?.let { return toBitmap(it) }
            if (p.hasMasking) return p.masked(toBitmap(asAdaptive(base)), entry.component, sizePx)
            return toBitmap(asAdaptive(base))
        }
        if (prefs.themedIcons) {
            themed(base)?.let { return toBitmap(it) }
            if (prefs.forceThemed) return toBitmap(forceThemed(base))
        }
        return toBitmap(asAdaptive(base))
    }

    private fun themed(base: Drawable): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val mono = (base as? AdaptiveIconDrawable)?.monochrome ?: return null
        val tinted = mono.mutate().constantState?.newDrawable()?.mutate() ?: mono.mutate()
        tinted.setTint(themedForeground)
        return AdaptiveIconDrawable(ColorDrawable(themedBackground), tinted)
    }

    /** For apps with no monochrome layer: derive a silhouette from the foreground and tint it. */
    private fun forceThemed(base: Drawable): Drawable {
        val adaptive = asAdaptive(base)
        val size = (sizePx * 1.5f).toInt()
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        adaptive.foreground?.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(layer))
        }
        val pixels = IntArray(size * size)
        layer.getPixels(pixels, 0, size, 0, 0, size, size)
        val opaque = pixels.count { Color.alpha(it) > 200 }
        val fullBleed = opaque > pixels.size * 0.8f
        // A full-bleed foreground has no usable alpha, so measure each pixel's distance from the corner color.
        val reference = luminance(pixels[size * size / 8 + size / 8])
        val tint = themedForeground
        for (i in pixels.indices) {
            val alpha = if (fullBleed) {
                (abs(luminance(pixels[i]) - reference) * 4f).coerceAtMost(1f) * 255f
            } else {
                Color.alpha(pixels[i]).toFloat()
            }
            pixels[i] = (alpha.toInt() shl 24) or (tint and 0xFFFFFF)
        }
        layer.setPixels(pixels, 0, size, 0, 0, size, size)
        return AdaptiveIconDrawable(ColorDrawable(themedBackground), BitmapDrawable(context.resources, layer))
    }

    private fun luminance(color: Int) =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    /** Legacy square icons get wrapped so every icon shares the system shape. */
    private fun asAdaptive(base: Drawable): AdaptiveIconDrawable =
        base as? AdaptiveIconDrawable
            ?: AdaptiveIconDrawable(ColorDrawable(Color.WHITE), InsetDrawable(base, 0.22f))

    private fun toBitmap(drawable: Drawable): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(out))
        return out
    }

    private fun badge(icon: Bitmap, entry: AppEntry): Bitmap {
        if (entry.user == Process.myUserHandle()) return icon
        val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, icon), entry.user)
        return toBitmap(badged)
    }

    /** A pinned shortcut's own icon with its app's icon as a small badge, as Pixel Launcher draws them. */
    fun shortcutIcon(key: String, info: ShortcutInfo?, app: AppEntry?, launcherApps: LauncherApps): Bitmap {
        cache.get("sc:$key")?.let { return it }
        val density = context.resources.displayMetrics.densityDpi
        val drawable = info?.let { runCatching { launcherApps.getShortcutIconDrawable(it, density) }.getOrNull() }
        val base = when {
            drawable != null -> toBitmap(asAdaptive(drawable))
            app != null -> return icon(app)
            else -> toBitmap(asAdaptive(context.getDrawable(android.R.drawable.sym_def_app_icon)!!))
        }
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        if (app != null) {
            val badge = (sizePx * 0.4f).toInt()
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(icon(app), null, Rect(sizePx - badge, sizePx - badge, sizePx, sizePx), paint)
        }
        cache.put("sc:$key", out)
        return out
    }

    fun folderIcon(children: List<AppEntry>): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // A step away from the themed icon color, or tinted previews would melt into the circle.
        paint.color = context.getColor(
            if (night) android.R.color.system_neutral2_700 else android.R.color.system_accent1_200,
        )
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        val mini = (sizePx * 0.36f).toInt()
        val gap = (sizePx * 0.04f).toInt()
        val shown = children.take(4)
        val rows = (shown.size + 1) / 2
        val startX = (sizePx - mini * 2 - gap) / 2
        val startY = (sizePx - mini * rows - gap * (rows - 1)) / 2
        shown.forEachIndexed { i, child ->
            val left = startX + (i % 2) * (mini + gap)
            val top = startY + (i / 2) * (mini + gap)
            canvas.drawBitmap(icon(child), null, Rect(left, top, left + mini, top + mini), paint)
        }
        return out
    }
}
