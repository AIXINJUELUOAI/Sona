@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

// Keep the platform Slider's gestures, keyboard controls and accessibility; replace only its visuals.
@Composable
internal fun WaveSeekBar(position: Long, duration: Long, playing: Boolean, enabled: Boolean,
    onSeek: (Long) -> Unit, modifier: Modifier = Modifier, trackKey: Any? = null) {
    var seeking by remember(trackKey) { mutableStateOf(false) }
    var preview by remember(trackKey) { mutableFloatStateOf(0f) }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val state = rememberSliderState()
    state.value = if (seeking) preview else fraction
    val amplitude by animateFloatAsState(if (playing && enabled && !seeking) 0.38f else 0f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "seek-wave")
    val strokeWidth = with(LocalDensity.current) { 3.dp.toPx() }
    val color = MaterialTheme.colorScheme.primary
    Slider(state = state, onValueChange = { seeking = true; preview = it },
        onValueChangeFinished = { if (seeking) onSeek((preview * duration).roundToLong()); seeking = false },
        enabled = enabled && duration > 0,
        modifier = modifier.heightIn(min = 48.dp).testTag("wave-seek").semantics { contentDescription = "播放进度" },
        thumb = { Box(Modifier.size(4.dp, 18.dp).background(color, RoundedCornerShape(2.dp))) },
        track = { slider ->
            LinearWavyProgressIndicator(progress = { slider.value },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                color = color, trackColor = color.copy(alpha = 0.22f),
                stroke = Stroke(strokeWidth, cap = StrokeCap.Round),
                trackStroke = Stroke(strokeWidth, cap = StrokeCap.Round),
                gapSize = 5.dp, stopSize = 0.dp, amplitude = { amplitude }, wavelength = 32.dp,
                waveSpeed = if (playing && enabled && !seeking) 10.dp else 0.dp)
        })
}

