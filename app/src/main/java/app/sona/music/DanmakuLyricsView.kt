package app.sona.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Region
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.floor

internal fun lyricRowHeight(context: Context, fontSize: Float): Float {
    val metrics = context.resources.displayMetrics
    val font = TextPaint().apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = fontSize * metrics.scaledDensity }
    return maxOf(DanmakuDefaults.LaneDp * metrics.density, font.fontSpacing + 8 * metrics.density)
}

/** Shared, display-synchronised renderer for fixed lyrics, danmaku and both previews. */
internal class DanmakuLyricsView(context: Context) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private data class Entry(val cue: DanmakuCue, val original: StaticLayout, val translated: StaticLayout?,
        val lane: Int, val until: Long) {
        val width: Int get() = maxOf(original.width, translated?.width ?: 0)
    }
    private val entries = ArrayList<Entry>()
    private var cues: List<DanmakuCue> = emptyList()
    private var config = FloatingLyricsConfig()
    private var clock: (() -> Long)? = null
    private var playbackRate: () -> Float = { 1f }
    private var running: () -> Boolean = { true }
    private var period = 0L
    private var last = -1L
    private var cursor = 0
    private var fixed: Entry? = null
    private var upcoming: StaticLayout? = null
    private var fallbackText = ""
    private var fallback: StaticLayout? = null
    private var introCue: DanmakuCue? = null
    private var hasTranslations = false
    private var scale = 1f
    private var nextLane = 0
    private var density = resources.displayMetrics.density
    private var lineHeight = DanmakuDefaults.LaneDp * density
    private val slotHeight get() = lineHeight * if (config.showTranslation && hasTranslations) 2 else 1
    private val lanes get() = (height / slotHeight).toInt().coerceAtLeast(1)
    private var speed = DanmakuDefaults.SpeedDpPerSecond * density / 1000f
    private var position = 0L
    private var drawPosition = 0.0
    private var anchorTime = 0L
    private var anchorPosition = 0.0
    private var sampledAt = 0L
    private var anchorRate = 1f
    private var wasRunning = false
    private val hitRegion = Region()

    fun configure(options: FloatingLyricsConfig, previewScale: Float = 1f) {
        val oldTextSize = paint.textSize
        val reset = options.rate != config.rate || options.style != config.style || options.fixedLines != config.fixedLines ||
            options.fontSize != config.fontSize || options.showTranslation != config.showTranslation || scale != previewScale
        config = options
        scale = previewScale.coerceAtLeast(.1f)
        density = resources.displayMetrics.density * scale
        paint.textSize = options.fontSize * resources.displayMetrics.scaledDensity * scale
        lineHeight = maxOf(DanmakuDefaults.LaneDp * density, paint.fontSpacing + 8 * density)
        speed = DanmakuDefaults.SpeedDpPerSecond * density * options.rate / 1000f
        if (reset || oldTextSize != paint.textSize) reset() else wake()
    }

    fun timeline(lines: List<DanmakuCue>, source: () -> Long, loopDuration: Long = 0L,
        rate: () -> Float = { 1f }, isRunning: () -> Boolean = { true }) {
        cues = lines
        hasTranslations = lines.any { it.translation.isNotBlank() }
        clock = source
        playbackRate = rate
        running = isRunning
        period = loopDuration
        reset()
    }

    fun reset() {
        entries.clear(); fixed = null; upcoming = null; fallback = null; last = -1; nextLane = 0
        anchorTime = 0
        wake()
    }

    fun stop() {
        freeze()
        clock = null
        cues = emptyList()
        entries.clear(); fixed = null; upcoming = null; fallback = null
        invalidate()
    }
    fun freeze() {
        removeCallbacks(frame)
        if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
    }

    fun setFallbackText(text: String) {
        if (fallbackText == text) return
        fallbackText = text
        fallback = null
        invalidate()
    }

    fun setIntroCue(cue: DanmakuCue?) {
        if (introCue == cue) return
        introCue = cue
        reset()
    }

    private fun fixedFallback(): StaticLayout? {
        if (config.style != FloatingLyricsStyle.Default || fixed != null || fallbackText.isBlank() || width <= 0) return null
        return fallback ?: layout(fallbackText, stationary = true).also { fallback = it }
    }

    /** Match the actual centred text, with a small touch allowance, rather than the full window. */
    fun containsFixedText(x: Float, y: Float): Boolean {
        fixedTextRegion(hitRegion)
        return hitRegion.contains(floor(x.toDouble()).toInt(), floor(y.toDouble()).toInt())
    }

    fun fixedTextRegion(region: Region) {
        region.setEmpty()
        val original = fixed?.original ?: fixedFallback() ?: return
        val next = if (fixed != null) upcoming else null
        val total = original.height + (next?.let { it.height + 6 * density } ?: 0f)
        val top = (height - total) / 2f
        fun add(line: StaticLayout, lineTop: Float) {
            val left = (width - line.width) / 2f
            region.op(floor((left + line.getLineLeft(0) - 12 * density).toDouble()).toInt(),
                floor((lineTop - 8 * density).toDouble()).toInt(),
                ceil((left + line.getLineRight(0) + 12 * density).toDouble()).toInt(),
                ceil((lineTop + line.height + 8 * density).toDouble()).toInt(), Region.Op.UNION)
        }
        add(original, top)
        next?.let { add(it, top + original.height + 6 * density) }
    }

    private fun layout(text: String, stationary: Boolean = false): StaticLayout {
        val w = if (stationary) (width - 12 * density).toInt().coerceAtLeast(1)
            else ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt().coerceAtLeast(1) + 2
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setIncludePad(false).setMaxLines(1)
            .setAlignment(if (stationary) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
        if (stationary) builder.setEllipsize(TextUtils.TruncateAt.END)
        return builder.build()
    }

    private fun x(entry: Entry, now: Double) = (width - speed * (now - entry.cue.start)).toFloat()

    private fun enter(index: Int, now: Long) {
        val cue = cues[index]
        if (config.style == FloatingLyricsStyle.Default) {
            fixed = Entry(cue, layout(cue.text, stationary = true), null, 0, cue.end)
            upcoming = if (config.fixedLines == 2) cues.getOrNull(index + 1)?.let { layout(it.text, stationary = true) } else null
            return
        }
        enterBullet(cue, now)
    }

    private fun enterBullet(cue: DanmakuCue, now: Long) {
        val original = layout(cue.text)
        val translated = cue.translation.takeIf { config.showTranslation && it.isNotBlank() }?.let { layout(it) }
        val extent = maxOf(original.width, translated?.width ?: 0)
        // A long original or translation determines the full group's natural exit, without a timeout.
        val until = cue.start + ceil(((width + extent) / speed).toDouble()).toLong()
        if (until <= now) return
        val tails = FloatArray(lanes) { Float.NEGATIVE_INFINITY }
        for (entry in entries) tails[entry.lane] = maxOf(tails[entry.lane], x(entry, now.toDouble()) + entry.width)
        val lane = (0 until lanes).map { (it + nextLane) % lanes }
            .firstOrNull { tails[it] <= width - 12 * density }
            ?: tails.indices.minByOrNull { tails[it] } ?: 0
        // Reuse tracks horizontally. Dense passages add entries; no old lyric is evicted or delayed.
        entries += Entry(cue, original, translated, lane, until)
        nextLane = (lane + 1) % lanes
    }

    /** Player position is sampled; visual motion advances continuously on Android's animation clock. */
    private fun visualPosition(read: () -> Long): Double {
        if (config.style != FloatingLyricsStyle.Danmaku) return read().coerceAtLeast(0).toDouble()
        val now = AnimationUtils.currentAnimationTimeMillis()
        val playing = running()
        val rate = playbackRate()
        val predicted = anchorPosition + if (wasRunning) (now - anchorTime) * anchorRate.toDouble() else 0.0
        if (anchorTime == 0L || playing != wasRunning || rate != anchorRate || now - sampledAt >= 250) {
            val actual = read().coerceAtLeast(0).toDouble()
            val error = actual - predicted
            // Seek/pause/rate changes resynchronise immediately; small clock corrections do not jerk the text.
            anchorPosition = if (anchorTime == 0L || playing != wasRunning || rate != anchorRate || abs(error) > 200)
                actual else maxOf(drawPosition, predicted + error * .1)
            anchorTime = now; sampledAt = now; anchorRate = rate; wasRunning = playing
            return anchorPosition
        }
        return predicted
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != VISIBLE || width == 0 || height == 0) return
            val read = clock ?: return
            val raw = visualPosition(read)
            drawPosition = if (period > 0) raw % period else raw
            position = drawPosition.toLong()
            val now = position
            if (last < 0 || now < last) {
                entries.clear(); fixed = null; upcoming = null
                cursor = cues.indexOfLast { it.start <= now }.coerceAtLeast(0)
                if (config.style == FloatingLyricsStyle.Danmaku && (cues.isEmpty() || now < cues.first().start)) {
                    introCue?.takeIf { it.start <= now }?.let { enterBullet(it, now) }
                }
            }
            entries.removeAll { now >= it.until }
            while (cursor < cues.size && cues[cursor].start <= now) enter(cursor++, now)
            fixed?.let { if (now >= it.until) { fixed = null; upcoming = null } }
            last = now
            if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(
                if (running() && entries.isNotEmpty()) REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
            invalidate()
            if (!running()) return
            val held = fixed
            val fading = held != null && (now - held.cue.start < 120 || held.until - now <= DanmakuDefaults.FadeMs)
            if (entries.isNotEmpty() || fading) postOnAnimation(this)
            else {
                val wakeAt = listOfNotNull(cues.getOrNull(cursor)?.start, held?.until?.minus(DanmakuDefaults.FadeMs),
                    period.takeIf { it > 0 }).filter { it > now }.minOrNull()
                if (wakeAt != null) postDelayed(this, ((wakeAt - now) / playbackRate().coerceAtLeast(.1f)).toLong().coerceAtLeast(16))
            }
        }
    }

    private fun wake() { removeCallbacks(frame); if (clock != null && isAttachedToWindow) postOnAnimation(frame) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); wake() }
    override fun onDetachedFromWindow() { freeze(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (clock == null) return
        if (visibility == VISIBLE) reset() else freeze()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); reset() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.clipRect(0, 0, width, height)
        for (entry in entries) {
            val groupHeight = entry.original.height + (entry.translated?.let { it.height + 4 * density } ?: 0f)
            val y = entry.lane * slotHeight + (slotHeight - groupHeight) / 2f
            val alpha = fade(entry)
            drawText(canvas, entry.original, x(entry, drawPosition), y, config.textColor, alpha)
            entry.translated?.let { drawText(canvas, it, x(entry, drawPosition), y + entry.original.height + 4 * density, config.textColor, alpha) }
        }
        fixed?.let { entry ->
            val next = upcoming
            val total = entry.original.height + (next?.let { it.height + 6 * density } ?: 0f)
            val y = (height - total) / 2f
            val alpha = fade(entry)
            drawText(canvas, entry.original, (width - entry.original.width) / 2f, y, config.textColor, alpha)
            next?.let { drawText(canvas, it, (width - it.width) / 2f, y + entry.original.height + 6 * density, config.textColor, alpha * .5f) }
        }
        fixedFallback()?.let { line ->
            drawText(canvas, line, (width - line.width) / 2f, (height - line.height) / 2f, config.textColor, 1f)
        }
        canvas.restore()
    }

    private fun fade(entry: Entry): Float =
        min(1f, (entry.until - position).coerceAtLeast(0) / DanmakuDefaults.FadeMs.toFloat()) *
            min(1f, (position - entry.cue.start).coerceAtLeast(0) / 120f)

    private fun drawText(canvas: Canvas, layout: StaticLayout, x: Float, y: Float, color: Int, alpha: Float) {
        canvas.save(); canvas.translate(x, y)
        paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = (alpha * 255).toInt()
        layout.draw(canvas)
        canvas.restore()
    }
}
