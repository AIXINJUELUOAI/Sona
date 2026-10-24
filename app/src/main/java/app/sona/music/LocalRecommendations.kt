package app.sona.music

import java.util.Random

data class LocalCollection(val key: String, val title: String, val songs: List<Song>)

/**
 * Daily mix drawn from the device library: favourites, most played, long-unheard and never-played
 * slots, then a scored fill. Seeded by the day so the same date yields the same list.
 */
internal fun buildLocalDaily(songs: List<Song>, stats: Map<String, ListeningStats>, favorites: Set<String>,
    now: Long = System.currentTimeMillis(), limit: Int = 30): List<Song> {
    if (songs.isEmpty() || limit <= 0) return emptyList()
    val random = Random(now / 86_400_000L)
    val all = songs.distinctBy { it.key }
    fun plays(song: Song) = stats[song.key]?.plays ?: 0
    fun lastPlayed(song: Song) = stats[song.key]?.lastPlayed ?: 0L
    val picked = HashSet<String>()
    val result = mutableListOf<Song>()
    fun take(list: List<Song>, count: Int) {
        var added = 0
        for (song in list) {
            if (added >= count || result.size >= limit) return
            if (picked.add(song.key)) { result += song; added++ }
        }
    }
    take(all.filter { it.key in favorites }.shuffled(random), 8)
    take(all.filter { plays(it) > 0 && it.key !in favorites }
        .sortedWith(compareByDescending<Song> { plays(it) }.thenByDescending { lastPlayed(it) }), 12)
    take(all.filter { lastPlayed(it) > 0 && now - lastPlayed(it) > 30L * 86_400_000 && it.key !in favorites }
        .shuffled(random), 6)
    take(all.filter { plays(it) == 0 && it.key !in favorites }.shuffled(random), 4)
    if (result.size < limit) take(all.filter { it.key !in picked }.shuffled(random), limit - result.size)
    return spreadArtists(result)
}

/** Local "playlists" for the discovery strip: one row per recognisable pattern in the library. */
internal fun buildLocalCollections(songs: List<Song>, stats: Map<String, ListeningStats>, favorites: Set<String>,
    now: Long = System.currentTimeMillis()): List<LocalCollection> {
    if (songs.isEmpty()) return emptyList()
    val random = Random(now / 86_400_000L)
    val collections = mutableListOf<LocalCollection>()
    val favouriteSongs = songs.filter { it.key in favorites }
    if (favouriteSongs.isNotEmpty()) collections += LocalCollection("favorites", "本地收藏", favouriteSongs)

    val recentlyAdded = songs.filter { it.localDateAdded > 0 && now / 1000 - it.localDateAdded <= 30L * 86_400 }
        .sortedByDescending { it.localDateAdded }
    if (recentlyAdded.size >= 5) collections += LocalCollection("added", "最近添加", recentlyAdded.take(60))

    val topArtist = songs.filter { it.artist.isNotBlank() && it.artist != "未知歌手" }
        .groupBy { it.artist }
        .mapValues { (_, tracks) -> tracks.sumOf { stats[it.key]?.plays ?: 0 } }
        .filterValues { it > 0 }
        .maxByOrNull { it.value }?.key
    if (topArtist != null) {
        val tracks = songs.filter { it.artist == topArtist }
        if (tracks.size >= 3) collections += LocalCollection("artist", "常听歌手 · $topArtist", tracks)
    }

    val forgotten = songs.filter { song ->
        val last = stats[song.key]?.lastPlayed ?: 0L
        last > 0 && now - last > 30L * 86_400_000
    }
    if (forgotten.size >= 5) collections += LocalCollection("stale", "很久没听", forgotten.shuffled(random).take(50))

    val album = songs.filter { it.album.isNotBlank() }.groupBy { it.album }
        .entries.maxByOrNull { it.value.size }?.value
    if (album != null && album.size >= 3) collections += LocalCollection("album", "整张专辑 · ${album.first().album}", album)
    return collections
}

/** Avoids two tracks by the same artist next to each other without changing the chosen set. */
private fun spreadArtists(songs: List<Song>): List<Song> {
    val remaining = songs.toMutableList()
    val result = mutableListOf<Song>()
    while (remaining.isNotEmpty()) {
        val last = result.lastOrNull()?.artist
        val index = remaining.indexOfFirst { it.artist != last }.coerceAtLeast(0)
        result += remaining.removeAt(index)
    }
    return result
}
