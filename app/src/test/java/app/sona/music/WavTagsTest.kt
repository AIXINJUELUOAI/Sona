package app.sona.music

import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@UnstableApi
class WavTagsTest {
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun chunk(id: String, data: ByteArray) = id.toByteArray() + le(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray() + chunks.fold(byteArrayOf()) { result, bytes -> result + bytes }
        return "RIFF".toByteArray() + le(body.size) + body
    }
    private fun frame(id: String, data: ByteArray) = id.toByteArray() + ByteBuffer.allocate(4).putInt(data.size).array() + byteArrayOf(0, 0) + data
    private fun tag(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(byteArrayOf()) { result, bytes -> result + bytes }
        val size = (3 downTo 0).map { (body.size shr (7 * it) and 127).toByte() }.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + size + body
    }
    private fun text(id: String, value: String) = frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))

    @Test fun trailingId3ProvidesActualTitleArtistCoverAndLyrics() {
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val id3 = tag(text("TIT2", "真实歌名"), text("TPE1", "实际歌手"), text("TALB", "实际专辑"),
            frame("USLT", byteArrayOf(3) + "chi\u0000[00:01.00]第一句".toByteArray(Charsets.UTF_8)),
            frame("APIC", byteArrayOf(0) + "image/jpeg\u0000".toByteArray() + byteArrayOf(3, 0) + image))
        val bytes = wav(chunk("JUNK", byteArrayOf(1, 2, 3)), chunk("fmt ", ByteArray(16)),
            chunk("data", ByteArray(100)), chunk("ID3 ", id3))
        val tags = readWavTags(ByteArrayInputStream(bytes))!!
        assertEquals("真实歌名", tags.title); assertEquals("实际歌手", tags.artist); assertEquals("实际专辑", tags.album)
        assertArrayEquals(image, tags.artwork)
        assertEquals(listOf(LyricLine(1000, "第一句")), tags.lyrics.lines)
        val indexed = Song(-1, "01.文件名", "未知歌手", localUri = "content://audio/1")
        val corrected = indexed.withWavTags(tags, "file:///cache/cover.art")
        assertEquals("真实歌名", corrected.title); assertEquals("实际歌手", corrected.artist)
        assertEquals("file:///cache/cover.art", corrected.cover); assertEquals(indexed.key, corrected.key)
    }

    @Test fun riffInfoIsDecodedAsAFallbackButId3TakesPrecedence() {
        fun info(key: String, value: String) = chunk(key, value.toByteArray(Charset.forName("GB18030")) + byteArrayOf(0))
        val list = chunk("LIST", "INFO".toByteArray() + info("IART", "周杰伦") + info("INAM", "红尘客栈") + info("IPRD", "十二新作"))
        val plain = readWavTags(ByteArrayInputStream(wav(chunk("data", ByteArray(2)), list)))!!
        assertEquals("周杰伦", plain.artist); assertEquals("红尘客栈", plain.title); assertEquals("十二新作", plain.album)
        val preferred = readWavTags(ByteArrayInputStream(wav(list, chunk("id3 ", tag(text("TIT2", "ID3 标题"))))))!!
        assertEquals("ID3 标题", preferred.title)
        assertEquals("周杰伦", preferred.artist)
    }

    @Test fun nonWavAndTruncatedChunkDoNotInventMetadataOrAllocateHugeChunks() {
        assertNull(readWavTags(ByteArrayInputStream("ID3not-a-wave-file".toByteArray())))
        val broken = "RIFF".toByteArray() + le(100) + "WAVEid3 ".toByteArray() + le(-1)
        try { readWavTags(ByteArrayInputStream(broken)); fail("truncated oversized chunk accepted") }
        catch (_: java.io.EOFException) { }
        val blank = Song(-1, "文件名", "未知歌手", cover = "content://cover", localUri = "content://audio/1")
        assertEquals(blank, blank.withWavTags(WavTags(), ""))
    }

    @Test fun providedWavReadsAllMetadataWithoutReadingTheAudioPayload() {
        val path = System.getenv("SONA_WAV_SAMPLE").orEmpty()
        assumeTrue("Set SONA_WAV_SAMPLE to run the real-file regression", path.isNotBlank() && File(path).isFile)
        var readBytes = 0L
        var skippedBytes = 0L
        val tags = object : FilterInputStream(File(path).inputStream()) {
            override fun read(): Int = `in`.read().also { if (it >= 0) readBytes++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { if (it > 0) readBytes += it }
            override fun skip(count: Long): Long = `in`.skip(count).also { skippedBytes += it }
        }.use(::readWavTags)!!
        assertEquals("红尘客栈", tags.title); assertEquals("周杰伦", tags.artist); assertEquals("十二新作", tags.album)
        assertEquals(189114, tags.artwork!!.size)
        assertEquals("601804c0ae0504e828b070019e66f47627ccdf0c8877a7845481145a1e733c3d",
            MessageDigest.getInstance("SHA-256").digest(tags.artwork).joinToString("") { "%02x".format(it.toInt() and 255) })
        assertTrue(tags.lyrics.lines.size >= 30)
        assertEquals(22230L, tags.lyrics.lines.first().timeMs)
        assertTrue("Audio data should be skipped", skippedBytes >= 72_500_382)
        assertTrue("Only metadata should be read", readBytes < 1_000_000)
        println("Real WAV: title/artist/album/artwork/timed lyrics PASS; lyricLines=${tags.lyrics.lines.size}; read=$readBytes; skipped=$skippedBytes")
    }
}
