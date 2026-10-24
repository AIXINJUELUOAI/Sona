@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import java.time.LocalDate

/** Detail page behind the drawer's habit card: totals, a range chart and the most heard songs. */
@UnstableApi
@Composable
internal fun ListeningStatsScreen(model: MusicViewModel, back: () -> Unit) {
    val history by model.settings.listeningHistory.updates.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(0) }
    val today = LocalDate.now()
    val window = if (range == 0) 7 else 30
    val days = remember(history, today, window) { history.lastDays(today, window) }
    val total = days.sumOf { it.milliseconds }
    val active = days.count { it.milliseconds > 0 }
    val known = remember(model.localSongs, model.recent) { (model.localSongs + model.recent).associateBy { it.key } }
    val top = remember(history, today, window, known) { history.topHeard(today, window, known, 5) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("听歌统计", back, subtitle = "本机记录 · 保留最近 35 天")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentBottomPadding(model.current != null)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(if (range == 0) "本周" else "近 30 天", total, total / window, active)
            RangeToggle(range) { range = it }
            ChartCard(days, range == 0)
            if (top.isNotEmpty()) TopSongsCard(top) { index -> model.play(top.map { it.song }, index) }
        }
    }
}

@Composable
private fun SummaryCard(label: String, total: Long, daily: Long, activeDays: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(listeningDurationLabel(total), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("日均", listeningDurationLabel(daily), Modifier.weight(1f))
                Stat("听歌天数", "$activeDays 天", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RangeToggle(range: Int, onRange: (Int) -> Unit) {
    val labels = listOf("本周", "近 30 天")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        labels.forEachIndexed { index, label ->
            ToggleButton(checked = range == index, onCheckedChange = { onRange(index) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                else ButtonGroupDefaults.connectedTrailingButtonShapes()) { Text(label) }
        }
    }
}

@Composable
private fun ChartCard(days: List<ListeningDay>, labelled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每天时长", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(if (labelled) "以今天结束的 7 天" else "以今天结束的 30 天",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            val max = days.maxOfOrNull { it.milliseconds } ?: 0L
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (labelled) 8.dp else 2.dp),
                verticalAlignment = Alignment.Bottom) {
                days.forEach { day ->
                    val isToday = day.date == days.last().date
                    val fraction = if (max > 0) (day.milliseconds.toFloat() / max).coerceIn(0f, 1f) else 0f
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height((84.dp * fraction).coerceAtLeast(4.dp)).clip(CircleShape)
                            .background(if (isToday) scheme.primary else scheme.primary.copy(alpha = .32f)))
                        if (labelled) {
                            Spacer(Modifier.height(6.dp))
                            Text(if (isToday) "今" else weekdayLabel(day.date),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopSongsCard(entries: List<HeardEntry>, onPlay: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("常听歌曲", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
            entries.forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPlay(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    Artwork(entry.song.cover, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(entry.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Text(listeningDurationLabel(entry.milliseconds), style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun weekdayLabel(date: LocalDate): String = "一二三四五六日"[date.dayOfWeek.value - 1].toString()
