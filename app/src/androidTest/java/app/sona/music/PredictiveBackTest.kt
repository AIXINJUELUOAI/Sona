package app.sona.music

import android.content.ComponentName
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Uses metadata only: no audio playback, account changes or remote favorites. */
@UnstableApi
class PredictiveBackTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun model(): MusicViewModel {
        lateinit var model: MusicViewModel
        ui.runOnIdle { model = ViewModelProvider(ui.activity)[MusicViewModel::class.java] }
        return model
    }

    private fun dragBack(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) {
        ui.runOnIdle {
            ui.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, edge))
        }
        ui.runOnIdle {
            ui.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 400f, progress, edge))
        }
        ui.waitForIdle()
    }

    private fun cancelBack() {
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        ui.waitForIdle()
    }

    private fun commitBack() {
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun width(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    @Test fun backDuringPlayerEntranceHoldsTheVisibleFrameAndCanCancelOrCommit() {
        val model = model()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        ui.runOnIdle {
            future = MediaController.Builder(ui.activity, SessionToken(ui.activity,
                ComponentName(ui.activity, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        fun frame(name: String): Bitmap {
            val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "interrupt-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return bitmap
        }
        fun changedPixels(a: Bitmap, b: Bitmap): Float {
            var changed = 0
            var count = 0
            // Sample the lower half, away from system bars and the home page's header.
            for (y in a.height / 2 until a.height * 9 / 10 step 4) {
                for (x in a.width / 10 until a.width * 9 / 10 step 4) {
                    val first = a.getPixel(x, y)
                    val second = b.getPixel(x, y)
                    // The cover palette has its own colour tween; ignore tiny colour-only changes.
                    if (listOf(0, 8, 16).any { shift ->
                        kotlin.math.abs(((first shr shift) and 255) - ((second shr shift) and 255)) > 8
                    }) changed++
                    count++
                }
            }
            return changed.toFloat() / count
        }
        try {
            ui.waitUntil(10_000) { model.connected }
            ui.runOnIdle {
                model.page = Page.Home
                model.playerExpanded = false
                model.lyricsVisible = false
                model.queueVisible = false
                controller.pause()
                controller.setMediaItem(MediaItem.Builder().setMediaId("-802")
                    .setUri("https://example.invalid/player-interrupt-only")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("动画交接").setArtist("手势测试").build()).build())
            }
            ui.waitUntil(5_000) { model.current?.id == -802L }
            ui.waitForIdle()
            for ((delay, edge) in listOf(48L to BackEventCompat.EDGE_LEFT, 128L to BackEventCompat.EDGE_RIGHT)) {
                for (cancel in listOf(true, false)) {
                    val name = "$delay-$cancel"
                    ui.mainClock.autoAdvance = false
                    ui.onNodeWithTag("floating-player").performClick()
                    ui.mainClock.advanceTimeBy(delay)
                    dragBack(0f, edge)
                    ui.mainClock.advanceTimeBy(32)
                    val held = frame("$name-held")
                    ui.mainClock.advanceTimeBy(160)
                    val later = frame("$name-held-later")
                    val drift = changedPixels(held, later)
                    assertTrue("Entrance must pause under the finger; changed=$drift", drift < .01f)
                    ui.runOnIdle {
                        ui.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, .7f, edge))
                    }
                    ui.mainClock.advanceTimeBy(32)
                    val dragged = frame("$name-dragged")
                    val response = changedPixels(later, dragged)
                    assertTrue("The visible overlay must respond immediately; changed=$response", response > .01f)
                    assertTrue(model.playerExpanded)
                    ui.runOnIdle {
                        if (cancel) ui.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
                        else ui.activity.onBackPressedDispatcher.onBackPressed()
                    }
                    for (elapsed in listOf(16L, 32L, 80L, 160L)) {
                        ui.mainClock.advanceTimeBy(elapsed)
                        frame("$name-release-$elapsed").recycle()
                    }
                    ui.mainClock.autoAdvance = true
                    ui.waitForIdle()
                    if (cancel) {
                        assertTrue(model.playerExpanded)
                        assertEquals(width("expanded-player"), ui.onRoot().fetchSemanticsNode().boundsInRoot.width, 2f)
                        ui.onNodeWithContentDescription("收起播放器").performClick()
                        ui.waitForIdle()
                    } else {
                        assertFalse(model.playerExpanded)
                    }
                    ui.onNodeWithTag("floating-player").assertIsDisplayed()
                    held.recycle()
                    later.recycle()
                    dragged.recycle()
                }
            }
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnIdle { controller.clearMediaItems(); controller.release() }
        }
    }

    private fun recordTransition(name: String, action: () -> Unit, check: (Int) -> Unit = {}) {
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnIdle(action)
            var previous = 0
            for (elapsed in listOf(16, 80, 160, 240, 320, 400)) {
                ui.mainClock.advanceTimeBy((elapsed - previous).toLong())
                ui.waitForIdle()
                check(elapsed)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(80)
                val image = instrumentation.uiAutomation.takeScreenshot()
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "back-$name-$elapsed.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                previous = elapsed
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
    }

    @Test fun eitherEdgePreviewsAsymmetricallyAndCommitsToTheRight() {
        val model = model()
        ui.runOnIdle { model.page = Page.Home; model.playerExpanded = false; model.queueVisible = false }
        recordTransition("enter", { model.page = Page.Library })
        for ((name, edge) in listOf("left" to BackEventCompat.EDGE_LEFT, "right" to BackEventCompat.EDGE_RIGHT)) {
            ui.runOnIdle { model.page = Page.Library }
            ui.waitForIdle()
            dragBack(.7f, edge)
            val before = ui.onNodeWithTag("browse-page-Library").fetchSemanticsNode().boundsInRoot
            val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
            val leftGap = before.left - root.left
            val rightGap = root.right - before.right
            if (edge == BackEventCompat.EDGE_LEFT) assertTrue(leftGap > rightGap)
            else assertTrue(rightGap > leftGap)
            recordTransition(name, { ui.activity.onBackPressedDispatcher.onBackPressed() }) { elapsed ->
                if (elapsed == 80 || elapsed == 160) {
                    // Check the actual commit frames, not just the final navigation state.
                    ui.onNodeWithTag("browse-backdrop-Home").assertExists()
                    val now = ui.onNodeWithTag("browse-page-Library").fetchSemanticsNode().boundsInRoot
                    assertTrue("Both edges must continue to the right from the released position", now.left > before.left + 20f)
                }
            }
            assertEquals(Page.Home, model.page)
            ui.onNodeWithTag("browse-page-Library").assertDoesNotExist()
            ui.onNodeWithTag("browse-backdrop-Home").assertDoesNotExist()
        }
        ui.runOnIdle { model.page = Page.Library }
        ui.waitForIdle()
        recordTransition("button", { model.back() }) { elapsed ->
            if (elapsed == 80) {
                val page = ui.onNodeWithTag("browse-page-Library").fetchSemanticsNode().boundsInRoot
                assertTrue("Toolbar back also exits to the right", page.left > 20f)
            }
        }
        assertEquals(Page.Home, model.page)
    }

    @Test fun listsPreviewWithoutPoppingAndCommitToTheirActualParent() {
        val model = model()
        ui.runOnIdle {
            model.playerExpanded = false
            model.queueVisible = false
            model.page = Page.Home
        }
        ui.onNodeWithTag("daily-cover").assertHasNoClickAction()
        for (title in listOf("为你精选", "最近播放")) {
            ui.runOnIdle { model.openSongs(title, emptyList()) }
            ui.waitForIdle()
            val fullWidth = width("browse-page-Songs")
            dragBack(.7f)
            assertEquals(Page.Songs, model.page)
            assertEquals(fullWidth * .93f, width("browse-page-Songs"), 2f)
            ui.onNodeWithTag("browse-backdrop-Home").assertExists()
            ui.onNodeWithContentDescription("搜索").assertDoesNotExist()
            cancelBack()
            assertEquals(Page.Songs, model.page)
            assertEquals(fullWidth, width("browse-page-Songs"), 2f)
            ui.onNodeWithTag("browse-backdrop-Home").assertDoesNotExist()
            dragBack(.8f, BackEventCompat.EDGE_RIGHT)
            commitBack()
            assertEquals(Page.Home, model.page)
        }
        ui.runOnIdle { model.page = Page.Library }
        ui.runOnIdle { model.openSongs("从资料库打开的歌单", emptyList()) }
        dragBack(.5f)
        ui.onNodeWithTag("browse-backdrop-Library").assertExists()
        commitBack()
        assertEquals(Page.Library, model.page)
        dragBack(.5f)
        commitBack()
        assertEquals(Page.Home, model.page)
    }

    @Test fun lyricsReturnBeforePlayerAndSharedElementsWaitForRelease() {
        val model = model()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        ui.runOnIdle {
            future = MediaController.Builder(ui.activity, SessionToken(ui.activity,
                ComponentName(ui.activity, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            ui.waitUntil(10_000) { model.connected }
            ui.runOnIdle {
                model.page = Page.Home
                model.queueVisible = false
                controller.pause()
                controller.setMediaItem(MediaItem.Builder().setMediaId("-801")
                    .setUri("https://example.invalid/predictive-back-only")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("返回手势检查").setArtist("布局测试").build()).build())
                model.playerExpanded = true
                model.lyricsVisible = true
            }
            ui.waitUntil(5_000) { model.current?.id == -801L }
            ui.waitForIdle()
            val lyricWidth = width("lyrics-page")
            dragBack(.6f)
            assertTrue(model.lyricsVisible)
            assertTrue(model.playerExpanded)
            assertEquals(lyricWidth * .94f, width("lyrics-page"), 2f)
            assertEquals(lyricWidth, width("lyrics-backdrop-player"), 2f)
            ui.onNodeWithContentDescription("收起播放器").assertDoesNotExist()
            cancelBack()
            assertEquals(lyricWidth, width("lyrics-page"), 2f)
            dragBack(.6f)
            val lyricBefore = ui.onNodeWithTag("lyrics-page").fetchSemanticsNode().boundsInRoot
            recordTransition("lyrics", { ui.activity.onBackPressedDispatcher.onBackPressed() }) { elapsed ->
                if (elapsed == 80) {
                    ui.onNodeWithTag("lyrics-backdrop-player").assertExists()
                    val now = ui.onNodeWithTag("lyrics-page").fetchSemanticsNode().boundsInRoot
                    assertTrue(now.left > lyricBefore.left + 20f)
                }
            }
            assertFalse(model.lyricsVisible)
            assertTrue(model.playerExpanded)
            ui.onNodeWithTag("now-playing-page").assertIsDisplayed()
            ui.onNodeWithTag("lyrics-backdrop-player").assertDoesNotExist()

            val playerWidth = width("expanded-player")
            val artworkWidth = width("player-artwork")
            dragBack(.8f)
            assertTrue(model.playerExpanded)
            ui.onNodeWithTag("floating-player").assertDoesNotExist()
            // The cover scales with its page; it does not travel toward the mini player mid-gesture.
            assertEquals(playerWidth * .92f, width("expanded-player"), 2f)
            assertEquals(artworkWidth * .92f, width("player-artwork"), 2f)
            cancelBack()
            assertEquals(playerWidth, width("expanded-player"), 2f)
            dragBack(.7f)
            recordTransition("player", { ui.activity.onBackPressedDispatcher.onBackPressed() })
            assertFalse(model.playerExpanded)
            ui.onNodeWithTag("floating-player").assertIsDisplayed()
            ui.onNodeWithTag("floating-player").performClick()
            ui.waitForIdle()
            assertEquals(playerWidth, width("expanded-player"), 2f)
            assertEquals(-801L, model.current?.id)
            dragBack(.7f)
            ui.mainClock.autoAdvance = false
            try {
                ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
                ui.mainClock.advanceTimeBy(32)
                ui.runOnIdle { model.playerExpanded = true }
            } finally {
                ui.mainClock.autoAdvance = true
            }
            ui.waitForIdle()
            assertEquals(playerWidth, width("expanded-player"), 2f)
        } finally {
            ui.runOnIdle { controller.clearMediaItems(); controller.release() }
        }
    }
}
