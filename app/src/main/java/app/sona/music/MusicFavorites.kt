package app.sona.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One account-scoped state and write path for the app and the system media controls. */
internal class MusicFavorites(private val app: MusicApplication) {
    var profile by mutableStateOf<Profile?>(null)
        private set
    var cloud by mutableStateOf(emptySet<Long>())
        private set
    var local by mutableStateOf(app.settings.localFavorites())
    var changing by mutableStateOf(false)
        private set
    private var loadedFor: Long? = null
    private var generation = 0
    private val mutex = Mutex()

    fun updateProfile(user: Profile?) {
        if (user == null || profile?.id != user.id) { generation++; loadedFor = null; cloud = emptySet() }
        profile = user
    }
    fun replaceCloud(ids: Set<Long>) { cloud = ids; loadedFor = profile?.id }
    fun isLiked(song: Song) = if (song.isLocal) song.key in local else song.id in cloud

    suspend fun refresh(force: Boolean = true) = mutex.withLock {
        if (!force && profile != null && loadedFor == profile?.id) return@withLock
        val start = generation
        val user = withContext(Dispatchers.IO) { if (app.settings.cookie.isBlank()) null else app.api.profile() }
        if (generation != start) return@withLock
        // Account information remains usable if loading the favorite list fails separately.
        updateProfile(user)
        if (user == null) { replaceCloud(emptySet()); return@withLock }
        val accountGeneration = generation
        val ids = withContext(Dispatchers.IO) { app.api.liked(user.id) }
        if (generation == accountGeneration && profile?.id == user.id) replaceCloud(ids)
    }

    suspend fun refreshIds(user: Profile): Set<Long> = mutex.withLock {
        val start = generation
        val ids = withContext(Dispatchers.IO) { app.api.liked(user.id) }
        if (generation == start && profile?.id == user.id) replaceCloud(ids)
        cloud
    }

    suspend fun toggle(song: Song): Boolean = mutex.withLock {
        changing = true
        try {
            if (song.isLocal) {
                val next = !isLiked(song)
                app.settings.setLocalFavorite(song, next)
                local = app.settings.localFavorites()
                return@withLock next
            }
            check(app.settings.cookie.isNotBlank()) { "请先在 Sona 中登录网易云账号" }
            var start = generation
            val user = profile ?: withContext(Dispatchers.IO) { app.api.profile() }
                ?: error("请先在 Sona 中登录网易云账号")
            check(generation == start) { "账号已切换，请重试" }
            updateProfile(user)
            start = generation
            if (loadedFor != user.id) {
                val ids = withContext(Dispatchers.IO) { app.api.liked(user.id) }
                check(generation == start && profile?.id == user.id) { "账号已切换，请重试" }
                replaceCloud(ids)
            }
            val next = song.id !in cloud
            withContext(Dispatchers.IO) { app.api.like(song.id, next, user.id) }
            check(generation == start && profile?.id == user.id) { "账号已切换，请重试" }
            replaceCloud(if (next) cloud + song.id else cloud - song.id)
            next
        } finally { changing = false }
    }
}
