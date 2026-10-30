package app.sona.music

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.Region
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.hypot

/** One small overlay window: editing controls disappear together when it is locked. */
internal class FixedLyricsWindow(context: Context, val lyrics: DanmakuLyricsView,
    private val changeBounds: (dx: Int, dy: Int, resize: Boolean, finished: Boolean) -> Unit,
    private val changeOptions: (FloatingLyricsConfig) -> Unit,
    private val resizeForSettings: () -> Unit,
    private val touchAreaChanged: (Region) -> Unit) : FrameLayout(context) {
    companion object {
        const val ToolbarDp = 48
        const val MinWidthDp = 192
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private var options = FloatingLyricsConfig()
    var isSettingsOpen = false
        private set
    private val editor = FrameLayout(context)
    private val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val placeholder = TextView(context).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val handle = ImageButton(context).apply {
        setImageResource(R.drawable.ic_floating_resize)
        imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        setPadding(0, 0, 0, 0)
        background = null
        contentDescription = "拖动弧线调整窗口大小，点按放大"
        setOnClickListener { changeBounds(dp(24), dp(12), true, true) }
        setOnTouchListener { _, event -> drag(event, true) }
    }
    private val settingsPanel = FloatingLyricsControls(context, { settings(false) }, changeOptions)
    private var locked = true
    private var controlsVisible = false
    val capturesBackground: Boolean get() = !locked && (controlsVisible || isSettingsOpen)
    private val touchRegion = Region()
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        touchRegion.setEmpty()
        if (!locked) {
            if (capturesBackground) touchRegion.set(0, 0, width, height) else lyricRegion(touchRegion)
        }
        touchAreaChanged(touchRegion)
        true
    }
    private var pressedLyric = false
    private var canDrag = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isClickable = true
        contentDescription = "悬浮歌词，点按切换调整控件，拖动移动"
        // Keep the lyric baseline stable when the toolbar hides or the window locks.
        editor.addView(lyrics, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = dp(ToolbarDp) })
        editor.addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = dp(ToolbarDp) })
        toolbar.addView(lyricIcon(context, R.drawable.ic_floating_settings, "歌词设置") { settings(true) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(lyricIcon(context, R.drawable.ic_floating_lock, "锁定歌词，点击穿透") {
            changeOptions(options.copy(fixedLocked = true))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        editor.addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, dp(ToolbarDp), Gravity.TOP or Gravity.RIGHT))
        editor.addView(handle, LayoutParams(dp(ToolbarDp), dp(ToolbarDp), Gravity.BOTTOM or Gravity.RIGHT))
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(settingsPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        settingsPanel.visibility = GONE
        setOnClickListener {
            if (!locked) { controlsVisible = !controlsVisible; controls(); resizeForSettings() }
        }
        for ((label, offset) in listOf("向左移动" to (-24 to 0), "向右移动" to (24 to 0),
            "向上移动" to (0 to -24), "向下移动" to (0 to 24))) {
            ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                if (locked || isSettingsOpen || !controlsVisible) false else { changeBounds(dp(offset.first), dp(offset.second), false, true); true }
            }
        }
    }

    fun configure(options: FloatingLyricsConfig, emptyText: String?, colorTitle: String = "歌词颜色") {
        this.options = options
        placeholder.text = emptyText
        placeholder.visibility = if (emptyText != null) VISIBLE else GONE
        placeholder.textSize = options.fontSize
        placeholder.setTextColor(options.textColor)
        placeholder.alpha = lyrics.alpha
        locked = options.fixedLocked
        if (locked) { isSettingsOpen = false; controlsVisible = false }
        importantForAccessibility = if (locked) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_YES
        settingsPanel.configure(options, colorTitle)
        controls()
    }

    private fun rounded(color: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(16).toFloat()
        if (border) setStroke(dp(1).coerceAtLeast(1), 0x99ffffff.toInt())
    }

    private fun controls() {
        editor.visibility = if (isSettingsOpen) GONE else VISIBLE
        settingsPanel.visibility = if (isSettingsOpen) VISIBLE else GONE
        toolbar.visibility = if (!locked && controlsVisible) VISIBLE else GONE
        handle.visibility = toolbar.visibility
        editor.background = if (!locked && controlsVisible) rounded(0x33202124, true) else null
        ViewCompat.setStateDescription(this, if (controlsVisible) "调整控件已展开" else "调整控件已收起")
        invalidate()
    }

    fun showControls() {
        if (!locked && !controlsVisible) { controlsVisible = true; controls(); resizeForSettings() }
    }

    private fun dismissControls() {
        if (!controlsVisible && !isSettingsOpen) return
        controlsVisible = false
        if (isSettingsOpen) settings(false) else { controls(); resizeForSettings() }
    }

    private fun settings(open: Boolean) {
        if (locked || isSettingsOpen == open) return
        isSettingsOpen = open
        controls()
        resizeForSettings()
        if (open) lyrics.freeze() else lyrics.reset()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            dismissControls()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        if (isSettingsOpen) {
            if (event.actionMasked == MotionEvent.ACTION_UP) dismissControls()
            return true
        }
        return drag(event, false)
    }

    private fun containsLyric(x: Float, y: Float): Boolean {
        lyricRegion(touchRegion)
        return touchRegion.contains(x.toInt(), y.toInt())
    }

    private fun lyricRegion(region: Region) {
        if (placeholder.visibility != VISIBLE) {
            lyrics.fixedTextRegion(region)
            region.translate(lyrics.left, lyrics.top)
            region.op(0, 0, width, height, Region.Op.INTERSECT)
            return
        }
        val textWidth = placeholder.paint.measureText(placeholder.text.toString())
            .coerceAtMost((placeholder.width - placeholder.paddingLeft - placeholder.paddingRight).coerceAtLeast(0).toFloat())
        val cx = placeholder.left + placeholder.width / 2f
        val cy = placeholder.top + placeholder.height / 2f
        val halfWidth = textWidth / 2f + dp(12)
        val halfHeight = placeholder.paint.fontSpacing / 2f + dp(8)
        region.set((cx - halfWidth).toInt(), (cy - halfHeight).toInt(), (cx + halfWidth).toInt(), (cy + halfHeight).toInt())
        region.op(0, 0, width, height, Region.Op.INTERSECT)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(preDraw) }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    private fun drag(event: MotionEvent, resize: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY; dragged = false
                pressedLyric = !resize && containsLyric(event.x, event.y)
                canDrag = resize || controlsVisible || pressedLyric
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged && hypot(event.rawX - downX, event.rawY - downY) <= slop) return true
                dragged = true
                if (!canDrag) return true
                val dx = (event.rawX - lastX).toInt()
                val dy = (event.rawY - lastY).toInt()
                if (dx != 0 || dy != 0) {
                    changeBounds(dx, dy, resize, false)
                    lastX += dx; lastY += dy
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragged && canDrag) changeBounds(0, 0, resize, true)
                else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (resize) handle.performClick()
                    else if (!dragged && pressedLyric) performClick()
                    else dismissControls()
                }
            }
        }
        return true
    }
}
