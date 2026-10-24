@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@UnstableApi
@Composable
internal fun ListeningOverview(model: MusicViewModel, visible: Boolean, close: () -> Unit) {
    val history by model.settings.listeningHistory.updates.collectAsStateWithLifecycle()
    val today by produceState(LocalDate.now(), visible) {
        value = LocalDate.now()
        while (visible) { delay(30_000); value = LocalDate.now() }
    }
    val scheme = MaterialTheme.colorScheme
    val todaySongs = history.days[today].orEmpty().values
    val todayMs = todaySongs.sumOf { it.milliseconds }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekMs = history.days.filterKeys { it >= weekStart && it <= today }.values.sumOf { day ->
        day.values.sumOf { it.milliseconds }
    }
    val hasDuration = history.days.values.any { day -> day.values.any { it.milliseconds > 0 } }
    val frequent = remember(history, today, model.localSongs, model.recent) {
        val known = (model.localSongs + model.recent).associateBy { it.key }
        val heard = history.days.filterKeys { it >= today.minusDays(29) && it <= today }.values
            .flatMap { it.values }.groupBy { it.song.key }.values
            .sortedWith(compareByDescending<List<HeardSong>> { songs -> songs.sumOf { it.milliseconds } }
                .thenByDescending { songs -> songs.maxOf { it.lastHeard } })
            .filter { songs -> songs.sumOf { it.milliseconds } >= 1_000 }
            .map { songs -> songs.maxBy { it.lastHeard }.song.let { known[it.key] ?: it } }
        if (heard.isNotEmpty()) heard.take(3) else {
            val counts = model.settings.listeningStats()
            known.values.filter { (counts[it.key]?.plays ?: 0) > 0 }
                .sortedWith(compareByDescending<Song> { counts[it.key]?.plays ?: 0 }
                    .thenByDescending { counts[it.key]?.lastPlayed ?: 0 }).take(3)
        }
    }
    Crossfade(targetState = hasDuration, label = "listening-summary") { recorded ->
        if (recorded) Column(Modifier.clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(4.dp), color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ListeningHeadphoneBadge()
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("听歌概况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("今天", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Text(listeningDurationLabel(todayMs), style = MaterialTheme.typography.headlineLarge,
                        color = scheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ListeningMetric("本周时长", listeningDurationLabel(weekMs), scheme.secondaryContainer,
                    scheme.onSecondaryContainer, Modifier.weight(1f).fillMaxHeight())
                ListeningMetric("今日听过", "${todaySongs.count { it.milliseconds >= 1_000 }} 首", scheme.tertiaryContainer,
                    scheme.onTertiaryContainer, Modifier.weight(1f).fillMaxHeight())
            }
        } else Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ListeningHeadphoneBadge()
                Text("从第一首歌开始",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { close(); model.page = if (model.profile == null) Page.Local else Page.Home },
                    shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("去听歌")
                }
            }
        }
    }
    if (frequent.isNotEmpty()) {
        Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("最近常听", Modifier.padding(start = 8.dp, top = 8.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            frequent.forEachIndexed { index, song ->
                val active = song.key == model.current?.key
                SegmentedListItem(onClick = { close(); model.play(frequent, index) },
                    shapes = ListItemDefaults.segmentedShapes(index, frequent.size).copy(shape = segmentedOuterShape(index, frequent.size)),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = if (active) scheme.primaryContainer else scheme.surfaceContainer,
                        contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurface,
                        supportingContentColor = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant),
                    leadingContent = { Artwork(song.cover, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))) },
                    supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { SongRowStatus(song) }) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        }
        }
    }
}

@Composable
private fun ListeningMetric(title: String, value: String, background: Color, foreground: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(4.dp), color = background, contentColor = foreground) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ListeningHeadphoneBadge() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Headphones, null, Modifier.size(24.dp)) }
    }
}
