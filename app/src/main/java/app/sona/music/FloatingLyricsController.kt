package app.sona.music

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the playback service while attached; no second foreground service is needed. */
@UnstableApi
internal class FloatingLyricsController(private val app: MusicApplication) {
    var options by mutableStateOf(app.settings.floatingLyrics)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var isPreviewing by mutableStateOf(false)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= 30) {
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else app
    }
    private val windows by lazy { windowContext.getSystemService(WindowManager::class.java) }
    private var player: Player? = null
    private var view: DanmakuLyricsView? = null
    private var root: View? = null
    private var fixedWindow: FixedLyricsWindow? = null
    private var parameters: WindowManager.LayoutParams? = null
    private var touchSurface: Any? = null
    private var surfaceRegion = Region()
    private var legacyTouchView: View? = null
    private val legacyBounds = Rect()
    private var legacyParameters: WindowManager.LayoutParams? = null
    private var uiVisible = false
    private var registered = false
    private var songKey: String? = null
    private var attemptedKey: String? = null
    private var load: Job? = null
    private var colorTracking: Job? = null
    private var foregroundPackage: String? = null
    private var pausedAt: Long? = null
    private val pauseTimeout = Runnable { refresh() }
    private var cues: List<DanmakuCue> = emptyList()
    private var introCue: DanmakuCue? = null
    private var previewStart = 0L
    private var previewUntil = 0L
    private var showingPreview = false
    private var adjustOnShow = false
    private var shownCues: List<DanmakuCue>? = null
    private val demo = danmakuCues(DanmakuDefaults.demo, DanmakuDefaults.DemoDuration)
    private val previewTimeout = Runnable { previewUntil = 0; isPreviewing = false; refresh() }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                if (player.currentPosition < (introCue?.start ?: 0)) introCue = null
                view?.reset()
            }
            refresh()
        }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { handler.post { refresh() } }
        override fun onLowMemory() = Unit
    }

    fun attach(player: Player) {
        detach()
        this.player = player
        player.addListener(listener)
        register()
        refresh()
    }

    fun detach() {
        player?.removeListener(listener); player = null
        load?.cancel(); load = null; songKey = null; attemptedKey = null; cues = emptyList()
        previewUntil = 0; isPreviewing = false; handler.removeCallbacks(previewTimeout)
        clearPause(); trackAppColor(false)
        hide()
        if (registered) {
            app.unregisterReceiver(screen); app.unregisterComponentCallbacks(configuration); registered = false
        }
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(screen, filter)
        app.registerComponentCallbacks(configuration)
        registered = true
    }

    fun setUiVisible(visible: Boolean) { uiVisible = visible; refresh() }
    fun update(config: FloatingLyricsConfig) {
        val openingFixed = config.enabled && config.style == FloatingLyricsStyle.Default &&
            (!options.enabled || options.style != FloatingLyricsStyle.Default)
        val next = (if (openingFixed) config.copy(fixedLocked = false) else config).bounded()
        if (options.enabled && !next.enabled) {
            previewUntil = 0; isPreviewing = false; handler.removeCallbacks(previewTimeout)
            clearPause()
            load?.cancel(); attemptedKey = null
        }
        options = next
        if (!next.enabled || next.style != FloatingLyricsStyle.Default) adjustOnShow = false
        app.settings.floatingLyrics = next
        if (next.enabled) register()
        if (!next.enabled) message = null
        refresh()
    }
    fun retry() { load?.cancel(); cues = emptyList(); attemptedKey = null; message = null; refresh() }
    fun preview() {
        if (!Settings.canDrawOverlays(app)) { message = "请先允许 Sona 显示在其他应用上层"; return }
        register()
        previewStart = SystemClock.elapsedRealtime()
        previewUntil = previewStart + 15_000
        isPreviewing = true
        handler.removeCallbacks(previewTimeout); handler.postDelayed(previewTimeout, 15_000)
        refresh()
    }
    fun stopPreview() { previewUntil = 0; isPreviewing = false; handler.removeCallbacks(previewTimeout); refresh() }
    fun refreshPermission() = refresh()

    fun adjustWindow() {
        adjustOnShow = true
        update(options.copy(enabled = true, style = FloatingLyricsStyle.Default, fixedLocked = false))
    }

    private fun clearPause() { pausedAt = null; handler.removeCallbacks(pauseTimeout) }

    private fun effectiveOptions(): FloatingLyricsConfig {
        val color = options.appColors[foregroundPackage] ?: options.textColor
        return options.copy(textColor = color, translationColor = color)
    }

    // Inline controls edit the active rule without replacing the global fallback with its colour.
    private fun updateFromOverlay(config: FloatingLyricsConfig) {
        val target = foregroundPackage?.takeIf { options.appColors.containsKey(it) }
        val changedColor = config.textColor != effectiveOptions().textColor
        update(config.copy(textColor = if (target != null) options.textColor else config.textColor,
            appColors = if (target != null && changedColor) options.appColors + (target to config.textColor) else options.appColors))
    }

    private fun trackAppColor(visible: Boolean) {
        if (!visible || options.appColors.isEmpty() || !hasLyricUsageAccess(app)) {
            colorTracking?.cancel(); colorTracking = null; foregroundPackage = null
            return
        }
        if (colorTracking?.isActive == true) return
        colorTracking = scope.launch {
            val tracker = LyricForegroundApp(app)
            while (isActive) {
                val next = withContext(Dispatchers.IO) {
                    if (hasLyricUsageAccess(app)) runCatching { tracker.read() }.getOrNull() else null
                }
                if (foregroundPackage != next) { foregroundPackage = next; refresh() }
                if (!hasLyricUsageAccess(app)) break
                delay(750)
            }
        }
    }

    private fun refresh() {
        val current = player?.currentMediaItem?.let(::songFromItem)
        if (current?.key != songKey) {
            clearPause()
            songKey = current?.key; attemptedKey = null; load?.cancel(); cues = emptyList(); introCue = null; hide(); message = null
        }
        val preview = SystemClock.elapsedRealtime() < previewUntil
        if ((options.enabled || preview) && !Settings.canDrawOverlays(app)) {
            options = options.copy(enabled = false); app.settings.floatingLyrics = options
            previewUntil = 0; isPreviewing = false; clearPause(); trackAppColor(false)
            message = "悬浮窗权限未开启"; hide(); return
        }
        if (options.enabled && current != null && attemptedKey != current.key) {
            attemptedKey = current.key
            load = scope.launch {
                try {
                    val lines = withContext(Dispatchers.IO) {
                        if (current.isLocal) LocalLyricsReader(app, app.settings).load(current).lines
                        else app.api.lyrics(current.id)
                    }
                    if (songKey != current.key) return@launch
                    val duration = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: current.duration
                    cues = danmakuCues(lines, duration)
                    message = if (cues.isEmpty()) "当前歌曲没有可显示的同步歌词" else null
                    refresh()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (songKey == current.key) { message = "悬浮歌词读取未完成，可重试或切换歌曲"; refresh() }
                }
            }
        }
        val awake = app.getSystemService(PowerManager::class.java).isInteractive &&
            !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val playing = player?.let { it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING) } == true
        if (playing || current == null || !options.enabled) clearPause()
        else if (pausedAt == null) {
            pausedAt = SystemClock.elapsedRealtime()
            handler.postDelayed(pauseTimeout, 5000)
        }
        val pauseExpired = pausedAt?.let { SystemClock.elapsedRealtime() - it >= 5000 } == true
        val editing = options.enabled && options.style == FloatingLyricsStyle.Default && !options.fixedLocked
        val noContent = current == null
        val visible = awake && !uiVisible && (preview || (options.enabled && (editing || !noContent) && !pauseExpired))
        trackAppColor(visible)
        if (!visible) { hide(soft = awake && !uiVisible && pauseExpired); return }
        val songInfo = if (preview) "Sona - 悬浮歌词预览" else current?.let { "${it.title} - ${it.artist}" }.orEmpty()
        show(if (preview) demo else cues, preview, songInfo)
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(fullWidth: Boolean = false): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(metrics.bounds).apply {
                if (!fullWidth) { left += insets.left; right -= insets.right }
                top += insets.top; bottom -= insets.bottom
            }
        }
        val size = Point().also { windows.defaultDisplay.getRealSize(it) }
        val insets = root?.rootWindowInsets
        return Rect(if (fullWidth) 0 else insets?.systemWindowInsetLeft ?: 0, insets?.systemWindowInsetTop ?: 0,
            size.x - (if (fullWidth) 0 else insets?.systemWindowInsetRight ?: 0), size.y - (insets?.systemWindowInsetBottom ?: 0))
    }

    private fun show(lines: List<DanmakuCue>, preview: Boolean, songInfo: String) {
        try {
            val effective = effectiveOptions()
            val fixed = options.style == FloatingLyricsStyle.Default
            val safe = safeBounds(fullWidth = !fixed)
            if (safe.isEmpty) { hide(); return }
            if (root != null && fixed != (fixedWindow != null)) hide()
            val region = if (!fixed) LyricRegion(0f, 0f, 1f, .5f)
                else if (safe.width() > safe.height()) options.landscape else options.portrait
            val lyricWidth = (safe.width() * region.width).toInt().coerceIn(
                (if (fixed) fixedMinWidth(safe) else 1).coerceAtMost(safe.width()), safe.width())
            val lyricHeight = (safe.height() * region.height).toInt().coerceIn(
                (if (fixed) fixedMinHeight() else 1).coerceAtMost(safe.height()), safe.height())
            val inlineSettings = fixed && !options.fixedLocked && fixedWindow?.isSettingsOpen == true
            val expanded = fixed && !options.fixedLocked && fixedWindow?.capturesBackground == true
            val width = if (inlineSettings) dp(320).coerceAtMost(safe.width()) else lyricWidth
            val height = if (inlineSettings) dp(420).coerceAtMost(safe.height()) else lyricHeight
            val lyricX = (safe.width() * region.x).toInt().coerceIn(0, safe.width() - lyricWidth)
            val maxAlpha = if (Build.VERSION.SDK_INT >= 31)
                (app.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch - .01f).coerceIn(0f, 1f) else 1f
            val touchFlags = if (fixed && !options.fixedLocked && (Build.VERSION.SDK_INT >= 33 || expanded)) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                touchFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = safe.left + (if (inlineSettings) lyricX + lyricWidth - width else lyricX).coerceIn(0, safe.width() - width)
                y = safe.top + (safe.height() * region.y).toInt().coerceIn(0, safe.height() - height)
                // Editing controls stay legible; the lyric keeps the same opacity before and after locking.
                alpha = if (expanded) 1f else options.opacity * maxAlpha
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                layoutInDisplayCutoutMode = if (!fixed && Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                title = "Sona 悬浮歌词"
            }
            val rendering = view ?: DanmakuLyricsView(windowContext).also {
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                it.configure(effective)
                val container = if (fixed) FixedLyricsWindow(windowContext, it, ::changeBounds, ::updateFromOverlay, ::refresh, ::updateTouchArea).also { window ->
                    fixedWindow = window
                } else it
                // Assign before addView so a failed attachment is cleaned up by hide().
                root = container
                view = it
                windows.addView(container, params)
            }
            val container = root ?: return
            val old = parameters
            if (old != null && (old.width != width || old.height != height || old.x != params.x || old.y != params.y ||
                    old.alpha != params.alpha || old.flags != params.flags)) {
                windows.updateViewLayout(container, params)
            }
            parameters = params
            container.animate().cancel()
            container.alpha = 1f
            rendering.alpha = if (expanded) options.opacity * maxAlpha else 1f
            rendering.configure(effective)
            rendering.setFallbackText(if (fixed) songInfo else "")
            if (!fixed && !preview && introCue == null && songInfo.isNotBlank()) {
                val now = (player?.currentPosition ?: 0).coerceAtLeast(0)
                if (lines.isEmpty() || now < lines.first().start) {
                    // Preserve this start across lyric loads, view recreation and configuration changes.
                    introCue = DanmakuCue(now, Long.MAX_VALUE, songInfo)
                }
            }
            rendering.setIntroCue(if (!fixed && !preview) introCue else null)
            fixedWindow?.configure(effective, if (lines.isEmpty() && songInfo.isBlank()) "播放歌曲后显示歌词" else null,
                if (options.appColors.containsKey(foregroundPackage)) "此应用歌词颜色" else "歌词颜色")
            if (adjustOnShow && fixed) { adjustOnShow = false; fixedWindow?.showControls() }
            if (shownCues !== lines || showingPreview != preview) {
                shownCues = lines; showingPreview = preview
                if (preview) rendering.timeline(lines, { SystemClock.elapsedRealtime() - previewStart }, DanmakuDefaults.DemoDuration)
                else rendering.timeline(lines, { player?.currentPosition ?: 0 },
                    rate = { player?.playbackParameters?.speed ?: 1f }, isRunning = { player?.isPlaying == true })
            }
            if (inlineSettings) rendering.freeze()
            if (expanded || !fixed || options.fixedLocked) removeLegacyTouchView()
        } catch (e: RuntimeException) {
            android.util.Log.w("SonaLyrics", "Overlay unavailable", e)
            message = "悬浮窗显示未完成，请检查显示在其他应用上层的权限"
            hide()
        }
    }

    private fun updateTouchArea(region: Region) {
        val window = fixedWindow ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            val surface = window.rootSurfaceControl ?: return
            if (surface !== touchSurface || surfaceRegion != region) {
                surface.setTouchableRegion(region)
                touchSurface = surface; surfaceRegion.set(region)
            }
            return
        }
        // Android 10-12: a small, transparent input window follows the visible lyric block.
        // The full rendering window is not touchable, so empty space still reaches the app behind.
        if (options.fixedLocked || window.capturesBackground || region.isEmpty) { removeLegacyTouchView(); return }
        val params = parameters ?: return
        val bounds = region.bounds
        val next = WindowManager.LayoutParams(bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; x = params.x + bounds.left; y = params.y + bounds.top
            layoutInDisplayCutoutMode = params.layoutInDisplayCutoutMode
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            title = "Sona 歌词点击区域"
        }
        try {
            legacyBounds.set(bounds)
            val target = legacyTouchView ?: View(windowContext).apply {
                contentDescription = "悬浮歌词，点按展开调整控件"
                isFocusable = true
                setOnClickListener { fixedWindow?.performClick() }
                setOnTouchListener { _, event ->
                    val shifted = MotionEvent.obtain(event)
                    shifted.offsetLocation(legacyBounds.left.toFloat(), legacyBounds.top.toFloat())
                    try { fixedWindow?.dispatchTouchEvent(shifted) ?: false } finally { shifted.recycle() }
                }
            }.also { legacyTouchView = it; windows.addView(it, next) }
            val old = legacyParameters
            if (old != null && (old.width != next.width || old.height != next.height || old.x != next.x || old.y != next.y)) {
                windows.updateViewLayout(target, next)
            }
            legacyParameters = next
        } catch (e: RuntimeException) {
            android.util.Log.w("SonaLyrics", "Lyric touch area unavailable", e)
            removeLegacyTouchView()
        }
    }

    private fun removeLegacyTouchView() {
        val old = legacyTouchView ?: return
        legacyTouchView = null; legacyParameters = null
        runCatching { windows.removeViewImmediate(old) }
    }

    private fun dp(value: Int) = (value * windowContext.resources.displayMetrics.density).toInt()
    private fun fixedMinWidth(safe: Rect) = maxOf(dp(FixedLyricsWindow.MinWidthDp), (safe.width() * .2f).toInt())
    private fun fixedMinHeight() = dp(FixedLyricsWindow.ToolbarDp) +
        kotlin.math.ceil((lyricRowHeight(windowContext, options.fontSize) * options.fixedLines).toDouble()).toInt()

    private fun changeBounds(dx: Int, dy: Int, resize: Boolean, finished: Boolean) {
        if (options.fixedLocked || fixedWindow?.isSettingsOpen != false) return
        val container = root ?: return
        val params = parameters ?: return
        val safe = safeBounds()
        if (safe.isEmpty) return
        if (resize) {
            val maxWidth = (safe.right - params.x).coerceIn(1, safe.width())
            val maxHeight = minOf(safe.bottom - params.y, maxOf((safe.height() * .6f).toInt(), fixedMinHeight())).coerceAtLeast(1)
            params.width = (params.width + dx).coerceIn(fixedMinWidth(safe).coerceAtMost(maxWidth), maxWidth)
            params.height = (params.height + dy).coerceIn(fixedMinHeight().coerceAtMost(maxHeight), maxHeight)
        } else {
            params.x = (params.x + dx).coerceIn(safe.left, (safe.right - params.width).coerceAtLeast(safe.left))
            params.y = (params.y + dy).coerceIn(safe.top, (safe.bottom - params.height).coerceAtLeast(safe.top))
        }
        try { windows.updateViewLayout(container, params) }
        catch (e: RuntimeException) { android.util.Log.w("SonaLyrics", "Window adjustment interrupted", e); hide(); return }
        if (finished) {
            val region = LyricRegion((params.x - safe.left).toFloat() / safe.width(),
                (params.y - safe.top).toFloat() / safe.height(), params.width.toFloat() / safe.width(),
                params.height.toFloat() / safe.height()).bounded()
            update(if (safe.width() > safe.height()) options.copy(landscape = region) else options.copy(portrait = region))
        }
    }

    private fun hide(soft: Boolean = false) {
        removeLegacyTouchView()
        touchSurface = null; surfaceRegion.setEmpty()
        val old = root
        if (soft && old != null) {
            view?.freeze()
            old.animate().alpha(0f).setDuration(180).withEndAction { if (root === old) hide() }.start()
            return
        }
        view?.stop()
        old?.let { it.animate().cancel(); runCatching { windows.removeViewImmediate(it) } }
        root = null; view = null; fixedWindow = null; parameters = null; shownCues = null; showingPreview = false
    }
}
