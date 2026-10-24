package app.sona.music

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.Transliterator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class LocalSongSort(val label: String) {
    Title("歌名"), Artist("歌手"), Album("专辑"), DateAdded("添加时间")
}

internal data class LocalSongOrder(val songs: List<Song>, val labels: List<String>)

/** Sort the enriched metadata that users actually see, independently of the playing queue. */
internal suspend fun orderLocalSongs(songs: List<Song>, sort: LocalSongSort, descending: Boolean): LocalSongOrder {
    val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII")
    val collator = (Collator.getInstance(Locale.ROOT) as RuleBasedCollator).apply { setNumericCollation(true) }
    val keys = HashMap<String, String>()
    fun key(text: String) = keys.getOrPut(text) {
        transliterator.transliterate(text).lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
    }
    data class Entry(val song: Song, val primary: String, val title: String)
    val entries = songs.map { song ->
        currentCoroutineContext().ensureActive()
        Entry(song, key(when (sort) {
            LocalSongSort.Title -> song.title
            LocalSongSort.Artist -> song.artist
            LocalSongSort.Album -> song.album
            LocalSongSort.DateAdded -> ""
        }), key(song.title))
    }.sortedWith { a, b ->
        val primary = if (sort == LocalSongSort.DateAdded) a.song.localDateAdded.compareTo(b.song.localDateAdded)
            else collator.compare(a.primary, b.primary)
        (if (descending) -primary else primary).takeIf { it != 0 }
            ?: collator.compare(a.title, b.title).takeIf { it != 0 }
            ?: a.song.key.compareTo(b.song.key)
    }
    currentCoroutineContext().ensureActive()
    val zone = ZoneId.systemDefault()
    return LocalSongOrder(entries.map { it.song }, entries.map {
        if (sort == LocalSongSort.DateAdded) {
            if (it.song.localDateAdded > 0) Instant.ofEpochSecond(it.song.localDateAdded).atZone(zone).toLocalDate().toString()
            else "未知日期"
        } else it.primary.firstOrNull()?.let { char -> if (char.isDigit()) "#" else char.uppercaseChar().toString() } ?: "#"
    })
}
