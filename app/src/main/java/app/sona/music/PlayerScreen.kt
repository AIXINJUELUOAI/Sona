@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package app.sona.music

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

@UnstableApi
@Composable
fun PlayerScreen(model: MusicViewModel, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
    coverShape: Shape, styleSwitchEnabled: Boolean) {
    val motion = MaterialTheme.motionScheme
    val scheme = MaterialTheme.colorScheme
    // Style changes need their own clock: the seekable expansion transition is idle
    // after opening and cannot drive child animations when only boldPlayer changes.
    val classicTitleState = remember {
        MutableTransitionState(visibility.transition.currentState == EnterExitState.Visible && !model.boldPlayer)
    }
    val boldTitleState = remember {
        MutableTransitionState(visibility.transition.currentState == EnterExitState.Visible && model.boldPlayer)
    }
    classicTitleState.targetState = visibility.transition.targetState == EnterExitState.Visible && !model.boldPlayer
    boldTitleState.targetState = visibility.transition.targetState == EnterExitState.Visible && model.boldPlayer
    val classicTitle = rememberTransition(classicTitleState, label = "classic-player-title")
    val boldTitle = rememberTransition(boldTitleState, label = "bold-player-title")
    val backPreview = rememberBackPreview(
        enabled = model.playerExpanded && model.lyricsVisible && !model.queueVisible && !model.outputVisible,
        onBack = { model.lyricsVisible = false })
    val lyricsTransition = androidx.compose.animation.core.updateTransition(model.lyricsVisible, label = "player-lyrics")
    val lyricsOnScreen = lyricsTransition.currentState || lyricsTransition.targetState
    val dim by lyricsTransition.animateFloat({ androidx.compose.animation.core.tween(PageMotionDuration,
        easing = androidx.compose.animation.core.FastOutSlowInEasing) }, label = "lyrics-backdrop-dim") { if (it) 1f else 0f }
    @Composable fun Header(showLyrics: Boolean) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = scheme.onPrimary.copy(alpha = .7f), contentColor = scheme.primary),
                onClick = { if (showLyrics) model.lyricsVisible = false else model.playerExpanded = false }) {
                Icon(Icons.Rounded.KeyboardArrowDown, if (showLyrics) "返回播放页" else "收起播放器")
            }
            if (!showLyrics) {
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                with(shared) {
                    classicTitle.AnimatedVisibility({ it }, enter = EnterTransition.None, exit = ExitTransition.None) {
                    MarqueeTitle(model.current?.title.orEmpty(),
                        Modifier.fillMaxWidth()
                            .sharedBounds(rememberSharedContentState("title:${model.current?.id}"), this,
                                enter = fadeIn(tween(300)), exit = fadeOut(tween(300)),
                                boundsTransform = { _, _ -> tween(300) },
                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.TopStart))
                            .testTag("player-title"))
                    }
                }
                AnimatedVisibility(!model.boldPlayer, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                Text(model.current?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.primary.copy(alpha = .8f))
                }
                }
            } else Spacer(Modifier.weight(1f))
            PlayerTopActions(onOutput = { model.queueVisible = false; model.outputVisible = true },
                onQueue = { model.outputVisible = false; model.queueVisible = true })
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().background(scheme.primaryContainer)) {
        // Retain the real player beneath lyrics; do not rebuild it or expose its controls mid-gesture.
        Column(Modifier.fillMaxSize().background(scheme.primaryContainer)
            .then(if (lyricsOnScreen) Modifier.backPreviewBackdrop("lyrics-backdrop-player") { dim }
                else Modifier.testTag("now-playing-page"))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            Header(false)
            Box(Modifier.weight(1f)) {
                NowPlaying(model, shared, visibility, coverShape, boldTitle,
                    // The seekable/shared animation clocks are not interaction state.
                    // Gate on the actual page and overlays so a finished-looking player stays tappable.
                    canSwitchStyle = styleSwitchEnabled && !lyricsOnScreen && !model.queueVisible && !model.outputVisible)
            }
        }
        lyricsTransition.AnimatedVisibility({ it }, modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None, exit = ExitTransition.None) {
            val phase = pageMotionProgress()
            Column(Modifier.fillMaxSize().backPreview(backPreview, travel = phase).background(scheme.primaryContainer)
                .testTag("lyrics-page").windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
                Header(true)
                Box(Modifier.weight(1f)) { LyricsContent(model) }
                Row(Modifier.padding(top = 8.dp, bottom = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledIconToggleButton(checked = model.playing, onCheckedChange = { model.togglePlay() },
                        shapes = IconButtonDefaults.toggleableShapes(shape = CircleShape, pressedShape = CircleShape, checkedShape = CircleShape),
                        modifier = Modifier.size(56.dp)) {
                        Icon(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (model.playing) "暂停" else "播放")
                    }
                    SeekControl(model, Modifier.weight(1f))
                }
            }
        }
    }
}

private const val TitleMarqueeDelayMillis = 1800
private val TitleMarqueeVelocity = 28.dp
// Wide enough that the last glyph dissolves instead of being cut by a hard line.
private val TitleFadeWidth = 24.dp
// 24dp at 28dp/s: the leading fade is complete once the first glyph has cleared it.
private const val TitleFadeRampMillis = 860

/**
 * One-line title ticker with soft edges: the trailing edge always fades out, the leading edge only
 * once the ticker has started, so a short resting title keeps its first glyph solid.
 *
 * Both fades are painted by the title itself rather than by sibling overlays. A sibling is not part
 * of the shared element, so it is skipped while the title cross-fades in the transition overlay and
 * only covers the text once the transition lands: that read as a mask snapping over the last glyph.
 * The width is measured during composition for the same reason, instead of via onTextLayout, which
 * leaves a new title without its fade for the first frames.
 */
@Composable
private fun MarqueeTitle(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleLarge.copy(color = scheme.primary, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val natural = remember(text, style) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width }
        val overflowing = natural > available
        var ticking by remember(text) { mutableStateOf(false) }
        LaunchedEffect(text, overflowing) {
            ticking = false
            if (overflowing) { delay(TitleMarqueeDelayMillis.toLong()); ticking = true }
        }
        // Seeded from the measured width so the trailing fade exists on the title's first frame;
        // only a later width change (rotation, split screen) animates.
        val trailing = remember(text) { Animatable(if (overflowing) 1f else 0f) }
        LaunchedEffect(overflowing) { trailing.animateTo(if (overflowing) 1f else 0f, tween(200)) }
        // Recreated per title, so a new title starts with a clean leading edge instead of fading
        // the previous state out over the first glyph.
        val leading = remember(text) { Animatable(0f) }
        LaunchedEffect(ticking) {
            leading.animateTo(if (ticking) 1f else 0f, tween(TitleFadeRampMillis, easing = LinearEasing))
        }
        Text(text, modifier = modifier.fillMaxWidth()
            // Outside basicMarquee so the fade stays pinned to the edge while the text travels.
            .drawWithContent {
                drawContent()
                val width = TitleFadeWidth.toPx()
                val background = scheme.primaryContainer
                if (trailing.value > 0.01f) drawRect(
                    Brush.horizontalGradient(listOf(background.copy(alpha = 0f), background),
                        startX = size.width - width, endX = size.width), alpha = trailing.value)
                if (leading.value > 0.01f) drawRect(
                    Brush.horizontalGradient(listOf(background, background.copy(alpha = 0f)),
                        startX = 0f, endX = width), alpha = leading.value)
            }
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = TitleMarqueeDelayMillis,
                velocity = TitleMarqueeVelocity),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = style)
    }
}

@Composable
internal fun PlayerTopActions(onOutput: () -> Unit, onQueue: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = scheme.onPrimary.copy(alpha = .7f), contentColor = scheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        FilledTonalIconButton(onClick = onOutput, shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 8.dp, bottomEnd = 8.dp),
            colors = colors, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.SpeakerGroup, "媒体输出切换") }
        FilledTonalIconButton(onClick = onQueue, shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 24.dp, bottomEnd = 24.dp),
            colors = colors, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.QueueMusic, "播放队列") }
    }
}

@UnstableApi
@Composable
private fun NowPlaying(model: MusicViewModel, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope, coverShape: Shape,
    boldTitle: Transition<Boolean>, canSwitchStyle: Boolean) {
    val song = model.current ?: return
    val displayedBold = model.boldPlayer
    val motion = MaterialTheme.motionScheme
    val scheme = MaterialTheme.colorScheme
    val playInteraction = remember { MutableInteractionSource() }
    val playPressed by playInteraction.collectIsPressedAsState()
    val playScale by animateFloatAsState(if (playPressed) .96f else 1f,
        motion.fastSpatialSpec(), label = "play-press-scale")
    val timeLineHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
    with(shared) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val controlHeight = ((maxWidth * .9f - 16.dp) / 3 / 1.15f)
                .coerceAtMost((maxHeight * 0.15f).coerceIn(64.dp, 96.dp))
            Column(Modifier.fillMaxSize().padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SongHero(song, model.position, model.duration, Modifier.weight(1f), buffering = model.buffering, coverShape = coverShape,
                    classic = !displayedBold,
                    // Outgoing/shared artwork can still be drawn over the mini player.
                    // It must not accept the tap that opens the player again.
                    onCoverClick = if (canSwitchStyle && model.playerStyleMode == PlayerStyleMode.TapToSwitch)
                        ({ model.togglePlayerStyle(displayedBold) }) else null,
                    artworkModifier = Modifier.sharedElement(rememberSharedContentState("art:${song.key}"), visibility,
                        boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() }, zIndexInOverlay = 1f),
                    titleTransition = boldTitle,
                    titleModifier = { titleVisibility ->
                        // The title keeps its shared bounds in both styles so bold <-> default keeps the
                        // continuous move; the artwork's higher overlay z-index keeps the cover on top
                        // while the player expands from the mini player.
                        Modifier.sharedBounds(rememberSharedContentState("title:${song.id}"), titleVisibility,
                            enter = fadeIn(tween(300)), exit = fadeOut(tween(300)),
                            boundsTransform = { _, _ -> tween(300) },
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.TopStart))
                    })
                // Both styles reserve the time row so switching never moves the controls.
                Box(Modifier.fillMaxWidth().height((36.dp + timeLineHeight).coerceAtLeast(48.dp))) {
                    SeekControl(model, Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    androidx.compose.animation.AnimatedVisibility(!model.boldPlayer, modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(model.position), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                        Text(formatTime(model.duration), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    }
                    }
                }
                Row(Modifier.fillMaxWidth(.9f).align(Alignment.CenterHorizontally).height(controlHeight),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = model::previous, shape = CircleShape, modifier = Modifier.weight(1f).height(controlHeight),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary)) { Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(32.dp)) }
                    FilledIconButton(onClick = model::togglePlay, shape = CircleShape, interactionSource = playInteraction,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = scheme.tertiaryFixedDim,
                            contentColor = scheme.onTertiaryFixed),
                        modifier = Modifier.weight(1f).height(controlHeight).graphicsLayer { scaleX = playScale; scaleY = playScale }
                            .sharedBounds(rememberSharedContentState("play-button"), visibility, boundsTransform = { _, _ -> motion.defaultSpatialSpec<Rect>() })) {
                        Icon(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (model.playing) "暂停" else "播放", Modifier.size(40.dp))
                    }
                    FilledTonalIconButton(onClick = model::next, shape = CircleShape, modifier = Modifier.weight(1f).height(controlHeight),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary)) { Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(32.dp)) }
                }
                PlayerModeBar(model.shuffled, model.repeat, model.isLiked(song), { model.setPlaybackMode(it, PlaybackOrigin.OutsideList) },
                    { model.lyricsVisible = true }, model::toggleLike,
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).widthIn(max = 296.dp).fillMaxWidth(0.86f))
            }
        }
    }
}

@Composable
internal fun SongHero(song: Song, position: Long, duration: Long,
    modifier: Modifier = Modifier, artworkModifier: Modifier = Modifier,
    titleModifier: @Composable (AnimatedVisibilityScope) -> Modifier = { Modifier }, buffering: Boolean = false,
    coverShape: Shape = MaterialShapes.Cookie9Sided.toShape(), classic: Boolean = false, onCoverClick: (() -> Unit)? = null,
    showMetadata: Boolean = true, coverHorizontalOffset: Dp = 12.dp, showPlaybackTime: Boolean = true,
    boldCoverLift: Dp = 16.dp, boldCoverScale: Float = 1f,
    compactBoldTitle: Boolean = false,
    titleTransition: Transition<Boolean> = updateTransition(!classic, label = "hero-title")) {
    val title = remember(song.title) { playerTitle(song.title) }
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    LookaheadScope {
    val lookahead = this
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 12.dp)) {
        val boldCoverSize = ((maxWidth * 0.70f).coerceAtMost(maxHeight * 0.58f) * boldCoverScale)
            .coerceAtMost(maxWidth * 0.70f).coerceAtMost(maxHeight)
        val coverSize = if (classic) maxWidth.coerceAtMost((maxHeight - 48.dp).coerceAtLeast(1.dp)) else boldCoverSize
        val coverTop = if (classic) ((maxHeight - coverSize) * .35f).coerceAtLeast(0.dp) else 0.dp
        val metaWidth = (maxWidth - boldCoverSize - 8.dp).coerceAtLeast(96.dp)
        // Let the cover overlap the title instead of shrinking the title to clear the cover.
        val titleFraction = when {
            title.isShortCjk -> 0.70f
            // Seven-character titles keep the 4+3 break but size like five columns, matching 8-10-character titles.
            title.boldColumns == 4 -> 0.80f
            else -> 1f
        }
        // Keep the two-line canvas independent of the cover. Only trim the landscape
        // font ceiling slightly; subtracting the cover height forces every line to shrink.
        val titleMaxFont = (maxWidth.value * if (compactBoldTitle) 0.26f else 0.28f)
            .coerceAtMost(if (compactBoldTitle) 102f else 110f).sp
        val titleHeight = with(LocalDensity.current) { (titleMaxFont.value * 2.5f).sp.toDp() }
            .coerceAtMost(maxHeight * 0.78f)
        // Explicit breaks and a two-line limit let auto-size fit five characters across
        // for 8–10-character names, without reducing the size of shorter titles.
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val titleMeasureStyle = MaterialTheme.typography.displayLarge
        val singleLineFits = remember(title, titleMaxFont, titleFraction, maxWidth, density, measurer, titleMeasureStyle) {
            title.boldColumns == null && !title.isShortCjk && !title.isLongCjk &&
            '\n' !in title.boldText && measurer.measure(title.boldText,
                style = titleMeasureStyle.copy(fontSize = (titleMaxFont.value * .70f).coerceAtLeast(24f).sp,
                    fontWeight = FontWeight.Black), softWrap = false, maxLines = 1).size.width <=
                with(density) { (maxWidth * titleFraction).toPx() }
        }
        val titleMaxLines = if (singleLineFits) 1 else if (title.boldColumns != null) 2 else if (title.isLongCjk) if (title.main.codePointCount(0, title.main.length) > 10) 3 else 2 else 3
        titleTransition.AnimatedVisibility({ it },
            modifier = Modifier.fillMaxWidth(titleFraction).height(titleHeight),
            enter = EnterTransition.None, exit = ExitTransition.None) {
        BasicText(title.boldText, modifier = titleModifier(this).fillMaxSize()
            .then(if (compactBoldTitle) Modifier.padding(bottom = 4.dp) else Modifier).testTag("player-title"),
            style = MaterialTheme.typography.displayLarge.copy(color = scheme.primary,
                platformStyle = if (compactBoldTitle) PlatformTextStyle(includeFontPadding = true)
                    else MaterialTheme.typography.displayLarge.platformStyle,
                fontWeight = FontWeight.Black, lineHeight = TextUnit.Unspecified),
            maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = titleMaxFont, stepSize = 2.sp))
        }
        AnimatedVisibility(!classic && showMetadata, modifier = Modifier.align(Alignment.BottomStart).width(metaWidth),
            enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
        Column(Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (title.version.isNotBlank()) Text(title.version, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                Text(song.artist, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (song.album.isNotBlank()) {
                    Text(song.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                if (showPlaybackTime) BasicText("${formatTime(position)} / ${formatTime(duration)}", Modifier.fillMaxWidth(), maxLines = 1,
                    style = MaterialTheme.typography.headlineSmall.copy(color = scheme.onSurface, fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Start),
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 25.sp))
        }
        }
        // The bold cover sits slightly higher than the meta block, so title, artwork and the
        // artist/time column read as one diagonal group instead of two aligned edges.
        val coverLift = if (classic) 0.dp else boldCoverLift
        Box(Modifier.align(if (classic) Alignment.TopCenter else Alignment.BottomEnd)
            .animateBounds(lookahead, boundsTransform = { _, _ -> motion.defaultSpatialSpec() })
            .offset(x = if (classic) 0.dp else coverHorizontalOffset, y = coverTop - coverLift).size(coverSize)
            .clip(coverShape).then(if (onCoverClick != null) Modifier.clickable(onClickLabel = "切换播放页样式", onClick = onCoverClick) else Modifier)
            .testTag("player-artwork")) {
            Artwork(song.cover, artworkModifier.fillMaxSize().clip(coverShape))
            CoverBufferingIndicator(buffering, song.id)
        }
    }
    }
}

@Composable
internal fun CoverBufferingIndicator(buffering: Boolean, trackKey: Any?) {
    val visible by produceState(false, buffering, trackKey) {
        value = false
        if (buffering) { delay(350); value = true }
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        CircularProgressIndicator(Modifier.fillMaxSize().padding(1.dp).testTag("cover-buffering"),
            strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
    }
}

@Composable
internal fun PlayerModeBar(shuffled: Boolean, repeat: Int, liked: Boolean, onMode: (PlaybackMode) -> Unit,
    onLyrics: () -> Unit, onLike: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val mode = when {
        shuffled -> PlaybackMode.Shuffle
        repeat == Player.REPEAT_MODE_ONE -> PlaybackMode.RepeatOne
        repeat == Player.REPEAT_MODE_ALL -> PlaybackMode.RepeatAll
        else -> PlaybackMode.Sequential
    }
    val icon = when (mode) {
        PlaybackMode.Shuffle -> Icons.Rounded.Shuffle
        PlaybackMode.RepeatOne -> Icons.Rounded.RepeatOne
        PlaybackMode.RepeatAll -> Icons.Rounded.Repeat
        PlaybackMode.Sequential -> Icons.Rounded.ArrowForward
    }
    // Fade the whole cluster once instead of per element: alpha on the group keeps the fills and
    // the track in the same relationship, while per-element alpha would muddy them against the track.
    Row(modifier.alpha(.85f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Both state segments share the playback-mode fill, so the bottom row stays one colour;
        // the neutral track keeps them from competing with the transport row above.
        Box(Modifier.weight(1f).background(scheme.surfaceContainerLowest.copy(alpha = .7f), CircleShape).padding(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ModeSegment(mode != PlaybackMode.Sequential, icon, mode.label, {
                onMode(when (mode) {
                    PlaybackMode.Sequential -> PlaybackMode.RepeatOne
                    PlaybackMode.RepeatOne -> PlaybackMode.RepeatAll
                    PlaybackMode.RepeatAll -> PlaybackMode.Shuffle
                    PlaybackMode.Shuffle -> PlaybackMode.Sequential
                })
            }, Modifier.weight(1f), scheme.primaryFixed, scheme.onPrimaryFixed, startRadius = 24.dp, description = "播放模式")
            ModeSegment(liked, if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "喜欢", onLike,
                Modifier.weight(1f), scheme.primaryFixed, scheme.onPrimaryFixed, endRadius = 24.dp)
        }
        }
        // The capsule's recipe, wrapped into a circle: track-coloured disc, 5dp inset, glass chip.
        // A glass fill alone would show the page colour through it and read as purple.
        Surface(onClick = onLyrics, modifier = Modifier.size(58.dp), shape = CircleShape,
            color = scheme.surfaceContainerLowest.copy(alpha = .7f), contentColor = scheme.primary) {
            Box(Modifier.fillMaxSize().padding(5.dp)
                .background(scheme.onSurface.copy(alpha = .07f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lyrics, "打开歌词", Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ModeSegment(active: Boolean, icon: ImageVector, status: String, onClick: () -> Unit,
    modifier: Modifier, activeColor: androidx.compose.ui.graphics.Color, activeContent: androidx.compose.ui.graphics.Color,
    startRadius: Dp? = null, endRadius: Dp? = null, description: String = status) {
    val radius by animateDpAsState(if (active) 16.dp else 8.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "mode-shape")
    val background by animateColorAsState(if (active) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = .07f),
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "mode-color")
    Surface(onClick = onClick, shape = RoundedCornerShape(topStart = startRadius ?: radius, bottomStart = startRadius ?: radius,
        topEnd = endRadius ?: radius, bottomEnd = endRadius ?: radius), color = background,
        contentColor = if (active) activeContent else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(48.dp).semantics { stateDescription = status }) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, description, Modifier.size(24.dp))
        }
    }
}

@UnstableApi
@Composable
fun SeekControl(model: MusicViewModel, modifier: Modifier = Modifier) {
    WaveSeekBar(model.position, model.duration, model.playing, !model.buffering, model::seek, modifier, model.current?.id)
}

@UnstableApi
@Composable
internal fun LyricsContent(model: MusicViewModel, compact: Boolean = false,
    onManualScrollChanged: ((Boolean) -> Unit)? = null) {
    val songKey = model.current?.key
    val local = model.current?.isLocal == true
    val lines = model.lyrics
    val active = activeLyric(lines, model.position)
    val state = rememberLazyListState()
    val plainState = rememberScrollState()
    val plain = model.plainLyrics.isNotBlank()
    val interactionSource = if (plain) plainState.interactionSource else state.interactionSource
    val dragging by interactionSource.collectIsDraggedAsState()
    val scrolling = if (plain) plainState.isScrollInProgress else state.isScrollInProgress
    var manualScroll by remember(songKey, plain) { mutableStateOf(false) }
    val scrollCallback by rememberUpdatedState(onManualScrollChanged)
    LaunchedEffect(dragging, scrolling, songKey, plain) {
        if (dragging) manualScroll = true
        scrollCallback?.invoke(manualScroll && (dragging || scrolling))
        if (!dragging && !scrolling) manualScroll = false
    }
    DisposableEffect(songKey, plain) {
        onDispose { scrollCallback?.invoke(false) }
    }
    var follow by remember(songKey) { mutableStateOf(true) }
    LaunchedEffect(state, compact) {
        if (compact) state.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(follow, state.isScrollInProgress) {
        if (compact && !follow && !state.isScrollInProgress) { delay(3000); follow = true }
    }
    LaunchedEffect(songKey) { state.scrollToItem(0); plainState.scrollTo(0) }
    LaunchedEffect(active, follow, songKey) {
        if (follow && active >= 0 && !state.isScrollInProgress) state.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        if (model.lyricsLoading) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else if (model.plainLyrics.isNotBlank()) key(songKey, model.plainLyrics) {
            LyricsViewport(compact) {
                Column(Modifier.fillMaxSize()
                    .verticalScroll(plainState).padding(top = if (compact) 16.dp else 48.dp, bottom = if (compact) 24.dp else 64.dp)) {
                    Text(model.plainLyrics, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        lineHeight = if (compact) 30.sp else 36.sp,
                        modifier = Modifier.testTag("plain-lyrics"))
                }
            }
        }
        else if (lines.isEmpty() && compact) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Lyrics, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (model.lyricsError != null) "歌词加载失败" else "暂无歌词", color = MaterialTheme.colorScheme.primary)
                if (model.lyricsError != null) TextButton(onClick = { model.current?.let { model.loadLyrics(it.id) } }) { Text("重试") }
            }
        }
        else if (lines.isEmpty()) EmptyState(if (local) "未找到本地歌词" else "让音乐说话",
            model.lyricsError ?: if (local) "这首歌曲没有检测到内嵌歌词或可用的本地 LRC。" else "这首歌曲暂时没有歌词", Icons.Rounded.Lyrics,
            if (model.lyricsError != null) "重试" else null) {
                model.current?.let { model.loadLyrics(it.id) }
            }
        else {
            LyricsViewport(compact) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = if (compact) 16.dp else 48.dp, bottom = if (compact) 24.dp else 64.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp)) {
                    itemsIndexed(lines) { index, line ->
                        val color by animateColorAsState(if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), MaterialTheme.motionScheme.fastEffectsSpec(), label = "lyric-highlight")
                        Column(Modifier.fillMaxWidth().clickable { model.seek(line.timeMs); follow = true }.padding(vertical = 4.dp)) {
                            Text(line.text, color = color,
                                fontSize = if (compact) { if (index == active) 24.sp else 19.sp } else { if (index == active) 28.sp else 23.sp },
                                lineHeight = if (compact) 30.sp else 34.sp, fontWeight = FontWeight.Bold)
                            if (line.translation.isNotBlank()) Text(line.translation, Modifier.padding(top = 6.dp), color = color, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Fades lyrics into the page colour at both edges instead of clipping them on a hard line. */
@Composable
private fun ColumnScope.LyricsViewport(compact: Boolean = false, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.primaryContainer
    Box(Modifier.fillMaxWidth().weight(1f)) {
        content()
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (compact) 16.dp else 48.dp)
            .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (compact) 24.dp else 64.dp)
            .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))))
    }
}

@UnstableApi
@Composable
@Suppress("DEPRECATION")
fun QueueSheet(model: MusicViewModel) {
    val queue = model.queue
    val queueMediaIndices = model.queueMediaIndices
    val state = rememberLazyListState(initialFirstVisibleItemIndex = model.queueIndex.coerceAtLeast(0))
    val containerColor = BottomSheetDefaults.ContainerColor
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 16.dp
    ModalBottomSheet(onDismissRequest = { model.queueVisible = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = containerColor,
        // Keep the scrolling viewport edge-to-edge; reserve the bottom inset inside the list.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = containerColor.luminance() > 0.5f)) {
        // The sheet owns a dialog window; the Activity's system bar settings do not reach it.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                window.isNavigationBarContrastEnforced = false
            }
        }
        val mode = when {
            model.shuffled -> PlaybackMode.Shuffle
            model.repeat == Player.REPEAT_MODE_ALL -> PlaybackMode.RepeatAll
            model.repeat == Player.REPEAT_MODE_ONE -> PlaybackMode.RepeatOne
            else -> PlaybackMode.Sequential
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("接下来播放", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text("${queue.size} 首 · ${mode.label}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LocatePlayingButton(state, queue, model.current?.key, bottomInset = bottomPadding)
            FilledTonalIconButton(onClick = model::cycleQueueMode, shape = CircleShape,
                modifier = Modifier.size(48.dp).semantics { stateDescription = mode.label }) {
                Icon(when (mode) {
                    PlaybackMode.Shuffle -> Icons.Rounded.Shuffle
                    PlaybackMode.RepeatAll -> Icons.Rounded.Repeat
                    PlaybackMode.RepeatOne -> Icons.Rounded.RepeatOne
                    PlaybackMode.Sequential -> Icons.Rounded.ArrowDownward
                }, "切换播放模式，当前${mode.label}")
            }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).scrollViewport(16.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(queue, key = { index, _ -> queueMediaIndices[index] }) { index, song ->
                val current = index == model.queueIndex
                val scheme = MaterialTheme.colorScheme
                SegmentedListItem(onClick = { model.selectQueue(index) },
                    modifier = Modifier.animateItem(),
                    shapes = if (current) ListItemDefaults.segmentedShapes(0, 1).copy(shape = outerCornerShape)
                        else ListItemDefaults.segmentedShapes(index, model.queue.size).copy(shape = segmentedOuterShape(index, model.queue.size)),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = if (current) scheme.primaryContainer else scheme.surfaceContainerHigh,
                        contentColor = if (current) scheme.onPrimaryContainer else scheme.onSurface,
                        supportingContentColor = if (current) scheme.onPrimaryContainer.copy(alpha = .8f) else scheme.onSurfaceVariant),
                    leadingContent = { Artwork(song.cover, Modifier.size(44.dp).clip(if (current) CircleShape else MaterialTheme.shapes.medium)) },
                    supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { SongRowStatus(song) }) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
