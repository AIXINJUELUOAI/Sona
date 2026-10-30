@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package app.sona.music

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import androidx.graphics.shapes.Morph
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@UnstableApi
@Composable
@Suppress("DEPRECATION")
fun SonaApp(model: MusicViewModel, dark: Boolean, systemScheme: ColorScheme) {
    val songScheme = rememberCoverScheme(model.current?.cover, dark, systemScheme)
    val snackbar = remember { SnackbarHostState() }
    val homeScroll = rememberLazyListState()
    // Back previews and the restored home must start at the same carousel position.
    val homeCarousel = rememberCarouselState {
        1 + if (model.profile != null) model.discovery.distinctBy { it.id }.size
        else model.localCollections.count { it.key != "favorites" }
    }
    val searchScroll = rememberLazyListState()
    val libraryScroll = rememberLazyListState()
    // Back previews and restored settings share the same position from their first frame.
    val floatingLyricsScroll = rememberScrollState()
    val lyricColorsScroll = rememberScrollState()
    val savedPages = rememberSaveableStateHolder()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    @Composable fun BrowseContent(page: Page, preview: Boolean = false) {
        when (page) {
            Page.Home -> HomeScreen(model, homeScroll, homeCarousel)
            Page.Search -> SearchScreen(model, searchScroll, preview)
            Page.Library -> LibraryScreen(model, libraryScroll)
            Page.Login -> LoginScreen(model, preview)
            Page.Songs -> SongsScreen(model, model::back)
            Page.Daily -> SongsScreen(model, model::back, dailyRecommendations = true)
            Page.Local -> LocalMusicScreen(model, preview)
            Page.Stats -> ListeningStatsScreen(model, model::back)
            Page.FloatingLyrics -> FloatingLyricsSettingsScreen(model, floatingLyricsScroll, preview)
            Page.LyricColors -> LyricColorsScreen(model, lyricColorsScroll)
        }
    }
    LaunchedEffect(model.error) {
        val message = model.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        if (model.error == message) model.error = null
    }
    val expanded = model.playerExpanded && model.current != null
    val view = LocalView.current
    val activity = LocalActivity.current
    val systemBarSurface = if (expanded) songScheme.primaryContainer else systemScheme.surface
    SideEffect {
        activity?.window?.let { window ->
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = systemBarSurface.luminance() > 0.5f
                isAppearanceLightNavigationBars = systemBarSurface.luminance() > 0.5f
            }
        }
    }
    Box(Modifier.fillMaxSize().background(systemBarSurface)) {
        // Browse pages stay composed under the player so collapsing never rebuilds the home screen.
        ModalNavigationDrawer(drawerContent = { SettingsDrawer(model, drawer) { scope.launch { drawer.close() } } }, drawerState = drawer,
            gesturesEnabled = !expanded && (drawer.isOpen || model.page == Page.Home)) {
            // Surface supplies onSurface as the content colour, so text follows light/dark themes.
            Surface(Modifier.fillMaxSize(), color = systemScheme.surfaceContainer, contentColor = systemScheme.onSurface) {
                val browseTransition = updateTransition(model.page, label = "browse-page")
                browseTransition.AnimatedContent(modifier = Modifier.fillMaxSize().clipToBounds(),
                    transitionSpec = { (EnterTransition.None togetherWith ExitTransition.None)
                        .apply { targetContentZIndex = model.navigationDepth.toFloat() }
                        .using(SizeTransform(clip = false)) }) { page ->
                    val returnPage = remember(page) { model.previousPage }
                    val backPreview = rememberBackPreview(
                        enabled = page == model.page && page != Page.Home &&
                            !expanded && !drawer.isOpen && !model.queueVisible && !model.outputVisible,
                        onBack = { model.back() })
                    val phase = pageMotionProgress()
                    val exiting = transition.targetState == EnterExitState.PostExit
                    val travel = if (exiting == model.navigatingBack) phase else 0f
                    val revealing = backPreview.progress.value > 0f || backPreview.committed
                    // Keep the preview stationary beneath the outgoing page until exit completes.
                    Box(Modifier.fillMaxSize()) {
                        if (revealing && (page == model.page || backPreview.committed)) {
                            Box(Modifier.fillMaxSize().background(systemScheme.surface)
                                .backPreviewBackdrop("browse-backdrop-${returnPage.name}") {
                                    if (backPreview.committed) 1f - phase else 1f
                                }) {
                                BrowseContent(returnPage, preview = true)
                            }
                        }
                        Box(Modifier.fillMaxSize().backPreview(backPreview, travel).background(systemScheme.surface)
                            .testTag("browse-page-${page.name}")) {
                            savedPages.SaveableStateProvider(page) { BrowseContent(page) }
                        }
                    }
                }
            }
        }
        PlayerOverlay(model, songScheme, expanded)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = if (model.current != null && !expanded) 88.dp else 16.dp))
    }
    if (model.queueVisible) MaterialTheme(colorScheme = songScheme) { QueueSheet(model) }
    if (model.accountVisible) AccountSheet(model,
        onDismiss = { model.accountVisible = false },
        onLogin = {
            model.accountVisible = false
            model.page = Page.Login
            scope.launch { drawer.close() }
        })
    if (model.outputVisible) MaterialTheme(colorScheme = songScheme) {
        MediaOutputSheet(playing = model.playing, onPlay = model::togglePlay,
            onPrevious = model::previous, onNext = model::next,
            previousEnabled = model.canSkipPrevious, nextEnabled = model.canSkipNext) { model.outputVisible = false }
    }
}

@UnstableApi
@Composable
private fun BoxScope.PlayerOverlay(model: MusicViewModel, songScheme: ColorScheme, expanded: Boolean) {
    val playerState = remember { SeekableTransitionState(expanded) }
    val playerTransition = rememberTransition(playerState, label = "shared-player")
    val motion = MaterialTheme.motionScheme
    // The shape mode decides what the bold player's cover silhouette is; the ViewModel owns the pick
    // so "never repeat the previous track" can remember what was shown.
    val playerCover = if (model.boldPlayer) coverPolygons[model.coverShapeIndex.coerceIn(0, coverPolygons.size - 1)]
        else MaterialShapes.Square
    // Normalized before morphing, the same way the framework's loading indicator does it, so shapes
    // with different vertex counts can still morph into the circular mini-player cover.
    val coverMorph = remember(playerCover) {
        runCatching { Morph(playerCover.normalized(), MaterialShapes.Circle.normalized()) }.getOrNull()
    }
    val coverProgress by playerTransition.animateFloat({ motion.defaultSpatialSpec() }, label = "cover-shape") {
        if (it) 0f else 1f
    }
    val classicCorner by playerTransition.animateFloat({ motion.defaultSpatialSpec() }, label = "classic-cover-corner") {
        if (it) 6f else 50f
    }
    val playerAtRest = playerState.currentState == playerState.targetState
    // A seekable transition resets its timeline after completion. The resting artwork
    // must use its destination shape rather than an animation sample from that timeline.
    val coverShape = when {
        playerAtRest && !playerState.targetState -> CircleShape
        !model.boldPlayer -> RoundedCornerShape(percent = if (playerAtRest) 6 else classicCorner.roundToInt().coerceIn(0, 50))
        playerAtRest -> MorphShape(playerCover)
        else -> MorphShape(playerCover, coverMorph, coverProgress.coerceIn(0f, 1f))
    }
    var releaseExpansion by remember { mutableFloatStateOf(1f) }
    val playerBackPreview = rememberBackPreview(
        enabled = expanded && !model.deskMode && !model.lyricsVisible && !model.queueVisible && !model.outputVisible,
        onBack = {
            releaseExpansion = if (playerAtRest) 1f else (1f - coverProgress).coerceIn(0f, 1f)
            model.playerExpanded = false
        })
    LaunchedEffect(expanded, playerBackPreview.gesturing) {
        if (playerBackPreview.gesturing) {
            // Keep the exact displayed entrance frame while the gesture owns the player.
            playerState.seekTo(playerState.fraction)
        } else {
            playerState.animateTo(expanded)
        }
    }
        model.current?.let { song ->
            // Transform the overlay and all its shared children together, not just the
            // normal player layer. Keep browse content outside this transformed scope.
            SharedTransitionLayout(Modifier.fillMaxSize().backPreview(playerBackPreview) {
                val remaining = if (!expanded && playerBackPreview.committed) {
                    if (!playerState.targetState) {
                        // Spatial springs reach their destination before the shared timeline's
                        // tail ends. Restore scale with spatial motion, not elapsed time, so the
                        // mini player does not grow after it has already landed.
                        if (playerAtRest || releaseExpansion <= 0f) 0f
                        else ((1f - coverProgress) / releaseExpansion).coerceIn(0f, 1f)
                    } else 1f
                } else 1f
                playerBackPreview.progress.value * remaining
            }) {
                val shared = this
                MaterialTheme(colorScheme = songScheme) {
                    playerTransition.AnimatedVisibility({ !it }, Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                        MiniPlayer(model, song, Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("floating-player")
                            .sharedBounds(shared.rememberSharedContentState("player-container"), this,
                                boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() },
                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillBounds),
                                clipInOverlayDuringTransition = OverlayClip(CircleShape)), shared, this, coverShape)
                    }
                    playerTransition.AnimatedVisibility({ it }, Modifier.fillMaxSize(),
                        enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                        val cornerProgress by transition.animateFloat({ motion.defaultSpatialSpec() }, label = "player-corner") {
                            if (it == EnterExitState.Visible) 0f else 1f
                        }
                        val corner = 32.dp * cornerProgress.coerceIn(0f, 1f)
                        Surface(modifier = Modifier.fillMaxSize().testTag("expanded-player")
                            .sharedBounds(shared.rememberSharedContentState("player-container"), this,
                                boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() },
                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillBounds),
                                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner))),
                            color = songScheme.primaryContainer, contentColor = songScheme.onPrimaryContainer) {
                            if (model.deskMode) DeskPlayerScreen(model)
                            else PlayerScreen(model, shared, this, coverShape,
                                styleSwitchEnabled = expanded && !playerBackPreview.gesturing &&
                                    !playerBackPreview.committed)
                        }
                    }
                }
            }
        }
}

@UnstableApi
@Composable
private fun MiniPlayer(
    model: MusicViewModel, song: Song, modifier: Modifier,
    shared: SharedTransitionScope, visibility: AnimatedVisibilityScope, coverShape: Shape
) {
    val motion = MaterialTheme.motionScheme
    val scheme = MaterialTheme.colorScheme
    val threshold = with(LocalDensity.current) { 92.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var passedThreshold by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(if (dragging) drag else 0f, if (dragging) snap() else motion.fastSpatialSpec(), label = "mini-drag")
    with(shared) {
        Surface(onClick = { model.playerExpanded = true }, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer,
            shape = RoundedCornerShape(32.dp), shadowElevation = 6.dp,
            modifier = modifier.fillMaxWidth().height(64.dp)
                .graphicsLayer { translationX = offset * 0.18f; scaleX = 1f + (abs(offset) / threshold).coerceAtMost(1f) * 0.025f }
                .draggable(state = rememberDraggableState {
                    drag = (drag + it).coerceIn(-threshold * 1.6f, threshold * 1.6f)
                    val passed = abs(drag) >= threshold
                    if (passed && !passedThreshold) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    passedThreshold = passed
                }, orientation = Orientation.Horizontal,
                    onDragStarted = { dragging = true; drag = offset; passedThreshold = false },
                    onDragStopped = { velocity ->
                        if (abs(drag) >= threshold || abs(velocity) > threshold * 10) { if (drag < 0 || (drag == 0f && velocity < 0)) model.next() else model.previous() }
                        dragging = false; drag = 0f; passedThreshold = false
                    })) {
            Row(Modifier.padding(start = 7.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp)
                    .sharedElement(rememberSharedContentState("art:${song.key}"), visibility,
                        boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() }, zIndexInOverlay = 1f).clip(coverShape)) {
                    BufferingArtwork(song, model.buffering && !model.playerExpanded, coverShape, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, Modifier.sharedBounds(rememberSharedContentState("title:${song.id}"), visibility,
                        boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() }),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer.copy(alpha = .8f))
                }
                PlaybackButton(model.playing, model::togglePlay, Modifier.size(64.dp, 52.dp)
                    .sharedBounds(rememberSharedContentState("play-button"), visibility,
                        boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() }), height = 52.dp, capsule = true)
            }
        }
    }
}
