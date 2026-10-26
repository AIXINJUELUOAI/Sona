@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
    val todayMs = history.millisecondsOn(today)
    val todayCount = history.days[today].orEmpty().values.count { it.milliseconds >= 1_000 }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekMs = history.millisecondsBetween(weekStart, today)
    val hasDuration = history.days.values.any { day -> day.values.any { it.milliseconds > 0 } }
    val days = remember(history, today) { history.lastDays(today, 7) }
    Crossfade(targetState = hasDuration, label = "listening-summary") { recorded ->
        if (recorded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewCard(todayMs, todayCount, weekMs, weekMs / today.dayOfWeek.value.coerceAtLeast(1))
            HabitCard(days, history.activeDays(today, 7), history.streak(today)) {
                close()
                model.page = Page.Stats
            }
        } else EmptyCard {
            close()
            model.page = if (model.profile == null) Page.Local else Page.Home
        }
    }
}

@Composable
private fun OverviewCard(todayMs: Long, todayCount: Int, weekMs: Long, dailyMs: Long) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListeningBadge()
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("听歌概况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("今天 · $todayCount 首", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Text(listeningDurationLabel(todayMs), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ListeningStat("本周", listeningDurationLabel(weekMs), Modifier.weight(1f))
                ListeningStat("日均", listeningDurationLabel(dailyMs), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HabitCard(days: List<ListeningDay>, activeDays: Int, streak: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("听歌习惯", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(habitLabel(activeDays, streak), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, "听歌统计", tint = scheme.onSurfaceVariant)
            }
            ListeningTimeline(days)
        }
    }
}

private fun habitLabel(activeDays: Int, streak: Int): String =
    if (streak >= 2) "这周 $activeDays 天在听 · 连续 $streak 天" else "这周 $activeDays 天在听"

@Composable
private fun EmptyCard(onListen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListeningBadge()
            Text("从第一首歌开始", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("播放过的时长会按天记录在这里", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Button(onClick = onListen, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("去听歌")
            }
        }
    }
}

@Composable
private fun ListeningStat(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ListeningTimeline(days: List<ListeningDay>) {
    ListeningDayChart(days, barHeight = 56.dp)
}

@Composable
private fun ListeningBadge() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Headphones, null, Modifier.size(20.dp))
        }
    }
}
