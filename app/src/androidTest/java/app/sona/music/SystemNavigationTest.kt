package app.sona.music

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Run once per navigation mode: gesture and three-button. Checks the actual bottom pixels. */
@UnstableApi
class SystemNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    @Test fun homeNavigationSurfaceSurvivesThemeAndPlayerChanges() {
        lateinit var model: MusicViewModel
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        ui.runOnIdle {
            model = ViewModelProvider(ui.activity)[MusicViewModel::class.java]
            future = MediaController.Builder(ui.activity, SessionToken(ui.activity,
                ComponentName(ui.activity, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("navigationLabel", "default")
        fun checkBottom(name: String, expected: Int) {
            ui.waitForIdle()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try {
                val actual = bitmap.getPixel(4, bitmap.height - 8)
                val delta = maxOf(abs(AndroidColor.red(actual) - AndroidColor.red(expected)),
                    abs(AndroidColor.green(actual) - AndroidColor.green(expected)),
                    abs(AndroidColor.blue(actual) - AndroidColor.blue(expected)))
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "navigation-$label-$name.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("$name bottom background: expected=$expected actual=$actual", delta <= 3)
            } finally { bitmap.recycle() }
        }
        try {
            ui.waitUntil(10_000) { model.connected }
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val scheme = if (dark) dynamicDarkColorScheme(ui.activity) else dynamicLightColorScheme(ui.activity)
                ui.runOnIdle {
                    model.setAppearance(theme)
                    model.playerExpanded = false
                    model.lyricsVisible = false
                    model.queueVisible = false
                    model.page = Page.Home
                }
                for (page in listOf(Page.Home, Page.Library, Page.Search, Page.Home)) {
                    ui.runOnIdle { model.page = page }
                    ui.waitForIdle()
                    ui.runOnIdle {
                        val window = ui.activity.window
                        assertFalse(window.isNavigationBarContrastEnforced)
                        assertEquals(AndroidColor.TRANSPARENT, window.navigationBarColor)
                        assertEquals(scheme.surface.luminance() > .5f,
                            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars)
                    }
                    checkBottom("$theme-${page.name}", scheme.surface.toArgb())
                }
                ui.runOnIdle {
                    controller.pause()
                    controller.setMediaItem(MediaItem.Builder().setMediaId("-401")
                        .setUri("https://example.invalid/navigation-only")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("导航栏检查").setArtist("布局测试").build()).build())
                }
                ui.waitUntil(5000) { model.current != null }
                ui.runOnIdle { model.playerExpanded = true }
                ui.waitForIdle()
                ui.runOnIdle { model.playerExpanded = false }
                checkBottom("$theme-Home-after-player", scheme.surface.toArgb())
            }
        } finally {
            ui.runOnIdle { controller.clearMediaItems(); controller.release() }
        }
    }
}
