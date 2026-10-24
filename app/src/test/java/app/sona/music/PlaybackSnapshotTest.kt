package app.sona.music

import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    private val local = Song(-8, "红尘客栈", "周杰伦", "十二新作", "file:///cache/cover.art", 273_000, "content://media/external/audio/media/8")
    private val cloud = Song(10, "云端歌曲", "歌手", duration = 200_000)

    @Test fun queuePositionModesAndDuplicateSongsSurviveSerialization() {
        val snapshot = PlaybackSnapshot(listOf(local, cloud, local), 2, 120_345, true, 2, listOf(2, 0, 1))
        assertEquals(snapshot, PlaybackSnapshot.decode(snapshot.encode()))
        assertFalse(snapshot.encode().contains("https://"))
    }

    @Test fun removingFilesRemapsShuffleOrderWithoutLosingCurrentProgress() {
        val snapshot = PlaybackSnapshot(listOf(local, cloud, local.copy(localUri = "content://missing")), 1,
            42_123, true, 1, listOf(2, 1, 0))
        val restored = snapshot.retaining { it.localUri != "content://missing" }!!
        assertEquals(listOf(local, cloud), restored.songs)
        assertEquals(1, restored.index)
        assertEquals(42_123L, restored.position)
        assertEquals(listOf(1, 0), restored.order)
        val next = snapshot.copy(index = 2).retaining { it.localUri != "content://missing" }!!
        assertEquals(cloud, next.songs[next.index])
        assertEquals(0L, next.position)
    }

    @Test fun invalidDataAndEmptyLibrariesAreHandled() {
        assertNull(PlaybackSnapshot.decode("corrupt"))
        assertNull(PlaybackSnapshot.decode("{\"version\":99}"))
        assertNull(PlaybackSnapshot(emptyList(), 0, 0, false, 0, emptyList()).retaining { true })
        val restored = PlaybackSnapshot(listOf(local), 0, Long.MAX_VALUE, false, 99, listOf(3)).retaining { true }!!
        assertEquals(local.duration - 1, restored.position)
        assertEquals(0, restored.repeat)
        assertEquals(listOf(0), restored.order)
    }

    @Test fun greetingChangesAtTheEstablishedTimeBoundaries() {
        assertEquals("晚上好", greetingForHour(4))
        assertEquals("早上好", greetingForHour(5))
        assertEquals("早上好", greetingForHour(10))
        assertEquals("中午好", greetingForHour(11))
        assertEquals("中午好", greetingForHour(13))
        assertEquals("下午好", greetingForHour(14))
        assertEquals("下午好", greetingForHour(18))
        assertEquals("晚上好", greetingForHour(19))
    }
}
