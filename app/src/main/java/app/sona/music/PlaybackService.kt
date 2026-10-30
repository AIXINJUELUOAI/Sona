package app.sona.music

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.CommandButton
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.snapshotFlow
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        const val ANCHOR_SHUFFLE = "app.sona.music.ANCHOR_SHUFFLE"
        const val KEEP_SHUFFLE_ORDER = "app.sona.music.KEEP_SHUFFLE_ORDER"
        const val TOGGLE_FLOATING_LYRICS = "app.sona.music.TOGGLE_FLOATING_LYRICS"
        const val TOGGLE_FAVORITE = "app.sona.music.TOGGLE_FAVORITE"
        const val QUEUE_SOURCE = "sona_queue_source"
    }
    private var session: MediaSession? = null
    private var preloader: AudioPreloader? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lyricsCommand = SessionCommand(TOGGLE_FLOATING_LYRICS, Bundle.EMPTY)
    private val favoriteCommand = SessionCommand(TOGGLE_FAVORITE, Bundle.EMPTY)

    private fun mediaButtons(player: Player?): List<CommandButton> {
        val app = application as MusicApplication
        val enabled = app.floatingLyrics.options.enabled
        val song = player?.currentMediaItem?.let(::songFromItem)
        val liked = song?.let(app.favorites::isLiked) == true
        return listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (enabled) R.drawable.ic_lyrics_word_on else R.drawable.ic_lyrics_word)
            .setDisplayName(if (enabled) "关闭悬浮歌词" else "开启悬浮歌词")
            .setSessionCommand(lyricsCommand)
            // Use an additional action slot, preserving the primary playback controls.
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (liked) R.drawable.ic_media_favorite_on else R.drawable.ic_media_favorite)
            .setDisplayName(if (liked) "取消收藏" else "收藏")
            .setSessionCommand(favoriteCommand)
            .setEnabled(song != null && !app.favorites.changing)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build())
    }
    private fun refreshButtons(player: Player? = session?.player) { session?.setMediaButtonPreferences(mediaButtons(player)) }
    private val storage = Executors.newSingleThreadExecutor()
    private var restoring = true
    private var applyingRestore = false
    private var restoreAllowed = true
    private var destroyed = false
    private var lastSnapshot: PlaybackSnapshot? = null
    private var snapshotSaved = false
    private var ticks = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var trackedSong: Song? = null
    private var tracker = ListeningTracker()
    private var trackingPlaying = false
    private var tickTime = 0L
    private fun anchorShuffle(player: ExoPlayer) {
        if (applyingRestore || !player.shuffleModeEnabled) return
        val current = player.currentMediaItemIndex
        if (current !in 0 until player.mediaItemCount) return
        // Keep the media list intact so disabling shuffle restores its original order.
        val order = listOf(current) + (0 until player.mediaItemCount).filter { it != current }.shuffled()
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        player.repeatMode = Player.REPEAT_MODE_ALL
    }
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed || !trackingPlaying) return
            recordElapsed()
            if (++ticks % 6 == 0) savePlayback()
            handler.postDelayed(this, 5000)
        }
    }
    private fun savePlayback() {
        if (restoring || destroyed) return
        val player = session?.player ?: return
        val count = player.mediaItemCount
        val timeline = player.currentTimeline
        val snapshot = if (count == 0) null else PlaybackSnapshot(
            (0 until count).map { songFromItem(player.getMediaItemAt(it)) },
            player.currentMediaItemIndex, if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0),
            player.shuffleModeEnabled, player.repeatMode,
            if (timeline.windowCount == count) queueOrder(count, timeline.getFirstWindowIndex(true)) {
                timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true)
            } else (0 until count).toList(), player.playlistMetadata.extras?.getString(QUEUE_SOURCE).orEmpty())
        if (snapshotSaved && snapshot == lastSnapshot) return
        snapshotSaved = true
        lastSnapshot = snapshot
        val settings = (application as MusicApplication).settings
        storage.execute { settings.savePlayback(snapshot) }
    }

    private fun restorePlayback(player: ExoPlayer) {
        val settings = (application as MusicApplication).settings
        storage.execute {
            val snapshot = settings.playbackSnapshot()?.retaining { song ->
                !song.isLocal || !canReadLocalMusic(this) || runCatching {
                    contentResolver.openFileDescriptor(android.net.Uri.parse(song.localUri), "r")?.use { true } == true
                }.getOrDefault(false)
            }
            handler.post {
                if (destroyed) return@post
                if (restoreAllowed && player.mediaItemCount == 0 && snapshot != null) {
                    applyingRestore = true
                    try {
                        player.setMediaItems(snapshot.songs.map { it.mediaItem() }, snapshot.index, snapshot.position)
                        player.playlistMetadata = MediaMetadata.Builder().setExtras(Bundle().apply {
                            putString(QUEUE_SOURCE, snapshot.source)
                        }).build()
                        player.setShuffleOrder(DefaultShuffleOrder(snapshot.order.toIntArray(), System.nanoTime()))
                        player.shuffleModeEnabled = snapshot.shuffle
                        player.repeatMode = if (snapshot.shuffle) Player.REPEAT_MODE_ALL else snapshot.repeat
                        // Keep STATE_IDLE: reopening Sona restores the position without fetching or playing audio.
                        player.playWhenReady = false
                    } finally { applyingRestore = false }
                }
                restoring = false
                savePlayback()
            }
        }
    }
    private fun recordElapsed() {
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = if (tickTime == 0L) 0 else now - tickTime
        tickTime = now
        val song = trackedSong ?: return
        if (trackingPlaying) {
            val settings = (application as MusicApplication).settings
            val endTime = System.currentTimeMillis()
            storage.execute { settings.listeningHistory.record(song, elapsed, endTime) }
            if (tracker.advance(elapsed, song.duration)) storage.execute {
                settings.recordListening(song, skipped = false)
                settings.listeningHistory.recordPlay(song, endTime)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val api = (application as MusicApplication).api
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000).setReadTimeoutMs(20_000))) { spec ->
            if (spec.uri.scheme == "sona") {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: error("歌曲编号异常")
                spec.withUri(android.net.Uri.parse(api.stream(id)))
            } else spec
        }
        preloader = runCatching { AudioPreloader((application as MusicApplication).audioCache, source) }
            .onFailure { android.util.Log.w("PlaybackService", "Audio cache unavailable", it) }.getOrNull()
        val output = (application as MusicApplication).output
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(preloader?.playbackSource ?: source))
            // Apply the same previous-track policy to app, notification and media-button commands.
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .setAudioOutputProvider(output.audioOutputProvider()).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    if (shuffleModeEnabled) repeatMode = Player.REPEAT_MODE_ALL
                }
                override fun onRepeatModeChanged(repeatMode: Int) {
                    // Shuffle always traverses a complete cycle, including after controller updates.
                    if (shuffleModeEnabled && repeatMode != Player.REPEAT_MODE_ALL) {
                        this@apply.repeatMode = Player.REPEAT_MODE_ALL
                    }
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (destroyed) return
                    preloader?.update(player)
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) refreshButtons(player)
                    if (restoring && !applyingRestore && player.mediaItemCount > 0) restoreAllowed = false
                    if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYLIST_METADATA_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED)) savePlayback()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (destroyed) return
                    recordElapsed()
                    trackingPlaying = isPlaying
                    handler.removeCallbacks(tick)
                    if (isPlaying) handler.postDelayed(tick, 5000)
                    else storage.execute { (application as MusicApplication).settings.listeningHistory.flush() }
                    output.refresh()
                }
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                    if (destroyed) return
                    // Reordering is requested explicitly by an outside-list action, never by
                    // a track transition, list selection, or playback restoration.
                    recordElapsed()
                    val settings = (application as MusicApplication).settings
                    storage.execute { settings.listeningHistory.flush() }
                    if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && tracker.skipped()) {
                        trackedSong?.let { settings.recordListening(it, skipped = true) }
                    }
                    trackedSong = item?.let(::songFromItem)
                    tracker = ListeningTracker()
                    if (!applyingRestore) trackedSong?.let(settings::rememberSong)
                }
            })
        }
        output.attach(player)
        val openApp = android.app.PendingIntent.getActivity(this, 0,
            android.content.Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(openApp)
            .setMediaButtonPreferences(mediaButtons(player))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(ANCHOR_SHUFFLE, Bundle.EMPTY))
                            .add(lyricsCommand)
                            .add(favoriteCommand)
                            .add(SessionCommand(KEEP_SHUFFLE_ORDER, Bundle.EMPTY)).build()).build()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == TOGGLE_FLOATING_LYRICS) {
                        val lyrics = (application as MusicApplication).floatingLyrics
                        val enabled = !lyrics.options.enabled
                        if (enabled && !Settings.canDrawOverlays(this@PlaybackService)) {
                            Toast.makeText(this@PlaybackService, "请先在 Sona → 设置 → 悬浮歌词中允许悬浮窗权限", Toast.LENGTH_LONG).show()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                        }
                        lyrics.update(lyrics.options.copy(enabled = enabled))
                        refreshButtons(player)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == TOGGLE_FAVORITE) {
                        val app = application as MusicApplication
                        val song = player.currentMediaItem?.let(::songFromItem)
                            ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                        if (app.favorites.changing) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                        val result = SettableFuture.create<SessionResult>()
                        serviceScope.launch {
                            try {
                                app.favorites.toggle(song)
                                refreshButtons(player)
                                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                            } catch (e: CancellationException) { result.cancel(false); throw e }
                            catch (e: Exception) {
                                Toast.makeText(this@PlaybackService, e.message ?: "收藏未完成，请重试", Toast.LENGTH_LONG).show()
                                result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                            }
                        }
                        return result
                    }
                    if (customCommand.customAction !in setOf(ANCHOR_SHUFFLE, KEEP_SHUFFLE_ORDER)) {
                        return super.onCustomCommand(session, controller, customCommand, args)
                    }
                    if (args.getString(QUEUE_SOURCE).orEmpty() != player.playlistMetadata.extras?.getString(QUEUE_SOURCE).orEmpty()) {
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                    }
                    if (customCommand.customAction == ANCHOR_SHUFFLE) {
                        if (args.getString("song") == player.currentMediaItem?.mediaId) anchorShuffle(player)
                    } else if (player.shuffleModeEnabled) {
                        val order = args.getIntArray("order")
                        if (order != null && order.sorted() == (0 until player.mediaItemCount).toList()) {
                            player.setShuffleOrder(DefaultShuffleOrder(order, System.nanoTime()))
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        (application as MusicApplication).floatingLyrics.attach(player)
        serviceScope.launch {
            val app = application as MusicApplication
            snapshotFlow { listOf(app.floatingLyrics.options.enabled, app.favorites.cloud, app.favorites.local, app.favorites.changing) }.collect {
                refreshButtons(player)
            }
        }
        serviceScope.launch {
            try { (application as MusicApplication).favorites.refresh(force = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("SonaFavorites", "Favorite state not refreshed", e) }
        }
        restorePlayback(player)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        recordElapsed()
        storage.execute { (application as MusicApplication).settings.listeningHistory.flush() }
        savePlayback()
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        serviceScope.cancel()
        (application as MusicApplication).floatingLyrics.detach()
        recordElapsed()
        storage.execute { (application as MusicApplication).settings.listeningHistory.flush() }
        savePlayback()
        destroyed = true
        preloader?.close()
        preloader = null
        storage.shutdown()
        handler.removeCallbacks(tick)
        (application as MusicApplication).output.detach()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
