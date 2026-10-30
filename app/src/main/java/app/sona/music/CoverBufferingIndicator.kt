package app.sona.music

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/** Keep layout bounds fixed while the image makes room for a thin buffering arc. */
@Composable
internal fun BufferingArtwork(song: Song, buffering: Boolean, shape: Shape, modifier: Modifier = Modifier) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = buffering && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val visible by produceState(false, running, song.key) {
        value = false
        if (running) { delay(350); value = true }
    }
    val transition = updateTransition(visible && running, label = "buffering-artwork")
    val scale by transition.animateFloat({ tween(250) }, label = "buffering-cover-scale") {
        if (it) .94f else 1f
    }
    val opacity by transition.animateFloat({ tween(if (targetState) 200 else 160) }, label = "buffering-arc-alpha") {
        if (it) 1f else 0f
    }
    Box(modifier) {
        Artwork(song.cover, Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.clip(shape), song.title)
        if (opacity > 0f && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
            CoverBufferingIndicator(shape, opacity)
        }
    }
}

/** Only the moving segment is drawn; no full ring or opaque backing covers the artwork. */
@Composable
private fun CoverBufferingIndicator(shape: Shape, opacity: Float) {
    val progress = rememberInfiniteTransition(label = "cover-buffering").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "outline-progress")
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().testTag("cover-buffering").drawWithCache {
        val stroke = 1.5.dp.toPx().coerceAtMost(size.minDimension * .018f)
        val inset = stroke * .6f
        val outline = shape.createOutline(Size((size.width - inset * 2).coerceAtLeast(1f),
            (size.height - inset * 2).coerceAtLeast(1f)), layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Generic -> addPath(outline.path)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Rectangle -> addRect(outline.rect)
            }
        }
        val measure = PathMeasure().apply { setPath(path, true) }
        val segment = Path()
        val activeStroke = Stroke(stroke, cap = StrokeCap.Round)
        onDrawBehind {
            val start = progress.value * measure.length
            val end = start + measure.length * .18f
            segment.reset()
            measure.getSegment(start, end.coerceAtMost(measure.length), segment)
            if (end > measure.length) measure.getSegment(0f, end - measure.length, segment)
            translate(inset, inset) {
                drawPath(segment, color, alpha = opacity, style = activeStroke)
            }
        }
    })
}
