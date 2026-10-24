package app.sona.music

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.EOFException
import java.io.InputStream

internal data class WavTags(val title: String = "", val artist: String = "", val album: String = "",
    val artwork: ByteArray? = null, val lyrics: LocalLyricContent = LocalLyricContent())

// RIFF chunks can follow the entire audio data chunk. Skip audio bytes instead of
// limiting the search to the file header or loading the recording into memory.
@UnstableApi
internal fun readWavTags(input: InputStream): WavTags? {
    fun readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("WAV 标签数据不完整")
            if (count == 0) throw java.io.IOException("WAV 标签读取未完成")
            offset += count
        }
        return bytes
    }
    fun skipExactly(size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count <= 0) throw EOFException("WAV 数据不完整")
                remaining -= count
            }
        }
    }
    fun le32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 255) shl (8 * index))
    }
    val header = ByteArray(12)
    var got = 0
    while (got < header.size) {
        val count = input.read(header, got, header.size - got)
        if (count <= 0) return null
        got += count
    }
    if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    var title = ""; var artist = ""; var album = ""
    var infoTitle = ""; var infoArtist = ""; var infoAlbum = ""
    var artwork: ByteArray? = null
    var frontCover = false
    val lyricCandidates = mutableListOf<LocalLyricContent>()
    repeat(10_000) {
        val first = input.read()
        if (first < 0) return WavTags(title.ifBlank { infoTitle }, artist.ifBlank { infoArtist }, album.ifBlank { infoAlbum },
            artwork, lyricCandidates.firstOrNull { it.lines.isNotEmpty() } ?: lyricCandidates.firstOrNull() ?: LocalLyricContent())
        val chunk = byteArrayOf(first.toByte()) + readExactly(7)
        val name = String(chunk, 0, 4, Charsets.US_ASCII)
        val size = le32(chunk, 4)
        when {
            name.equals("id3 ", ignoreCase = true) && size <= 16 * 1024 * 1024 -> {
                val bytes = readExactly(size.toInt())
                val metadata = Id3Decoder().decode(bytes, bytes.size)
                if (metadata != null) for (index in 0 until metadata.length()) when (val entry = metadata[index]) {
                    is TextInformationFrame -> when (entry.id) {
                        "TIT2", "TT2" -> title = entry.values.joinToString(" / ")
                        "TPE1", "TP1" -> artist = entry.values.joinToString(" / ")
                        "TALB", "TAL" -> album = entry.values.joinToString(" / ")
                        "USLT", "ULT" -> lyricCandidates += localLyricContent(entry.values.joinToString("\n"), "内嵌歌词")
                        "TXXX", "TXX" -> if (entry.description.orEmpty().uppercase(java.util.Locale.ROOT)
                            .replace("_", "").replace(" ", "") in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS"))
                            lyricCandidates += localLyricContent(entry.values.joinToString("\n"), "内嵌歌词")
                    }
                    is BinaryFrame -> id3LyricContent(entry.id, entry.data).takeUnless { it.isEmpty }?.let(lyricCandidates::add)
                    is ApicFrame -> if (entry.pictureData.isNotEmpty() && (artwork == null || (!frontCover && entry.pictureType == 3))) {
                        artwork = entry.pictureData
                        frontCover = entry.pictureType == 3
                    }
                }
            }
            name == "LIST" && size in 4..1024 * 1024 -> {
                val bytes = readExactly(size.toInt())
                if (String(bytes, 0, 4, Charsets.US_ASCII) == "INFO") {
                    var position = 4
                    while (position + 8 <= bytes.size) {
                        val key = String(bytes, position, 4, Charsets.US_ASCII)
                        val length = le32(bytes, position + 4)
                        if (length > bytes.size - position - 8) break
                        val value = bytes.copyOfRange(position + 8, position + 8 + length.toInt())
                        if (key in setOf("INAM", "IART", "IPRD")) {
                            val text = runCatching { decodeLyricBytes(value) }
                                .getOrElse { String(value, Charsets.ISO_8859_1) }.trim('\u0000', '\uFEFF', ' ', '\n', '\r')
                            when (key) { "INAM" -> infoTitle = text; "IART" -> infoArtist = text; "IPRD" -> infoAlbum = text }
                        }
                        position += 8 + length.toInt() + (length.toInt() and 1)
                    }
                }
            }
            else -> skipExactly(size)
        }
        if (size and 1L != 0L) skipExactly(1)
    }
    throw java.io.IOException("WAV 区块数量异常")
}

internal fun Song.withWavTags(tags: WavTags, artworkUri: String): Song = copy(
    title = tags.title.ifBlank { title }, artist = tags.artist.ifBlank { artist },
    album = tags.album.ifBlank { album }, cover = artworkUri.ifBlank { cover })
