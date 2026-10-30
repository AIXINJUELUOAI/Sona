package app.sona.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MusicSettings(context: Context) {
    private val prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)
    internal var floatingLyrics: FloatingLyricsConfig
        get() = FloatingLyricsConfig.decode(prefs.getString("floating_lyrics", "").orEmpty())
        set(value) { prefs.edit().putString("floating_lyrics", value.bounded().encode()).apply() }
    private val recentState = MutableStateFlow(recentSongs())
    internal val recentSongsState = recentState.asStateFlow()
    internal val listeningHistory = ListeningHistory(context,
        recentState.value.isNotEmpty() || listeningStats().isNotEmpty())
    var localSort: LocalSongSort
        get() = LocalSongSort.entries.firstOrNull { it.name == prefs.getString("local_sort", null) } ?: LocalSongSort.Title
        set(value) { prefs.edit().putString("local_sort", value.name).apply() }
    var localSortDescending: Boolean
        get() = prefs.getBoolean("local_sort_descending", false)
        set(value) { prefs.edit().putBoolean("local_sort_descending", value).apply() }
    internal fun playbackSnapshot(): PlaybackSnapshot? = PlaybackSnapshot.decode(prefs.getString("playback", "").orEmpty())
    // The playback service calls this on its serial I/O executor, including its final save.
    @android.annotation.SuppressLint("ApplySharedPref")
    internal fun savePlayback(snapshot: PlaybackSnapshot?) {
        val edit = prefs.edit()
        if (snapshot == null) edit.remove("playback") else edit.putString("playback", snapshot.encode())
        if (!edit.commit()) android.util.Log.w("SonaPlayback", "Playback snapshot persistence failed")
    }
    var lyricsTree: String
        get() = prefs.getString("lyrics_tree", "").orEmpty()
        set(value) { prefs.edit().putString("lyrics_tree", value).apply() }
    fun lyricFile(songKey: String): String = prefs.getString("lyric_file:$songKey", "").orEmpty()
    fun setLyricFile(songKey: String, uri: String?) {
        if (uri == null) prefs.edit().remove("lyric_file:$songKey").apply()
        else prefs.edit().putString("lyric_file:$songKey", uri).apply()
    }
    var theme: String
        get() = prefs.getString("theme", "dark").orEmpty()
        set(value) { prefs.edit().putString("theme", value).apply() }
    var playerStyleMode: PlayerStyleMode
        get() = PlayerStyleMode.entries.firstOrNull { it.name == prefs.getString("player_style_mode", null) } ?: PlayerStyleMode.Default
        set(value) { prefs.edit().putString("player_style_mode", value.name).apply() }
    // fixed / perSong / cycle: how the bold player picks its cover silhouette.
    var coverShapeMode: String
        get() = prefs.getString("cover_shape_mode", "perSong").orEmpty()
        set(value) { prefs.edit().putString("cover_shape_mode", value).apply() }
    var coverFixedShape: Int
        get() = prefs.getInt("cover_fixed_shape", 1)
        set(value) { prefs.edit().putInt("cover_fixed_shape", value).apply() }
    var lastBoldPlayer: Boolean
        get() = prefs.getBoolean("player_style_bold", false)
        set(value) { prefs.edit().putBoolean("player_style_bold", value).apply() }
    // Local identity for people who never sign in to NetEase.
    var localName: String
        get() = prefs.getString("local_name", "").orEmpty()
        set(value) { prefs.edit().putString("local_name", value).apply() }
    var localAvatar: String
        get() = prefs.getString("local_avatar", "").orEmpty()
        set(value) { prefs.edit().putString("local_avatar", value).apply() }
    // The local daily mix is fixed for the day it was generated.
    var localDailyDate: String
        get() = prefs.getString("local_daily_date", "").orEmpty()
        set(value) { prefs.edit().putString("local_daily_date", value).apply() }
    var localDailyKeys: List<String>
        get() = runCatching {
            val array = JSONArray(prefs.getString("local_daily_keys", "[]"))
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
        set(value) { prefs.edit().putString("local_daily_keys", JSONArray(value).toString()).apply() }
    @Volatile private var cachedCookie: String? = null
    var cookie: String
        @Synchronized get() {
            cachedCookie?.let { return it }
            val stored = prefs.getString("session", null) ?: return ""
            return runCatching {
                val parts = stored.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8).also { cachedCookie = it }
            }.getOrElse { clearSession(); "" }
        }
        @Synchronized set(value) {
            if (value == cachedCookie) return
            if (value.isBlank()) { clearSession(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("session", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
            cachedCookie = value
        }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("sona.session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("sona.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun clearSession() { cachedCookie = ""; prefs.edit().remove("session").apply() }
    fun searches(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("searches", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
    fun rememberSearch(value: String) {
        prefs.edit().putString("searches", JSONArray((listOf(value) + searches()).distinct().take(12)).toString()).apply()
    }
    fun clearSearches() { prefs.edit().remove("searches").apply() }
    fun recentSongs(): List<Song> = runCatching {
        JSONArray(prefs.getString("recent", "[]")).objects().map(::parseSong)
    }.getOrDefault(emptyList())
    @Synchronized fun rememberSong(song: Song) {
        val songs = (listOf(song) + recentSongs()).distinctBy { it.key }.take(200)
        val json = JSONArray(songs.map(::songJson))
        prefs.edit().putString("recent", json.toString()).apply()
        recentState.value = songs
    }
    fun localFavorites(): Set<String> = prefs.getStringSet("local_favorites", emptySet()).orEmpty().toSet()
    fun setLocalFavorite(song: Song, liked: Boolean) {
        val next = if (liked) localFavorites() + song.key else localFavorites() - song.key
        prefs.edit().putStringSet("local_favorites", next).apply()
    }
    @Synchronized fun listeningStats(): Map<String, ListeningStats> = runCatching {
        JSONArray(prefs.getString("listening_stats", "[]")).objects().associate {
            it.getString("key") to ListeningStats(it.optInt("plays"), it.optInt("skips"), it.optLong("last"))
        }
    }.getOrDefault(emptyMap())
    @Synchronized fun recordListening(song: Song, skipped: Boolean) {
        val values = listeningStats().toMutableMap()
        val old = values[song.key] ?: ListeningStats()
        values[song.key] = old.copy(plays = (old.plays + if (skipped) 0 else 1).coerceAtMost(10000),
            skips = (old.skips + if (skipped) 1 else 0).coerceAtMost(10000), lastPlayed = System.currentTimeMillis())
        val json = JSONArray(values.entries.sortedByDescending { it.value.lastPlayed }.take(1000).map { (key, s) ->
            org.json.JSONObject().put("key", key).put("plays", s.plays).put("skips", s.skips).put("last", s.lastPlayed)
        })
        prefs.edit().putString("listening_stats", json.toString()).apply()
    }
}
