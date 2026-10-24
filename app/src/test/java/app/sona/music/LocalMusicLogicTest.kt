package app.sona.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalMusicLogicTest {
    @Test fun localHistoryPreservesItsSourceAndDoesNotCollideWithCloudSongs() {
        val song = parseSong(JSONObject("""{"id":-7,"name":"同名歌曲","localUri":"content://media/external/audio/media/7","al":{"picUrl":"content://media/external/audio/albumart/2"}}"""))
        val cloud = Song(7, song.title, song.artist)
        assertTrue(song.isLocal)
        assertFalse(cloud.isLocal)
        assertNotEquals(song.key, cloud.key)
        assertEquals("content://media/external/audio/media/7", song.key)
        assertEquals("content://media/external/audio/albumart/2", song.cover)
        assertFalse(parseSong(JSONObject("""{"id":7,"name":"旧版历史"}""")).isLocal)
    }

    @Test fun searchMatchesTitleArtistAndAlbumWithoutMergingDifferentVersions() {
        val songs = listOf(Song(-1, "Hello", "Adele", "25", localUri = "content://one"),
            Song(-2, "Hello (Live)", "ADELE", "Live", localUri = "content://two"),
            Song(-3, "夜曲", "周杰伦", "十一月的萧邦", localUri = "content://three"))
        assertEquals(listOf(songs[0], songs[1]), searchLocalSongs(songs, "  hello AdElE  "))
        assertEquals(listOf(songs[0]), searchLocalSongs(songs, "25"))
        assertEquals(listOf(songs[2]), searchLocalSongs(songs, "周杰伦 萧邦"))
        assertTrue(searchLocalSongs(songs, "missing").isEmpty())
        assertTrue(searchLocalSongs(songs, " ").isEmpty())
    }

    @Test fun rankingUsesFavoritesSkipsRecencyAndKeepsSourcesDistinct() {
        val favorite = Song(1, "收藏", "A")
        val skipped = Song(2, "跳过", "B")
        val fresh = Song(3, "新歌", "C")
        val local = Song(-1, "收藏", "A", localUri = "content://local/1")
        val stats = mapOf(skipped.key to ListeningStats(skips = 10), fresh.key to ListeningStats(plays = 1))
        val result = recommendSongs(listOf(favorite, skipped, fresh, local, favorite), stats, setOf(favorite.key), now = 1_000_000_000)
        assertEquals(favorite, result.first())
        assertTrue(result.indexOf(fresh) < result.indexOf(skipped))
        assertEquals(4, result.size)
        assertTrue(local in result)
        val older = Song(10, "旧歌", "D")
        val recent = Song(11, "刚听过", "E")
        val now = 2_000_000_000L
        val times = mapOf(older.key to ListeningStats(plays = 3, lastPlayed = now - 86_400_000),
            recent.key to ListeningStats(plays = 3, lastPlayed = now))
        assertEquals(older, recommendSongs(listOf(recent, older), times, emptySet(), now).first())
    }

    @Test fun recommendationsIncludeExplorationAndAreStableWithinADay() {
        val known = (1L..9L).map { Song(it, "Known $it", "Artist $it") }
        val unheard = Song(50, "Unheard", "New artist")
        val stats = known.associate { it.key to ListeningStats(plays = 20) }
        val result = recommendSongs(known + unheard, stats, emptySet(), now = 1_000_000_000, limit = 5)
        assertEquals(5, result.size)
        assertEquals(unheard, result[4])
        assertEquals(result, recommendSongs(known + unheard, stats, emptySet(), now = 1_000_000_000, limit = 5))
        assertTrue(recommendSongs(emptyList(), emptyMap(), emptySet()).isEmpty())
    }

    @Test fun listeningUsesElapsedPlaybackNotSeekPositionAndCountsOnlyOnce() {
        val tracker = ListeningTracker()
        repeat(5) { assertFalse(tracker.advance(5_000, 180_000)) }
        assertTrue(tracker.skipped())
        assertTrue(tracker.advance(5_000, 180_000))
        assertFalse(tracker.skipped())
        assertFalse(tracker.advance(5_000, 180_000))
        val short = ListeningTracker()
        assertFalse(short.skipped())
        assertTrue(short.advance(5_000, 10_000))
        val resumed = ListeningTracker()
        assertFalse(resumed.advance(180_000, 180_000)) // delayed tick is capped, not a fake full listen
        assertEquals(5_000, resumed.listenedMs)
    }
}
