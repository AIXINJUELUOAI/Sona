package app.sona.music

import java.time.LocalDate

internal data class ListeningDay(val date: LocalDate, val milliseconds: Long)

internal data class HeardEntry(val song: Song, val milliseconds: Long, val plays: Int = 0)

internal fun ListeningHistoryState.millisecondsOn(date: LocalDate): Long =
    days[date].orEmpty().values.sumOf { it.milliseconds }

/** Total recorded time between two dates, inclusive. */
internal fun ListeningHistoryState.millisecondsBetween(from: LocalDate, to: LocalDate): Long =
    days.filterKeys { it >= from && it <= to }.values.sumOf { day -> day.values.sumOf { it.milliseconds } }

/** The last [count] days ending at [end], oldest first. */
internal fun ListeningHistoryState.lastDays(end: LocalDate, count: Int): List<ListeningDay> =
    (count - 1 downTo 0).map { offset ->
        val date = end.minusDays(offset.toLong())
        ListeningDay(date, millisecondsOn(date))
    }

internal fun ListeningHistoryState.activeDays(end: LocalDate, count: Int): Int =
    lastDays(end, count).count { it.milliseconds > 0 }

/** Consecutive days with listening, counting back from [end]. */
internal fun ListeningHistoryState.streak(end: LocalDate): Int {
    var streak = 0
    var date = end
    while (millisecondsOn(date) > 0) {
        streak++
        date = date.minusDays(1)
    }
    return streak
}

/** Rank by qualified plays; listening time breaks ties. Old duration records stay intact. */
internal fun ListeningHistoryState.topHeard(end: LocalDate, count: Int, known: Map<String, Song>, limit: Int): List<HeardEntry> =
    days.filterKeys { it >= end.minusDays((count - 1).toLong()) && it <= end }.values
        .flatMap { it.values }.groupBy { it.song.key }.values
        .map { songs ->
            val latest = songs.maxBy { it.lastHeard }.song
            HeardEntry(known[latest.key] ?: latest, songs.sumOf { it.milliseconds }, songs.sumOf { it.plays })
        }
        .filter { it.plays > 0 }
        .sortedWith(compareByDescending<HeardEntry> { it.plays }.thenByDescending { it.milliseconds })
        .take(limit)
