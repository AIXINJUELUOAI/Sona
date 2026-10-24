package app.sona.music

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NeteaseLiveSmokeTest {
    @Test fun anonymousReadOnlyEndpoints() {
        assumeTrue(System.getenv("SONA_LIVE_SMOKE") == "1")
        var cookies = ""
        val api = NeteaseApi({ cookies }, { cookies = it })
        val songs = api.search("想去海边")
        assertTrue("Search should return real songs", songs.isNotEmpty())
        println("search_count=${songs.size}")
        val lists = api.discovery()
        assertTrue("Discovery should return real playlists", lists.isNotEmpty())
        println("playlist_count=${lists.size}")
        val tracks = api.tracks(lists.first().id)
        assertTrue(tracks.isNotEmpty())
        println("playlist_tracks=${tracks.size}")
        val lyrics = api.lyrics(songs.first().id)
        println("lyric_lines=${lyrics.size}")
        println("qr_key_created=${api.qrKey().isNotBlank()}")
        val stream = api.stream(songs.first().id)
        assertTrue(stream.startsWith("https://"))
        println("stream_url_resolved=true")
    }
}
