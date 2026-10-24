package app.sona.music

import org.json.JSONArray
import org.json.JSONObject

data class Song(val id: Long, val title: String, val artist: String, val album: String = "", val cover: String = "", val duration: Long = 0,
    val localUri: String = "", val localDateAdded: Long = 0) {
    val isLocal: Boolean get() = localUri.isNotBlank()
    val key: String get() = if (isLocal) localUri else id.toString()
    val sourceLabel: String get() = if (isLocal) "本地" else "网易云"
}

enum class SearchSource(val label: String) { All("全部"), Local("本地"), Cloud("网易云") }
enum class PlayerStyleMode(val label: String) { Default("默认"), Bold("大胆"), TapToSwitch("点击切换") }
data class MusicList(val id: Long, val name: String, val cover: String, val count: Int = 0)
data class LyricLine(val timeMs: Long, val text: String, val translation: String = "")
data class Profile(val id: Long, val name: String, val avatarUrl: String = "")

enum class PlaybackMode(val label: String) {
    Sequential("顺序播放"), RepeatAll("列表循环"), RepeatOne("单曲循环"), Shuffle("随机播放")
}

enum class PlaybackOrigin { SongList, OutsideList }

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun parseSong(json: JSONObject): Song {
    val artists = json.optJSONArray("ar") ?: json.optJSONArray("artists") ?: JSONArray()
    val album = json.optJSONObject("al") ?: json.optJSONObject("album") ?: JSONObject()
    return Song(json.getLong("id"), json.optString("name", "未命名歌曲"),
        artists.objects().joinToString(" / ") { it.optString("name") }.ifBlank { "未知歌手" },
        album.optString("name"), album.optString("picUrl").replace("http://", "https://"),
        json.optLong("dt", json.optLong("duration")), json.optString("localUri"), json.optLong("localDateAdded"))
}

fun parseList(json: JSONObject) = MusicList(json.getLong("id"), json.optString("name"),
    json.optString("coverImgUrl", json.optString("picUrl")).replace("http://", "https://"), json.optInt("trackCount"))

private val lyricTag = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

fun parseLyrics(original: String, translated: String = ""): List<LyricLine> {
    fun lines(source: String): List<Pair<Long, String>> {
        val offset = offsetTag.find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return source.lineSequence().flatMap { line ->
            val tags = lyricTag.findAll(line).toList()
            val content = line.replace(lyricTag, "").trim()
            if (content.isBlank()) emptyList<Pair<Long, String>>().asSequence() else tags.asSequence().map { match ->
                val (minutes, seconds, fraction) = match.destructured
                val ms = minutes.toLong() * 60_000 + seconds.toLong() * 1000 + fraction.padEnd(3, '0').take(3).toLong()
                (ms + offset).coerceAtLeast(0) to content
            }
        }.toList().sortedBy { it.first }
    }
    val translations = lines(translated).toMap()
    return lines(original).map { (time, text) -> LyricLine(time, text, translations[time].orEmpty()) }
}

fun activeLyric(lines: List<LyricLine>, position: Long): Int = lines.indexOfLast { it.timeMs <= position }
fun formatTime(ms: Long): String = "%d:%02d".format((ms.coerceAtLeast(0) / 60_000), (ms.coerceAtLeast(0) / 1000) % 60)

data class PlayerTitle(val main: String, val version: String = "") {
    private val characters = main.codePoints().filter { !Character.isWhitespace(it) }.toArray()
    private val isCjk = characters.isNotEmpty() && characters.all {
        when (Character.UnicodeScript.of(it)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> it == 0x30fc || it == 0xff70 // Japanese prolonged sound marks.
        }
    }
    val isShortCjk: Boolean get() = characters.size in 1..6 && isCjk
    val isLongCjk: Boolean get() = characters.size > 6 && isCjk
    val boldColumns: Int? get() = if (!isCjk) null else when (characters.size) {
        4 -> 2
        6 -> 3
        7 -> 4
        in 8..10 -> 5
        else -> null
    }
    val boldText: String get() {
        val columns = boldColumns ?: return main
        return String(characters, 0, columns) + "\n" + String(characters, columns, characters.size - columns)
    }
}

// Only detach known version suffixes; parentheses that are part of a song name stay intact.
fun playerTitle(title: String): PlayerTitle {
    val suffix = Regex("\\s*[（(]((?:Live|现场版|现场|Remix|伴奏|Instrumental|Acoustic)[^()（）]*)[）)]$", RegexOption.IGNORE_CASE).find(title)
    return if (suffix != null && suffix.range.first > 0) PlayerTitle(title.take(suffix.range.first).trim(), suffix.groupValues[1])
    else PlayerTitle(title)
}
