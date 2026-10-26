@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package app.sona.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Desk mode for landscape. Two fixed compositions:
 * Both styles use equal columns: cover or bold title/artwork on the left,
 * information or lyrics and the same fixed transport layout on the right.
 * Tapping the cover swaps them. Chrome is gone: back collapses the player, and a right-to-left
 * swipe over the right column brings the lyrics in for the default style.
 */
@UnstableApi
@Composable
internal fun DeskPlayerScreen(model: MusicViewModel) {
    val song = model.current ?: return
    val scheme = MaterialTheme.colorScheme
    BackHandler { model.playerExpanded = false }
    val bold = model.deskBold
    val lyrics = remember { Animatable(if (model.deskLyricsOpen) 1f else 0f) }
    LaunchedEffect(lyrics) {
        snapshotFlow { lyrics.value }.collect { model.deskLyricsOpen = it >= .5f }
    }
    val scope = rememberCoroutineScope()
    val safe = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInset = with(LocalDensity.current) {
        maxOf(safe.getLeft(this, layoutDirection), safe.getRight(this, layoutDirection)).toDp()
    }
    Box(Modifier.fillMaxSize().background(scheme.primaryContainer)
        .windowInsetsPadding(safe.only(WindowInsetsSides.Vertical))
        .padding(horizontal = horizontalInset + 24.dp, vertical = 24.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val boxWidth = maxWidth
            val boxHeight = maxHeight
            if (boxWidth > boxHeight) {
                val content = minOf(boxWidth, 1100.dp)
                val columnWidth = (content - 40.dp) / 2
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(Modifier.width(content).fillMaxHeight()) {
                        if (bold) {
                            BoldArtwork(model, song, Modifier.width(columnWidth).fillMaxHeight()) { model.deskBold = false }
                        } else {
                            CoverTile(song, minOf(columnWidth, boxHeight),
                                Modifier.width(columnWidth).fillMaxHeight(), buffering = model.buffering) { model.deskBold = true }
                        }
                        Spacer(Modifier.width(40.dp))
                        Box(Modifier.width(columnWidth).fillMaxHeight()
                            .then(if (bold) Modifier else Modifier.lyricsSwipe(lyrics, scope))) {
                            InfoColumn(model, song, lyrics.value, bold)
                            if (!bold) DeskLyricsOverlay(model, lyrics.value, Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                // Portrait only happens in a constrained window; keep the same pieces stacked.
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if (bold) BoldArtwork(model, song, Modifier.fillMaxSize()) { model.deskBold = false }
                        else CoverTile(song, minOf(maxWidth, maxHeight), Modifier.fillMaxSize(),
                            buffering = model.buffering) { model.deskBold = true }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()
                        .then(if (bold) Modifier else Modifier.lyricsSwipe(lyrics, scope))) {
                        InfoColumn(model, song, lyrics.value, bold)
                        if (!bold) DeskLyricsOverlay(model, lyrics.value, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Default composition: the artwork alone, tap to switch to the bold layout. */
@Composable
private fun CoverTile(song: Song, size: androidx.compose.ui.unit.Dp, modifier: Modifier,
    buffering: Boolean, onTap: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(RoundedCornerShape(28.dp)).clickable(onClickLabel = "切换大胆桌搭", onClick = onTap)) {
            Artwork(song.cover, Modifier.fillMaxSize(), song.title)
            CoverBufferingIndicator(buffering, song.key)
        }
    }
}

/** Metadata occupies the flexible space; seek and transport keep their bounds in both styles. */
@Composable
private fun InfoColumn(model: MusicViewModel, song: Song, lyrics: Float, bold: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val collapsed = bold && model.deskControlsCollapsed
    val controlsProgress by animateFloatAsState(if (collapsed) 0f else 1f,
        tween(360), label = "desk-controls")
    val compactSize = with(LocalDensity.current) { 48.dp.toPx() }
    Box(Modifier.fillMaxSize().clipToBounds()) {
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = if (bold) 1f else 1f - lyrics }) {
        if (bold) Box(Modifier.fillMaxWidth().weight(1f)) {
            if (!model.lyricsLoading && (model.lyrics.isNotEmpty() || model.plainLyrics.isNotBlank())) {
                LyricsContent(model, compact = true, onManualScrollChanged = {
                    if (it) model.deskControlsCollapsed = true
                })
            }
        } else {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(song.title, Modifier.fillMaxWidth().basicMarquee(initialDelayMillis = 1800),
                maxLines = 1, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = scheme.primary)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = scheme.primary.copy(alpha = .78f))
            if (song.album.isNotBlank()) Text(song.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = scheme.primary.copy(alpha = .55f))
        }
        }
        // Keep the full-size controls anchored at the bottom while their reserved height
        // collapses. Scale toward the same bottom-right bounds used by the compact button.
        Column(Modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val reservedHeight = (placeable.height * controlsProgress).roundToInt()
                layout(placeable.width, reservedHeight) {
                    if (controlsProgress > 0.001f) placeable.place(0, reservedHeight - placeable.height)
                }
            }.graphicsLayer {
                transformOrigin = TransformOrigin(1f, 1f)
                val minX = (compactSize / size.width.coerceAtLeast(1f)).coerceAtMost(1f)
                val minY = (compactSize / size.height.coerceAtLeast(1f)).coerceAtMost(1f)
                scaleX = minX + (1f - minX) * controlsProgress
                scaleY = minY + (1f - minY) * controlsProgress
                alpha = (controlsProgress / .25f).coerceIn(0f, 1f)
            }) {
            DeskSeek(model, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            DeskControls(model, Modifier.fillMaxWidth())
        }
    }
    if (collapsed || controlsProgress < 1f) FilledIconButton(onClick = { model.deskControlsCollapsed = false },
        enabled = collapsed && controlsProgress < .25f,
        modifier = Modifier.align(Alignment.BottomEnd).size(48.dp).graphicsLayer {
            alpha = (1f - controlsProgress / .25f).coerceIn(0f, 1f)
        }, shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = scheme.primary, contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.primary, disabledContentColor = scheme.onPrimary)) {
        Icon(Icons.Rounded.Tune, "展开播放控制", Modifier.size(24.dp))
    }
    }
}

/** Reuse the portrait title, artwork and metadata; the right column owns playback time. */
@Composable
private fun BoldArtwork(model: MusicViewModel, song: Song, modifier: Modifier, onTap: () -> Unit) {
    val shape = MorphShape(coverPolygons[model.coverShapeIndex.coerceIn(0, coverPolygons.size - 1)])
    SongHero(song, model.position, model.duration, modifier, buffering = model.buffering,
        coverShape = shape, onCoverClick = onTap, coverHorizontalOffset = 0.dp, showPlaybackTime = false,
        boldCoverLift = 0.dp, boldCoverScale = 1.2f, compactBoldTitle = true)
}

@Composable
private fun DeskSeek(model: MusicViewModel, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Bring labels into the slider's empty lower padding while preserving its 48dp touch target.
    Column(modifier, verticalArrangement = Arrangement.spacedBy((-8).dp)) {
        SeekControl(model, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(model.position), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            Text(formatTime(model.duration), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

@Composable
private fun DeskControls(model: MusicViewModel, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = model::previous, shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = scheme.primary, contentColor = scheme.onPrimary),
            modifier = Modifier.weight(1f).height(64.dp)) {
            Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(30.dp))
        }
        FilledIconButton(onClick = model::togglePlay, shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = scheme.tertiaryFixedDim, contentColor = scheme.onTertiaryFixed),
            modifier = Modifier.weight(1f).height(72.dp)) {
            Icon(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (model.playing) "暂停" else "播放", Modifier.size(36.dp))
        }
        FilledTonalIconButton(onClick = model::next, shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = scheme.primary, contentColor = scheme.onPrimary),
            modifier = Modifier.weight(1f).height(64.dp)) {
            Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(30.dp))
        }
    }
}

/** Lyrics slide in from the right and own the column while visible. */
@Composable
private fun DeskLyricsOverlay(model: MusicViewModel, progress: Float, modifier: Modifier) {
    if (progress <= 0.001f) return
    Box(modifier.graphicsLayer {
        translationX = (1f - progress) * size.width
        alpha = progress
    }) {
        LyricsContent(model, compact = true)
    }
}

/** Right-to-left opens the lyrics, the opposite swipe closes them; a flick commits either way. */
private fun Modifier.lyricsSwipe(lyrics: Animatable<Float, *>, scope: CoroutineScope): Modifier =
    pointerInput(Unit) {
        val velocity = VelocityTracker()
        var motion: Job? = null
        var start = 0f
        var startedOpen = false
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                motion?.cancel()
                start = lyrics.value
                startedOpen = start >= 0.5f
                distance = 0f
                velocity.resetTracking()
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                velocity.addPosition(change.previousUptimeMillis, change.previousPosition)
                velocity.addPosition(change.uptimeMillis, change.position)
                distance += dragAmount
                val progress = (start - distance / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                motion?.cancel()
                motion = scope.launch { lyrics.snapTo(progress) }
            },
            onDragEnd = {
                val speed = velocity.calculateVelocity().x
                val open = when {
                    abs(speed) >= 500.dp.toPx() -> speed < 0f
                    abs(distance) >= size.width * 0.15f -> distance < 0f
                    else -> startedOpen
                }
                motion?.cancel()
                motion = scope.launch { lyrics.animateTo(if (open) 1f else 0f, tween(220)) }
            },
            onDragCancel = {
                motion?.cancel()
                motion = scope.launch { lyrics.animateTo(if (startedOpen) 1f else 0f, tween(220)) }
            })
    }
