package app.sona.music

import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_PLAYER = "app.sona.music.OPEN_PLAYER"
    }
    private val musicModel by lazy { androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java] }

    override fun onStart() {
        super.onStart()
        musicModel.setUiActive(true)
        (application as MusicApplication).floatingLyrics.setUiVisible(true)
        musicModel.refreshLocalMusic()
    }
    override fun onStop() {
        musicModel.setUiActive(false)
        (application as MusicApplication).floatingLyrics.setUiVisible(false)
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntentDestination(intent)
    }
    override fun onResume() {
        super.onResume()
        (application as MusicApplication).floatingLyrics.refreshPermission()
    }
    private fun openIntentDestination(intent: Intent) {
        if (intent.action != ACTION_OPEN_PLAYER) return
        musicModel.lyricsVisible = false
        musicModel.queueVisible = false
        musicModel.outputVisible = false
        // Keep the request while the media controller restores the current song.
        musicModel.playerExpanded = true
        intent.action = Intent.ACTION_MAIN
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            musicModel.playerExpanded = it.getBoolean("playerExpanded", musicModel.playerExpanded)
            musicModel.deskLyricsOpen = it.getBoolean("deskLyricsOpen", musicModel.deskLyricsOpen)
        }
        openIntentDestination(intent)
        // Apply before the controller reconnects; a missing song during restoration must not
        // force an already expanded landscape player back to portrait.
        updatePlayerOrientation(musicModel.playerExpanded)
        enableEdgeToEdge()
        setContent {
            val model: MusicViewModel = viewModel()
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val expanded = model.playerExpanded && model.current != null
            // The expanded player is the only page allowed to rotate: turning it is what opens
            // desk mode, and every other page stays locked to portrait.
            LaunchedEffect(model.playerExpanded) {
                updatePlayerOrientation(model.playerExpanded)
            }
            LaunchedEffect(expanded, landscape) {
                model.deskMode = expanded && landscape
                if (model.deskMode) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            SonaTheme(model.theme) { dark, scheme ->
                SonaApp(model, dark, scheme)
            }
        }
    }

    private fun updatePlayerOrientation(expanded: Boolean) {
        val orientation = if (expanded) ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("playerExpanded", musicModel.playerExpanded)
        outState.putBoolean("deskLyricsOpen", musicModel.deskLyricsOpen)
        super.onSaveInstanceState(outState)
    }
}
