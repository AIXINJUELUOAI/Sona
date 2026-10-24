package app.sona.music

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal const val MaxLyricBytes = 1024 * 1024

data class LocalLyricContent(val lines: List<LyricLine> = emptyList(), val text: String = "", val source: String = "") {
    val isEmpty: Boolean get() = lines.isEmpty() && text.isBlank()
}

internal fun localLyricContent(text: String, source: String): LocalLyricContent {
    val clean = text.trim('\uFEFF', '\u0000', ' ', '\r', '\n', '\t')
    val lines = parseLyrics(clean)
    if (lines.isNotEmpty()) return LocalLyricContent(lines.groupBy { it.timeMs }.map { (time, entries) ->
        LyricLine(time, entries.first().text, entries.drop(1).map { it.text }.distinct().joinToString("\n"))
    }, source = source)
    val plain = clean.replace(Regex("\\[(?:ar|ti|al|au|by|offset|re|ve|length):[^]]*]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[\\d+:\\d{2}(?:[.:]\\d{1,3})?]"), "").trim()
    return LocalLyricContent(text = plain, source = source)
}

internal fun decodeLyricBytes(bytes: ByteArray): String {
    require(bytes.size <= MaxLyricBytes) { "歌词文件超过 1 MB，请选择较小的 LRC 文件" }
    if (bytes.size >= 2) {
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).trimStart('\uFEFF')
        if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).trimStart('\uFEFF')
    }
    for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"))) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trimStart('\uFEFF')
        } catch (_: java.nio.charset.CharacterCodingException) { /* Try the next common LRC encoding. */ }
    }
    error("歌词文字编码未识别，请将 LRC 保存为 UTF-8 后重试")
}

// ID3 USLT/ULT and millisecond SYLT/SLT payloads; the container is decoded by Media3.
internal fun id3LyricContent(id: String, data: ByteArray): LocalLyricContent {
    if (data.size !in 5..MaxLyricBytes) return LocalLyricContent()
    val encoding = data[0].toInt() and 255
    val charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> return LocalLyricContent()
    }
    val step = if (encoding == 1 || encoding == 2) 2 else 1
    fun decode(start: Int, stop: Int): String {
        val descriptionStart = if (id == "SYLT" || id == "SLT") 6 else 4
        val effective = if (encoding == 1 && stop - start >= 2) {
            when {
                data[start] == 0xFF.toByte() && data[start + 1] == 0xFE.toByte() -> Charsets.UTF_16LE
                data[start] == 0xFE.toByte() && data[start + 1] == 0xFF.toByte() -> Charsets.UTF_16BE
                data.size > descriptionStart + 1 && data[descriptionStart] == 0xFF.toByte() &&
                    data[descriptionStart + 1] == 0xFE.toByte() -> Charsets.UTF_16LE
                else -> Charsets.UTF_16BE
            }
        } else charset
        return String(data, start, stop - start, effective).trim('\u0000', '\uFEFF', ' ', '\n', '\r')
    }
    fun end(start: Int): Int {
        var i = start
        while (i + step <= data.size) {
            if (data[i] == 0.toByte() && (step == 1 || data[i + 1] == 0.toByte())) return i
            i += step
        }
        return data.size
    }
    val source = "内嵌歌词"
    if (id == "USLT" || id == "ULT") {
        val start = end(4) + step
        if (start > data.size) return LocalLyricContent()
        return localLyricContent(decode(start, data.size), source)
    }
    if ((id != "SYLT" && id != "SLT") || data.size < 7 || data[4] != 2.toByte()) return LocalLyricContent()
    var position = end(6) + step
    val lines = mutableListOf<LyricLine>()
    while (position < data.size) {
        val stop = end(position)
        val timestamp = stop + step
        if (timestamp + 4 > data.size) break
        val text = decode(position, stop)
        val time = (0..3).fold(0L) { value, index -> (value shl 8) or (data[timestamp + index].toLong() and 255) }
        if (text.isNotBlank()) lines += LyricLine(time, text)
        position = timestamp + 4
    }
    return LocalLyricContent(lines.sortedBy { it.timeMs }, source = source)
}

// A selected music tree may contain several albums with identically named tracks.
// Match the actual volume and parent directory, never search all folders by title.
internal fun lyricRelativeSegments(treeId: String, volume: String, parent: String, filename: String): List<String>? {
    if (!treeId.contains(':') || filename.isBlank() || !filename.contains('.')) return null
    val treeVolume = treeId.substringBefore(':')
    val audioVolume = if (volume == "external_primary") "primary" else volume
    if (!treeVolume.equals(audioVolume, ignoreCase = true)) return null
    val root = treeId.substringAfter(':').trim('/').split('/').filter(String::isNotBlank)
    val folder = parent.trim('/').split('/').filter(String::isNotBlank)
    if ((root + folder).any { it == "." || it == ".." } || filename.contains('/')) return null
    if (root.size > folder.size || root.indices.any { !root[it].equals(folder[it], ignoreCase = true) }) return null
    val stem = filename.substringBeforeLast('.')
    if (stem.isBlank()) return null
    return folder.drop(root.size) + "$stem.lrc"
}
