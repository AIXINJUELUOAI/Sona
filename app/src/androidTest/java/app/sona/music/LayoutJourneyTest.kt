package app.sona.music

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises the real app/session without requiring the discovery or audio servers. */
@UnstableApi
class LayoutJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun fullLayoutAndExclusiveModesAcrossTitles() {
        lateinit var model: MusicViewModel
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        ui.runOnIdle {
            model = ViewModelProvider(ui.activity)[MusicViewModel::class.java]
            future = MediaController.Builder(ui.activity, SessionToken(ui.activity,
                ComponentName(ui.activity, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        ui.waitUntil(10_000) { model.connected }
        try {
            val cover = model.recent.firstOrNull { it.cover.isNotBlank() }?.cover
                ?: "android.resource://app.sona.music/drawable/ic_music"
            fun showSong(id: Long, title: String) {
                ui.runOnIdle {
                    controller.pause()
                    controller.setMediaItem(MediaItem.Builder().setMediaId(id.toString())
                        .setUri("https://example.invalid/layout-only")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("王力宏")
                            .setAlbumTitle("演唱会").setArtworkUri(Uri.parse(cover)).setDurationMs(243000).build()).build())
                    model.playerExpanded = true
                    model.lyricsVisible = false
                    model.queueVisible = false
                }
                ui.waitUntil(5_000) { model.current?.id == id }
                ui.waitForIdle()
            }
            ui.runOnIdle { model.setAppearance("light") }
            showSong(-101, "想去海边")
            screenshot("player-light")
            ui.onNodeWithContentDescription("播放模式").assertIsDisplayed()
            ui.onNodeWithContentDescription("打开歌词").assertIsDisplayed()
            screenshot("player-controls")
            ui.runOnIdle { model.setPlaybackMode(PlaybackMode.Sequential) }
            listOf(PlaybackMode.RepeatOne, PlaybackMode.RepeatAll, PlaybackMode.Shuffle, PlaybackMode.Sequential).forEach { mode ->
                ui.onNodeWithContentDescription("播放模式").performClick()
                if (mode == PlaybackMode.Shuffle) screenshot("mode-cycle")
                ui.onNodeWithText(mode.label).assertDoesNotExist()
                ui.waitUntil(5_000) {
                    model.shuffled == (mode == PlaybackMode.Shuffle) && model.repeat == when (mode) {
                        PlaybackMode.RepeatOne -> Player.REPEAT_MODE_ONE
                        PlaybackMode.RepeatAll -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }
            ui.onNodeWithContentDescription("打开歌词").performClick()
            ui.waitUntil { model.lyricsVisible }
            ui.onNodeWithContentDescription("返回播放页").performClick()
            ui.waitUntil { !model.lyricsVisible }
            ui.runOnIdle { model.setAppearance("dark") }
            showSong(-102, "爱错 (Live)")
            ui.onNodeWithText("Live").assertIsDisplayed()
            ui.onNodeWithText("演唱会").assertIsDisplayed()
            screenshot("player-live-dark")
            showSong(-103, "Everything Has Changed (Taylor's Version)")
            ui.onNodeWithTag("player-title").assertTextEquals("Everything Has Changed (Taylor's Version)")
            ui.onNodeWithContentDescription("播放模式").assertIsDisplayed()
            screenshot("player-long-dark")
            ui.onNodeWithContentDescription("收起播放器").performClick()
            ui.runOnIdle { model.page = Page.Home; model.setAppearance("light") }
            ui.onNodeWithTag("floating-player").assertIsDisplayed()
            ui.onNodeWithText("首页").assertDoesNotExist()
            ui.onAllNodesWithText("资料库", useUnmergedTree = true).onFirst().assertExists()
            screenshot("home-dock")
            ui.onNodeWithTag("home-sections").performScrollToNode(hasText("私人 FM"))
            ui.onNodeWithText("私人 FM").assertIsDisplayed()
            ui.onNodeWithContentDescription("搜索").performClick()
            ui.waitUntil { model.page == Page.Search }
            screenshot("search")
            ui.onNodeWithContentDescription("返回").performClick()
            ui.waitUntil { model.page == Page.Home }
            ui.onNodeWithContentDescription("打开菜单").performClick()
            ui.onNodeWithText("外观").assertIsDisplayed()
            screenshot("drawer")
            ui.onNodeWithText("资料库", useUnmergedTree = true).performClick()
            ui.waitUntil { model.page == Page.Library }
            ui.onNodeWithContentDescription("返回").performClick()
            ui.waitUntil { model.page == Page.Home }
        } finally {
            ui.runOnIdle { controller.clearMediaItems(); controller.release() }
        }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200)
        val label = InstrumentationRegistry.getArguments().getString("layoutLabel", "default")
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "layout-$label-$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
