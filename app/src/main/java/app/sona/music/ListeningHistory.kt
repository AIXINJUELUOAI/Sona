package app.sona.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class HeardSong(val song: Song, val milliseconds: Long, val lastHeard: Long)
internal data class ListeningHistoryState(
    val startedAt: Long,
    val hasLegacyHistory: Boolean,
    val days: Map<LocalDate, Map<String, HeardSong>> = emptyMap()
)

/** Device-local history, independent of cloud accounts. Keep 35 days for weekly/recent summaries. */
internal class ListeningHistory(context: Context, hasLegacyHistory: Boolean) {
    private val prefs = context.getSharedPreferences("listening_history", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read(hasLegacyHistory))
    val updates = state.asStateFlow()
    private var unpersistedMs = 0L
    private var dirty = false

    init {
        prefs.edit().putLong("started_at", state.value.startedAt)
            .putBoolean("legacy", state.value.hasLegacyHistory).apply()
    }

    private fun read(legacy: Boolean): ListeningHistoryState {
        val days = linkedMapOf<LocalDate, Map<String, HeardSong>>()
        val oldest = LocalDate.now().minusDays(34)
        runCatching { JSONArray(prefs.getString("days", "[]")) }.getOrNull()?.objects()?.forEach { day ->
            runCatching {
                val date = LocalDate.parse(day.getString("date"))
                if (date < oldest) return@runCatching
                days[date] = day.getJSONArray("songs").objects().mapNotNull { item ->
                    runCatching {
                        val song = parseSong(item.getJSONObject("song"))
                        song.key to HeardSong(song, item.getLong("ms").coerceAtLeast(0), item.optLong("last"))
                    }.getOrNull()
                }.toMap()
            }
        }
        return ListeningHistoryState(prefs.getLong("started_at", System.currentTimeMillis()),
            prefs.getBoolean("legacy", legacy), days)
    }

    @Synchronized
    fun record(song: Song, elapsedMs: Long, endTime: Long = System.currentTimeMillis()) {
        if (elapsedMs <= 0) return
        val zone = ZoneId.systemDefault()
        val days = state.value.days.filterKeys { it >= LocalDate.now(zone).minusDays(34) }.toMutableMap()
        var start = endTime - elapsedMs
        // Split actual elapsed time at local midnight, including daylight-saving boundaries.
        while (start < endTime) {
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val end = minOf(endTime, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val entries = days[date].orEmpty().toMutableMap()
            val previous = entries[song.key]
            entries[song.key] = HeardSong(song, (previous?.milliseconds ?: 0) + end - start, end)
            days[date] = entries
            start = end
        }
        state.value = state.value.copy(days = days)
        dirty = true
        unpersistedMs += elapsedMs
        if (unpersistedMs >= 15_000) flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val json = JSONArray(state.value.days.map { (date, songs) ->
            JSONObject().put("date", date.toString()).put("songs", JSONArray(songs.values.map {
                JSONObject().put("song", songJson(it.song)).put("ms", it.milliseconds).put("last", it.lastHeard)
            }))
        })
        prefs.edit().putString("days", json.toString()).apply()
        dirty = false
        unpersistedMs = 0
    }
}

internal fun listeningDurationLabel(ms: Long): String = when {
    ms <= 0 -> "0 分钟"
    ms < 60_000 -> "不到 1 分钟"
    ms < 3_600_000 -> "${ms / 60_000} 分钟"
    ms % 3_600_000 < 60_000 -> "${ms / 3_600_000} 小时"
    else -> "${ms / 3_600_000} 小时 ${ms % 3_600_000 / 60_000} 分钟"
}
