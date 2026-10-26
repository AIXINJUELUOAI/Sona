@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.sona.music

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import java.io.File

@UnstableApi
@Composable
fun HomeScreen(model: MusicViewModel, scroll: LazyListState, carousel: CarouselState) {
    val loggedIn = model.profile != null
    val hero = if (loggedIn) model.daily else model.localDaily
    val heroActive = model.current?.let { current -> hero.any { it.key == current.key } } == true
    HomeContent(
        loggedIn = loggedIn,
        daily = model.daily,
        localDaily = model.localDaily,
        discovery = model.discovery,
        localCollections = model.localCollections,
        heroActive = heroActive,
        heroPlaying = heroActive && model.playing,
        heroCover = if (heroActive) model.current?.cover else null,
        refreshing = model.busy,
        scroll = scroll,
        carousel = carousel,
        onRefresh = model::refresh,
        onDaily = {
            if (loggedIn) model.openDaily()
            else if (model.localDaily.isEmpty()) model.page = Page.Local
            else model.openLocalDaily()
        },
        onDailyPlay = {
            when {
                heroActive -> model.togglePlay()
                loggedIn -> model.playDaily()
                model.localDaily.isEmpty() -> model.page = Page.Local
                else -> model.play(model.localDaily, origin = PlaybackOrigin.OutsideList,
                    source = model.localDailySource, restartQueue = true)
            }
        },
        onFavorites = model::openLikedSongs,
        onPlaylist = model::openList,
        onLocalCollection = model::openLocalCollection,
        onLibrary = { model.page = Page.Library },
        onSearch = { model.page = Page.Search },
        onRecent = model::openRecent,
        onRadio = model::playRadio,
        bottomInset = contentBottomPadding(model.current != null),
        onLocal = { model.page = Page.Local }
    )
}

@Composable
internal fun HomeContent(
    loggedIn: Boolean, daily: List<Song>, localDaily: List<Song>,
    discovery: List<MusicList>, localCollections: List<LocalCollection>, refreshing: Boolean,
    heroActive: Boolean, heroPlaying: Boolean, heroCover: String?,
    scroll: LazyListState, carousel: CarouselState, onRefresh: () -> Unit, onDaily: () -> Unit, onDailyPlay: () -> Unit,
    onFavorites: () -> Unit, onPlaylist: (MusicList) -> Unit, onLocalCollection: (LocalCollection) -> Unit,
    onLibrary: () -> Unit, onSearch: () -> Unit, onRecent: () -> Unit, onRadio: () -> Unit,
    bottomInset: Dp = 24.dp, onLocal: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileSize = ((maxWidth - 40.dp) * 0.44f).coerceAtMost(maxHeight * 0.21f).coerceIn(124.dp, 200.dp)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(rememberGreeting(), Modifier.weight(1f),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 52.sp),
                fontWeight = FontWeight.Black, maxLines = 1)
            FilledTonalIconButton(onClick = onSearch, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Search, "搜索")
            }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh,
            modifier = Modifier.weight(1f).scrollViewport(20.dp).testTag("home-refresh")) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("home-sections"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                    top = 8.dp, bottom = bottomInset),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(key = "daily") {
                    DailyHero(loggedIn, if (loggedIn) daily else localDaily, discovery,
                        heroCover, heroActive, heroPlaying, onDaily, onDailyPlay)
                }
                if (loggedIn || localCollections.isNotEmpty()) item(key = "playlists") {
                    Column(Modifier.testTag("home-playlists"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("为你精选", "资料库", onLibrary)
                        HomePlaylistStrip(loggedIn, discovery, localCollections, refreshing,
                            onFavorites, onPlaylist, onLocalCollection, onRefresh, tileSize, carousel)
                    }
                }
                item(key = "list") {
                    // Private FM is a signed-in feature: in local mode the row disappears instead of
                    // leading to a login wall.
                    val rows = buildList {
                        add(HomeRow("本地音乐", "手机里的音乐，离线也能听", Icons.Outlined.Folder, onLocal))
                        if (loggedIn) add(HomeRow("私人 FM", "根据喜好持续推荐", Icons.Rounded.Sensors, onRadio))
                        add(HomeRow("最近播放", "接着上次的继续", Icons.Rounded.History, onRecent))
                    }
                    Column(Modifier.testTag("home-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("听点别的")
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            rows.forEachIndexed { index, row ->
                                SegmentedListItem(onClick = row.onClick,
                                    shapes = ListItemDefaults.segmentedShapes(index, rows.size).copy(shape = segmentedOuterShape(index, rows.size)),
                                    colors = ListItemDefaults.segmentedColors(containerColor = scheme.surfaceContainer),
                                    leadingContent = { Icon(row.icon, null, tint = scheme.primary) },
                                    supportingContent = { Text(row.subtitle) },
                                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }) {
                                    Text(row.title, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

private data class HomeRow(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

private data class HomeTile(val key: String, val title: String, val cover: String, val icon: ImageVector?, val onClick: () -> Unit)

// Greeting, daily card and an overlapping shaped cover, following the approved home mock-up.
@Composable
private fun DailyHero(loggedIn: Boolean, hero: List<Song>, discovery: List<MusicList>, activeCover: String?,
    active: Boolean, playing: Boolean, onDaily: () -> Unit, onDailyPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home-daily")) {
        val coverSize = (maxWidth * 0.54f).coerceIn(156.dp, 230.dp)
        // The cover sits on the card's top-right corner and hides it: it rises above the card
        // by about a third of its height and pokes slightly past the card's right edge.
        val overflowEnd = 14.dp
        val rise = coverSize * 0.34f
        val cardLift = 12.dp
        val cardTop = 8.dp + rise - cardLift
        val coverDrop = 24.dp
        Surface(onClick = onDaily, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, shape = outerCornerShape,
            modifier = Modifier.padding(top = cardTop).fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth(0.53f).heightIn(min = coverSize - 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column {
                    Text(if (loggedIn) "每日推荐" else "今日推荐", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (hero.isNotEmpty()) hero.size.toString() else if (loggedIn) "为你" else "0",
                            fontSize = 50.sp, lineHeight = 54.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        if (hero.isNotEmpty()) Text(" 首", Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                MorphingControl(if (active && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    when {
                        active && playing -> "暂停"
                        !loggedIn && hero.isEmpty() -> "扫描本地音乐"
                        loggedIn -> "播放每日推荐"
                        else -> "播放今日推荐"
                    },
                    onDailyPlay, Modifier.width(96.dp).height(48.dp), height = 48.dp, iconSize = 28.dp,
                    containerColor = scheme.primary, contentColor = scheme.onPrimary)
            }
        }
        val shape = MaterialShapes.Cookie9Sided.toShape()
        // While the mix is playing, the hero shows the track that is actually sounding.
        Artwork(activeCover?.takeIf { it.isNotBlank() } ?: hero.firstOrNull()?.cover ?: discovery.firstOrNull()?.cover,
            Modifier.align(Alignment.TopEnd).padding(top = cardTop - rise + coverDrop).offset(x = overflowEnd).size(coverSize).clip(shape)
                .clickable(onClickLabel = if (loggedIn) "查看每日推荐" else "查看今日推荐", onClick = onDaily).testTag("daily-cover"))
    }
}

@Composable
private fun HomePlaylistStrip(loggedIn: Boolean, discovery: List<MusicList>, localCollections: List<LocalCollection>,
    loading: Boolean, onFavorites: () -> Unit, onPlaylist: (MusicList) -> Unit,
    onLocalCollection: (LocalCollection) -> Unit, onRetry: () -> Unit, tileSize: Dp, carousel: CarouselState) {
    val tiles = buildList {
        add(HomeTile("favorites", "我喜欢的音乐", "", Icons.Rounded.Favorite, onFavorites))
        if (loggedIn) discovery.distinctBy { it.id }.forEach { list ->
            add(HomeTile("list-${list.id}", list.name, list.cover, null) { onPlaylist(list) })
        } else localCollections.filter { it.key != "favorites" }.forEach { collection ->
            add(HomeTile(collection.key, collection.title, collection.songs.firstOrNull()?.cover.orEmpty(), null) {
                onLocalCollection(collection)
            })
        }
    }
    val display: List<HomeTile?> = if (tiles.isEmpty()) List(2) { null } else tiles
    val tileShape = outerCornerShape
    @Composable fun Tile(tile: HomeTile?, modifier: Modifier, coverModifier: Modifier = Modifier) {
        val tileIcon = tile?.icon
        Column(modifier.clickable(enabled = tile != null || !loading) {
            if (tile != null) tile.onClick() else onRetry()
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = tileShape,
                color = if (tileIcon != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().height(tileSize).then(coverModifier)) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        tileIcon != null -> Icon(tileIcon, null, Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        tile != null -> Artwork(tile.cover, Modifier.fillMaxSize(), tile.title)
                        loading -> LoadingIndicator(Modifier.size(32.dp))
                        else -> Icon(Icons.Rounded.Refresh, "重新加载推荐")
                    }
                }
            }
            Text(tile?.title ?: if (loading) "正在发现好音乐" else "轻点重试",
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (display.size <= 2) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            display.forEach { Tile(it, Modifier.weight(1f)) }
        }
    } else {
        HorizontalMultiBrowseCarousel(state = carousel, preferredItemWidth = tileSize,
            itemSpacing = 8.dp, modifier = Modifier.fillMaxWidth().height(tileSize + 30.dp)) { index ->
            Tile(display[index], Modifier.fillMaxSize(), Modifier.maskClip(tileShape))
        }
    }
}

@Composable
fun SectionHeading(title: String, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        action?.let { TextButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(it); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp)) } }
    }
}

@UnstableApi
@Composable
fun SearchScreen(model: MusicViewModel, scroll: LazyListState, preview: Boolean = false) {
    val searchResults = model.results
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val submit = { focus.clearFocus(); keyboard?.hide(); model.search() }
    LaunchedEffect(preview) { if (!preview && model.results.isEmpty() && model.query.isBlank()) requester.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalIconButton(onClick = model::back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
            }
            TextField(value = model.query, onValueChange = model::updateQuery, modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(requester),
                readOnly = preview, enabled = !preview,
                singleLine = true, shape = CircleShape, placeholder = { Text("歌曲、歌手或专辑") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (model.query.isNotEmpty()) IconButton(onClick = submit) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "搜索") } },
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            SearchSource.entries.forEachIndexed { index, source ->
                ToggleButton(checked = model.searchSource == source, onCheckedChange = { model.selectSearchSource(source) },
                    enabled = !preview, modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        SearchSource.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }) { Text(source.label) }
            }
        }
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).scrollViewport(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = contentBottomPadding(model.current != null)),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            if (model.submittedQuery.isBlank()) {
                item { SectionHeading("最近搜索", if (model.searches.isNotEmpty()) "清空" else null, model::clearSearches) }
                item { FlowRow(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.searches.forEach { keyword -> SuggestionChip(onClick = { model.search(keyword) }, label = { Text(keyword) }, shape = CircleShape) }
                    if (model.searches.isEmpty()) Text("搜索你喜欢的第一首歌", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { SectionHeading("按风格浏览") }
                item {
                    val scheme = MaterialTheme.colorScheme
                    val genres = listOf(
                        Triple("流行", Icons.Rounded.Mic, MaterialShapes.Cookie9Sided) to (scheme.primaryContainer to scheme.onPrimaryContainer),
                        Triple("摇滚", Icons.Rounded.Bolt, MaterialShapes.Burst) to (scheme.secondaryContainer to scheme.onSecondaryContainer),
                        Triple("民谣", Icons.Rounded.MusicNote, MaterialShapes.Clover4Leaf) to (scheme.tertiaryContainer to scheme.onTertiaryContainer),
                        Triple("电子", Icons.Rounded.Equalizer, MaterialShapes.Sunny) to (scheme.surfaceContainerHighest to scheme.onSurface))
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        genres.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (genre, colors) ->
                                    val (name, icon, polygon) = genre
                                    Card(onClick = { model.search(name) }, modifier = Modifier.weight(1f).height(112.dp),
                                        colors = CardDefaults.cardColors(containerColor = colors.first, contentColor = colors.second), shape = outerCornerShape) {
                                        Box(Modifier.fillMaxSize().padding(16.dp)) {
                                            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                            ShapeBadge(icon, polygon, 52.dp, Modifier.align(Alignment.BottomEnd),
                                                color = colors.second.copy(alpha = .14f), content = colors.second)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (model.submittedQuery.isNotBlank()) {
                if (model.searchSource != SearchSource.Cloud && !model.localPermission) item {
                    TextButton(onClick = { model.page = Page.Local }, shapes = ButtonDefaults.shapes()) { Text("读取本地音乐，一起搜索") }
                }
                if (searchResults.isEmpty() && !model.searchLoading && !model.localLoading && model.cloudSearchError == null) item {
                    EmptyState("没有找到匹配歌曲", "试试歌曲名、歌手或专辑名，也可以切换音乐来源。", Icons.Rounded.Search)
                }
                model.cloudSearchError?.let { message -> item {
                    Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { model.search(more = model.searchHasMore) }, shapes = ButtonDefaults.shapes()) { Text("重试网易云搜索") }
                } }
            }
            itemsIndexed(searchResults, key = { _, s -> s.key }) { index, song ->
                SongRow(song, song.key == model.current?.key, index, searchResults.size,
                    showSource = model.searchSource != SearchSource.Local) { model.play(searchResults, index, source = model.searchQueueSource) }
            }
            if (model.searchLoading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            else if (model.searchHasMore) item { TextButton(onClick = { model.search(more = true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("加载更多") } }
        }
    }
}

@UnstableApi
@Composable
fun LibraryScreen(model: MusicViewModel, scroll: LazyListState) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("资料库", model::back)
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).scrollViewport(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentBottomPadding(model.current != null)),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            item {
                Text("本地音乐", Modifier.padding(start = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                LocalPlaylistRow("本地音乐", if (model.localPermission) "${model.localSongs.size} 首" else "读取手机中的歌曲",
                    Icons.Outlined.Folder, 0) { model.page = Page.Local }
            }
            item {
                LocalPlaylistRow("本地收藏", "${model.localSongs.count(model::isLiked)} 首", Icons.Outlined.FavoriteBorder, 1) {
                    if (model.localPermission) model.openLocalFavorites() else model.page = Page.Local
                }
            }
            if (model.profile == null) item { EmptyState("音乐，都在这里", "登录网易云音乐，带上你的收藏和歌单", Icons.Rounded.LibraryMusic, "登录") { model.page = Page.Login } }
            else {
                item {
                    Text("${model.profile?.name.orEmpty()} · ${model.lists.size} 个歌单", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(model.lists, key = { _, list -> list.id }) { index, list ->
                    SegmentedListItem(onClick = { model.openList(list) }, shapes = ListItemDefaults.segmentedShapes(index, model.lists.size).copy(shape = segmentedOuterShape(index, model.lists.size)),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        leadingContent = { Artwork(list.cover, Modifier.size(56.dp).clip(MaterialTheme.shapes.large)) }, supportingContent = { Text("${list.count} 首") },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }) { Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) }
                }
                item { TextButton(onClick = model::refresh, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 8.dp)) { Text("刷新歌单") } }
            }
        }
    }
}

@Composable
private fun LocalPlaylistRow(title: String, subtitle: String, icon: ImageVector, index: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SegmentedListItem(onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, 2).copy(shape = segmentedOuterShape(index, 2)),
        colors = ListItemDefaults.segmentedColors(containerColor = scheme.surfaceContainer,
            contentColor = scheme.onSurface, supportingContentColor = scheme.onSurfaceVariant,
            trailingContentColor = scheme.onSurfaceVariant),
        leadingContent = {
            Surface(Modifier.size(56.dp), shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainerHigh, contentColor = scheme.onSurfaceVariant) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(28.dp)) }
            }
        }, supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }) { Text(title, fontWeight = FontWeight.SemiBold) }
}

@UnstableApi
@Composable
fun SongsScreen(model: MusicViewModel, back: () -> Unit, dailyRecommendations: Boolean = false) {
    val songs = if (dailyRecommendations) model.daily else model.songList
    val loading = if (dailyRecommendations) model.busy else model.listLoading
    val source = if (dailyRecommendations) model.dailySource else model.songListSource
    val scroll = key(source) { rememberLazyListState() }
    val bottomPadding = contentBottomPadding(model.current != null)
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showPlayLabel = maxWidth >= 440.dp
            PageHeader(if (dailyRecommendations) "每日推荐" else model.songListTitle, back,
                subtitle = "共 ${songs.size} 首") {
                LocatePlayingButton(scroll, songs, model.current?.key,
                    leadingItems = if (dailyRecommendations) 1 else 0, bottomInset = bottomPadding, alwaysVisible = true)
                Button(onClick = {
                    model.play(songs, source = source, restartQueue = true, origin = PlaybackOrigin.OutsideList)
                    model.playerExpanded = true
                }, enabled = songs.isNotEmpty() && !loading, shapes = ButtonDefaults.shapes(),
                    modifier = if (showPlayLabel) Modifier.height(48.dp) else Modifier.size(48.dp),
                    contentPadding = PaddingValues(horizontal = if (showPlayLabel) 16.dp else 0.dp)) {
                    Icon(Icons.Rounded.PlayArrow, if (showPlayLabel) null else "播放全部", Modifier.size(24.dp))
                    if (showPlayLabel) { Spacer(Modifier.width(6.dp)); Text("播放全部") }
                }
            }
        }
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).scrollViewport(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            if (dailyRecommendations) item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.dailyDate?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }
                        ?: if (loading) "正在获取每日推荐" else "今日推荐", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = model::refresh, enabled = !loading) { Text("刷新") }
                }
            }
            itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                SongRow(song, song.key == model.current?.key, index, songs.size,
                    showSource = !dailyRecommendations && !model.songListIsCloudOnly && model.songListTitle != "本地收藏") {
                    model.play(songs, index, source = source)
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            else if (!dailyRecommendations && model.listHasMore) item { TextButton(onClick = model::loadMoreList, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("加载更多") } }
            else if (songs.isEmpty()) item { EmptyState("还没有歌曲",
                if (dailyRecommendations) "暂未获取到每日推荐，请点击刷新重试。"
                else if (model.songListTitle == "为你混合推荐") "先读取本地音乐，或搜索并收听网易云歌曲，就能开始推荐。"
                else if (model.songListTitle == "我喜欢的音乐") "点亮喜欢，歌曲就会出现在这里"
                else "收藏、播放过的音乐和歌单内容会显示在这里", Icons.Rounded.MusicNote) }
        }
    }
}

// Expressive segmented row: large outer corners on the group, tight inner corners, a filled pill for the current song.
@Composable
fun SongRow(song: Song, active: Boolean, index: Int, count: Int, showSource: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SegmentedListItem(onClick = onClick, shapes = if (active) ListItemDefaults.segmentedShapes(0, 1).copy(shape = outerCornerShape)
            else ListItemDefaults.segmentedShapes(index, count).copy(shape = segmentedOuterShape(index, count)),
        modifier = Modifier.testTag("song-row-${song.id}"),
        colors = ListItemDefaults.segmentedColors(containerColor = if (active) scheme.primaryContainer else scheme.surfaceContainer,
            contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurface,
            supportingContentColor = if (active) scheme.onPrimaryContainer.copy(alpha = .8f) else scheme.onSurfaceVariant),
        leadingContent = { Artwork(song.cover, Modifier.size(48.dp).clip(if (active) CircleShape else MaterialTheme.shapes.medium)) },
        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { SongRowStatus(song, showSource) }) {
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: ImageVector, action: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShapeBadge(icon, MaterialShapes.Cookie9Sided, 88.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Button(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(it) } }
    }
}

// Settings live in the side drawer; the home page keeps only search in its top bar.
@UnstableApi
@Composable
fun SettingsDrawer(model: MusicViewModel, drawerState: DrawerState, close: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(drawerState.isOpen) { if (!drawerState.isOpen) settingsExpanded = false }
    val settingsArrow by animateFloatAsState(if (settingsExpanded) 180f else 0f,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "drawer-settings-arrow")
    ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = scheme.surfaceContainerLow, drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sona", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    val greetingName = model.profile?.name?.takeIf { it.isNotBlank() }
                        ?: model.localName.takeIf { it.isNotBlank() }
                    Text(greetingName?.let { "嗨，$it" } ?: "你的音乐日常", color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // One account entry: the avatar opens the account sheet in every state.
                Box(Modifier.clip(CircleShape).clickable { model.accountVisible = true }) {
                    when {
                        model.profile?.avatarUrl.orEmpty().isNotBlank() -> AsyncImage(model = model.profile?.avatarUrl,
                            contentDescription = "账号", modifier = Modifier.size(44.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop)
                        model.localAvatar.isNotBlank() -> AsyncImage(model = File(model.localAvatar),
                            contentDescription = "账号", modifier = Modifier.size(44.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop)
                        else -> ShapeBadge(Icons.Rounded.Person, MaterialShapes.Cookie9Sided, 44.dp,
                            color = scheme.secondaryContainer, content = scheme.onSecondaryContainer)
                    }
                }
            }
            val bottomPadding = if (model.current != null) 96.dp else 16.dp
            Column(Modifier.weight(1f).scrollViewport(16.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListeningOverview(model, drawerState.isOpen, close)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            SegmentedListItem(onClick = { settingsExpanded = !settingsExpanded },
                modifier = Modifier.semantics { stateDescription = if (settingsExpanded) "已展开" else "已收起" },
                shapes = ListItemDefaults.segmentedShapes(0, if (settingsExpanded) 2 else 1)
                    .copy(shape = segmentedOuterShape(0, if (settingsExpanded) 2 else 1)),
                colors = ListItemDefaults.segmentedColors(containerColor = scheme.surfaceContainerHigh),
                leadingContent = { DrawerActionIcon(Icons.Rounded.Settings, scheme.primaryContainer, scheme.onPrimaryContainer) },
                supportingContent = { Text("${when (model.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }} · ${model.playerStyleMode.label}",
                    style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(settingsArrow)) }) {
                Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            AnimatedVisibility(settingsExpanded) {
            Surface(shape = segmentedOuterShape(1, 2), color = scheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DrawerPreferences(model)
            }
            }
            }
            }
            Text("Sona · ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@UnstableApi
@Composable
private fun DrawerPreferences(model: MusicViewModel) {
    Text("外观", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (key, title) ->
            ToggleButton(checked = model.theme == key, onCheckedChange = { model.setAppearance(key) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }) { Text(title, maxLines = 1) }
        }
    }
    Text("播放页样式", Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerStylePreview(false, !model.boldPlayer, { model.setPlayerStyle(PlayerStyleMode.Default) }, Modifier.weight(1f))
        PlayerStylePreview(true, model.boldPlayer, { model.setPlayerStyle(PlayerStyleMode.Bold) }, Modifier.weight(1f),
            shapeIndex = model.coverShapeIndex)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        PlayerStyleMode.entries.forEachIndexed { index, mode ->
            ToggleButton(checked = model.playerStyleMode == mode, onCheckedChange = { model.setPlayerStyle(mode) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    PlayerStyleMode.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }) { Text(mode.label, maxLines = 1) }
        }
    }
    if (model.playerStyleMode != PlayerStyleMode.Default) {
        Text("封面形状", Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val shapes = listOf("fixed" to "固定", "perSong" to "每首歌", "cycle" to "轮换")
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            shapes.forEachIndexed { index, (key, label) ->
                ToggleButton(checked = model.coverShapeMode == key, onCheckedChange = { model.selectCoverShapeMode(key) },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        shapes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }) { Text(label, maxLines = 1) }
            }
        }
        if (model.coverShapeMode == "fixed") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                coverPolygons.forEachIndexed { index, polygon ->
                    val selected = model.coverFixedShapeIndex == index
                    Box(Modifier.size(44.dp).clip(MorphShape(polygon))
                        .background(if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { model.selectFixedCoverShape(index) })
                }
            }
        }
    }
}

@Composable
private fun DrawerActionIcon(icon: ImageVector, background: Color, foreground: Color) {
    Surface(shape = CircleShape, color = background, contentColor = foreground) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
    }
}

@Composable
private fun PlayerStylePreview(bold: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    shapeIndex: Int = 0) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                if (bold) {
                    // Two lines mirror the bold layout's stacked title; the cover is meant to overlap it.
                    Text("SO\nNA", Modifier.align(Alignment.TopStart), color = scheme.primary,
                        style = MaterialTheme.typography.headlineLarge.copy(lineHeight = 36.sp),
                        fontWeight = FontWeight.Black, maxLines = 2)
                } else {
                    Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("sona", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = scheme.primary)
                        Text("hi Listener", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                if (bold) {
                    // Live sample of the current cover-shape mode, fading between silhouettes.
                    // Shifted left and up so it visibly bites into the second title line, the way
                    // the real bold layout lets the artwork cover the title.
                    Crossfade(targetState = shapeIndex,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-18).dp, y = (-2).dp).size(58.dp),
                        animationSpec = tween(220), label = "preview-cover-shape") { index ->
                        Box(Modifier.fillMaxSize()
                            .clip(MorphShape(coverPolygons[index.coerceIn(0, coverPolygons.size - 1)]))
                            .background(scheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MusicNote, null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
                        }
                    }
                } else {
                    Box(Modifier.align(Alignment.BottomCenter).size(46.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(scheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Text(if (bold) "大胆" else "默认", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@UnstableApi
@Composable
fun LoginScreen(model: MusicViewModel, preview: Boolean = false) {
    val context = LocalContext.current
    DisposableEffect(preview) {
        if (!preview) model.startQrLogin()
        onDispose { if (!preview) model.stopQrLogin() }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("登录网易云音乐", model::back)
        Column(Modifier.fillMaxWidth().weight(1f).scrollViewport(24.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = contentBottomPadding(false)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("把喜欢的，都带来。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            model.qrBitmap?.let { bitmap ->
                Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.White) { Image(bitmap.asImageBitmap(), "网易云登录二维码", Modifier.size(240.dp).padding(14.dp)) }
                Text("可以截图后在网易云音乐的扫一扫中从相册识别", style = MaterialTheme.typography.bodySmall)
            } ?: LoadingIndicator(Modifier.size(64.dp))
            Text(model.qrMessage)
            FilledTonalButton(onClick = model::startQrLogin, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("刷新二维码") }
            TextButton(onClick = { context.startActivity(Intent(context, WebLoginActivity::class.java)) }) { Text("使用网易云网页登录") }
            TextButton(onClick = { model.refresh(); model.page = Page.Home }) { Text("我已完成网页登录") }
        }
    }
}
