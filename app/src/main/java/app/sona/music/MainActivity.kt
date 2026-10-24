package app.sona.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java].refreshLocalMusic()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: MusicViewModel = viewModel()
            SonaTheme(model.theme) { dark, scheme ->
                SonaApp(model, dark, scheme)
            }
        }
    }
}
