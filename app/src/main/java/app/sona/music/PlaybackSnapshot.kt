package app.sona.music

import org.json.JSONArray
import org.json.JSONObject

/** A playlist stores stable song identities, never temporary streaming URLs. */
internal data class PlaybackSnapshot(
    val songs: List<Song>, val index: Int, val position: Long,
    val shuffle: Boolean, val repeat: Int, val order: List<Int>
) {
    fun retaining(keep: (Song) -> Boolean): PlaybackSnapshot? {
        val retained = songs.indices.filter { keep(songs[it]) }
        if (retained.isEmpty()) return null
        val traversal = order.takeIf { it.sorted() == songs.indices.toList() } ?: songs.indices.toList()
        val chosen = index.takeIf { it in retained }
            ?: traversal.drop((traversal.indexOf(index)).coerceAtLeast(0)).firstOrNull { it in retained }
            ?: retained.first()
        val song = songs[chosen]
        val restoredPosition = if (chosen == index) position.coerceAtLeast(0) else 0
        return copy(songs = retained.map(songs::get), index = retained.indexOf(chosen),
            position = if (song.duration > 0) restoredPosition.coerceAtMost((song.duration - 1).coerceAtLeast(0)) else restoredPosition,
            repeat = repeat.takeIf { it in 0..2 } ?: 0,
            order = traversal.filter { it in retained }.map(retained::indexOf))
    }

    fun encode(): String = JSONObject().put("version", 1)
        .put("songs", JSONArray(songs.map(::songJson))).put("index", index).put("position", position)
        .put("shuffle", shuffle).put("repeat", repeat).put("order", JSONArray(order)).toString()

    companion object {
        fun decode(value: String): PlaybackSnapshot? = runCatching {
            val json = JSONObject(value)
            require(json.getInt("version") == 1)
            val songs = json.getJSONArray("songs").objects().map(::parseSong)
            val order = json.getJSONArray("order")
            PlaybackSnapshot(songs, json.getInt("index"), json.getLong("position"),
                json.getBoolean("shuffle"), json.getInt("repeat"), (0 until order.length()).map(order::getInt))
                .retaining { it.isLocal || it.id > 0 }
        }.getOrNull()
    }
}

internal fun songJson(song: Song): JSONObject = JSONObject().put("id", song.id).put("name", song.title)
    .put("dt", song.duration).put("localUri", song.localUri).put("localDateAdded", song.localDateAdded)
    .put("ar", JSONArray().put(JSONObject().put("name", song.artist)))
    .put("al", JSONObject().put("name", song.album).put("picUrl", song.cover))
