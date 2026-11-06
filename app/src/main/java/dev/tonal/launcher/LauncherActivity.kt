package dev.tonal.launcher

import android.app.ActivityOptions
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt

class LauncherActivity : AppCompatActivity() {
    private val app get() = launcherApp
    private lateinit var root: DragLayer
    private lateinit var home: HomeLayout
    private lateinit var homeContent: LinearLayout
    private lateinit var workspace: Workspace
    private lateinit var indicator: PageIndicator
    private lateinit var dockFrame: FrameLayout
    private lateinit var searchPill: LinearLayout
    private lateinit var lightsButton: ImageView
    private lateinit var drawer: AppDrawer
    private lateinit var dropBar: LinearLayout
    private lateinit var dropRemove: TextView
    private lateinit var dropUninstall: TextView
    private lateinit var drag: DragController
    private lateinit var widgetHost: LauncherWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var feed: FeedClient
    private lateinit var dock: CellLayout
    private val widgetViews = HashMap<Int, LauncherWidgetView>()
    private var prefsDirty = false
    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null
    private var extraDragPage = false
    private var hoveredDrop = DROP_NONE
    private lateinit var weatherChip: TextView
    private var weatherFetchedAt = 0L
    private var boundRevision = -1

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.USAGE) prefsDirty = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        app.icons.refreshIfColorsChanged()

        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = LauncherWidgetHost(this, WIDGET_HOST_ID)
        widgetHost.onLongPress = { onWidgetLongPress(it) }

        buildViews()
        setContentView(root)

        feed = FeedClient(this, ::onFeedScroll) {}
        workspace.feed = feed
        workspace.feedEnabled = app.prefs.feedEnabled
        if (app.prefs.feedEnabled) feed.connect()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    root.hasTransient -> root.dismissTop()
                    drawer.isOpen -> drawer.close()
                    else -> workspace.snapTo(0)
                }
            }
        })

        app.prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        app.apps.onChanged = {
            bindAll()
            drawer.refresh()
        }
        app.apps.reload {
            if (app.model.isFirstRun) seedDefaults()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        app.prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        app.apps.onChanged = null
        feed.disconnect()
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
        feed.setStarted(true)
    }

    override fun onStop() {
        super.onStop()
        widgetHost.stopListening()
        feed.setStarted(false)
    }

    override fun onResume() {
        super.onResume()
        feed.setResumed(true)
        if (prefsDirty) {
            prefsDirty = false
            applyPrefs()
        } else if (app.model.revision != boundRevision) {
            // Something was pinned to the home screen while another app was in front.
            bindAll()
        }
        refreshWeather(force = false)
    }

    private fun refreshWeather(force: Boolean) {
        if (!app.prefs.haWeather || !app.ha.isConfigured) {
            weatherChip.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - weatherFetchedAt < WEATHER_REFRESH_MS) return
        weatherFetchedAt = now
        app.ha.weather { result ->
            val weather = result.getOrNull()
            if (weather?.temperature == null) {
                android.util.Log.i(
                    "TonalWeather",
                    result.exceptionOrNull()?.let { "lookup failed: $it" }
                        ?: weather?.let { "${it.entityId} has no temperature" }
                        ?: "no weather entity in Home Assistant",
                )
                weatherChip.visibility = View.GONE
                return@weather
            }
            val tint = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant, 0)
            val icon = getDrawable(weatherIcon(weather.condition))!!.mutate().apply {
                setTint(tint)
                setBounds(0, 0, 20.dp, 20.dp)
            }
            weatherChip.setCompoundDrawablesRelative(icon, null, null, null)
            weatherChip.text = "${weather.temperature.roundToInt()}°"
            weatherChip.contentDescription = "${weather.condition}, ${weather.temperature.roundToInt()}${weather.unit}"
            weatherChip.visibility = View.VISIBLE
        }
    }

    private fun weatherIcon(condition: String) = when (condition) {
        "sunny" -> R.drawable.ic_weather_sunny
        "clear-night" -> R.drawable.ic_weather_night
        "partlycloudy" -> R.drawable.ic_weather_partly
        "rainy", "pouring", "hail" -> R.drawable.ic_weather_rain
        "snowy", "snowy-rainy" -> R.drawable.ic_weather_snow
        "lightning", "lightning-rainy" -> R.drawable.ic_weather_storm
        else -> R.drawable.ic_weather_cloudy
    }

    override fun onPause() {
        super.onPause()
        feed.setResumed(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        feed.onWindowAttached()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        feed.onWindowDetached(isChangingConfigurations)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The home button: step back toward the first page, one layer at a time.
        val wasOnTop = !root.hasTransient && !drawer.isOpen
        root.dismissAll()
        drawer.close()
        feed.close()
        if (wasOnTop) workspace.snapTo(0)
    }

    private fun applyPrefs() {
        app.icons.clear()
        app.icons.refreshIfColorsChanged()
        app.model.fitToGrid(app.prefs.columns, app.prefs.rows)
        app.model.save()
        searchPill.visibility = if (app.prefs.searchBar) View.VISIBLE else View.GONE
        lightsButton.visibility = if (app.ha.isConfigured) View.VISIBLE else View.GONE
        refreshWeather(force = true)
        workspace.feedEnabled = app.prefs.feedEnabled
        if (app.prefs.feedEnabled) feed.connect() else feed.disconnect()
        drawer.setColumns(app.prefs.columns)
        bindAll()
    }

    // region View construction

    private fun buildViews() {
        root = DragLayer(this)
        drag = DragController(this, root)
        root.drag = drag

        home = HomeLayout(this).apply {
            onDrawerDrag = { drawer.dragBy(it) }
            onDrawerRelease = { drawer.release(it) }
            onSwipeDown = { expandNotifications() }
            dragInProgress = { drag.isActive }
        }
        workspace = Workspace(this).apply {
            onPositionChanged = { position ->
                indicator.position = position
                val pages = (pageCount - 1).coerceAtLeast(1)
                windowToken?.let {
                    WallpaperManager.getInstance(context).setWallpaperOffsets(it, (position / pages).coerceIn(0f, 1f), 0.5f)
                }
            }
            dragInProgress = { drag.isActive }
        }
        indicator = PageIndicator(this)
        dockFrame = FrameLayout(this)
        searchPill = buildSearchPill()
        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20.dp))
            addView(dockFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 84.dp))
            addView(
                searchPill,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dp).apply { setMargins(20.dp, 4.dp, 20.dp, 12.dp) },
            )
        }
        home.addView(homeContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        searchPill.visibility = if (app.prefs.searchBar) View.VISIBLE else View.GONE
        lightsButton.visibility = if (app.ha.isConfigured) View.VISIBLE else View.GONE

        drawer = AppDrawer(this).apply {
            onLaunch = { view, entry ->
                launch(view, entry)
                close(animated = false)
            }
            onLongPress = { onDrawerLongPress(it) }
            onWebSearch = { query ->
                webSearch(query)
                close(animated = false)
            }
            onProgress = { progress ->
                homeContent.alpha = 1f - progress
                val light = progress > 0.5f && resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
                WindowCompat.getInsetsController(window, this).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            setColumns(app.prefs.columns)
        }

        dropRemove = dropTargetLabel(R.string.remove, R.drawable.ic_close)
        dropUninstall = dropTargetLabel(R.string.uninstall, R.drawable.ic_delete)
        dropBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            addView(dropRemove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(dropUninstall, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(home, match)
        root.addView(dropBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 96.dp))
        root.addView(drawer, FrameLayout.LayoutParams(match))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            homeContent.setPadding(0, bars.top, 0, bars.bottom)
            dropBar.setPadding(0, bars.top, 0, 0)
            (dropBar.layoutParams as FrameLayout.LayoutParams).height = bars.top + 64.dp
            drawer.applyInsets(bars.top, bars.bottom, ime.bottom)
            insets
        }

        root.post { bindAll() }
    }

    private fun buildSearchPill(): LinearLayout {
        val pillColor = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHigh, 0)
        val tint = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant, 0)
        fun glyph(res: Int, description: Int, click: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            contentDescription = getString(description)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { click() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(pillColor)
                cornerRadius = 26.dp.toFloat()
            }
            setOnClickListener { openGlobalSearch() }
            addView(glyph(R.drawable.ic_search, R.string.search) { openGlobalSearch() }, LinearLayout.LayoutParams(52.dp, 52.dp))
            weatherChip = TextView(this@LauncherActivity).apply {
                setTextColor(tint)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = 6.dp
                setPadding(4.dp, 0, 12.dp, 0)
                visibility = View.GONE
                setOnClickListener { webSearch(getString(R.string.weather)) }
            }
            addView(weatherChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 52.dp))
            addView(View(this@LauncherActivity), LinearLayout.LayoutParams(0, 1, 1f))
            lightsButton = glyph(R.drawable.ic_lightbulb_outline, R.string.lights) { LightsPanel(this@LauncherActivity).show() }
            addView(lightsButton, LinearLayout.LayoutParams(52.dp, 52.dp))
            addView(glyph(R.drawable.ic_mic, R.string.voice_search) { voiceSearch() }, LinearLayout.LayoutParams(52.dp, 52.dp))
        }
    }

    private fun dropTargetLabel(text: Int, icon: Int) = TextView(this).apply {
        setText(text)
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        textSize = 14f
        setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        val glyph = getDrawable(icon)!!.mutate().apply { setTint(0xFFFFFFFF.toInt()) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
        compoundDrawablePadding = 8.dp
        setPadding(24.dp, 0, 24.dp, 0)
    }

    // endregion

    // region Binding the model to views

    fun bindAll() {
        val model = app.model
        val cols = app.prefs.columns
        val rows = app.prefs.rows
        val hidden = drag.draggedItem

        for (i in 0 until workspace.childCount) (workspace.getChildAt(i) as ViewGroup).removeAllViews()
        workspace.removeAllViews()
        dockFrame.removeAllViews()

        val pages = model.pageCount + if (extraDragPage) 1 else 0
        val cells = (0 until pages).map { page ->
            CellLayout(this, cols, rows, CONTAINER_HOME, page).also { cell ->
                cell.setOnLongClickListener {
                    showHomeMenu()
                    true
                }
                workspace.addView(cell)
            }
        }
        workspace.clampCurrentPage()
        indicator.pageCount = pages

        dock = CellLayout(this, cols, 1, CONTAINER_DOCK, 0)
        dock.setOnLongClickListener {
            showHomeMenu()
            true
        }
        dockFrame.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cells[0].addView(buildDateHeader(), CellLayout.Params(0, 0, cols, 1))

        // Copied because dropping a stale widget below mutates the list.
        for (item in model.items.toList()) {
            if (item === hidden || item.container > 0L) continue
            val parent = if (item.container == CONTAINER_DOCK) dock else cells.getOrNull(item.page) ?: continue
            val view = when (item.type) {
                ItemType.APP -> appView(item, item.container == CONTAINER_DOCK)
                ItemType.FOLDER -> folderView(item, item.container == CONTAINER_DOCK)
                ItemType.WIDGET -> widgetView(item)
                ItemType.SHORTCUT -> shortcutView(item, item.container == CONTAINER_DOCK)
            } ?: continue
            parent.addView(view, CellLayout.Params(item.x, item.y, item.spanX, item.spanY))
        }
        boundRevision = model.revision
    }

    private fun shortcutView(item: Item, inDock: Boolean): IconView? {
        val key = ShortcutKey.parse(item.key) ?: return null
        val info = app.apps.shortcut(key)
        val owner = app.apps.findForPackage(key.pkg, key.serial)
        return IconView(this).apply {
            this.item = item
            label = info?.shortLabel?.toString() ?: item.title.orEmpty()
            icon = app.icons.shortcutIcon(key.toString(), info, owner, app.apps.launcherApps)
            showLabel = !inDock
            setLabelStyle(0xFFFFFFFF.toInt(), true)
            setOnClickListener { launchShortcut(this, key) }
            setOnLongClickListener {
                onIconLongPress(this)
                true
            }
        }
    }

    private fun launchShortcut(view: View, key: ShortcutKey) {
        val bounds = android.graphics.Rect()
        view.getGlobalVisibleRect(bounds)
        val options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()
        try {
            val user = app.apps.userFor(key.serial) ?: throw IllegalStateException("profile gone")
            app.apps.launcherApps.startShortcut(key.pkg, key.id, bounds, options, user)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.shortcut_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDateHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(24.dp, 0, 24.dp, 0)
        addView(TextClock(context).apply {
            format12Hour = "EEEE, MMM d"
            format24Hour = "EEEE, MMM d"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(10f, 0f, 2f, 0xCC000000.toInt())
            setOnClickListener { openCalendar() }
        })
    }

    private fun appView(item: Item, inDock: Boolean): IconView? {
        val entry = app.apps.find(item.key) ?: return null
        return IconView(this).apply {
            this.item = item
            this.entry = entry
            label = entry.label
            icon = app.icons.icon(entry)
            showLabel = !inDock
            setLabelStyle(0xFFFFFFFF.toInt(), true)
            setOnClickListener { launch(this, entry) }
            setOnLongClickListener {
                onIconLongPress(this)
                true
            }
        }
    }

    private fun folderView(item: Item, inDock: Boolean): IconView? {
        val kids = app.model.children(item.id).mapNotNull { app.apps.find(it.key) }
        if (kids.isEmpty()) return null
        return IconView(this).apply {
            this.item = item
            label = item.title ?: getString(R.string.folder_default)
            icon = app.icons.folderIcon(kids)
            showLabel = !inDock
            setLabelStyle(0xFFFFFFFF.toInt(), true)
            setOnClickListener { openFolder(item) }
            setOnLongClickListener {
                onIconLongPress(this)
                true
            }
        }
    }

    private fun widgetView(item: Item): View? {
        val info = widgetManager.getAppWidgetInfo(item.widgetId)
        if (info == null) {
            app.model.items.remove(item)
            app.model.save()
            return null
        }
        val view = widgetViews.getOrPut(item.widgetId) {
            widgetHost.createView(this, item.widgetId, info) as LauncherWidgetView
        }
        view.item = item
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    private fun seedDefaults() {
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val packages = wanted.mapNotNull {
            packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.distinct()
        packages.mapNotNull { app.apps.findForPackage(it) }.take(app.prefs.columns).forEachIndexed { i, entry ->
            app.model.items += Item(app.model.newId(), ItemType.APP, CONTAINER_DOCK, x = i, key = entry.key)
        }
        app.model.save()
        bindAll()
    }

    // endregion

    // region Launching

    private fun launch(view: View, entry: AppEntry) {
        val bounds = android.graphics.Rect()
        view.getGlobalVisibleRect(bounds)
        val options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()
        try {
            app.apps.launcherApps.startMainActivity(entry.component, entry.user, bounds, options)
            app.apps.recordLaunch(entry)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun openGlobalSearch() {
        val google = Intent("android.search.action.GLOBAL_SEARCH").setPackage(FeedClient.GOOGLE_APP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startSafely(google)) drawer.open(focusSearch = true)
    }

    private fun voiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_WEB_SEARCH)
        if (!startSafely(intent)) Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
    }

    private fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query)
        if (!startSafely(intent)) {
            startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
    }

    private fun openCalendar() {
        val uri = Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())
        if (!startSafely(Intent(Intent.ACTION_VIEW, uri))) startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    private fun expandNotifications() {
        runCatching {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        }
    }

    fun uninstall(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.component.packageName))
            .putExtra(Intent.EXTRA_USER, entry.user)
        startSafely(intent)
    }

    private fun openWallpaperPicker() {
        startSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
    }

    // endregion

    // region Long press and popups

    private fun onIconLongPress(view: IconView) {
        val item = view.item ?: return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showItemPopup(view, item, view.entry)
        val bitmap = view.icon ?: return
        drag.begin(item, null, bitmap, view.iconSize, view.iconSize, 1, 1)
    }

    private fun onDrawerLongPress(view: IconView) {
        val entry = view.entry ?: return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showItemPopup(view, null, entry)
        drag.begin(null, entry, app.icons.icon(entry), view.iconSize, view.iconSize, 1, 1)
    }

    private fun onWidgetLongPress(view: LauncherWidgetView) {
        val item = view.item ?: return
        showWidgetPopup(view, item)
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(snapshot))
        drag.begin(item, null, snapshot, width, height, item.spanX, item.spanY)
    }

    private class PopupRow(val icon: Drawable?, val label: CharSequence, val tint: Boolean = true, val action: () -> Unit)

    private fun showItemPopup(anchor: View, item: Item?, entry: AppEntry?) {
        val rows = ArrayList<PopupRow>()
        if (entry != null) {
            shortcutRows(entry).forEach { rows += it }
            rows += PopupRow(getDrawable(R.drawable.ic_info), getString(R.string.app_info)) {
                app.apps.launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
            }
        }
        if (item != null) {
            rows += PopupRow(getDrawable(R.drawable.ic_close), getString(R.string.remove)) { removeItem(item) }
        }
        showPopup(anchor, rows)
    }

    private fun showWidgetPopup(view: LauncherWidgetView, item: Item) {
        val rows = ArrayList<PopupRow>()
        val info = view.appWidgetInfo
        if (info != null && info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) {
            rows += PopupRow(getDrawable(R.drawable.ic_resize), getString(R.string.resize)) { showResize(item, info) }
        }
        rows += PopupRow(getDrawable(R.drawable.ic_close), getString(R.string.remove)) { removeItem(item) }
        showPopup(view, rows)
    }

    private fun showHomeMenu() {
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val rows = listOf(
            PopupRow(getDrawable(R.drawable.ic_wallpaper), getString(R.string.wallpaper)) { openWallpaperPicker() },
            PopupRow(getDrawable(R.drawable.ic_widgets), getString(R.string.widgets)) { showWidgetPicker() },
            PopupRow(getDrawable(R.drawable.ic_lightbulb_outline), getString(R.string.lights)) { LightsPanel(this).show() },
            PopupRow(getDrawable(R.drawable.ic_settings), getString(R.string.home_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        showPopup(null, rows)
    }

    private fun shortcutRows(entry: AppEntry): List<PopupRow> {
        val launcherApps = app.apps.launcherApps
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(entry.component.packageName)
            setActivity(entry.component)
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
            )
        }
        val shortcuts = runCatching { launcherApps.getShortcuts(query, entry.user) }.getOrNull().orEmpty()
        return shortcuts.sortedBy { it.rank }.take(4).map { info ->
            val icon = runCatching { launcherApps.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi) }.getOrNull()
            PopupRow(icon, info.shortLabel ?: info.id, tint = false) {
                runCatching { launcherApps.startShortcut(info, null, null) }
            }
        }
    }

    private fun showPopup(anchor: View?, rows: List<PopupRow>) {
        val surface = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHigh, 0)
        val textColor = MaterialColors.getColor(this, MaterialR.attr.colorOnSurface, 0)
        val iconTint = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant, 0)
        lateinit var dismiss: () -> Unit
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = 24.dp.toFloat()
            }
            elevation = 8.dp.toFloat()
            setPadding(0, 8.dp, 0, 8.dp)
            clipToOutline = true
        }
        rows.forEach { row ->
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 0, 20.dp, 0)
                minimumHeight = 48.dp
                addView(ImageView(context).apply {
                    setImageDrawable(row.icon?.mutate())
                    if (row.tint) setColorFilter(iconTint)
                }, LinearLayout.LayoutParams(22.dp, 22.dp).apply { marginEnd = 14.dp })
                addView(TextView(context).apply {
                    text = row.label
                    setTextColor(textColor)
                    textSize = 14f
                    maxLines = 1
                })
                setOnClickListener {
                    root.dismissAll()
                    drawer.close(animated = false)
                    row.action()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp))
        }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST),
        )
        val width = card.measuredWidth.coerceAtLeast(190.dp)
        val height = card.measuredHeight
        val anchorRect = android.graphics.Rect()
        var x: Float
        var y: Float
        if (anchor != null) {
            anchor.getGlobalVisibleRect(anchorRect)
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            anchorRect.offset(-rootLocation[0], -rootLocation[1])
            x = anchorRect.centerX() - width / 2f
            y = anchorRect.top - height - 8.dp.toFloat()
            if (y < 48.dp) y = anchorRect.bottom + 8.dp.toFloat()
        } else {
            x = root.lastX - width / 2f
            y = root.lastY - height - 16.dp
            if (y < 48.dp) y = root.lastY + 16.dp
        }
        x = x.coerceIn(8.dp.toFloat(), (root.width - width - 8.dp).toFloat())
        y = y.coerceIn(8.dp.toFloat(), (root.height - height - 8.dp).toFloat().coerceAtLeast(8.dp.toFloat()))
        card.translationX = x
        card.translationY = y
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(120).start()
        root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        dismiss = { root.removeView(card) }
        root.pushTransient(
            card,
            inside = { px, py -> px >= x && px <= x + width && py >= y && py <= y + height },
            dismiss = dismiss,
        )
    }

    private fun openFolder(folder: Item) {
        val surface = MaterialColors.getColor(this, MaterialR.attr.colorSurfaceContainerHigh, 0)
        val textColor = MaterialColors.getColor(this, MaterialR.attr.colorOnSurface, 0)
        val kids = app.model.children(folder.id)
        val columns = if (kids.size <= 4) 2 else if (kids.size <= 9) 3 else 4
        val title = EditText(this).apply {
            setText(folder.title ?: getString(R.string.folder_default))
            setSingleLine()
            setTextColor(textColor)
            textSize = 20f
            gravity = Gravity.CENTER
            background = null
        }
        val grid = GridLayout(this).apply { columnCount = columns }
        kids.forEach { child ->
            val entry = app.apps.find(child.key) ?: return@forEach
            grid.addView(IconView(this).apply {
                item = child
                this.entry = entry
                label = entry.label
                icon = app.icons.icon(entry)
                setLabelStyle(textColor, false)
                setOnClickListener {
                    root.dismissAll()
                    launch(this, entry)
                }
                setOnLongClickListener {
                    onIconLongPress(this)
                    true
                }
            }, GridLayout.LayoutParams().apply {
                width = 80.dp
                height = 96.dp
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = 32.dp.toFloat()
            }
            elevation = 12.dp.toFloat()
            setPadding(16.dp, 12.dp, 16.dp, 16.dp)
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(grid)
        }
        val cardWidth = columns * 80.dp + 32.dp
        root.addView(card, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
        root.pushTransient(
            card,
            inside = { px, py -> px >= card.left && px <= card.right && py >= card.top && py <= card.bottom },
            dismiss = {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
                val name = title.text.toString().trim()
                if (name.isNotEmpty() && name != folder.title) {
                    folder.title = name
                    app.model.save()
                    bindAll()
                }
                root.removeView(card)
            },
        )
    }

    private fun showResize(item: Item, info: AppWidgetProviderInfo) {
        val cell = if (item.container == CONTAINER_HOME) workspace.getChildAt(item.page) as? CellLayout else null
        cell ?: return
        // Rebinding replaces the cell this frame measures against, so a finished resize closes it.
        val frame = ResizeFrame(
            this, item, cell, info,
            onResized = {
                root.dismissTop()
                bindAll()
            },
            onDismiss = { root.dismissTop() },
        )
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.pushTransient(frame, inside = { _, _ -> true }, dismiss = { root.removeView(frame) })
    }

    // endregion

    // region Drag callbacks

    fun onDragStarted(canUninstall: Boolean) {
        root.dismissAll()
        if (drawer.isOpen) drawer.close(animated = false)
        extraDragPage = true
        dropUninstall.visibility = if (canUninstall) View.VISIBLE else View.GONE
        dropBar.visibility = View.VISIBLE
        dropBar.bringToFront()
        bindAll()
    }

    fun onDragEnded() {
        extraDragPage = false
        dropBar.visibility = View.INVISIBLE
        hoveredDrop = DROP_NONE
        app.model.save()
        bindAll()
    }

    fun dropTargetAt(x: Float, y: Float): Int {
        if (y > dropBar.height) return DROP_NONE
        if (dropUninstall.visibility != View.VISIBLE) return DROP_REMOVE
        return if (x < root.width / 2f) DROP_REMOVE else DROP_UNINSTALL
    }

    fun highlightDropBar(target: Int) {
        if (target == hoveredDrop) return
        hoveredDrop = target
        dropRemove.alpha = if (target == DROP_UNINSTALL) 0.6f else 1f
        dropUninstall.alpha = if (target == DROP_REMOVE) 0.6f else 1f
    }

    fun cellUnder(x: Float, y: Float, allowDock: Boolean): CellLayout? {
        val location = IntArray(2)
        dock.getLocationInWindow(location)
        if (y >= location[1] && y < location[1] + dock.height) return if (allowDock) dock else null
        return if (workspace.childCount > 0) workspace.currentCell else null
    }

    fun flipPage(direction: Int) {
        workspace.snapTo(workspace.currentPage + direction)
    }

    fun removeItem(item: Item) {
        val model = app.model
        if (item.type == ItemType.WIDGET) {
            widgetHost.deleteAppWidgetId(item.widgetId)
            widgetViews.remove(item.widgetId)
        }
        if (item.type == ItemType.FOLDER) model.items.removeAll(model.children(item.id).toSet())
        model.items.remove(item)
        if (item.type == ItemType.SHORTCUT) {
            // Unpin with the system too, or the app keeps believing the shortcut is on the home screen.
            ShortcutKey.parse(item.key)?.let { removed ->
                val stillPinned = model.items.filter { it.type == ItemType.SHORTCUT }
                    .mapNotNull { ShortcutKey.parse(it.key) }
                    .filter { it.pkg == removed.pkg && it.serial == removed.serial }
                    .map { it.id }
                app.apps.syncPinned(removed.pkg, removed.serial, stillPinned)
            }
        }
        if (item.container > 0) model.normalizeFolder(item.container)
        model.pruneEmptyPages()
        model.save()
        bindAll()
    }

    // endregion

    // region Widgets

    private fun showWidgetPicker() {
        val cell = workspace.currentCell
        WidgetPicker(this) { addWidget(it) }.show(cell.cellWidth, cell.cellHeight)
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureWidget(id, info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_BIND)
        }
    }

    private fun configureWidget(id: Int, info: AppWidgetProviderInfo) {
        val configurable = info.configure != null &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0
        if (configurable) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE, null)
                return
            } catch (e: Exception) {
                Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                widgetHost.deleteAppWidgetId(id)
                return
            }
        }
        placeWidget(id, info)
    }

    private fun placeWidget(id: Int, info: AppWidgetProviderInfo) {
        val cols = app.prefs.columns
        val rows = app.prefs.rows
        val cell = workspace.currentCell
        val (sx, sy) = WidgetPicker.spansFor(info, cell.cellWidth, cell.cellHeight, cols, rows)
        val (page, x, y) = app.model.findFree(workspace.currentPage, sx, sy, cols, rows)
        app.model.items += Item(
            app.model.newId(), ItemType.WIDGET, CONTAINER_HOME, page, x, y, sx, sy, widgetId = id,
        )
        app.model.save()
        bindAll()
        workspace.post { workspace.snapTo(page) }
    }

    @Deprecated("Widget binding and configuration only have activity-result entry points")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        val info = pendingWidgetInfo
        if (id == -1 || info == null) return
        when (requestCode) {
            REQUEST_BIND -> if (resultCode == RESULT_OK) configureWidget(id, info) else abandonWidget(id)
            REQUEST_CONFIGURE -> if (resultCode == RESULT_OK) placeWidget(id, info) else abandonWidget(id)
        }
    }

    private fun abandonWidget(id: Int) {
        widgetHost.deleteAppWidgetId(id)
        pendingWidgetId = -1
        pendingWidgetInfo = null
    }

    // endregion

    private fun onFeedScroll(progress: Float) {
        homeContent.translationX = progress * root.width
        homeContent.alpha = 1f - abs(progress)
    }

    companion object {
        private const val WIDGET_HOST_ID = 1024
        private const val WEATHER_REFRESH_MS = 10 * 60 * 1000L
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
        const val DROP_NONE = 0
        const val DROP_REMOVE = 1
        const val DROP_UNINSTALL = 2
    }
}
