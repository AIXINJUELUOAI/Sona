package app.sona.music

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

@UnstableApi
internal class LocalLyricsReader(private val context: Context, private val settings: MusicSettings) {
    private val resolver get() = context.contentResolver

    suspend fun load(song: Song): LocalLyricContent {
        require(song.isLocal)
        settings.lyricFile(song.key).takeIf(String::isNotBlank)?.let {
            return readFile(Uri.parse(it), "手动选择的 LRC")
        }
        var embeddedFailure: Exception? = null
        try {
            readEmbedded(Uri.parse(song.localUri)).takeUnless { it.isEmpty }?.let { return it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { embeddedFailure = e }
        if (settings.lyricsTree.isNotBlank()) {
            findSidecar(song, Uri.parse(settings.lyricsTree))?.let { return readFile(it, "同名 LRC") }
        }
        if (embeddedFailure != null) throw IOException("音频标签读取未完成，可重试或手动选择 LRC 文件", embeddedFailure)
        return LocalLyricContent()
    }

    fun readFile(uri: Uri, source: String): LocalLyricContent {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MaxLyricBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, MaxLyricBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
            ?: throw IOException("歌词文件未打开，请重新选择")
        val result = localLyricContent(decodeLyricBytes(bytes), source)
        require(!result.isEmpty) { "歌词文件没有可显示的内容" }
        return result
    }

    private suspend fun readEmbedded(uri: Uri): LocalLyricContent {
        require(uri.scheme == "content") { "本地音频地址异常" }
        // Media3's WAV extractor reads the audio stream but omits trailing RIFF ID3 chunks.
        resolver.openInputStream(uri)?.use(::readWavTags)?.let { return it.lyrics }
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
            val groups = try {
                withTimeout(12_000) {
                    suspendCancellableCoroutine<TrackGroupArray> { continuation ->
                        val future = retriever.retrieveTrackGroups()
                        continuation.invokeOnCancellation { future.cancel(false) }
                        future.addListener({
                            try { continuation.resume(future.get()) }
                            catch (e: Exception) { continuation.resumeWithException(e) }
                        }, Executor { it.run() })
                    }
                }
            } catch (e: TimeoutCancellationException) { throw IOException("读取音频标签超时", e) }
            val candidates = mutableListOf<LocalLyricContent>()
            for (group in 0 until groups.length) for (track in 0 until groups[group].length) {
                val metadata = groups[group].getFormat(track).metadata ?: continue
                for (index in 0 until metadata.length()) {
                    val entry = metadata[index]
                    val result = when (entry) {
                        is BinaryFrame -> id3LyricContent(entry.id, entry.data)
                        is VorbisComment -> if (isLyricTag(entry.key)) localLyricContent(entry.value, "内嵌歌词") else null
                        is TextInformationFrame -> if (entry.id == "USLT" || entry.id == "ULT" ||
                            ((entry.id == "TXXX" || entry.id == "TXX") && isLyricTag(entry.description.orEmpty())))
                            localLyricContent(entry.values.joinToString("\n"), "内嵌歌词") else null
                        is InternalFrame -> if (isLyricTag(entry.description)) localLyricContent(entry.text, "内嵌歌词") else null
                        else -> null
                    }
                    if (result != null && !result.isEmpty) candidates += result
                }
            }
            return candidates.firstOrNull { it.lines.isNotEmpty() } ?: candidates.firstOrNull() ?: LocalLyricContent()
        }
    }

    private fun isLyricTag(key: String) = key.uppercase(java.util.Locale.ROOT).replace("_", "").replace(" ", "") in
        setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")

    private fun findSidecar(song: Song, tree: Uri): Uri? {
        require(tree.authority == "com.android.externalstorage.documents") { "请选择手机或 SD 卡中的音乐文件夹" }
        val columns = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.VOLUME_NAME)
        val segments = resolver.query(Uri.parse(song.localUri), columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            lyricRelativeSegments(DocumentsContract.getTreeDocumentId(tree), cursor.getString(2).orEmpty(),
                cursor.getString(1).orEmpty(), cursor.getString(0).orEmpty())
        } ?: return null
        var documentId = DocumentsContract.getTreeDocumentId(tree)
        for ((index, name) in segments.withIndex()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val matches = mutableListOf<Pair<String, String>>()
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) if (name.equals(cursor.getString(1), ignoreCase = true)) {
                    matches += cursor.getString(0) to cursor.getString(2)
                }
            } ?: throw IOException("音乐文件夹读取未完成，请重新选择文件夹")
            if (matches.isEmpty()) return null
            require(matches.size == 1) { "找到多个同名歌词或目录，请手动选择 LRC 文件" }
            val match = matches.single()
            val directory = match.second == DocumentsContract.Document.MIME_TYPE_DIR
            if ((index < segments.lastIndex) != directory) return null
            documentId = match.first
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    }
}
