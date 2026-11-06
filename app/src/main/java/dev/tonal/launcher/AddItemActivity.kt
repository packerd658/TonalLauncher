package dev.tonal.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Where Android sends "add to home screen" requests from apps, such as a Google Maps route or a
 * Chrome page. Shows what would be added and pins it on the person's say-so.
 */
class AddItemActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = launcherApp
        val request = app.apps.launcherApps.getPinItemRequest(intent)
        val info = request?.shortcutInfo
        if (request == null || !request.isValid || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || info == null) {
            finish()
            return
        }

        val serial = app.apps.serialFor(info.userHandle)
        val key = ShortcutKey(info.`package`, info.id, serial)
        val owner = app.apps.findForPackage(info.`package`, serial)
        val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(ImageView(context).apply {
                setImageBitmap(app.icons.shortcutIcon(key.toString(), info, owner, app.apps.launcherApps))
            }, LinearLayout.LayoutParams(64.dp, 64.dp))
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, 0))
                setPadding(0, 12.dp, 0, 0)
            })
            owner?.let {
                addView(TextView(context).apply {
                    text = it.label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, 0))
                })
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_home)
            .setView(preview)
            .setPositiveButton(R.string.add) { _, _ ->
                if (request.accept()) {
                    app.model.addShortcut(key, label, app.prefs.columns, app.prefs.rows)
                    Toast.makeText(this, getString(R.string.added_to_home, label), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }
}
