package app.sona.music

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@UnstableApi
class UiRefreshTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun googleLoadingShapesAndPlaybackPressFeedback() {
        var playing by mutableStateOf(false)
        var taps = 0
        var cover = ""
        ui.runOnIdle {
            cover = androidx.lifecycle.ViewModelProvider(ui.activity)[MusicViewModel::class.java]
                .recent.firstOrNull()?.cover.orEmpty()
        }
        ui.activity.runOnUiThread {
            ui.activity.setContent {
                SonaTheme("dark") { _, _ -> Surface {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (column in 0..2) {
                                val index = row * 3 + column
                                if (index >= 8) { Spacer(Modifier.weight(1f)); continue }
                                val shape = artworkShape(index.toLong())
                                Box(Modifier.weight(1f).aspectRatio(1f)) {
                                    Artwork(cover, Modifier.fillMaxSize().padding(6.dp).clip(shape))
                                    CoverBufferingIndicator(true, index, shape)
                                }
                            }
                        }
                        PlaybackButton(playing, { playing = !playing; taps++ }, Modifier.size(108.dp, 76.dp), 76.dp)
                    }
                } }
            }
        }
        ui.onNodeWithContentDescription("播放").assertIsDisplayed()
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeBy(1000)
        ui.onAllNodesWithTag("cover-buffering").assertCountEquals(8)
        screenshot("google-loading-shapes")
        ui.onNodeWithContentDescription("播放").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(180)
        screenshot("play-button-held")
        ui.onNodeWithContentDescription("播放").performTouchInput { cancel() }
        ui.runOnIdle { assertEquals(0, taps) }
        ui.onNodeWithContentDescription("播放").performClick()
        ui.mainClock.advanceTimeBy(600)
        ui.runOnIdle { assertEquals(1, taps) }
        ui.onNodeWithContentDescription("暂停").assertIsDisplayed()
        ui.mainClock.autoAdvance = true
    }

    @Test fun lyricsResumeFiveSecondsAfterManualScrolling() {
        var position by mutableLongStateOf(5000L)
        val lines = (0..40).map { LyricLine(it * 1000L, "第 $it 行歌词") }
        ui.activity.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme { Surface { FollowingLyrics(lines, position, 1L) { position = it } } }
            }
        }
        ui.onNodeWithText("暂停跟随").assertDoesNotExist()
        val list = ui.onNodeWithTag("lyrics-list")
        fun state(value: String) = list.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, value))
        state("自动跟随")
        ui.mainClock.autoAdvance = false
        list.performTouchInput { swipeUp(durationMillis = 1200) }
        ui.mainClock.advanceTimeBy(1500)
        state("手动浏览")
        ui.runOnIdle { position = 6000L }
        ui.mainClock.advanceTimeBy(2000)
        state("手动浏览")
        list.performTouchInput { swipeUp(durationMillis = 1200) }
        ui.mainClock.advanceTimeBy(2000)
        state("手动浏览")
        ui.mainClock.advanceTimeBy(6000)
        state("自动跟随")
        ui.mainClock.advanceTimeBy(1500)
        ui.onNodeWithText("第 6 行歌词").assertIsDisplayed()
        ui.mainClock.autoAdvance = true
    }

    @Test fun bufferingStaysOnCoverWithoutMovingArtwork() {
        var buffering by mutableStateOf(false)
        var track by mutableLongStateOf(1L)
        ui.activity.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme(colorScheme = coverColorScheme(0xff007f97.toInt(), true)) {
                    Surface {
                        SongHero(Song(track, "光辉岁月", "Beyond", "光辉岁月"), 0, 301000,
                            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), buffering = buffering)
                    }
                }
            }
        }
        ui.onAllNodesWithText("光辉岁月").assertCountEquals(2)
        val bounds = ui.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        ui.runOnIdle { buffering = true }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithTag("cover-buffering").assertDoesNotExist()
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithTag("cover-buffering").assertIsDisplayed()
        assertEquals(bounds, ui.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot)
        screenshot("cover-buffering")
        ui.runOnIdle { buffering = false; track = 2L }
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithTag("cover-buffering").assertDoesNotExist()
        assertEquals(bounds, ui.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot)
        ui.mainClock.autoAdvance = true
    }

    @Test fun coverPalettesKeepReadableText() {
        for (dark in listOf(false, true)) {
            for (seed in listOf(0xff007f97, 0xffb52947, 0xff3f8b40, 0xffe5b738, 0xff888888)) {
                val scheme = coverColorScheme(seed.toInt(), dark)
                listOf(scheme.onSurface to scheme.surface, scheme.onSurfaceVariant to scheme.surface,
                    scheme.onPrimary to scheme.primary, scheme.onSecondaryContainer to scheme.secondaryContainer,
                    scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onTertiaryFixed to scheme.tertiaryFixedDim).forEach { (fg, bg) ->
                    assertTrue("Text contrast must remain readable", androidx.core.graphics.ColorUtils.calculateContrast(
                        fg.toArgb(), bg.toArgb()) >= 4.5)
                }
            }
        }
    }

    @Test fun homeKeepsThreeSectionsWithSparseAndPopulatedRecommendations() {
        var populated by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var refreshCount = 0
        var favoritesCount = 0
        val cover = "android.resource://app.sona.music/drawable/ic_music"
        ui.activity.runOnUiThread {
            ui.activity.setContent {
                SonaTheme(if (dark) "dark" else "light") { _, _ ->
                    Surface {
                        HomeContent(null, emptyList(), if (populated) (1L..6L).map { MusicList(it, "发现歌单 $it", cover) } else emptyList(),
                            false, rememberLazyListState(), { refreshCount++ }, {}, { favoritesCount++ }, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        ui.onNodeWithText("我喜欢的音乐").assertIsDisplayed()
        ui.onNodeWithText("我喜欢的音乐").performClick()
        ui.runOnIdle { assertEquals(1, favoritesCount) }
        ui.onNodeWithText("我的歌单").assertDoesNotExist()
        ui.onNodeWithText("发现歌单").assertDoesNotExist()
        ui.onNodeWithText("刷新推荐").assertDoesNotExist()
        ui.onNodeWithContentDescription("重新加载推荐").performClick()
        ui.runOnIdle { assertEquals(1, refreshCount) }
        ui.onNodeWithTag("home-refresh").performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.85f, durationMillis = 600) }
        ui.runOnIdle { assertTrue(refreshCount >= 2) }
        screenshot("home-sparse-light")
        ui.runOnIdle { populated = true; dark = true }
        ui.onNodeWithTag("home-sections").performScrollToNode(hasText("私人 FM"))
        ui.onNodeWithText("最近播放").assertIsDisplayed()
        ui.onNodeWithText("私人 FM").assertIsDisplayed()
        screenshot("home-populated-dark")
    }

    @Test fun playerActionsTitlesAndAccessibleSeeking() {
        var title by mutableStateOf("爱错 (Live)")
        var outputs = 0
        var queues = 0
        var lyrics = 0
        var seekPosition = -1L
        var shuffled by mutableStateOf(false)
        var liked by mutableStateOf(false)
        var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        ui.activity.runOnUiThread {
            ui.activity.setContent {
                SonaTheme("dark") { _, _ ->
                    Surface {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
                            PlayerTopActions({ outputs++ }, { queues++ })
                            SongHero(Song(1, title, "王力宏", "演唱会", duration = 243000), 84000, 243000, Modifier.weight(1f))
                            WaveSeekBar(84000, 243000, false, true, { seekPosition = it }, Modifier.fillMaxWidth())
                            PlayerModeBar(shuffled, repeat, liked, { mode ->
                                shuffled = mode == PlaybackMode.Shuffle
                                repeat = when (mode) { PlaybackMode.RepeatOne -> Player.REPEAT_MODE_ONE; PlaybackMode.RepeatAll -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
                            }, { lyrics++ }, { liked = !liked })
                        }
                    }
                }
            }
        }
        ui.onNodeWithText("爱错", substring = false).assertIsDisplayed()
        ui.onNodeWithText("Live").assertIsDisplayed()
        ui.onNodeWithText("演唱会").assertIsDisplayed()
        ui.onNodeWithContentDescription("媒体输出切换").performClick()
        ui.onNodeWithContentDescription("播放队列").performClick()
        ui.onNodeWithContentDescription("打开歌词").performClick()
        ui.runOnIdle { assertEquals(1, outputs); assertEquals(1, queues); assertEquals(1, lyrics) }
        ui.onNodeWithTag("wave-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        ui.runOnIdle { assertEquals(121500L, seekPosition) }
        ui.onNodeWithTag("wave-seek").performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(width * 0.2f, height / 2f),
                end = androidx.compose.ui.geometry.Offset(width * 0.7f, height / 2f), durationMillis = 400)
        }
        ui.runOnIdle { assertTrue("Dragging should seek to roughly 70 percent", seekPosition in 155000L..185000L) }
        listOf(PlaybackMode.RepeatOne, PlaybackMode.RepeatAll, PlaybackMode.Shuffle, PlaybackMode.Sequential).forEach { mode ->
            ui.onNodeWithContentDescription("播放模式").performClick()
            ui.onNodeWithText(mode.label).assertDoesNotExist()
            ui.runOnIdle {
                assertEquals(mode == PlaybackMode.Shuffle, shuffled)
                assertEquals(when (mode) {
                    PlaybackMode.RepeatOne -> Player.REPEAT_MODE_ONE
                    PlaybackMode.RepeatAll -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }, repeat)
            }
        }
        ui.onNodeWithContentDescription("喜欢").performClick()
        ui.runOnIdle { assertTrue(liked) }
        screenshot("player-live-title")
        ui.runOnIdle { title = "Everything Has Changed (Taylor's Version)" }
        ui.onNodeWithTag("player-title").assertTextEquals("Everything Has Changed (Taylor's Version)")
        screenshot("player-long-title")
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
