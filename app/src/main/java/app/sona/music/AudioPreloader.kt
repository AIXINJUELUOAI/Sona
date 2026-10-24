package app.sona.music

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import java.util.concurrent.Executors

@UnstableApi
internal class AudioPreloader(cache: Cache, upstream: DataSource.Factory) {
    // Cache sits outside URL resolution: sona://song/<id> remains the stable cache key.
    // Playback never waits for a prefetch lock and does not copy local files into the cache.
    val playbackSource = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).setCacheWriteDataSinkFactory(null)
    private val writerSource = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
    private val worker = Executors.newSingleThreadExecutor()
    private var targets = emptyList<Uri>()
    @Volatile private var generation = 0
    @Volatile private var writer: CacheWriter? = null

    // Called on the player's application thread; the worker only receives immutable URIs.
    fun update(player: Player) {
        val next = mutableListOf<Uri>()
        val timeline = player.currentTimeline
        if (player.isPlaying && !timeline.isEmpty) {
            var index = player.currentMediaItemIndex
            val visited = mutableSetOf(index)
            val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
            for (step in 0 until 3) {
                index = timeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled)
                if (index == C.INDEX_UNSET || !visited.add(index)) break
                val uri = player.getMediaItemAt(index).localConfiguration?.uri
                if (uri?.scheme == "sona") next += uri
            }
        }
        val upcoming = next.distinct()
        if (upcoming == targets) return
        targets = upcoming
        val token = ++generation
        writer?.cancel()
        if (upcoming.isEmpty()) return
        worker.execute {
            for (uri in upcoming) {
                if (token != generation) break
                val task = CacheWriter(writerSource.createDataSource(), DataSpec.Builder().setUri(uri).build(), null, null)
                writer = task
                try {
                    if (token != generation) break
                    task.cache()
                } catch (e: Exception) {
                    // A failed prefetch must not interrupt playback; normal playback can retry upstream.
                    if (token == generation) Log.w("AudioPreloader", "Prefetch skipped for $uri", e)
                } finally {
                    writer = null
                }
            }
        }
    }

    fun close() {
        generation++
        writer?.cancel()
        worker.shutdown()
    }
}
