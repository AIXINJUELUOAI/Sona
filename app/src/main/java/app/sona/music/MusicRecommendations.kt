package app.sona.music

import java.util.Locale
import kotlin.math.ln

data class ListeningStats(val plays: Int = 0, val skips: Int = 0, val lastPlayed: Long = 0)

fun searchLocalSongs(songs: List<Song>, query: String): List<Song> {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return emptyList()
    return songs.filter { song ->
        val text = "${song.title} ${song.artist} ${song.album}".lowercase(Locale.ROOT)
        terms.all(text::contains)
    }.sortedByDescending { it.title.equals(query.trim(), ignoreCase = true) }
}

/** Small, explainable on-device ranking. Every fifth slot explores an unheard track. */
fun recommendSongs(candidates: List<Song>, stats: Map<String, ListeningStats>, favorites: Set<String>,
    now: Long = System.currentTimeMillis(), limit: Int = 30): List<Song> {
    if (limit <= 0) return emptyList()
    val songs = candidates.distinctBy { it.key }
    val artistAffinity = songs.filter { it.artist != "未知歌手" && it.artist.isNotBlank() }
        .groupBy { it.artist.lowercase(Locale.ROOT) }.mapValues { (_, tracks) ->
            tracks.sumOf { (stats[it.key]?.plays ?: 0).coerceAtMost(20) + if (it.key in favorites) 4 else 0 }
        }
    val day = now / 86_400_000L
    fun variety(song: Song): Double = (("$day:${song.key}".hashCode().toLong() and 0x7fffffff) % 1000) / 1000.0
    fun score(song: Song): Double {
        val s = stats[song.key] ?: ListeningStats()
        val hours = ((now - s.lastPlayed).coerceAtLeast(0) / 3_600_000.0)
        return (if (song.key in favorites) 8.0 else 0.0) + 2 * ln(1.0 + s.plays.coerceAtMost(100)) +
            ln(1.0 + (artistAffinity[song.artist.lowercase(Locale.ROOT)] ?: 0)) -
            3 * ln(1.0 + s.skips.coerceAtMost(100)) - (if (s.lastPlayed > 0 && hours < 24) 4 * (1 - hours / 24) else 0.0) +
            variety(song)
    }
    val ranked = songs.sortedByDescending(::score).toMutableList()
    val result = mutableListOf<Song>()
    while (ranked.isNotEmpty() && result.size < limit) {
        val choices = if (result.size % 5 == 4) ranked.filter {
            (stats[it.key]?.plays ?: 0) == 0 && (stats[it.key]?.skips ?: 0) == 0
        }.ifEmpty { ranked } else ranked
        val next = choices.firstOrNull { result.lastOrNull()?.artist != it.artist } ?: choices.first()
        result += next
        ranked.remove(next)
    }
    return result
}

/** Accumulates actual playing time, so seeking to the end does not count as listening. */
internal class ListeningTracker {
    var listenedMs = 0L
        private set
    private var counted = false
    fun advance(deltaMs: Long, durationMs: Long): Boolean {
        listenedMs += deltaMs.coerceIn(0, 5_000)
        val threshold = if (durationMs > 0) minOf(30_000L, durationMs / 2) else 30_000L
        if (!counted && listenedMs >= threshold.coerceAtLeast(1_000)) { counted = true; return true }
        return false
    }
    fun skipped(): Boolean = !counted && listenedMs >= 2_000
}
