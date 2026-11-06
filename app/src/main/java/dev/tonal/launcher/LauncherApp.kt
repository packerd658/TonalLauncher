package dev.tonal.launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.preference.PreferenceManager

class LauncherApp : Application() {
    lateinit var prefs: Prefs
    lateinit var apps: AppRepository
    lateinit var icons: IconProvider
    lateinit var model: LauncherModel
    lateinit var ha: HomeAssistant

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(PreferenceManager.getDefaultSharedPreferences(this))
        ha = HomeAssistant(prefs)
        apps = AppRepository(this, prefs)
        icons = IconProvider(this, prefs)
        model = LauncherModel(this)
        apps.onPackageRemoved = { pkg, serial -> model.removePackage(pkg, serial) }
    }
}

val Context.launcherApp: LauncherApp get() = applicationContext as LauncherApp

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

class Prefs(val sp: SharedPreferences) {
    val themedIcons get() = sp.getBoolean(THEMED, true)
    val forceThemed get() = sp.getBoolean(FORCE_THEMED, false)
    val iconPack get() = sp.getString(ICON_PACK, "").orEmpty()
    val columns get() = sp.getString(COLUMNS, "5")!!.toInt()
    val rows get() = sp.getString(ROWS, "6")!!.toInt()
    val searchBar get() = sp.getBoolean(SEARCH_BAR, true)
    val feedEnabled get() = sp.getBoolean(FEED, true)
    val haLocal get() = sp.getString(HA_LOCAL, "").orEmpty()
    val haRemote get() = sp.getString(HA_REMOTE, "").orEmpty()
    val haToken get() = sp.getString(HA_TOKEN, "").orEmpty()

    /** Null until the person first picks lights, so defaults can apply until then. */
    val haHidden: Set<String>? get() = sp.getStringSet(HA_HIDDEN, null)

    val haWeather get() = sp.getBoolean(HA_WEATHER, true)

    /** Empty means use the first weather entity Home Assistant has. */
    val haWeatherEntity get() = sp.getString(HA_WEATHER_ENTITY, "").orEmpty()

    companion object {
        const val HA_WEATHER_ENTITY = "ha_weather_entity"
        const val HA_HIDDEN = "ha_hidden"
        const val HA_WEATHER = "ha_weather"
        const val HA_LOCAL = "ha_local"
        const val HA_REMOTE = "ha_remote"
        const val HA_TOKEN = "ha_token"
        const val THEMED = "themed_icons"
        const val FORCE_THEMED = "force_themed"
        const val ICON_PACK = "icon_pack"
        const val COLUMNS = "columns"
        const val ROWS = "rows"
        const val SEARCH_BAR = "search_bar"
        const val FEED = "feed"
        const val USAGE = "usage"
    }
}
