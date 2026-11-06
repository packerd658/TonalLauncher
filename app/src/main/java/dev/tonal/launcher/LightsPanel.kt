package dev.tonal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bottom sheet of Home Assistant lights, grouped by area, kept fresh while it's open. */
class LightsPanel(private val context: Context) {
    private val ha = context.launcherApp.ha
    private val handler = Handler(Looper.getMainLooper())
    private val dialog = BottomSheetDialog(context)
    private val subtitle = TextView(context)
    private val allOff = MaterialButton(context, null, MaterialR.attr.materialButtonTonalStyle)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = HashMap<String, LightTile>()
    private var lights: List<Light> = emptyList()
    private var allLights: List<Light> = emptyList()
    private var layoutKey = ""
    private var showing = false
    private val onSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, Color.BLACK)
    private val onSurfaceVariant = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun show() {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 8.dp, 16.dp, 8.dp)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    setText(R.string.lights)
                    textSize = 24f
                    setTextColor(onSurface)
                })
                addView(subtitle.apply {
                    textSize = 14f
                    setTextColor(onSurfaceVariant)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(allOff.apply {
                setText(R.string.lights_all_off)
                visibility = View.GONE
                setOnClickListener { turnAllOff() }
            })
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32.dp)
            addView(header)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(NestedScrollView(context).apply { addView(body) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener {
            showing = false
            handler.removeCallbacks(poll)
        }
        showMessage(context.getString(R.string.lights_loading), loading = true)
        dialog.show()
        showing = true
        handler.post(poll)
    }

    private fun refresh() {
        ha.lights { result ->
            if (!showing) return@lights
            result.onSuccess { bind(it) }.onFailure { showError(it) }
        }
    }

    private fun bind(all: List<Light>) {
        allLights = all
        val hidden = ha.hiddenIds(all)
        val list = all.filter { it.id !in hidden }
        lights = list
        subtitle.setText(if (ha.isRemote) R.string.lights_remote else R.string.lights_local)
        allOff.visibility = if (list.any { it.on }) View.VISIBLE else View.GONE
        if (all.isEmpty()) {
            showMessage(context.getString(R.string.lights_none))
            return
        }
        if (list.isEmpty()) {
            showMessage(context.getString(R.string.lights_all_hidden), action = R.string.home_settings) {
                dialog.dismiss()
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
            return
        }
        // Only rebuild when the set of lights or their rooms changed, so a poll never interrupts a drag.
        val key = list.joinToString { it.id + "@" + it.area }
        if (key != layoutKey) {
            layoutKey = key
            buildGrid(list)
        }
        list.forEach { tiles[it.id]?.light = it }
    }

    private fun buildGrid(list: List<Light>) {
        content.removeAllViews()
        tiles.clear()
        val groups = list.groupBy { it.area ?: context.getString(R.string.lights_other) }
            .toSortedMap(compareBy({ it == context.getString(R.string.lights_other) }, { it.lowercase() }))
        val showHeaders = groups.size > 1 || list.any { it.area != null }
        groups.forEach { (area, members) ->
            if (showHeaders) {
                content.addView(TextView(context).apply {
                    text = area
                    textSize = 14f
                    setTextColor(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
                    setPadding(28.dp, 16.dp, 28.dp, 8.dp)
                })
            }
            members.chunked(2).forEach { pair ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16.dp, 0, 16.dp, 0)
                }
                pair.forEachIndexed { i, light ->
                    val tile = LightTile(context, light, ::toggle, ::setBrightness, ::openDetail)
                    tiles[light.id] = tile
                    row.addView(tile, LinearLayout.LayoutParams(0, 88.dp, 1f).apply {
                        if (i == 0) marginEnd = 4.dp else marginStart = 4.dp
                        bottomMargin = 8.dp
                    })
                }
                if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 88.dp, 1f).apply { marginStart = 4.dp })
                content.addView(row)
            }
        }
    }

    private fun showMessage(text: String, loading: Boolean = false, action: Int? = null, onAction: (() -> Unit)? = null) {
        layoutKey = ""
        tiles.clear()
        content.removeAllViews()
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32.dp, 32.dp, 32.dp, 32.dp)
            if (loading) addView(ProgressBar(context))
            addView(TextView(context).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(onSurfaceVariant)
                setPadding(0, 12.dp, 0, 12.dp)
            })
            if (action != null) {
                addView(MaterialButton(context).apply {
                    setText(action)
                    setOnClickListener { onAction?.invoke() }
                })
            }
        })
    }

    private fun showError(error: Throwable) {
        subtitle.text = ""
        allOff.visibility = View.GONE
        val openSettings = {
            dialog.dismiss()
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        when ((error as? HaException)?.kind) {
            HaException.Kind.NOT_CONFIGURED ->
                showMessage(context.getString(R.string.lights_not_configured), action = R.string.home_settings, onAction = openSettings)
            HaException.Kind.UNAUTHORIZED ->
                showMessage(context.getString(R.string.lights_unauthorized), action = R.string.home_settings, onAction = openSettings)
            else -> showMessage(context.getString(R.string.lights_unreachable), action = R.string.lights_retry) {
                showMessage(context.getString(R.string.lights_loading), loading = true)
                refresh()
            }
        }
    }

    private fun toggle(light: Light) {
        if (light.on) {
            light.state = "off"
            ha.turnOff(listOf(light.id), ::afterCommand)
        } else {
            light.state = "on"
            ha.turnOn(listOf(light.id), callback = ::afterCommand)
        }
        tiles[light.id]?.invalidate()
        allOff.visibility = if (lights.any { it.on }) View.VISIBLE else View.GONE
    }

    private fun setBrightness(light: Light, percent: Int) {
        if (percent <= 0) {
            light.state = "off"
            ha.turnOff(listOf(light.id), ::afterCommand)
        } else {
            light.state = "on"
            light.brightness = (percent * 255 / 100f).roundToInt()
            ha.turnOn(listOf(light.id), JSONObject().put("brightness_pct", percent), ::afterCommand)
        }
        tiles[light.id]?.invalidate()
        allOff.visibility = if (lights.any { it.on }) View.VISIBLE else View.GONE
    }

    private fun turnAllOff() {
        val on = lights.filter { it.on }
        if (on.isEmpty()) return
        on.forEach { it.state = "off" }
        tiles.values.forEach { it.invalidate() }
        allOff.visibility = View.GONE
        ha.turnOff(on.map { it.id }, ::afterCommand)
    }

    /** Re-read soon after a command so the tiles show what the lights actually did. */
    private fun afterCommand(result: Result<Unit>) {
        result.onFailure { showError(it) }
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 700)
    }

    private fun openDetail(light: Light) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }
        fun label(text: Int) = TextView(context).apply {
            setText(text)
            textSize = 14f
            setTextColor(onSurfaceVariant)
            setPadding(0, 12.dp, 0, 0)
        }
        if (light.dimmable) {
            column.addView(label(R.string.lights_brightness))
            column.addView(Slider(context).apply {
                valueFrom = 1f
                valueTo = 100f
                stepSize = 1f
                isTickVisible = false
                value = (light.level * 100).roundToInt().coerceIn(1, 100).toFloat()
                setLabelFormatter { "${it.roundToInt()}%" }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = setBrightness(light, slider.value.roundToInt())
                })
            })
        }
        if (light.supportsTemperature && light.maxKelvin > light.minKelvin) {
            column.addView(label(R.string.lights_temperature))
            column.addView(Slider(context).apply {
                valueFrom = light.minKelvin.toFloat()
                valueTo = light.maxKelvin.toFloat()
                value = (light.kelvin ?: (light.minKelvin + light.maxKelvin) / 2)
                    .coerceIn(light.minKelvin, light.maxKelvin).toFloat()
                setLabelFormatter { "${it.roundToInt()} K" }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) {
                        light.state = "on"
                        ha.turnOn(listOf(light.id), JSONObject().put("color_temp_kelvin", slider.value.roundToInt()), ::afterCommand)
                    }
                })
            })
        }
        if (light.supportsColor) {
            column.addView(label(R.string.lights_color))
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8.dp, 0, 8.dp)
                SWATCH_HUES.forEach { hue ->
                    addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.HSVToColor(floatArrayOf(hue, 0.75f, 1f)))
                        }
                        contentDescription = context.getString(R.string.lights_color)
                        setOnClickListener {
                            light.state = "on"
                            ha.turnOn(listOf(light.id), JSONObject().put("hs_color", JSONArray(listOf(hue, 75))), ::afterCommand)
                        }
                    }, LinearLayout.LayoutParams(0, 36.dp, 1f).apply { setMargins(4.dp, 0, 4.dp, 0) })
                }
            })
        }
        if (column.childCount == 0) {
            column.addView(TextView(context).apply {
                setText(R.string.lights_onoff_only)
                setTextColor(onSurfaceVariant)
            })
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(light.name)
            .setIcon(context.getDrawable(R.drawable.ic_lightbulb)?.mutate()?.apply {
                setTint(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
            })
            .setView(column)
            .setPositiveButton(R.string.lights_done, null)
            .setNeutralButton(R.string.lights_hide) { _, _ ->
                ha.hide(light.id, allLights)
                bind(allLights)
            }
            .show()
    }

    private companion object {
        const val POLL_MS = 4000L
        val SWATCH_HUES = listOf(0f, 30f, 55f, 120f, 185f, 220f, 275f, 320f)
    }
}

/**
 * One light, drawn like Android's device controls: the card fills from the left with brightness.
 * Tap toggles, a sideways drag sets brightness, long press opens the details.
 */
@SuppressLint("ViewConstructor")
class LightTile(
    context: Context,
    light: Light,
    private val onToggle: (Light) -> Unit,
    private val onBrightness: (Light, Int) -> Unit,
    private val onDetail: (Light) -> Unit,
) : View(context) {
    var light: Light = light
        set(value) {
            field = value
            contentDescription = value.name
            if (!dragging) invalidate()
        }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val background = MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainerHigh, Color.LTGRAY)
    private val fill = MaterialColors.getColor(context, MaterialR.attr.colorPrimaryContainer, Color.YELLOW)
    private val onFill = MaterialColors.getColor(context, MaterialR.attr.colorOnPrimaryContainer, Color.BLACK)
    private val onBackground = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * resources.displayMetrics.scaledDensity }
    private val statePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * resources.displayMetrics.scaledDensity }
    private val shape = Path()
    private val rect = RectF()
    private var downX = 0f
    private var downY = 0f
    private var startLevel = 0f
    private var dragLevel = 0f
    private var dragging = false
    private var moved = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onDetail(this.light)
    }

    init {
        contentDescription = light.name
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        shape.reset()
        shape.addRoundRect(rect, 24.dp.toFloat(), 24.dp.toFloat(), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val level = if (dragging) dragLevel else light.level
        val lit = level > 0f
        val fillRight = width * level
        alpha = if (light.available) 1f else 0.5f
        canvas.save()
        canvas.clipPath(shape)
        paint.color = background
        canvas.drawRect(rect, paint)
        paint.color = fill
        canvas.drawRect(0f, 0f, fillRight, height.toFloat(), paint)
        canvas.restore()

        // Content is drawn twice, each pass clipped to its side of the fill edge, so text stays
        // readable where the edge cuts through it.
        canvas.save()
        canvas.clipRect(fillRight, 0f, width.toFloat(), height.toFloat())
        drawContent(canvas, lit, level, onBackground)
        canvas.restore()
        if (fillRight > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, fillRight, height.toFloat())
            drawContent(canvas, lit, level, onFill)
            canvas.restore()
        }
    }

    private fun drawContent(canvas: Canvas, lit: Boolean, level: Float, color: Int) {
        val left = 16.dp
        val icon = context.getDrawable(if (lit) R.drawable.ic_lightbulb else R.drawable.ic_lightbulb_outline)!!.mutate()
        icon.setTint(color)
        icon.setBounds(left, 16.dp, left + 24.dp, 40.dp)
        icon.draw(canvas)

        namePaint.color = color
        statePaint.color = color
        val name = TextUtils.ellipsize(light.name, namePaint, width - left * 2f, TextUtils.TruncateAt.END)
        canvas.drawText(name, 0, name.length, left.toFloat(), height - 34.dp.toFloat(), namePaint)
        val state = when {
            !light.available -> context.getString(R.string.lights_unavailable)
            !lit -> context.getString(R.string.lights_off)
            !light.dimmable -> context.getString(R.string.lights_on)
            else -> "${(level * 100).roundToInt()}%"
        }
        canvas.drawText(state, left.toFloat(), height - 14.dp.toFloat(), statePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!light.available) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startLevel = light.level
                dragging = false
                moved = false
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    removeCallbacks(longPress)
                    if (light.dimmable && abs(dx) > abs(dy)) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) {
                    dragLevel = (startLevel + dx / width).coerceIn(0f, 1f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when {
                    dragging -> {
                        dragging = false
                        onBrightness(light, (dragLevel * 100).roundToInt())
                    }
                    !moved && !longPressed -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onToggle(light)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                dragging = false
                invalidate()
            }
        }
        return true
    }
}
