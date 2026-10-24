package app.sona.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

@UnstableApi
internal fun enrichWavSong(context: Context, song: Song, filename: String, modified: Long, size: Long, force: Boolean = false): Song {
    if (filename.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in setOf("wav", "wave")) return song
    val directory = File(context.cacheDir, "wav-tags").apply { mkdirs() }
    val key = MessageDigest.getInstance("SHA-256").digest("v2:${song.key}:$modified:$size".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val index = File(directory, "$key.json")
    if (!force && index.isFile) runCatching {
        val json = JSONObject(index.readText())
        val coverName = json.optString("artwork").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val cover = coverName?.let { File(directory, "$it.art") }
        if (coverName == null || cover?.isFile == true) return song.withWavTags(
            WavTags(json.optString("title"), json.optString("artist"), json.optString("album")),
            cover?.let { Uri.fromFile(it).toString() }.orEmpty())
    }
    // A single unreadable file must not abort the rest of the local library scan.
    return runCatching {
        val tags = context.contentResolver.openInputStream(Uri.parse(song.localUri))?.use(::readWavTags) ?: return song
        val cover = tags.artwork?.let { bytes ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            File(directory, "$hash.art").apply { if (!isFile) writeBytes(bytes) }
        }
        index.writeText(JSONObject().put("title", tags.title).put("artist", tags.artist).put("album", tags.album)
            .put("artwork", cover?.nameWithoutExtension.orEmpty()).toString())
        song.withWavTags(tags, cover?.let { Uri.fromFile(it).toString() }.orEmpty())
    }.getOrDefault(song)
}
