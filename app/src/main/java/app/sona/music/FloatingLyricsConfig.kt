package app.sona.music

import org.json.JSONObject

internal data class LyricRegion(val x: Float = .1f, val y: Float = .12f,
    val width: Float = .8f, val height: Float = .12f) {
    fun bounded(): LyricRegion {
        val w = width.coerceIn(.2f, 1f)
        val h = height.coerceIn(.04f, .6f)
        return copy(x = x.coerceIn(0f, 1f - w), y = y.coerceIn(0f, 1f - h), width = w, height = h)
    }
    fun json() = JSONObject().put("x", x).put("y", y).put("width", width).put("height", height)
    companion object {
        fun read(json: JSONObject?, fallback: LyricRegion): LyricRegion {
            fun number(key: String, default: Float) = json?.optDouble(key)?.toFloat()?.takeIf { it.isFinite() } ?: default
            return LyricRegion(number("x", fallback.x), number("y", fallback.y),
                number("width", fallback.width), number("height", fallback.height)).bounded()
        }
    }
}

internal enum class FloatingLyricsStyle(val label: String) { Default("默认"), Danmaku("弹幕") }

internal data class FloatingLyricsConfig(val enabled: Boolean = false, val opacity: Float = .9f,
    val rate: Float = 1f, val style: FloatingLyricsStyle = FloatingLyricsStyle.Danmaku,
    val fixedLines: Int = 1, val fixedLocked: Boolean = false,
    val fontSize: Float = 18f, val showTranslation: Boolean = false,
    val textColor: Int = -1, val translationColor: Int = -1,
    val appColors: Map<String, Int> = emptyMap(),
    val portrait: LyricRegion = LyricRegion(),
    val landscape: LyricRegion = LyricRegion(height = .28f)) {
    fun bounded() = copy(opacity = opacity.coerceIn(.25f, 1f), rate = rate.coerceIn(.5f, 2f),
        fixedLines = fixedLines.coerceIn(1, 2), fontSize = fontSize.coerceIn(12f, 32f),
        textColor = textColor or 0xff000000.toInt(), translationColor = textColor or 0xff000000.toInt(),
        appColors = appColors.filterKeys { it.isNotBlank() && it.length <= 255 }.mapValues { it.value or 0xff000000.toInt() },
        portrait = portrait.bounded(), landscape = landscape.bounded())
    fun encode(): String = JSONObject().put("enabled", enabled).put("opacity", opacity).put("rate", rate)
        .put("style", style.name).put("fixedLines", fixedLines).put("fixedLocked", fixedLocked).put("fontSize", fontSize)
        .put("showTranslation", showTranslation).put("textColor", textColor).put("translationColor", translationColor)
        .put("appColors", JSONObject(appColors))
        .put("portrait", portrait.json()).put("landscape", landscape.json()).toString()
    companion object {
        fun decode(raw: String): FloatingLyricsConfig = runCatching {
            val j = JSONObject(raw)
            fun number(key: String, default: Float) = j.optDouble(key).toFloat().takeIf { it.isFinite() } ?: default
            FloatingLyricsConfig(enabled = j.optBoolean("enabled"), opacity = number("opacity", .9f), rate = number("rate", 1f),
                style = FloatingLyricsStyle.entries.firstOrNull { it.name == j.optString("style") } ?: FloatingLyricsStyle.Danmaku,
                fixedLines = j.optInt("fixedLines", 1), fixedLocked = j.optBoolean("fixedLocked"), fontSize = number("fontSize", 18f),
                showTranslation = j.optBoolean("showTranslation"), textColor = j.optInt("textColor", -1),
                translationColor = j.optInt("translationColor", -1),
                appColors = j.optJSONObject("appColors")?.let { colors ->
                    colors.keys().asSequence().mapNotNull { key ->
                        (colors.opt(key) as? Number)?.toInt()?.let { key to it }
                    }.toMap()
                }.orEmpty(),
                portrait = LyricRegion.read(j.optJSONObject("portrait"), LyricRegion()),
                landscape = LyricRegion.read(j.optJSONObject("landscape"), LyricRegion(height = .28f))).bounded()
        }.getOrDefault(FloatingLyricsConfig())
    }
}

/** A single velocity for every bullet. Normal-song density is a default-speed reference, not a limit. */
internal object DanmakuDefaults {
    const val LaneDp = 30f
    const val SpeedDpPerSecond = 100f
    const val FadeMs = 220L
    val demo = listOf(
        LyricLine(0, "Let the music take us home", "让音乐带我们回家"), LyricLine(2800, "晚风轻轻吹过城市的街道"),
        LyricLine(5600, "Every moment is worth remembering", "每一个瞬间都值得被珍藏"), LyricLine(8400, "把喜欢的旋律留在身旁"),
        LyricLine(11_200, "抬头看看今天的天空"), LyricLine(14_000, "下一段旅程继续播放"))
    const val DemoDuration = 17_000L
}

internal data class DanmakuCue(val start: Long, val end: Long, val text: String, val translation: String = "")

// Only remove metadata rows; never merge, rewrite or split a sung line.
private val lyricCredit = Regex(
    "^(?:作词|作詞|作曲|编曲|編曲|词曲|詞曲|词\\s*[/／]\\s*曲|词|詞|曲|" +
        "制作人|製作人|制作|製作|配唱(?:制作人|制作|製作人|製作|指导|指導)?|" +
        "人声(?:制作|编辑|录音)|人聲(?:製作|編輯|錄音)|监制|監製|录音(?:室|师|工程师)?|錄音(?:室|師)?|" +
        "混音(?:师|工程师)?|混音師|母带(?:工程师|处理)?|母帶|和声(?:编写|编录)?|和聲|" +
        "演唱|歌手|联合出品|聯合出品|出品方|出品人|出品|发行(?:公司)?|發行|" +
        "企划|企劃|策划|总策划|总监制|音乐统筹|音乐监制|执行制作|制作统筹|项目统筹|" +
        "宣传|宣傳|宣传统筹|发行统筹|封面设计|视觉设计|文案|统筹|統籌|推广|推廣|营销|宣发|宣發|" +
        "版权(?:方|归属)?|版權|音乐制作|音乐发行|商务合作|商业合作|商务联系|" +
        "lyrics?|composer|arranger|producer|vocal|backing vocals?|written by|composed by|arranged by|produced by|recorded by|mixed by|mastered by|" +
        "vocal produc(?:er|tion)(?: by)?|vocal director|mixing|mastering|publisher|copyright|OP\\s*[/／]\\s*SP|OP|SP)" +
        "(?:\\s*[:：].*|\\s+.+|\\s*)$", RegexOption.IGNORE_CASE)
private val lyricPromotion = Regex(
    "(?:商务合作|商业合作|商务联系|合作邮箱|联系邮箱|投稿邮箱)\\s*[:：]|" +
        "现金激励|流量扶持|(?:音乐人|创作者)招募|未经著作权人许可|未经版权方授权|" +
        "^(?:本歌曲|本作品|此歌曲|此作品).{0,80}(?:出品|发行|版权|授权)", RegexOption.IGNORE_CASE)
private val lyricContact = Regex("^(?:[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|(?:https?://|www\\.)\\S+)$", RegexOption.IGNORE_CASE)

internal fun danmakuCues(lines: List<LyricLine>, duration: Long): List<DanmakuCue> {
    val ordered = lines.sortedBy { it.timeMs }
    return ordered.mapIndexedNotNull { index, line ->
        val text = line.text.trim().trim('\uFEFF', '\u200B')
        if (text.isEmpty() || lyricCredit.matches(text) || lyricPromotion.containsMatchIn(text) || lyricContact.matches(text)) null
        else DanmakuCue(line.timeMs, ordered.drop(index + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs
            ?: duration.takeIf { it > line.timeMs } ?: (line.timeMs + 4000), text, line.translation.trim())
    }.distinctBy { it.start to it.text }
}
