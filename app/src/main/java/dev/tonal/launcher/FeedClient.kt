package dev.tonal.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.WindowManager

/**
 * Client for the Google app's "minus one" overlay, the panel that shows Discover on Pixel Launcher.
 *
 * The protocol is private and unversioned from our side (ILauncherOverlay, from Launcher3's client
 * library), so the transaction numbers below are written by hand. The Google app decides for itself
 * whether to attach; a refusal shows up as overlayStatusChanged never reporting the attached bit.
 */
class FeedClient(
    private val activity: Activity,
    private val onScrollChanged: (Float) -> Unit,
    private val onStatusChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: IBinder? = null
    private var bound = false
    private var windowAttached = false
    private var activityState = 0
    private var serviceVersion = 1

    var attached = false
        private set

    val installed get() = resolve(activity) != null

    private var reconnects = 0

    /**
     * The Google app serves one launcher's feed at a time; when another client (seen with Hyperion's
     * dock) holds it, this one connects but never attaches. One fresh connection is worth a try in
     * case the other let go; beyond that it's up to the person to stop the other app.
     */
    private val attachWatchdog = Runnable {
        if (attached || !windowAttached || reconnects >= 1) return@Runnable
        reconnects++
        reconnect("no attach after ${ATTACH_TIMEOUT_MS}ms")
    }

    private val callback = object : Binder(), IInterface {
        init {
            attachInterface(this, CALLBACK_DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1 -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val progress = data.readFloat()
                    main.post { onScrollChanged(progress) }
                    return true
                }
                2 -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val status = data.readInt()
                    main.post {
                        Log.i(TAG, "overlay status $status")
                        attached = status and 1 != 0
                        lastStatusBits = status
                        if (attached) reconnects = 0
                        onStatusChanged()
                    }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "overlay service connected, api version $serviceVersion")
            overlay = service
            sendAttach()
            onStatusChanged()
            main.removeCallbacks(attachWatchdog)
            main.postDelayed(attachWatchdog, ATTACH_TIMEOUT_MS)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.i(TAG, "overlay service died")
            overlay = null
            attached = false
            onStatusChanged()
            // Android would rebind on its own, but the feed came back blank that way on a Pixel;
            // a fresh binding, as on a restart, brought it back.
            main.postDelayed({ if (bound) reconnect("service died") }, 500)
        }
    }

    private var generation = 0

    private fun reconnect(reason: String) {
        Log.i(TAG, "reconnecting: $reason")
        generation++
        disconnect()
        connect()
    }

    fun connect() {
        if (bound) return
        // Each reconnect asks with a distinct address: Android keys bindings by intent, so reusing one
        // could hand back the old connection instead of a fresh one.
        val intent = intent(activity, if (generation > 0) generation.toString() else null)
        val info = resolve(activity)
        if (info == null) {
            Log.i(TAG, "overlay service not found")
            onStatusChanged()
            return
        }
        serviceVersion = info.metaData?.getInt("service.api.version", 1) ?: 1
        bound = runCatching {
            activity.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY)
        }.onFailure { Log.w(TAG, "bindService failed", it) }.getOrDefault(false)
    }

    fun disconnect() {
        main.removeCallbacks(attachWatchdog)
        if (bound) {
            runCatching { activity.unbindService(connection) }
        }
        bound = false
        overlay = null
        attached = false
    }

    fun onWindowAttached() {
        windowAttached = true
        sendAttach()
    }

    fun onWindowDetached(changingConfigurations: Boolean) {
        windowAttached = false
        attached = false
        oneway(WINDOW_DETACHED) { writeInt(if (changingConfigurations) 1 else 0) }
    }

    fun setStarted(started: Boolean) {
        activityState = if (started) activityState or STATE_STARTED else activityState and STATE_STARTED.inv()
        // Coming home is the moment to notice a connection that went bad while another app was open.
        val service = overlay
        if (started && bound && service != null && (!service.isBinderAlive || (windowAttached && !attached))) {
            reconnect("unhealthy on return home")
            return
        }
        sendActivityState()
    }

    fun setResumed(resumed: Boolean) {
        activityState = if (resumed) activityState or STATE_RESUMED else activityState and STATE_RESUMED.inv()
        sendActivityState()
    }

    fun startScroll() = oneway(START_SCROLL) {}
    fun onScroll(progress: Float) = oneway(ON_SCROLL) { writeFloat(progress) }
    fun endScroll() = oneway(END_SCROLL) {}
    fun close() = oneway(CLOSE_OVERLAY) { writeInt(1) }

    private fun sendAttach() {
        if (overlay == null || !windowAttached) return
        val params = activity.window.attributes
        if (serviceVersion >= 3) {
            val bundle = Bundle().apply {
                putParcelable("layout_params", params)
                putParcelable("configuration", activity.resources.configuration)
                putInt("client_options", CLIENT_OPTIONS)
            }
            oneway(WINDOW_ATTACHED_2) {
                writeInt(1)
                bundle.writeToParcel(this, 0)
                writeStrongBinder(callback)
            }
        } else {
            oneway(WINDOW_ATTACHED) {
                writeInt(1)
                params.writeToParcel(this, 0)
                writeStrongBinder(callback)
                writeInt(CLIENT_OPTIONS)
            }
        }
        sendActivityState()
    }

    private fun sendActivityState() {
        if (serviceVersion >= 4) {
            oneway(SET_ACTIVITY_STATE) { writeInt(activityState) }
        } else if (activityState and STATE_RESUMED != 0) {
            oneway(ON_RESUME) {}
        } else {
            oneway(ON_PAUSE) {}
        }
    }

    private inline fun oneway(code: Int, write: Parcel.() -> Unit) {
        val binder = overlay ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(OVERLAY_DESCRIPTOR)
            data.write()
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "overlay call $code failed", e)
        } finally {
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "TonalFeed"
        private const val ATTACH_TIMEOUT_MS = 3000L
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        private const val OVERLAY_DESCRIPTOR = "com.google.android.libraries.launcherclient.ILauncherOverlay"
        private const val CALLBACK_DESCRIPTOR = "com.google.android.libraries.launcherclient.ILauncherOverlayCallback"

        private const val START_SCROLL = 1
        private const val ON_SCROLL = 2
        private const val END_SCROLL = 3
        private const val WINDOW_ATTACHED = 4
        private const val WINDOW_DETACHED = 5
        private const val CLOSE_OVERLAY = 6
        private const val ON_PAUSE = 7
        private const val ON_RESUME = 8
        private const val WINDOW_ATTACHED_2 = 14
        private const val SET_ACTIVITY_STATE = 16

        private const val STATE_STARTED = 1
        private const val STATE_RESUMED = 2

        // Bit 0 enables the overlay, bit 1 hotword, bit 2 the home screen feed.
        private const val CLIENT_OPTIONS = 0b111

        /** Last status bits reported, kept across activity instances for the settings screen. */
        var lastStatusBits = 0
            private set

        val isAttached get() = lastStatusBits and 1 != 0

        private fun intent(context: Context, attempt: String? = null) = Intent("com.android.launcher3.WINDOW_OVERLAY")
            .setPackage(GOOGLE_APP)
            .setData(
                Uri.parse("app://${context.packageName}:${Process.myUid()}").buildUpon()
                    .appendQueryParameter("v", "7")
                    .appendQueryParameter("cv", "9")
                    .apply { if (attempt != null) appendQueryParameter("attempt", attempt) }
                    .build(),
            )

        fun resolve(context: Context) = context.packageManager
            .resolveService(intent(context), PackageManager.GET_META_DATA)?.serviceInfo
    }
}
