package app.sona.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class NeteaseApi(private val readSession: () -> String, private val saveSession: (String) -> Unit) {
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private fun sessionCookies(): Map<String, String> = readSession().split(';').mapNotNull {
        val pair = it.trim().split('=', limit = 2)
        if (pair.size == 2) pair[0] to pair[1] else null
    }.toMap()
    @Synchronized
    private fun updateCookies(headers: Map<String?, List<String>>) {
        val cookies = sessionCookies().toMutableMap()
        headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { raw ->
            runCatching { HttpCookie.parse(raw) }.getOrDefault(emptyList()).forEach {
                if (it.maxAge == 0L) cookies.remove(it.name) else cookies[it.name] = it.value
            }
        }
        saveSession(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
    fun request(path: String, params: Map<String, Any> = emptyMap(), eapi: Boolean = false, check: Boolean = true, plain: Boolean = false): JSONObject {
        // Use one session snapshot for the encrypted header and the HTTP cookie.
        val cookies = sessionCookies()
        val csrf = cookies["__csrf"].orEmpty()
        val now = System.currentTimeMillis()
        val header = if (eapi) mapOf(
            "os" to "iphone", "appver" to "9.0.90", "osver" to "16.2", "channel" to "distribution",
            "deviceId" to cookies["deviceId"].orEmpty().ifBlank { deviceId }, "versioncode" to "140",
            "mobilename" to "", "buildver" to (now / 1000).toString(), "resolution" to "1920x1080",
            "__csrf" to csrf, "requestId" to "${now}_${(0..9999).random().toString().padStart(4, '0')}"
        ) + cookies.filterKeys { it in setOf("MUSIC_U", "MUSIC_A", "NMTID") }
        else cookies + mapOf("os" to "pc", "appver" to "3.1.17.204416")
        val payload = JSONObject(params)
        payload.put("csrf_token", csrf)
        val fields = if (plain) params.mapValues { it.value.toString() } else if (eapi) {
            payload.put("e_r", false)
            payload.put("header", JSONObject(header))
            NeteaseProtocol.eapi("/api$path", payload.toString())
        } else NeteaseProtocol.weapi(payload.toString())
        val host = if (plain) "https://music.163.com/api" else if (eapi) "https://interface.music.163.com/eapi" else "https://music.163.com/weapi"
        val form = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val connection = URL(host + path + if (plain) "?$form" else "").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (plain) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.doOutput = !plain
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Referer", "https://music.163.com/")
            connection.setRequestProperty("User-Agent", if (eapi) "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
                else "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
            connection.setRequestProperty("Cookie", header.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (!plain) connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("网易云连接未完成（HTTP $status），请稍后重试")
            val bytes = connection.inputStream.use { it.readBytes() }
            val raw = String(bytes, Charsets.UTF_8)
            val json = runCatching { JSONObject(raw) }.getOrElse {
                if (eapi) runCatching { JSONObject(NeteaseProtocol.decodeEapi(bytes)) }.getOrElse { throw IOException("歌曲服务返回格式异常") }
                else throw IOException("网易云返回格式异常，请稍后重试")
            }
            val code = json.optInt("code", 200)
            updateCookies(connection.headerFields)
            if (check && code != 200) {
                android.util.Log.w("SonaNetease", "Request failed: path=$path http=$status code=$code song=${params["trackId"] ?: params["ids"] ?: ""}")
                val reason = sequenceOf(json.optString("message"), json.optString("msg"))
                    .firstOrNull { it.isNotBlank() && it != "null" }?.take(180)
                throw IOException(when (code) {
                    301, 302 -> "请先登录网易云账号"
                    405, 460, -460 -> "网易云暂时限制了此请求，请稍后重试（$code）"
                    else -> reason?.let { "$it（$code）" } ?: "网易云请求未完成（$code）"
                })
            }
            return json
        } finally { connection.disconnect() }
    }
    fun search(text: String, offset: Int = 0): List<Song> {
        val songs = (request("/search/get", mapOf("s" to text, "type" to 1, "limit" to 30, "offset" to offset), plain = true)
            .optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()).objects().map(::parseSong)
        if (songs.isEmpty()) return songs
        val details = songDetails(songs.map { it.id }).associateBy { it.id }
        return songs.map { details[it.id] ?: it }
    }
    fun songDetails(ids: List<Long>): List<Song> = (request("/v3/song/detail", mapOf("c" to JSONArray(ids.map { JSONObject().put("id", it) }).toString()), plain = true)
        .optJSONArray("songs") ?: JSONArray()).objects().map(::parseSong)
    fun recommendations() = (request("/v3/discovery/recommend/songs").optJSONObject("data")?.optJSONArray("dailySongs") ?: JSONArray()).objects().map(::parseSong)
    fun discovery() = (request("/personalized/playlist", mapOf("limit" to 12, "total" to true, "n" to 1000)).optJSONArray("result") ?: JSONArray()).objects().map(::parseList)
    fun lists(uid: Long) = (request("/user/playlist", mapOf("uid" to uid, "limit" to 100, "offset" to 0)).optJSONArray("playlist") ?: JSONArray()).objects().map(::parseList)
    fun tracks(id: Long, offset: Int = 0): List<Song> {
        val detail = request("/v6/playlist/detail", mapOf("id" to id, "n" to 100000, "s" to 0), plain = true).getJSONObject("playlist")
        val ids = (detail.optJSONArray("trackIds") ?: JSONArray()).objects().drop(offset).take(100).map { it.getLong("id") }
        if (ids.isEmpty()) return emptyList()
        val indexed = songDetails(ids).associateBy { it.id }
        return ids.mapNotNull(indexed::get)
    }
    fun lyrics(id: Long): List<LyricLine> {
        val json = request("/song/lyric", mapOf("id" to id, "lv" to -1, "tv" to -1), plain = true)
        return parseLyrics(json.optJSONObject("lrc")?.optString("lyric").orEmpty(), json.optJSONObject("tlyric")?.optString("lyric").orEmpty())
    }
    fun profile(): Profile? {
        val profile = request("/w/nuser/account/get", check = false).optJSONObject("profile") ?: return null
        return Profile(profile.getLong("userId"), profile.optString("nickname"), profile.optString("avatarUrl"))
    }
    fun liked(uid: Long): Set<Long> {
        val ids = request("/song/like/get", mapOf("uid" to uid)).optJSONArray("ids") ?: JSONArray()
        return (0 until ids.length()).map { ids.getLong(it) }.toSet()
    }
    fun like(id: Long, liked: Boolean, uid: Long) {
        request("/song/like", mapOf("trackId" to id, "userid" to uid, "like" to liked), eapi = true)
    }
    fun radio() = (request("/v1/radio/get", mapOf("limit" to 3)).optJSONArray("data") ?: JSONArray()).objects().map(::parseSong)
    fun qrKey() = request("/login/qrcode/unikey", mapOf("type" to 3)).getString("unikey")
    fun qrStatus(key: String) = request("/login/qrcode/client/login", mapOf("key" to key, "type" to 3), check = false).optInt("code")
    fun stream(id: Long): String {
        val item = request("/song/enhance/player/url/v1", mapOf("ids" to "[$id]", "level" to "standard", "encodeType" to "mp3"), eapi = true)
            .optJSONArray("data")?.optJSONObject(0) ?: throw IOException("歌曲暂时没有可用音源")
        if (!item.isNull("freeTrialInfo")) throw IOException("这首歌曲当前仅提供试听，请确认账号的播放权益")
        val url = item.optString("url").takeUnless { it.isBlank() || it == "null" }
            ?: throw IOException("这首歌曲暂不可播放，请确认账号权益或版权地区")
        val secure = url.replaceFirst("http://", "https://")
        require(URI(secure).scheme == "https") { "播放地址格式异常" }
        return secure
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
