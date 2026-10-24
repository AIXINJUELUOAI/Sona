package app.sona.music

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@UnstableApi
class PlayerJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun searchPlaybackAndSharedPlayerRemainConnected() {
        lateinit var model: MusicViewModel
        ui.runOnIdle { model = ViewModelProvider(ui.activity)[MusicViewModel::class.java] }
        ui.runOnIdle { model.setAppearance("light"); model.playerExpanded = false; model.lyricsVisible = false; model.queueVisible = false }
        ui.waitUntil(30_000) { !model.busy }
        screenshot("home-light")
        ui.onNodeWithContentDescription("搜索").performClick()
        ui.waitUntil { model.page == Page.Search }
        ui.onNode(hasSetTextAction()).performTextInput("想去海边")
        ui.onNode(hasSetTextAction()).performImeAction()
        ui.waitUntil(45_000) { !model.searchLoading && model.results.isNotEmpty() }
        ui.waitForIdle()
        screenshot("search-light")
        val firstSong = model.results.first()
        assertTrue("MediaController must connect before playback", model.connected)
        ui.onNodeWithTag("song-row-${firstSong.id}").performClick()
        try {
            ui.waitUntil(40_000) { model.current?.id == firstSong.id && model.position > 0 }
        } catch (e: Throwable) {
            screenshot("playback-failure")
            throw AssertionError("connected=${model.connected}; queued=${model.queue.size}; current=${model.current?.id}; expected=${firstSong.id}; playing=${model.playing}; buffering=${model.buffering}; position=${model.position}; error=${model.error}", e)
        }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.waitUntil { model.page == Page.Home }
        ui.waitForIdle()
        ui.onNodeWithText("每日推荐").assertIsDisplayed()
        screenshot("home-mini-player")
        ui.mainClock.autoAdvance = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.playerExpanded = true }
        ui.mainClock.advanceTimeBy(80)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.playerExpanded = false }
        ui.mainClock.autoAdvance = true
        ui.waitForIdle()
        assertEquals(firstSong.id, model.current?.id)
        ui.onNodeWithText(firstSong.title).performClick()
        ui.waitUntil(10_000) { model.playerExpanded }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("收起播放器").assertIsDisplayed()
        ui.runOnIdle { model.seek(model.duration / 3) }
        ui.waitUntil(15_000) { !model.buffering && model.position > model.duration / 4 }
        screenshot("player-light")
        ui.runOnIdle { model.setPlaybackMode(PlaybackMode.Sequential) }
        listOf(PlaybackMode.RepeatOne, PlaybackMode.RepeatAll, PlaybackMode.Shuffle, PlaybackMode.Sequential).forEach { mode ->
            ui.onNodeWithContentDescription("播放模式").performClick()
            ui.onNodeWithText(mode.label).assertDoesNotExist()
            ui.waitUntil(5_000) {
                model.shuffled == (mode == PlaybackMode.Shuffle) && model.repeat == when (mode) {
                    PlaybackMode.RepeatOne -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    PlaybackMode.RepeatAll -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                }
            }
        }
        val before = model.current?.id
        ui.onNodeWithContentDescription("收起播放器").performClick()
        ui.waitForIdle()
        assertEquals(before, model.current?.id)
        ui.onNodeWithText(firstSong.title).performClick()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("媒体输出切换").assertIsDisplayed()
        ui.onNodeWithContentDescription("打开歌词").performClick()
        ui.waitUntil(25_000) { !model.lyricsLoading }
        ui.waitForIdle()
        screenshot("lyrics-light")
        ui.onNodeWithContentDescription("播放队列").performClick()
        ui.waitForIdle()
        ui.onNodeWithText("接下来播放").assertIsDisplayed()
        screenshot("queue-light")
        ui.runOnIdle { model.queueVisible = false; model.lyricsVisible = false; model.setAppearance("dark") }
        ui.waitForIdle()
        screenshot("player-dark")
        ui.runOnIdle { model.playerExpanded = false }
        ui.waitForIdle()
        ui.onNodeWithText("每日推荐").assertIsDisplayed()
        screenshot("home-dark")
        assertTrue(model.playing)
        assertEquals(before, model.current?.id)
        ui.runOnIdle { model.playerExpanded = true }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("媒体输出切换").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitUntil(10_000) { automation.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" }
        screenshot("system-media-output")
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitForIdle()
        assertEquals(before, model.current?.id)
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Give the Android render thread two or more frames after the Compose test clock settles.
        android.os.SystemClock.sleep(200)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
