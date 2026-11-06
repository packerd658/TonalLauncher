package dev.tonal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.core.content.edit
import org.json.JSONObject
import java.util.concurrent.Executors

class AppEntry(val info: LauncherActivityInfo, val serial: Long) {
    val component: ComponentName = info.componentName
    val user: UserHandle = info.user
    val label: String = info.label.toString()
    val key: String = component.flattenToString() + "#" + serial
}

/** Every launchable activity across all profiles, kept current through LauncherApps callbacks. */
class AppRepository(context: Context, private val prefs: Prefs) {
    val launcherApps = context.getSystemService(LauncherApps::class.java)!!
    private val userManager = context.getSystemService(UserManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    var apps: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    var onChanged: (() -> Unit)? = null
    var onPackageRemoved: ((String, Long) -> Unit)? = null

    init {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(pkg: String, user: UserHandle) {
                onPackageRemoved?.invoke(pkg, userManager.getSerialNumberForUser(user))
                reload()
            }
            override fun onPackageAdded(pkg: String, user: UserHandle) = reload()
            override fun onPackageChanged(pkg: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        }, main)
    }

    fun reload(done: (() -> Unit)? = null) {
        worker.execute {
            val list = userManager.userProfiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                launcherApps.getActivityList(null, user).map { AppEntry(it, serial) }
            }.sortedBy { it.label.lowercase() }
            main.post {
                apps = list
                byKey = list.associateBy { it.key }
                done?.invoke()
                onChanged?.invoke()
            }
        }
    }

    fun find(key: String?): AppEntry? = key?.let { byKey[it] }

    fun serialFor(user: UserHandle) = userManager.getSerialNumberForUser(user)

    fun userFor(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    /** The live ShortcutInfo for a pinned shortcut, or null if its app withdrew it. */
    fun shortcut(key: ShortcutKey): ShortcutInfo? {
        val user = userFor(key.serial) ?: return null
        if (!launcherApps.hasShortcutHostPermission()) return null
        val query = LauncherApps.ShortcutQuery()
            .setPackage(key.pkg)
            .setShortcutIds(listOf(key.id))
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
            )
        return runCatching { launcherApps.getShortcuts(query, user)?.firstOrNull() }.getOrNull()
    }

    /** Tells the system which of a package's shortcuts are still on the home screen. */
    fun syncPinned(pkg: String, serial: Long, ids: List<String>) {
        val user = userFor(serial) ?: return
        if (!launcherApps.hasShortcutHostPermission()) return
        runCatching { launcherApps.pinShortcuts(pkg, ids, user) }
    }

    fun findForPackage(pkg: String, serial: Long): AppEntry? =
        apps.firstOrNull { it.component.packageName == pkg && it.serial == serial }

    fun findForPackage(pkg: String): AppEntry? = apps.firstOrNull { it.component.packageName == pkg }

    fun recordLaunch(entry: AppEntry) {
        val usage = JSONObject(prefs.sp.getString(Prefs.USAGE, "{}")!!)
        usage.put(entry.key, usage.optInt(entry.key) + 1)
        prefs.sp.edit { putString(Prefs.USAGE, usage.toString()) }
    }

    fun suggested(count: Int): List<AppEntry> {
        val usage = JSONObject(prefs.sp.getString(Prefs.USAGE, "{}")!!)
        return apps.filter { usage.optInt(it.key) > 0 }
            .sortedByDescending { usage.optInt(it.key) }
            .take(count)
    }
}
