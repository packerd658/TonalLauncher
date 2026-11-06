package dev.tonal.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, Fragment())
                .commit()
        }
    }

    class Fragment : PreferenceFragmentCompat() {
        private val app get() = requireContext().launcherApp
        private lateinit var preview: PreviewPreference
        private lateinit var defaultHome: RowPreference
        private lateinit var iconPack: RowPreference
        private lateinit var grid: RowPreference
        private lateinit var feedStatus: RowPreference
        private lateinit var haTest: RowPreference
        private lateinit var haChoose: RowPreference

        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.THEMED, Prefs.FORCE_THEMED, Prefs.ICON_PACK -> {
                    app.icons.clear()
                    preview.refresh()
                    updateSummaries()
                }
                Prefs.SEARCH_BAR, Prefs.COLUMNS, Prefs.ROWS -> {
                    preview.refresh()
                    updateSummaries()
                }
                Prefs.FEED -> updateFeedStatus()
                Prefs.HA_LOCAL, Prefs.HA_REMOTE, Prefs.HA_TOKEN -> {
                    haTest.setSummary(R.string.pref_ha_test_idle)
                    haTest.setIconTint(R.drawable.ic_lightbulb, MaterialColors.getColor(requireContext(), androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context: Context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            preview = PreviewPreference(context)
            screen.addPreference(preview)

            val start = SectionHeader(context, R.string.pref_default_home).apply { title = null }
            screen.addPreference(start)
            defaultHome = RowPreference(context, R.drawable.ic_home).apply {
                setTitle(R.string.pref_default_home)
                setSummary(R.string.pref_default_home_summary)
                setOnPreferenceClickListener {
                    val roles = context.getSystemService(RoleManager::class.java)
                    startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    true
                }
            }
            start.addPreference(defaultHome)

            val style = SectionHeader(context, R.string.pref_category_style)
            screen.addPreference(style)
            style.addPreference(RowPreference(context, R.drawable.ic_wallpaper).apply {
                setTitle(R.string.wallpaper)
                setSummary(R.string.pref_wallpaper_summary)
                setOnPreferenceClickListener {
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper)))
                    }
                    true
                }
            })
            style.addPreference(RowSwitch(context, R.drawable.ic_palette).apply {
                key = Prefs.THEMED
                setTitle(R.string.pref_themed)
                setSummary(R.string.pref_themed_summary)
                setDefaultValue(true)
            })
            val forceThemed = RowSwitch(context, R.drawable.ic_auto_awesome).apply {
                key = Prefs.FORCE_THEMED
                setTitle(R.string.pref_force)
                setSummary(R.string.pref_force_summary)
                setDefaultValue(false)
            }
            style.addPreference(forceThemed)
            // Only resolvable once both switches are in the hierarchy.
            forceThemed.dependency = Prefs.THEMED
            iconPack = RowPreference(context, R.drawable.ic_category).apply {
                setTitle(R.string.pref_pack)
                setOnPreferenceClickListener {
                    pickIconPack()
                    true
                }
            }
            style.addPreference(iconPack)

            val layout = SectionHeader(context, R.string.pref_category_layout)
            screen.addPreference(layout)
            grid = RowPreference(context, R.drawable.ic_apps).apply {
                setTitle(R.string.pref_grid)
                setOnPreferenceClickListener {
                    pickGrid()
                    true
                }
            }
            layout.addPreference(grid)
            layout.addPreference(RowSwitch(context, R.drawable.ic_search).apply {
                key = Prefs.SEARCH_BAR
                setTitle(R.string.pref_search_bar)
                setSummary(R.string.pref_search_bar_summary)
                setDefaultValue(true)
            })

            val feed = SectionHeader(context, R.string.pref_category_feed)
            screen.addPreference(feed)
            feed.addPreference(RowSwitch(context, R.drawable.ic_feed).apply {
                key = Prefs.FEED
                setTitle(R.string.pref_feed)
                setSummary(R.string.pref_feed_summary_on)
                setDefaultValue(true)
            })
            feedStatus = RowPreference(context).apply {
                setTitle(R.string.pref_feed_status)
                isSelectable = false
                setOnPreferenceClickListener {
                    runCatching {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:" + FeedClient.GOOGLE_APP)),
                        )
                    }
                    true
                }
            }
            feed.addPreference(feedStatus)

            val homeAssistant = SectionHeader(context, R.string.pref_category_ha)
            screen.addPreference(homeAssistant)
            homeAssistant.addPreference(addressRow(context, Prefs.HA_LOCAL, R.string.pref_ha_local, R.string.pref_ha_local_hint, R.drawable.ic_link))
            homeAssistant.addPreference(addressRow(context, Prefs.HA_REMOTE, R.string.pref_ha_remote, R.string.pref_ha_remote_hint, R.drawable.ic_public))
            homeAssistant.addPreference(RowEditText(context, R.drawable.ic_key).apply {
                key = Prefs.HA_TOKEN
                setTitle(R.string.pref_ha_token)
                setDialogMessage(R.string.pref_ha_token_help)
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    it.setSingleLine()
                }
                // Never echo the token itself.
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    getString(if (p.text.isNullOrBlank()) R.string.pref_not_set else R.string.pref_ha_token_set)
                }
            })
            haTest = RowPreference(context, R.drawable.ic_lightbulb).apply {
                setTitle(R.string.pref_ha_test)
                setSummary(R.string.pref_ha_test_idle)
                setOnPreferenceClickListener {
                    testConnection()
                    true
                }
            }
            homeAssistant.addPreference(haTest)
            haChoose = RowPreference(context, R.drawable.ic_lightbulb_outline).apply {
                setTitle(R.string.pref_ha_choose)
                setSummary(R.string.pref_ha_choose_idle)
                setOnPreferenceClickListener {
                    chooseLights()
                    true
                }
            }
            homeAssistant.addPreference(haChoose)
            homeAssistant.addPreference(RowSwitch(context, R.drawable.ic_weather_partly).apply {
                key = Prefs.HA_WEATHER
                setTitle(R.string.pref_ha_weather)
                setSummary(R.string.pref_ha_weather_summary)
                setDefaultValue(true)
            })
            homeAssistant.addPreference(RowEditText(context, R.drawable.ic_weather_cloudy).apply {
                key = Prefs.HA_WEATHER_ENTITY
                setTitle(R.string.pref_ha_weather_entity)
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    it.hint = "weather.forecast_home"
                    it.setSingleLine()
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    p.text?.takeIf { it.isNotBlank() } ?: getString(R.string.pref_ha_weather_entity_auto)
                }
            })

            val about = SectionHeader(context, R.string.pref_category_about)
            screen.addPreference(about)
            about.addPreference(RowPreference(context, R.drawable.ic_info).apply {
                setTitle(R.string.pref_version)
                summary = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                isSelectable = false
            })

            updateSummaries()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.clipToPadding = false
            listView.isVerticalScrollBarEnabled = false
            ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                list.setPadding(0, 0, 0, bars.bottom + 24.dp)
                insets
            }
            setDivider(null)
            // The apps list may still be loading if settings opened before the launcher did.
            if (app.apps.apps.isEmpty()) app.apps.reload { if (isAdded) preview.refresh() }
        }

        override fun onResume() {
            super.onResume()
            app.prefs.sp.registerOnSharedPreferenceChangeListener(listener)
            val roles = requireContext().getSystemService(RoleManager::class.java)
            defaultHome.parent?.isVisible = !roles.isRoleHeld(RoleManager.ROLE_HOME)
            updateFeedStatus()
            updateSummaries()
            preview.refresh()
        }

        override fun onPause() {
            super.onPause()
            app.prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        }

        private fun updateSummaries() {
            val prefs = app.prefs
            grid.summary = getString(R.string.pref_grid_summary, prefs.columns, prefs.rows)
            val pack = prefs.iconPack
            iconPack.summary = if (pack.isEmpty()) {
                getString(R.string.pref_pack_none_summary)
            } else {
                IconPack.installed(requireContext()).firstOrNull { it.first == pack }?.second ?: pack
            }
        }

        private fun updateFeedStatus() {
            val context = requireContext()
            val connected = app.prefs.feedEnabled && FeedClient.isAttached
            val color = MaterialColors.getColor(
                context, if (connected) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError, Color.RED,
            )
            feedStatus.setIconTint(if (connected) R.drawable.ic_check_circle else R.drawable.ic_error, color)
            // Only the stuck case has something to tap through to.
            feedStatus.isSelectable = app.prefs.feedEnabled && !connected && FeedClient.resolve(context) != null
            feedStatus.setSummary(
                when {
                    !app.prefs.feedEnabled -> R.string.feed_status_off
                    FeedClient.resolve(context) == null -> R.string.feed_status_no_app
                    FeedClient.isAttached -> R.string.feed_status_connected
                    else -> R.string.feed_status_refused
                },
            )
        }

        private fun addressRow(context: Context, prefKey: String, title: Int, hint: Int, icon: Int) =
            RowEditText(context, icon).apply {
                key = prefKey
                setTitle(title)
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    it.hint = getString(hint)
                    it.setSingleLine()
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    p.text?.takeIf { it.isNotBlank() } ?: getString(R.string.pref_not_set)
                }
            }

        private fun testConnection() {
            val context = requireContext()
            haTest.setSummary(R.string.pref_ha_testing)
            app.ha.lights { result ->
                if (!isAdded) return@lights
                result.onSuccess { lights ->
                    val via = getString(if (app.ha.isRemote) R.string.pref_ha_via_remote else R.string.pref_ha_via_home)
                    haTest.summary = getString(R.string.pref_ha_test_ok, lights.size, via)
                    haTest.setIconTint(R.drawable.ic_check_circle, MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
                }.onFailure { error ->
                    haTest.setSummary(
                        when ((error as? HaException)?.kind) {
                            HaException.Kind.NOT_CONFIGURED -> R.string.lights_not_configured
                            HaException.Kind.UNAUTHORIZED -> R.string.lights_unauthorized
                            else -> R.string.lights_unreachable
                        },
                    )
                    haTest.setIconTint(R.drawable.ic_error, MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, Color.RED))
                }
            }
        }

        private fun chooseLights() {
            val context = requireContext()
            haChoose.setSummary(R.string.pref_ha_testing)
            app.ha.lights { result ->
                if (!isAdded) return@lights
                val all = result.getOrElse { error ->
                    haChoose.setSummary(
                        when ((error as? HaException)?.kind) {
                            HaException.Kind.NOT_CONFIGURED -> R.string.lights_not_configured
                            HaException.Kind.UNAUTHORIZED -> R.string.lights_unauthorized
                            else -> R.string.lights_unreachable
                        },
                    )
                    return@lights
                }.sortedWith(compareBy({ it.area == null }, { it.area?.lowercase() }, { it.name.lowercase() }))
                val hidden = app.ha.hiddenIds(all)
                val checked = BooleanArray(all.size) { all[it].id !in hidden }
                updateChooseSummary(checked.count { it }, all.size)
                val labels = all.map { light -> light.area?.let { "${light.name} · $it" } ?: light.name }.toTypedArray()
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.pref_ha_choose)
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        app.ha.setHidden(all.indices.filter { !checked[it] }.map { all[it].id }.toSet())
                        updateChooseSummary(checked.count { it }, all.size)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        private fun updateChooseSummary(shown: Int, total: Int) {
            haChoose.summary = getString(R.string.pref_ha_choose_summary, shown, total)
        }

        private fun pickGrid() {
            val options = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6, 5 to 7)
            val current = options.indexOf(app.prefs.columns to app.prefs.rows)
            val labels = options.map { (c, r) -> "$c × $r" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_grid)
                .setIcon(R.drawable.ic_apps)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    val (c, r) = options[which]
                    app.prefs.sp.edit {
                        putString(Prefs.COLUMNS, c.toString())
                        putString(Prefs.ROWS, r.toString())
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun pickIconPack() {
            val context = requireContext()
            val packs = listOf("" to getString(R.string.pref_pack_none)) + IconPack.installed(context)
            val selected = app.prefs.iconPack
            val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLACK)
            val onSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, Color.BLACK)
            val adapter = object : BaseAdapter() {
                override fun getCount() = packs.size
                override fun getItem(position: Int) = packs[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val (pkg, label) = packs[position]
                    val icon = if (pkg.isEmpty()) {
                        context.getDrawable(R.drawable.ic_palette)?.mutate()?.apply { setTint(primary) }
                    } else {
                        runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
                    }
                    return LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = 64.dp
                        setPadding(24.dp, 0, 24.dp, 0)
                        addView(ImageView(context).apply {
                            setImageDrawable(icon)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }, LinearLayout.LayoutParams(40.dp, 40.dp))
                        addView(TextView(context).apply {
                            text = label
                            textSize = 16f
                            setTextColor(onSurface)
                            setPadding(16.dp, 0, 16.dp, 0)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        if (pkg == selected) {
                            addView(ImageView(context).apply {
                                setImageDrawable(context.getDrawable(R.drawable.ic_check)?.mutate()?.apply { setTint(primary) })
                            }, LinearLayout.LayoutParams(24.dp, 24.dp))
                        }
                    }
                }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pref_pack)
                .setAdapter(adapter) { _, which -> app.prefs.sp.edit { putString(Prefs.ICON_PACK, packs[which].first) } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
