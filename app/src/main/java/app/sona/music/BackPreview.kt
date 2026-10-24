package app.sona.music

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Enter from the right and return to the right using the same spatial timing.
@Composable
internal fun AnimatedVisibilityScope.pageMotionProgress(): Float = transition.animateFloat(
    transitionSpec = { tween(PageMotionDuration, easing = FastOutSlowInEasing) }, label = "page-motion"
) { if (it == EnterExitState.Visible) 0f else 1f }.value

@Stable
internal class BackPreviewState {
    val progress = Animatable(0f)
    var recovery: Job? = null
    var committed by mutableStateOf(false)
    var gesturing by mutableStateOf(false)
    var direction by mutableIntStateOf(1)
    var screenCorner by mutableStateOf(32.dp)

    fun cornerRadius(scale: Float = 1f - 0.1f * progress.value, p: Float = progress.value): Dp {
        // Compensate for the layer scale so the visible radius ends at 32dp.
        return lerp(screenCorner, 32.dp, p) / scale
    }
}

/** The gesture only transforms a layer. Navigation and shared-element motion start on commit. */
@Composable
internal fun rememberBackPreview(
    enabled: Boolean,
    restoreAfterCommit: Boolean = false,
    onBack: (direction: Int) -> Unit,
): BackPreviewState {
    val state = remember { BackPreviewState() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(view, density) {
        fun updateCorner() {
            if (Build.VERSION.SDK_INT >= 31) {
                val radius = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                    .mapNotNull { view.rootWindowInsets?.getRoundedCorner(it)?.radius }.maxOrNull()
                if (radius != null && radius > 0) state.screenCorner = with(density) { radius.toDp() }
            }
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCorner() }
        view.addOnLayoutChangeListener(listener)
        updateCorner()
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    LaunchedEffect(enabled, state.committed) {
        // An interrupted exit can reuse the same composition when the page is opened again.
        if (enabled && state.committed && !restoreAfterCommit) {
            state.recovery?.cancel()
            state.progress.snapTo(0f)
            state.committed = false
        }
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        state.recovery?.cancel()
        state.gesturing = true
        state.direction = 1
        try {
            events.collect {
                state.direction = if (it.swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
                state.progress.snapTo(it.progress.coerceIn(0f, 1f))
            }
            state.committed = true
            onBack(state.direction)
            // Outgoing Compose pages retain their final scale until their exit animation finishes.
            if (restoreAfterCommit) {
                state.recovery = scope.launch {
                    state.progress.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
                    state.committed = false
                }
            }
        } catch (_: CancellationException) {
            state.recovery = scope.launch { state.progress.animateTo(0f, tween(180)) }
        } finally {
            state.gesturing = false
        }
    }
    return state
}

internal fun Modifier.backPreview(
    state: BackPreviewState,
    travel: Float = 0f,
    progress: () -> Float = { state.progress.value },
): Modifier = graphicsLayer {
    val progress = progress()
    val transform = pageTransform(size.width, 8.dp.toPx(), progress, state.direction, travel)
    scaleX = transform.scale
    scaleY = scaleX
    translationX = transform.translationX
    shape = RoundedCornerShape(state.cornerRadius(scaleX, progress))
    clip = progress > 0f || scaleX < 1f
}

/** A real destination page, visible but not interactive while it is behind the outgoing page. */
internal fun Modifier.backPreviewBackdrop(tag: String, dim: () -> Float = { 1f }): Modifier =
    clearAndSetSemantics { testTag = tag }
        .focusProperties { canFocus = false }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        .drawWithContent {
            drawContent()
            drawRect(Color.Black.copy(alpha = 0.08f * dim().coerceIn(0f, 1f)))
        }
