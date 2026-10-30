@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package app.sona.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val bold = model.boldPlayer
    val onSwitchStyle: (() -> Unit)? = if (model.playerStyleMode == PlayerStyleMode.TapToSwitch)
        ({ model.togglePlayerStyle(bold) }) else null
    val lyrics = remember { Animatable(if (model.deskLyricsOpen) 1f else 0f) }
    LaunchedEffect(lyrics) {
        snapshotFlow { lyrics.value }.collect { model.deskLyricsOpen = it >= .5f }
    }
    val scope = rememberCoroutineScope()
    val safe = WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize().background(scheme.primaryContainer)
        .windowInsetsPadding(safe)
        .padding(horizontal = 20.dp, vertical = 12.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val boxWidth = maxWidth
            val boxHeight = maxHeight
            if (boxWidth > boxHeight) {
                val columnWidth = (boxWidth - 32.dp) / 2
                Row(Modifier.fillMaxSize()) {
                    if (bold) {
                        BoldArtwork(model, song, Modifier.width(columnWidth).fillMaxHeight(), onSwitchStyle)
                    } else {
                        CoverTile(song, minOf(columnWidth, boxHeight),
                            Modifier.width(columnWidth).fillMaxHeight(), buffering = model.buffering, onTap = onSwitchStyle)
                    }
                    Spacer(Modifier.width(32.dp))
                    Box(Modifier.width(columnWidth).fillMaxHeight()
                        .then(if (bold) Modifier else Modifier.lyricsSwipe(lyrics, scope))) {
                        InfoColumn(model, song, lyrics.value, bold)
                    }
                }
            } else {
                // Portrait only happens in a constrained window; keep the same pieces stacked.
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if (bold) BoldArtwork(model, song, Modifier.fillMaxSize(), onSwitchStyle)
                        else CoverTile(song, minOf(maxWidth, maxHeight), Modifier.fillMaxSize(),
                            buffering = model.buffering, onTap = onSwitchStyle)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()
                        .then(if (bold) Modifier else Modifier.lyricsSwipe(lyrics, scope))) {
                        InfoColumn(model, song, lyrics.value, bold)
                    }
                }
            }
        }
    }
}

/** Default composition: the artwork alone, tap to switch to the bold layout. */
@Composable
private fun CoverTile(song: Song, size: androidx.compose.ui.unit.Dp, modifier: Modifier,
    buffering: Boolean, onTap: (() -> Unit)?) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(RoundedCornerShape(28.dp))
            .then(if (onTap != null) Modifier.clickable(onClickLabel = "切换播放页样式", onClick = onTap) else Modifier)) {
            BufferingArtwork(song, buffering, RoundedCornerShape(28.dp), Modifier.fillMaxSize())
        }
    }
}

/** Lyrics and the permanent playback buttons share the right pane, without a seek bar. */
@Composable
private fun InfoColumn(model: MusicViewModel, song: Song, lyrics: Float, bold: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            if (bold) LyricsContent(model, compact = true)
            else {
                Column(Modifier.fillMaxWidth().align(Alignment.BottomStart)
                    .graphicsLayer { alpha = 1f - lyrics }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(song.title, Modifier.fillMaxWidth().basicMarquee(initialDelayMillis = 1800),
                        maxLines = 1, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = scheme.primary)
                    Text(song.artist, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, color = scheme.primary.copy(alpha = .78f))
                    if (song.album.isNotBlank()) Text(song.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = scheme.primary.copy(alpha = .55f))
                }
                DeskLyricsOverlay(model, lyrics, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(if (bold) 8.dp else 20.dp))
        DeskControls(model, Modifier.fillMaxWidth().height(48.dp))
    }
}

/** Fixed pane-wide anchors: title above, metadata and artwork below. */
@Composable
private fun BoldArtwork(model: MusicViewModel, song: Song, modifier: Modifier, onTap: (() -> Unit)?) {
    val title = remember(song.title) { playerTitle(song.title) }
    val scheme = MaterialTheme.colorScheme
    val shape = MorphShape(coverPolygons[model.coverShapeIndex.coerceIn(0, coverPolygons.size - 1)])
    val artistHeight = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.toDp() * 2 }
    BoxWithConstraints(modifier) {
        // Geometry depends only on the pane, never the title or metadata length.
        val coverSize = minOf(maxWidth * .56f, maxHeight * .54f)
        val titleFont = (maxWidth.value * .28f).coerceIn(24f, 110f).sp
        Column(Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.TopStart) {
                // Native balanced wrapping handles CJK and mixed-language titles with one rule.
                BasicText(title.main, Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displayLarge.copy(color = scheme.primary,
                        fontWeight = FontWeight.Black, lineHeight = TextUnit.Unspecified,
                        lineBreak = LineBreak.Heading),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = titleFont, stepSize = 2.sp))
            }
            Row(Modifier.fillMaxWidth().height(coverSize), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                    // Empty rows stay above the artist, keeping the artist/album gap compact.
                    Text(title.version, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth().height(artistHeight), contentAlignment = Alignment.BottomStart) {
                        Text(song.artist, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(song.album, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Box(Modifier.size(coverSize).clip(shape)
                    .then(if (onTap != null) Modifier.clickable(onClickLabel = "切换播放页样式", onClick = onTap) else Modifier)) {
                    BufferingArtwork(song, model.buffering, shape, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DeskControls(model: MusicViewModel, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = model::previous, shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = scheme.primary, contentColor = scheme.onPrimary),
            modifier = Modifier.weight(1f).fillMaxHeight()) {
            Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(24.dp))
        }
        FilledIconButton(onClick = model::togglePlay, shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = scheme.tertiaryFixedDim, contentColor = scheme.onTertiaryFixed),
            modifier = Modifier.weight(1f).fillMaxHeight()) {
            Icon(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (model.playing) "暂停" else "播放", Modifier.size(28.dp))
        }
        FilledTonalIconButton(onClick = model::next, shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = scheme.primary, contentColor = scheme.onPrimary),
            modifier = Modifier.weight(1f).fillMaxHeight()) {
            Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(24.dp))
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
