package app.sona.music

import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class LocalLyricsTest {
    @androidx.media3.common.util.UnstableApi
    @Test fun media3ExtractsAnActualId3UsltFrame() {
        val payload = byteArrayOf(3) + "chi\u0000[00:02.00]实际标签歌词".toByteArray(Charsets.UTF_8)
        val frame = "USLT".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0) + payload
        val size = byteArrayOf((frame.size shr 21 and 127).toByte(), (frame.size shr 14 and 127).toByte(),
            (frame.size shr 7 and 127).toByte(), (frame.size and 127).toByte())
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + size + frame
        val metadata = androidx.media3.extractor.metadata.id3.Id3Decoder().decode(tag, tag.size)!!
        val entry = metadata[0] as androidx.media3.extractor.metadata.id3.BinaryFrame
        assertEquals("实际标签歌词", id3LyricContent(entry.id, entry.data).lines.single().text)
    }
    @Test fun lrcRetainsTimelineOffsetAndSameTimeTranslation() {
        val result = localLyricContent("\uFEFF[ti:歌名]\n[offset:-100]\n[00:01.25]第一句\n[00:01.25]Translation\n[00:03.00]第二句", "LRC")
        assertEquals(listOf(1150L, 2900L), result.lines.map { it.timeMs })
        assertEquals("第一句", result.lines.first().text)
        assertEquals("Translation", result.lines.first().translation)
        assertEquals("", result.text)
    }

    @Test fun plainLyricsStayReadableWithoutInventedTimestamps() {
        val result = localLyricContent("[ti:歌名]\r\n第一句\r\n第二句", "内嵌歌词")
        assertTrue(result.lines.isEmpty())
        assertEquals("第一句\r\n第二句", result.text)
        assertFalse(result.isEmpty)
        assertTrue(localLyricContent("[ti:歌名]\n[ar:歌手]", "LRC").isEmpty)
        assertTrue(localLyricContent("[00:01.00]\n[00:02.00]", "LRC").isEmpty)
    }

    @Test fun commonChineseEncodingsAndSizeLimit() {
        val text = "[00:01.00]中文歌词"
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"), Charsets.UTF_16)) {
            assertEquals(text, decodeLyricBytes(text.toByteArray(charset)))
        }
        assertEquals(text, decodeLyricBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        try { decodeLyricBytes(ByteArray(MaxLyricBytes + 1)); fail("oversized input accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun id3UnsynchronisedLyricsSupportUtf8AndUtf16Descriptors() {
        val text = "[00:02.00]内嵌歌词"
        val utf8 = byteArrayOf(3) + "chi".toByteArray() + "description\u0000$text".toByteArray()
        assertEquals(LyricLine(2000, "内嵌歌词"), id3LyricContent("USLT", utf8).lines.single())
        val utf16 = byteArrayOf(1) + "chi".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "描述\u0000$text".toByteArray(Charsets.UTF_16LE)
        assertEquals("内嵌歌词", id3LyricContent("ULT", utf16).lines.single().text)
        assertTrue(id3LyricContent("USLT", byteArrayOf(3, 1, 2)).isEmpty)
        assertTrue(id3LyricContent("APIC", utf8).isEmpty)
    }

    @Test fun id3SynchronisedLyricsRequireMillisecondTimingAndCompleteEntries() {
        val payload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(2, 1, 0) +
            "First\u0000".toByteArray() + ByteBuffer.allocate(4).putInt(1000).array() +
            "Second\u0000".toByteArray() + ByteBuffer.allocate(4).putInt(2500).array()
        assertEquals(listOf(LyricLine(1000, "First"), LyricLine(2500, "Second")), id3LyricContent("SYLT", payload).lines)
        assertEquals(1, id3LyricContent("SYLT", payload.dropLast(2).toByteArray()).lines.size)
        assertTrue(id3LyricContent("SYLT", payload.copyOf().apply { this[4] = 1 }).isEmpty)
    }

    @Test fun sidecarUsesTheAudioFilenameVolumeAndExactParentDirectory() {
        assertEquals(listOf("Album", "track.live.lrc"), lyricRelativeSegments("primary:Music", "external_primary", "Music/Album/", "track.live.flac"))
        assertEquals(listOf("track.lrc"), lyricRelativeSegments("primary:Music/Album", "external_primary", "Music/Album/", "track.mp3"))
        assertNull(lyricRelativeSegments("primary:Music/OtherAlbum", "external_primary", "Music/Album/", "track.mp3"))
        assertNull(lyricRelativeSegments("primary:Music", "ABCD-1234", "Music/Album/", "track.mp3"))
        assertNull(lyricRelativeSegments("primary:Music", "external_primary", "Music2/", "track.mp3"))
        assertNull(lyricRelativeSegments("primary:Music", "external_primary", "Music/../", "track.mp3"))
    }
}
