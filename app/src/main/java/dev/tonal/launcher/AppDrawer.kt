package dev.tonal.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/** Full-screen app list that slides up from the bottom, with search and usage-based suggestions. */
class AppDrawer(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private sealed class Row {
        class App(val entry: AppEntry) : Row()
        class WebSearch(val query: String) : Row()
        object Divider : Row()
    }

    private val rows = ArrayList<Row>()
    private val search = EditText(context)
    private val list = RecyclerView(context)
    private val grid = GridLayoutManager(context, 5)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var animator: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var closingDrag = false
    private var tracker: VelocityTracker? = null

    var onLaunch: ((View, AppEntry) -> Unit)? = null
    var onLongPress: ((IconView) -> Unit)? = null
    var onWebSearch: ((String) -> Unit)? = null
    var onProgress: ((Float) -> Unit)? = null

    var progress = 0f
        private set
    val isOpen get() = progress > 0f

    private val surface = MaterialColors.getColor(context, MaterialR.attr.colorSurface, 0)
    private val onSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, 0)
    private val onSurfaceVariant = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, 0)
    private val pillColor = MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainerHigh, 0)

    init {
        setBackgroundColor(surface)
        visibility = INVISIBLE
        isClickable = true

        search.apply {
            hint = context.getString(R.string.search_apps)
            setSingleLine()
            textSize = 16f
            setTextColor(onSurface)
            setHintTextColor(onSurfaceVariant)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                setColor(pillColor)
                cornerRadius = 28.dp.toFloat()
            }
            setPadding(20.dp, 0, 20.dp, 0)
            val glyph = context.getDrawable(R.drawable.ic_search)!!.mutate().apply { setTint(onSurfaceVariant) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
            compoundDrawablePadding = 12.dp
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            setOnEditorActionListener { _, _, _ ->
                val query = text.toString().trim()
                if (query.isEmpty()) return@setOnEditorActionListener false
                val first = rows.firstOrNull() as? Row.App
                if (first != null) onLaunch?.invoke(search, first.entry) else onWebSearch?.invoke(query)
                true
            }
        }

        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (rows[position] is Row.App) 1 else grid.spanCount
        }
        list.layoutManager = grid
        list.adapter = Adapter()
        list.clipToPadding = false
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            search,
            LayoutParams(LayoutParams.MATCH_PARENT, 52.dp, Gravity.TOP).apply { setMargins(16.dp, 0, 16.dp, 0) },
        )
    }

    fun applyInsets(top: Int, bottom: Int, imeBottom: Int) {
        (search.layoutParams as MarginLayoutParams).topMargin = top + 12.dp
        search.requestLayout()
        list.setPadding(8.dp, top + 76.dp, 8.dp, maxOf(bottom, imeBottom) + 8.dp)
    }

    fun setColumns(columns: Int) {
        grid.spanCount = columns
        refresh()
    }

    fun refresh() {
        val app = context.launcherApp
        val query = search.text.toString().trim()
        rows.clear()
        if (query.isEmpty()) {
            val suggested = app.apps.suggested(grid.spanCount)
            if (suggested.isNotEmpty()) {
                suggested.forEach { rows += Row.App(it) }
                rows += Row.Divider
            }
            app.apps.apps.forEach { rows += Row.App(it) }
        } else {
            val q = query.lowercase()
            val matches = app.apps.apps.filter { it.label.lowercase().contains(q) }
            matches.sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }.forEach { rows += Row.App(it) }
            rows += Row.WebSearch(query)
        }
        @Suppress("NotifyDataSetChanged")
        list.adapter?.notifyDataSetChanged()
    }

    fun setProgressNow(value: Float) {
        animator?.cancel()
        applyProgress(value)
    }

    private fun applyProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        visibility = if (progress > 0f) VISIBLE else INVISIBLE
        translationY = (1f - progress) * height.coerceAtLeast(1)
        onProgress?.invoke(progress)
    }

    fun dragBy(pixels: Float) = setProgressNow(progress + pixels / height.coerceAtLeast(1))

    fun release(upwardVelocity: Float) {
        val open = when {
            upwardVelocity > 800f -> true
            upwardVelocity < -800f -> false
            else -> progress > 0.35f
        }
        if (open) open() else close()
    }

    fun open(focusSearch: Boolean = false) {
        if (visibility != VISIBLE) refresh()
        animateTo(1f)
        if (focusSearch) {
            search.requestFocus()
            post {
                context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun close(animated: Boolean = true) {
        hideKeyboard()
        search.setText("")
        search.clearFocus()
        list.scrollToPosition(0)
        if (animated) animateTo(0f) else setProgressNow(0f)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            start()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                closingDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                if (dy > slop && dy > abs(ev.x - downX) && !list.canScrollVertically(-1)) {
                    closingDrag = true
                    lastY = ev.y
                    hideKeyboard()
                    tracker = VelocityTracker.obtain()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!closingDrag) return super.onTouchEvent(ev)
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragBy(lastY - ev.y)
                lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val velocity = -(tracker?.yVelocity ?: 0f)
                tracker?.recycle()
                tracker = null
                closingDrag = false
                if (velocity < -800f || progress < 0.7f) close() else open()
            }
        }
        return true
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.App -> 0
            is Row.WebSearch -> 1
            Row.Divider -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                0 -> IconView(context).apply {
                    setLabelStyle(onSurface, false)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 96.dp)
                }
                1 -> TextView(context).apply {
                    setTextColor(onSurface)
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(20.dp, 0, 20.dp, 0)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dp)
                }
                else -> View(context).apply {
                    setBackgroundColor(onSurfaceVariant and 0x33FFFFFF)
                    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp).apply {
                        setMargins(20.dp, 8.dp, 20.dp, 8.dp)
                    }
                }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.App -> (holder.itemView as IconView).apply {
                    entry = row.entry
                    label = row.entry.label
                    icon = context.launcherApp.icons.icon(row.entry)
                    setOnClickListener { onLaunch?.invoke(this, row.entry) }
                    setOnLongClickListener {
                        onLongPress?.invoke(this)
                        true
                    }
                }
                is Row.WebSearch -> (holder.itemView as TextView).apply {
                    text = context.getString(R.string.search_web, row.query)
                    setOnClickListener { onWebSearch?.invoke(row.query) }
                }
                Row.Divider -> Unit
            }
        }
    }
}
