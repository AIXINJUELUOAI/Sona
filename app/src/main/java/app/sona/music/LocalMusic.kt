package app.sona.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

fun canReadLocalMusic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

/** MediaStore owns indexing and storage access; no raw paths or all-files permission. */
@UnstableApi
suspend fun scanLocalMusic(context: Context, force: Boolean = false): List<Song> {
    check(canReadLocalMusic(context)) { "请允许读取音乐后再扫描" }
    val media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED)
    val songs = mutableListOf<Song>()
    val cursor = context.contentResolver.query(media, columns,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.IS_PENDING} = 0", null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC") ?: error("本地音乐列表暂时不可用，请重试")
    cursor.use {
        while (it.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val id = it.getLong(0)
            val albumId = it.getLong(4)
            val artist = it.getString(2).orEmpty().takeUnless { name -> name == "<unknown>" || name.isBlank() } ?: "未知歌手"
            val song = Song(-id, it.getString(1).orEmpty().ifBlank { it.getString(6).orEmpty().substringBeforeLast('.') },
                artist, it.getString(3).orEmpty().takeUnless { name -> name == "<unknown>" }.orEmpty(),
                if (albumId > 0) "content://media/external/audio/albumart/$albumId" else "", it.getLong(5),
                ContentUris.withAppendedId(media, id).toString(), it.getLong(9))
            songs += enrichWavSong(context, song, it.getString(6).orEmpty(), it.getLong(7), it.getLong(8), force)
        }
    }
    return songs.distinctBy { it.key }
}

/** Re-index known audio paths and discover files in accessible Music/Download folders. */
@Suppress("DEPRECATION")
internal suspend fun rescanLocalFiles(context: Context): Int {
    val paths = linkedSetOf<String>()
    context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media.DATA), "${MediaStore.Audio.Media.IS_PENDING} = 0", null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
        }
    }
    val roots = (listOf(Environment.getExternalStorageDirectory()) + context.getExternalFilesDirs(null)
        .mapNotNull { it?.parentFile?.parentFile?.parentFile?.parentFile }).distinct()
    val extensions = setOf("mp3", "flac", "wav", "wave", "m4a", "m4b", "aac", "ogg", "opus", "amr", "aiff", "aif")
    for (root in roots) for (name in listOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS)) {
        for (file in File(root, name).walkTopDown().onFail { _, _ -> }) {
            currentCoroutineContext().ensureActive()
            if (file.isFile && file.extension.lowercase(java.util.Locale.ROOT) in extensions) paths += file.absolutePath
        }
    }
    if (paths.isEmpty()) return 0
    return try { withTimeout(90_000) {
        suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(paths.size)
            val failed = AtomicInteger()
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { path, uri ->
                if (uri == null && path != null && File(path).isFile) failed.incrementAndGet()
                if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(failed.get())
            }
        }
    } } catch (e: TimeoutCancellationException) { throw java.io.IOException("系统音乐库重新索引超时，请重试", e) }
}
