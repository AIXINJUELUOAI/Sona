package app.sona.music

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

enum class Page { Home, Search, Library, Login, Songs, Local, Daily, Stats }

@UnstableApi
class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val music = app as MusicApplication
    private val api = music.api
    val settings = music.settings
    private val history = mutableListOf<Page>()
    var navigatingBack by mutableStateOf(false)
        private set
    private var currentPage by mutableStateOf(Page.Home)
    val previousPage: Page get() = history.lastOrNull() ?: Page.Home
    val navigationDepth: Int get() = history.size
    // Home is the root; every other page remembers where it was opened from.
    var page: Page
        get() = currentPage
        set(value) {
            if (value == currentPage) return
            navigatingBack = value == Page.Home
            if (value == Page.Home) history.clear() else history += currentPage
            currentPage = value
        }
    fun back() {
        if (currentPage == Page.Home) return
        navigatingBack = true
        currentPage = history.removeLastOrNull() ?: Page.Home
    }
    var playerExpanded by mutableStateOf(false)
    var deskMode by mutableStateOf(false)
    var deskBold by mutableStateOf(false)
    var deskControlsCollapsed by mutableStateOf(false)
    var deskLyricsOpen by mutableStateOf(false)
    var lyricsVisible by mutableStateOf(false)
    var queueVisible by mutableStateOf(false)
    var outputVisible by mutableStateOf(false)
    var theme by mutableStateOf(settings.theme)
    var playerStyleMode by mutableStateOf(settings.playerStyleMode)
        private set
    var coverShapeMode by mutableStateOf(settings.coverShapeMode)
        private set
    var coverShapeIndex by mutableIntStateOf(1)
        private set
    var coverFixedShapeIndex by mutableIntStateOf(settings.coverFixedShape)
        private set
    private var lastCoverShapeIndex = 1
    private var lastBoldPlayer by mutableStateOf(settings.lastBoldPlayer)
    val boldPlayer: Boolean get() = when (playerStyleMode) {
        PlayerStyleMode.Default -> false
        PlayerStyleMode.Bold -> true
        PlayerStyleMode.TapToSwitch -> lastBoldPlayer
    }
    var profile by mutableStateOf<Profile?>(null)
        private set
    var accountVisible by mutableStateOf(false)
    var localName by mutableStateOf(settings.localName)
        private set
    var localAvatar by mutableStateOf(settings.localAvatar)
        private set
    var localDaily by mutableStateOf(emptyList<Song>())
        private set
    var localCollections by mutableStateOf(emptyList<LocalCollection>())
        private set
    var lists by mutableStateOf(emptyList<MusicList>())
        private set
    var discovery by mutableStateOf(emptyList<MusicList>())
        private set
    var daily by mutableStateOf(emptyList<Song>())
        private set
    var dailyDate by mutableStateOf<LocalDate?>(null)
        private set
    var recent by mutableStateOf(settings.recentSongs())
        private set
    var liked by mutableStateOf(emptySet<Long>())
        private set
    var localSongs by mutableStateOf(emptyList<Song>())
        private set
    var localSort by mutableStateOf(settings.localSort)
        private set
    var localSortDescending by mutableStateOf(settings.localSortDescending)
        private set
    fun sortLocalSongs(sort: LocalSongSort, descending: Boolean) {
        localSort = sort
        localSortDescending = descending
        settings.localSort = sort
        settings.localSortDescending = descending
    }
    var localPermission by mutableStateOf(canReadLocalMusic(app))
        private set
    var localLoading by mutableStateOf(false)
        private set
    var localError by mutableStateOf<String?>(null)
        private set
    var localRefreshMessage by mutableStateOf<String?>(null)
        private set
    var localFavorites by mutableStateOf(settings.localFavorites())
        private set
    var recommendations by mutableStateOf(emptyList<Song>())
        private set
    private var localJob: Job? = null
    var searchSource by mutableStateOf(SearchSource.All)
        private set
    var submittedQuery by mutableStateOf("")
        private set
    var cloudSearchError by mutableStateOf<String?>(null)
        private set
    private var cloudResults by mutableStateOf(emptyList<Song>())
    private var cloudOffset = 0
    private var searchGeneration = 0
    var query by mutableStateOf("")
    var searches by mutableStateOf(settings.searches())
        private set
    val localResults: List<Song> by derivedStateOf { searchLocalSongs(localSongs, submittedQuery) }
    val results: List<Song> by derivedStateOf {
        when (searchSource) {
            SearchSource.All -> localResults + cloudResults
            SearchSource.Local -> localResults
            SearchSource.Cloud -> cloudResults
        }
    }
    var searchHasMore by mutableStateOf(false)
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var songListTitle by mutableStateOf("")
        private set
    var songList by mutableStateOf(emptyList<Song>())
        private set
    var songListSource by mutableStateOf("")
        private set
    val dailySource: String get() = "daily:${profile?.id}:$dailyDate"
    val localDailySource: String get() = "local-daily:${settings.localDailyDate}"
    val searchQueueSource: String get() = "search:${searchSource.name}:$submittedQuery"
    var listHasMore by mutableStateOf(false)
        private set
    var listLoading by mutableStateOf(false)
        private set
    private var openedList: MusicList? = null
    val songListIsCloudOnly: Boolean get() = openedList != null
    private var favoritesOpen = false
    private var favoriteCloudSongs = emptyList<Song>()
    private var favoriteRowsJob: Job? = null
    private var listGeneration = 0
    private var listJob: Job? = null
    private var searchJob: Job? = null
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var current by mutableStateOf<Song?>(null)
        private set
    var queue by mutableStateOf(emptyList<Song>())
        private set
    var queueIndex by mutableStateOf(-1)
        private set
    var queueMediaIndices by mutableStateOf(emptyList<Int>())
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var canSkipPrevious by mutableStateOf(false)
        private set
    var canSkipNext by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var shuffled by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var lyrics by mutableStateOf(emptyList<LyricLine>())
        private set
    var plainLyrics by mutableStateOf("")
        private set
    var lyricsSource by mutableStateOf("")
        private set
    var hasManualLyrics by mutableStateOf(false)
        private set
    var lyricsLoading by mutableStateOf(false)
        private set
    var lyricsError by mutableStateOf<String?>(null)
        private set
    var qrBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var qrMessage by mutableStateOf("正在生成二维码…")
        private set
    private var qrJob: Job? = null
    private var lyricJob: Job? = null
    private var lyricGeneration = 0
    private val localLyricsReader = LocalLyricsReader(app, settings)
    private var controller: MediaController? = null
    private var uiActive = false
    private var positionJob: Job? = null
    fun setUiActive(active: Boolean) {
        uiActive = active
        updatePositionPolling()
    }
    private fun updatePositionPolling() {
        if (!uiActive || controller?.isPlaying != true) {
            positionJob?.cancel()
            positionJob = null
            return
        }
        if (positionJob?.isActive == true) return
        positionJob = viewModelScope.launch {
            while (true) {
                controller?.let { position = it.currentPosition.coerceAtLeast(0) }
                delay(if (playerExpanded) 250 else 1000)
            }
        }
    }
    private var radioActive = false
    private var radioJob: Job? = null
    private val controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer(events) }
        override fun onPlayerError(exception: PlaybackException) {
            val messages = generateSequence<Throwable>(exception) { it.cause }.mapNotNull { it.message }.toList()
            if (current?.isLocal == true) {
                error = "本地歌曲读取失败，请检查音乐权限或文件是否已移动、删除"
                refreshLocalMusic()
                return
            }
            error = messages.lastOrNull { it.any { c -> c.code in 0x4e00..0x9fff } } ?: "播放连接中断，请重试或换一首歌曲"
        }
    }
    init {
        viewModelScope.launch {
            settings.recentSongsState.collect { recent = it }
        }
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess {
                controller = it; it.addListener(listener); connected = true; syncPlayer()
            }.onFailure { android.util.Log.e("SonaPlayback", "Media controller connection failed", it); error = "播放器启动失败，请重新打开 Sona" }
        }, ContextCompat.getMainExecutor(app))
        refreshLocalMusic()
        rebuildRecommendations()
        rebuildLocalHome()
        refresh()
    }
    private fun syncPlayer(events: Player.Events? = null) {
        val player = controller ?: return
        playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        buffering = player.playbackState == Player.STATE_BUFFERING
        canSkipPrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
        canSkipNext = (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && player.hasNextMediaItem()) ||
            (player.shuffleModeEnabled && player.mediaItemCount > 1 && player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        shuffled = player.shuffleModeEnabled
        repeat = player.repeatMode
        if (events == null || events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAYLIST_METADATA_CHANGED)) {
            val timeline = player.currentTimeline
            queueMediaIndices = if (timeline.windowCount == player.mediaItemCount) {
                queueOrder(player.mediaItemCount, timeline.getFirstWindowIndex(shuffled)) {
                    timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffled)
                }
            } else (0 until player.mediaItemCount).toList()
            queue = queueMediaIndices.map { songFromItem(player.getMediaItemAt(it)) }
        }
        queueIndex = queueMediaIndices.indexOf(player.currentMediaItemIndex)
        if (radioActive && queue.size - queueIndex <= 2 && radioJob?.isActive != true) appendRadio()
        val next = player.currentMediaItem?.let(::songFromItem)
        position = player.currentPosition.coerceAtLeast(0)
        duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: next?.duration ?: 0
        if (next?.key != current?.key) {
            lyricJob?.cancel(); lyricGeneration++
            current = next
            lyrics = emptyList(); plainLyrics = ""; lyricsSource = ""; lyricsError = null; lyricsLoading = false
            hasManualLyrics = next?.isLocal == true && settings.lyricFile(next.key).isNotBlank()
            next?.let { song ->
                recent = settings.recentSongs()
                loadLyrics(song.id)
            }
        } else current = next
        if (next?.key != coverShapeKey) {
            coverShapeKey = next?.key
            updateCoverShape()
        }
        prefetchUpcomingCovers()
        updatePositionPolling()
    }
    private var coverShapeKey: String? = null
    private val prefetchedCovers = HashSet<String>()

    /** fixed / perSong / cycle; per-song keeps each track's silhouette but never repeats the previous one. */
    private fun updateCoverShape() {
        val count = coverPolygons.size
        val key = current?.key
        val base = if (key.isNullOrBlank()) 1 else (key.hashCode() and 0x7fffffff) % count
        val index = when (coverShapeMode) {
            "fixed" -> coverFixedShapeIndex.coerceIn(0, count - 1)
            "cycle" -> (lastCoverShapeIndex + 1) % count
            else -> if (base == lastCoverShapeIndex) (base + 1) % count else base
        }
        lastCoverShapeIndex = index
        coverShapeIndex = index
    }
    fun selectFixedCoverShape(index: Int) {
        coverFixedShapeIndex = index.coerceIn(0, coverPolygons.size - 1)
        settings.coverFixedShape = coverFixedShapeIndex
        selectCoverShapeMode("fixed")
    }
    fun selectCoverShapeMode(value: String) {
        coverShapeMode = value
        settings.coverShapeMode = value
        lastCoverShapeIndex = -1
        updateCoverShape()
    }
    private fun prefetchUpcomingCovers() {
        if (queue.isEmpty()) return
        val pending = queue.drop(queueIndex + 1).take(10).map { it.cover }
            .filter { it.isNotBlank() && prefetchedCovers.add(it) }
        if (pending.isNotEmpty()) prefetchArtwork(getApplication<Application>(), pending)
    }
    fun forceRefreshLocalMusic() = refreshLocalMusic(force = true)
    fun refreshLocalMusic(force: Boolean = false) {
        localPermission = canReadLocalMusic(getApplication())
        if (!localPermission) {
            localJob?.cancel(); localSongs = emptyList(); localLoading = false; localError = null; localRefreshMessage = null
            rebuildRecommendations()
            rebuildLocalHome()
            return
        }
        if (localJob?.isActive == true) {
            if (force) viewModelScope.launch { localJob?.join(); refreshLocalMusic(force = true) }
            return
        }
        localJob = viewModelScope.launch {
            localLoading = true; localError = null
            if (force) localRefreshMessage = null
            try {
                val previous = localSongs.associateBy { it.key }
                var failed = 0
                localSongs = withContext(Dispatchers.IO) {
                    if (force) failed = rescanLocalFiles(getApplication())
                    scanLocalMusic(getApplication(), force)
                }
                val localByKey = localSongs.associateBy { it.key }
                recent = settings.recentSongs().map { if (it.isLocal) localByKey[it.key] ?: it else it }
                controller?.let { player ->
                    for (index in 0 until player.mediaItemCount) {
                        val item = player.getMediaItemAt(index)
                        val updated = localByKey[item.mediaId] ?: continue
                        if (songFromItem(item) != updated) player.replaceMediaItem(index, updated.mediaItem())
                    }
                    syncPlayer()
                }
                if (force) {
                    localArtworkRevision++
                    val added = localSongs.count { it.key !in previous }
                    val changed = localSongs.count { it.key in previous && it != previous[it.key] }
                    val removed = previous.keys.count { it !in localByKey }
                    localRefreshMessage = "已刷新 ${localSongs.size} 首 · 新增 $added · 更新 $changed · 移除 $removed" +
                        if (failed > 0) "（$failed 个文件未重新索引）" else ""
                    current?.takeIf { it.isLocal }?.let { loadLyrics(it.id) }
                }
                localFavorites = settings.localFavorites()
                rebuildRecommendations()
                rebuildLocalHome()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                localPermission = canReadLocalMusic(getApplication())
                if (!localPermission) localSongs = emptyList()
                localError = "扫描未完成，请检查音乐权限后重试"
                rebuildRecommendations()
                rebuildLocalHome()
            } finally { localLoading = false }
        }
    }
    fun isLiked(song: Song): Boolean = if (song.isLocal) song.key in localFavorites else song.id in liked
    private fun rebuildRecommendations() {
        val localKeys = localSongs.map { it.key }.toSet()
        val candidates = localSongs + daily + recent.filter { !it.isLocal || it.key in localKeys }
        recommendations = recommendSongs(candidates, settings.listeningStats(), localFavorites + liked.map(Long::toString))
    }
    fun openRecommendations() {
        recent = settings.recentSongs()
        rebuildRecommendations()
        openSongs("为你混合推荐", recommendations, "recommendations")
    }
    // Logged-out home: a daily mix and playlists derived only from the device library.
    private fun rebuildLocalHome() {
        refreshFavoriteRows()
        if (localSongs.isEmpty()) {
            localDaily = emptyList()
            localCollections = emptyList()
            settings.localDailyDate = ""
            settings.localDailyKeys = emptyList()
            return
        }
        val stats = settings.listeningStats()
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val byKey = localSongs.associateBy { it.key }
        val saved = if (settings.localDailyDate == today) settings.localDailyKeys else emptyList()
        val restored = saved.mapNotNull(byKey::get)
        localDaily = if (saved.isNotEmpty() && restored.size == saved.size) restored
            else buildLocalDaily(localSongs, stats, localFavorites, now).also { mix ->
                settings.localDailyDate = today
                settings.localDailyKeys = mix.map { it.key }
            }
        localCollections = buildLocalCollections(localSongs, stats, localFavorites, now)
    }
    fun openLocalDaily() = openSongs("今日推荐", localDaily, localDailySource)
    fun openLocalCollection(collection: LocalCollection) = openSongs(collection.title, collection.songs, "local-collection:${collection.key}")
    fun openLocalFavorites() = openSongs("本地收藏", localSongs.filter(::isLiked), "local-favorites")
    fun refresh() {
        refreshLocalMusic()
        if (busy) return
        viewModelScope.launch {
            busy = true
            val failures = mutableListOf<String>()
            suspend fun attempt(block: suspend () -> Unit) {
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures += e.message ?: "加载未完成" }
            }
            try {
                // Each section keeps its last successful data if another request fails.
                attempt { discovery = withContext(Dispatchers.IO) { api.discovery() }.distinctBy { it.id } }
                attempt { profile = if (settings.cookie.isNotBlank()) withContext(Dispatchers.IO) { api.profile() } else null }
                val user = profile
                if (user != null) {
                    attempt { lists = withContext(Dispatchers.IO) { api.lists(user.id) } }
                    attempt {
                        daily = withContext(Dispatchers.IO) { api.recommendations() }
                        dailyDate = LocalDate.now()
                    }
                    attempt { liked = withContext(Dispatchers.IO) { api.liked(user.id) } }
                } else { lists = emptyList(); daily = emptyList(); dailyDate = null; liked = emptySet() }
                if (failures.isNotEmpty()) error = "部分内容未刷新：${failures.first()}"
            }
            finally {
                rebuildRecommendations(); busy = false
                if (favoritesOpen) loadFavoriteSongs(refreshIds = false)
            }
        }
    }
    fun updateQuery(text: String) {
        query = text
        searchGeneration++; searchJob?.cancel(); searchLoading = false
        searchHasMore = false; cloudSearchError = null
        submittedQuery = ""; cloudResults = emptyList(); cloudOffset = 0
    }
    fun selectSearchSource(source: SearchSource) {
        if (source == searchSource) return
        searchSource = source
        if (query.isNotBlank()) search() else updateQuery("")
    }
    fun search(text: String = query, more: Boolean = false) {
        if (text.isBlank() || (more && (searchLoading || !searchHasMore))) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        query = text.trim(); submittedQuery = query
        settings.rememberSearch(query); searches = settings.searches()
        val keyword = query
        if (!more) { cloudResults = emptyList(); cloudOffset = 0; searchHasMore = false }
        cloudSearchError = null
        if (searchSource == SearchSource.Local) { searchLoading = false; return }
        val offset = cloudOffset
        searchJob = viewModelScope.launch {
            searchLoading = true
            try {
                val songs = withContext(Dispatchers.IO) { api.search(keyword, offset) }
                if (generation == searchGeneration && query == keyword) {
                    cloudResults = ((if (more) cloudResults else emptyList()) + songs).distinctBy { it.key }
                    cloudOffset = offset + songs.size
                    searchHasMore = songs.size == 30
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == searchGeneration) {
                    cloudSearchError = "网易云搜索未完成，请检查网络后重试"
                    searchHasMore = more
                }
            } finally { if (generation == searchGeneration) searchLoading = false }
        }
    }
    fun clearSearches() { settings.clearSearches(); searches = emptyList() }
    fun openList(list: MusicList) {
        openSongs(list.name, emptyList(), "playlist:${list.id}")
        openedList = list
        loadMoreList()
    }
    fun openLikedSongs() {
        openSongs("我喜欢的音乐", localSongs.filter(::isLiked), "favorites:${profile?.id ?: 0}")
        favoritesOpen = true
        loadFavoriteSongs()
    }
    private fun refreshFavoriteRows() {
        if (!favoritesOpen) return
        favoriteRowsJob?.cancel()
        val generation = listGeneration
        val songs = (localSongs.filter(::isLiked) +
            if (profile != null) favoriteCloudSongs.filter { it.id in liked } else emptyList()).distinctBy { it.key }
        favoriteRowsJob = viewModelScope.launch {
            val ordered = withContext(Dispatchers.Default) { orderLocalSongs(songs, LocalSongSort.Title, false).songs }
            if (favoritesOpen && generation == listGeneration) songList = ordered
        }
    }
    private fun loadFavoriteSongs(refreshIds: Boolean = true) {
        listJob?.cancel()
        val generation = ++listGeneration
        val user = profile
        listLoading = user != null
        listHasMore = false
        refreshFavoriteRows()
        if (user == null) return
        listJob = viewModelScope.launch {
            try {
                val ids = if (refreshIds) withContext(Dispatchers.IO) { api.liked(user.id) } else liked
                if (generation != listGeneration || profile?.id != user.id) return@launch
                liked = ids
                favoriteCloudSongs = favoriteCloudSongs.filter { it.id in ids }
                val known = favoriteCloudSongs.map { it.id }.toSet()
                for (batch in (ids - known).toList().chunked(100)) {
                    val tracks = withContext(Dispatchers.IO) { api.songDetails(batch) }
                    if (generation != listGeneration || profile?.id != user.id) return@launch
                    favoriteCloudSongs = (favoriteCloudSongs + tracks).distinctBy { it.key }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == listGeneration) error = e.message ?: "网易云收藏加载失败" }
            finally {
                if (generation == listGeneration) {
                    listLoading = false
                    refreshFavoriteRows()
                }
            }
        }
    }
    fun loadMoreList() {
        val list = openedList ?: return
        if (listLoading) return
        val offset = songList.size
        val generation = listGeneration
        listLoading = true
        listJob = viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    api.tracks(list.id, offset)
                }
                if (generation != listGeneration) return@launch
                songList = songList + tracks
                listHasMore = tracks.size == 100
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == listGeneration) { error = e.message; listHasMore = true } }
            finally { if (generation == listGeneration) listLoading = false }
        }
    }
    fun openSongs(title: String, songs: List<Song>, source: String = "songs:$title") {
        listGeneration++
        listJob?.cancel(); favoriteRowsJob?.cancel(); listLoading = false; openedList = null; favoritesOpen = false
        songListSource = source; songListTitle = title; songList = songs; listHasMore = false; page = Page.Songs
    }
    fun openRecent() {
        recent = settings.recentSongs()
        // Keep this visit's row order stable while the underlying history keeps updating.
        openSongs("最近播放", recent, "recent")
    }
    fun play(songs: List<Song>, index: Int = 0, radio: Boolean = false,
        origin: PlaybackOrigin = PlaybackOrigin.SongList, source: String = "", restartQueue: Boolean = false) {
        val player = controller ?: run { error = "播放器正在准备，请稍候"; return }
        if (songs.isEmpty()) return
        val targetIndex = index.coerceIn(songs.indices)
        if (songs[targetIndex].isLocal && !canReadLocalMusic(getApplication())) {
            refreshLocalMusic(); error = "请先在本地音乐页面允许读取音频"; return
        }
        val targetKey = songs[targetIndex].key
        val queueSource = player.playlistMetadata.extras?.getString(PlaybackService.QUEUE_SOURCE).orEmpty()
        val sameSource = source.isNotEmpty() && source == queueSource
        val sameQueue = radioActive == radio && player.mediaItemCount == songs.size &&
            songs.indices.all { player.getMediaItemAt(it).mediaId == songs[it].key }
        if (!restartQueue && (sameSource || (sameQueue && (source.isEmpty() || queueSource.isEmpty()))) &&
            player.mediaItemCount > 0 && radioActive == radio) {
            if (source.isNotEmpty() && queueSource.isEmpty()) player.playlistMetadata = MediaMetadata.Builder().setExtras(Bundle().apply {
                putString(PlaybackService.QUEUE_SOURCE, source)
            }).build()
            // A source can be re-sorted or paginated without replacing its active queue.
            val queued = (0 until player.mediaItemCount).associateBy { player.getMediaItemAt(it).mediaId }
            val missing = songs.distinctBy { it.key }.filter { it.key !in queued }
            if (missing.isNotEmpty()) {
                val count = player.mediaItemCount
                val timeline = player.currentTimeline
                val order = queueOrder(count, timeline.getFirstWindowIndex(true)) {
                    timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true)
                } + (count until count + missing.size).shuffled()
                player.addMediaItems(missing.map { it.mediaItem() })
                if (player.shuffleModeEnabled) player.sendCustomCommand(
                    SessionCommand(PlaybackService.KEEP_SHUFFLE_ORDER, Bundle.EMPTY), Bundle().apply {
                        putString(PlaybackService.QUEUE_SOURCE, player.playlistMetadata.extras?.getString(PlaybackService.QUEUE_SOURCE).orEmpty())
                        putIntArray("order", order.toIntArray())
                    })
            }
            val mediaIndex = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == targetKey } ?: return
            if (player.currentMediaItemIndex != mediaIndex || player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(mediaIndex)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            anchorShuffle(origin)
            player.play()
            return
        }
        radioJob?.cancel(); radioActive = radio
        val localByKey = localSongs.associateBy { it.key }
        player.playlistMetadata = MediaMetadata.Builder().setExtras(Bundle().apply {
            putString(PlaybackService.QUEUE_SOURCE, source)
        }).build()
        player.setMediaItems(songs.map { (if (it.isLocal) localByKey[it.key] ?: it else it).mediaItem() }, targetIndex, 0)
        if (player.shuffleModeEnabled) player.repeatMode = Player.REPEAT_MODE_ALL
        anchorShuffle(origin)
        player.prepare(); player.play()
    }
    fun togglePlay() {
        val player = controller ?: return
        if (player.playWhenReady) { player.pause(); return }
        if (current?.isLocal == true && !canReadLocalMusic(getApplication())) {
            refreshLocalMusic(); error = "请先在本地音乐页面允许读取音频"; return
        }
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
    fun next() { controller?.let { player ->
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        else if (player.shuffleModeEnabled && player.mediaItemCount > 1) {
            val first = player.currentTimeline.getFirstWindowIndex(true)
            if (first == C.INDEX_UNSET) return@let
            player.seekToDefaultPosition(first)
        } else return@let
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    } }
    fun previous() { controller?.let { player ->
        if (player.mediaItemCount == 0) return@let
        player.seekToPrevious()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    } }
    fun seek(ms: Long) { position = ms.coerceIn(0, duration.coerceAtLeast(0)); controller?.seekTo(position) }
    fun selectQueue(index: Int) {
        val mediaIndex = queueMediaIndices.getOrNull(index) ?: return
        controller?.seekToDefaultPosition(mediaIndex); controller?.play()
    }
    fun cycleQueueMode() = setPlaybackMode(when {
        shuffled -> PlaybackMode.Sequential
        repeat == Player.REPEAT_MODE_ALL -> PlaybackMode.Shuffle
        else -> PlaybackMode.RepeatAll
    }, PlaybackOrigin.SongList)
    private fun anchorShuffle(origin: PlaybackOrigin) {
        if (origin == PlaybackOrigin.OutsideList) {
            controller?.let { player ->
                player.sendCustomCommand(SessionCommand(PlaybackService.ANCHOR_SHUFFLE, Bundle.EMPTY), Bundle().apply {
                    putString(PlaybackService.QUEUE_SOURCE, player.playlistMetadata.extras?.getString(PlaybackService.QUEUE_SOURCE).orEmpty())
                    putString("song", player.currentMediaItem?.mediaId)
                })
            }
        }
    }
    fun setPlaybackMode(mode: PlaybackMode, origin: PlaybackOrigin) {
        controller?.let {
            it.shuffleModeEnabled = mode == PlaybackMode.Shuffle
            it.repeatMode = when (mode) {
                PlaybackMode.RepeatOne -> Player.REPEAT_MODE_ONE
                PlaybackMode.RepeatAll, PlaybackMode.Shuffle -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            if (mode == PlaybackMode.Shuffle) anchorShuffle(origin)
        }
    }
    fun toggleLike() {
        val song = current ?: return
        if (song.isLocal) {
            settings.setLocalFavorite(song, !isLiked(song)); localFavorites = settings.localFavorites()
            rebuildRecommendations()
            rebuildLocalHome()
            return
        }
        if (profile == null) { playerExpanded = false; page = Page.Login; return }
        val value = song.id !in liked
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.like(song.id, value) }
                liked = if (value) liked + song.id else liked - song.id
                rebuildRecommendations()
                if (favoritesOpen) loadFavoriteSongs(refreshIds = false)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    fun playRadio() {
        if (profile == null) { page = Page.Login; return }
        viewModelScope.launch {
            try { play(withContext(Dispatchers.IO) { api.radio() }, radio = true, origin = PlaybackOrigin.OutsideList,
                source = "radio:${profile?.id}", restartQueue = true); playerExpanded = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    private fun appendRadio() {
        radioJob = viewModelScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { api.radio() }
                if (radioActive) controller?.addMediaItems(songs.map { it.mediaItem() })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { radioActive = false; error = e.message ?: "私人 FM 暂未加载到更多音乐" }
        }
    }
    fun openDaily() {
        if (profile == null) { page = Page.Login; return }
        page = Page.Daily
        if (daily.isEmpty() || dailyDate != LocalDate.now()) refresh()
    }
    fun playDaily() {
        if (daily.isNotEmpty()) { play(daily, origin = PlaybackOrigin.OutsideList, source = dailySource, restartQueue = true); playerExpanded = true } else openDaily()
    }
    fun loadLyrics(id: Long) {
        val song = current?.takeIf { it.id == id } ?: return
        lyricJob?.cancel()
        val generation = ++lyricGeneration
        lyrics = emptyList(); plainLyrics = ""; lyricsSource = ""
        hasManualLyrics = song.isLocal && settings.lyricFile(song.key).isNotBlank()
        lyricJob = viewModelScope.launch {
            lyricsLoading = true; lyricsError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    if (song.isLocal) localLyricsReader.load(song)
                    else LocalLyricContent(lines = api.lyrics(id))
                }
                if (generation == lyricGeneration && current?.key == song.key) {
                    lyrics = result.lines; plainLyrics = result.text; lyricsSource = result.source
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == lyricGeneration) lyricsError = when (e) {
                    is SecurityException -> "歌词文件或音乐文件夹的访问已失效，请重新选择"
                    else -> e.message ?: "歌词加载未完成"
                }
            }
            finally { if (generation == lyricGeneration) lyricsLoading = false }
        }
    }
    fun selectLocalLyricFile(songKey: String, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }.orEmpty()
                    require(name.endsWith(".lrc", ignoreCase = true)) { "请选择 .lrc 歌词文件" }
                    localLyricsReader.readFile(uri, "手动选择的 LRC")
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    settings.setLyricFile(songKey, uri.toString())
                }
                if (current?.key == songKey) current?.let { loadLyrics(it.id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "歌词文件读取未完成，请重新选择" }
        }
    }
    fun selectLyricsTree(uri: Uri) {
        try {
            require(DocumentsContract.isTreeUri(uri) && uri.authority == "com.android.externalstorage.documents") {
                "请选择手机或 SD 卡中的音乐文件夹"
            }
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settings.lyricsTree = uri.toString()
            current?.takeIf { it.isLocal }?.let { loadLyrics(it.id) }
        } catch (e: Exception) { error = e.message ?: "音乐文件夹读取未完成，请重新选择" }
    }
    fun clearLocalLyricFile() {
        val song = current?.takeIf { it.isLocal } ?: return
        settings.setLyricFile(song.key, null)
        loadLyrics(song.id)
    }
    fun startQrLogin() {
        qrJob?.cancel(); qrBitmap = null; qrMessage = "正在生成二维码…"
        qrJob = viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { api.qrKey() }
                qrBitmap = withContext(Dispatchers.Default) {
                    val matrix = MultiFormatWriter().encode("https://music.163.com/login?codekey=$key", BarcodeFormat.QR_CODE, 512, 512)
                    Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                        setPixels(IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 512, 0, 0, 512, 512)
                    }
                }
                qrMessage = "使用网易云音乐扫一扫"
                repeat(90) {
                    delay(2000)
                    when (withContext(Dispatchers.IO) { api.qrStatus(key) }) {
                        800 -> { qrMessage = "二维码已过期，点击刷新"; return@launch }
                        802 -> qrMessage = "已扫码，请在网易云音乐中确认"
                        803 -> { qrMessage = "登录成功"; refresh(); page = Page.Home; return@launch }
                    }
                }
                qrMessage = "二维码已过期，点击刷新"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { qrMessage = e.message ?: "二维码生成未完成，请重试" }
        }
    }
    fun stopQrLogin() { qrJob?.cancel() }
    fun logout() {
        radioActive = false; radioJob?.cancel()
        listGeneration++; listJob?.cancel(); favoriteRowsJob?.cancel(); listLoading = false
        favoritesOpen = false; favoriteCloudSongs = emptyList()
        settings.clearSession(); profile = null; lists = emptyList(); daily = emptyList(); dailyDate = null; liked = emptySet()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        if (current?.isLocal != true) { controller?.stop(); controller?.clearMediaItems() }
        rebuildRecommendations(); page = Page.Home; accountVisible = false
    }
    fun saveLocalName(value: String) {
        localName = value.trim().take(24)
        settings.localName = localName
    }
    // Copies the picked photo into app storage: a content:// grant would not survive a restart.
    fun saveLocalAvatar(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val path = withContext(Dispatchers.IO) {
                    val target = File(app.filesDir, "profile-avatar-${System.currentTimeMillis()}")
                    val previous = settings.localAvatar
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取所选图片")
                    if (previous.isNotBlank()) File(previous).takeIf { it.parentFile == app.filesDir }?.delete()
                    target.absolutePath
                }
                localAvatar = path
                settings.localAvatar = path
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "头像读取失败，请换一张图片" }
        }
    }
    fun refreshProfile() {
        if (settings.cookie.isBlank()) return
        viewModelScope.launch {
            try {
                profile = withContext(Dispatchers.IO) { api.profile() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "资料刷新失败，请检查网络" }
        }
    }
    fun setAppearance(value: String) { theme = value; settings.theme = value }
    fun setPlayerStyle(value: PlayerStyleMode) {
        playerStyleMode = value
        settings.playerStyleMode = value
        if (value != PlayerStyleMode.TapToSwitch) {
            lastBoldPlayer = value == PlayerStyleMode.Bold
            settings.lastBoldPlayer = lastBoldPlayer
        }
    }
    fun togglePlayerStyle(displayedBold: Boolean) {
        if (!playerExpanded || lyricsVisible || queueVisible || outputVisible ||
            playerStyleMode != PlayerStyleMode.TapToSwitch || displayedBold != boldPlayer) return
        lastBoldPlayer = !displayedBold
        settings.lastBoldPlayer = lastBoldPlayer
    }
    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}
