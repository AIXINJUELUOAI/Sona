package app.sona.music

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi

@UnstableApi
fun Song.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(key)
    .setUri(if (isLocal) localUri else "sona://song/$id")
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(cover.takeIf(String::isNotBlank)?.let(Uri::parse)).setDurationMs(duration)
        .setExtras(Bundle().apply { putLong("song_id", id); putString("local_uri", localUri)
            putLong("local_date_added", localDateAdded) }).build()).build()

@UnstableApi
fun songFromItem(item: MediaItem): Song {
    val metadata = item.mediaMetadata
    return Song(metadata.extras?.takeIf { it.containsKey("song_id") }?.getLong("song_id") ?: item.mediaId.toLongOrNull() ?: 0,
        metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
        metadata.albumTitle?.toString().orEmpty(), metadata.artworkUri?.toString().orEmpty(), metadata.durationMs ?: 0,
        metadata.extras?.getString("local_uri").orEmpty(), metadata.extras?.getLong("local_date_added") ?: 0)
}
