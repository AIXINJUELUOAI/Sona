package app.sona.music

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MusicApplication : Application() {
    internal val audioCache by lazy {
        SimpleCache(File(cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(this))
    }
    internal val output by lazy { PlaybackOutput(this) }
    internal val floatingLyrics by lazy { FloatingLyricsController(this) }
    internal val favorites by lazy { MusicFavorites(this) }
    val settings by lazy { MusicSettings(this) }
    val api by lazy { NeteaseApi({ settings.cookie }, { settings.cookie = it }) }
}
