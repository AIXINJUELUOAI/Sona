package app.sona.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicLogicTest {
    @Test fun playerTitlesKeepVersionSuffixesAndEnglishWordsIntact() {
        assertEquals(PlayerTitle("爱错", "Live"), playerTitle("爱错 (Live)"))
        assertEquals(PlayerTitle("想去海边", "现场版"), playerTitle("想去海边（现场版）"))
        assertTrue(playerTitle("想去海边").isShortCjk)
        assertFalse(playerTitle("爱错 Live").isShortCjk)
        assertEquals("Everything Has Changed (Taylor's Version)", playerTitle("Everything Has Changed (Taylor's Version)").main)
        assertEquals("Don't Go (Stay)", playerTitle("Don't Go (Stay)").main)
    }
    @Test fun lyricsHandleMultipleTagsOffsetsAndTranslations() {
        val lyrics = parseLyrics("[ar:Artist]\n[offset:-100]\n[00:01.25][01:02.003]同一句\n[00:04]下一句", "[offset:-100]\n[00:01.250]Translation")
        assertEquals(listOf(1150L, 3900L, 61903L), lyrics.map { it.timeMs })
        assertEquals("Translation", lyrics[0].translation)
        assertEquals(-1, activeLyric(lyrics, 0))
        assertEquals(0, activeLyric(lyrics, 1150))
        assertEquals(2, activeLyric(lyrics, 999999))
    }
    @Test fun songAcceptsBothNeteaseResponseFormats() {
        val legacy = parseSong(JSONObject("""{"id":123,"name":"Song","artists":[{"name":"A"},{"name":"B"}],"album":{"name":"Album","picUrl":"http://example.com/a.jpg"},"duration":123000}"""))
        assertEquals("A / B", legacy.artist)
        assertEquals("https://example.com/a.jpg", legacy.cover)
        assertEquals(123000L, legacy.duration)
        val modern = parseSong(JSONObject("""{"id":123,"name":"Song","ar":[{"name":"A"}],"al":{"name":"Album"},"dt":456000}"""))
        assertEquals(456000L, modern.duration)
        assertEquals("7:36", formatTime(modern.duration))
        assertEquals("0:00", formatTime(-1))
    }
    @Test fun protocolProducesExpectedFieldShapes() {
        val data = NeteaseProtocol.weapi("{\"s\":\"sona\"}", "0123456789abcdef")
        assertEquals(256, data.getValue("encSecKey").length)
        assertTrue(data.getValue("params").isNotBlank())
        assertEquals(data, NeteaseProtocol.weapi("{\"s\":\"sona\"}", "0123456789abcdef"))
        val eapi = NeteaseProtocol.eapi("/api/song/lyric", "{\"id\":1}")
        val raw = eapi.getValue("params").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(NeteaseProtocol.decodeEapi(raw).startsWith("/api/song/lyric-36cd479b6b5-{\"id\":1}-36cd479b6b5-"))
    }
}
