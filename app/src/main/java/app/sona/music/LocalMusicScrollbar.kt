package app.sona.music

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun LocalMusicScrollbar(state: LazyListState, labels: List<String>, headerCount: Int, modifier: Modifier = Modifier) {
    if (labels.size < 2 || (!state.canScrollForward && !state.canScrollBackward)) return
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var height by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val handle = with(LocalDensity.current) { 48.dp.toPx() }
    val travel = (height - handle).coerceAtLeast(1f)
    val position by remember(state, headerCount, labels.size) { derivedStateOf {
        if (!state.canScrollForward) 1f else {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
            val fraction = state.firstVisibleItemScrollOffset.toFloat() / (first?.size ?: 1).coerceAtLeast(1)
            ((state.firstVisibleItemIndex - headerCount + fraction) / (labels.size - 1)).coerceIn(0f, 1f)
        }
    } }
    val fraction = if (dragging) ((dragY - handle / 2) / travel).coerceIn(0f, 1f) else position
    val index = (fraction * (labels.size - 1)).roundToInt().coerceIn(labels.indices)
    val active = dragging || state.isScrollInProgress
    val scheme = MaterialTheme.colorScheme
    val thumbWidth by animateDpAsState(if (dragging) 28.dp else 8.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "local-scroll-handle")
    fun seek(value: Float) {
        val target = (value.coerceIn(0f, 1f) * (labels.size - 1)).roundToInt()
        scrollJob?.cancel()
        scrollJob = scope.launch { state.scrollToItem(headerCount + target) }
    }
    Box(modifier) {
        if (dragging) Surface(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(-40.dp.roundToPx(), (fraction * travel).roundToInt()) },
            shape = RoundedCornerShape(16.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer
        ) { Text(labels[index], Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium) }
        Canvas(Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight()
            .onSizeChanged { height = it.height }
            .semantics {
                contentDescription = "快速滚动歌曲列表"
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                setProgress { seek(it); true }
            }
            .pointerInput(labels.size, headerCount, height) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; dragY = it.y; seek((dragY - handle / 2) / travel) },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false }
                ) { change, amount ->
                    change.consume()
                    dragY = (dragY + amount).coerceIn(handle / 2, (height - handle / 2).coerceAtLeast(handle / 2))
                    seek((dragY - handle / 2) / travel)
                }
            }) {
            val x = size.width - 4.dp.toPx()
            val top = fraction * travel
            val gap = 8.dp.toPx()
            if (active) {
                if (top > gap) drawLine(scheme.secondaryContainer, Offset(x, 0f), Offset(x, top - gap), 2.dp.toPx(), StrokeCap.Round)
                if (top + handle + gap < size.height) drawLine(scheme.secondaryContainer,
                    Offset(x, top + handle + gap), Offset(x, size.height), 2.dp.toPx(), StrokeCap.Round)
            }
            drawRoundRect(scheme.primary.copy(alpha = if (active) 1f else .6f),
                topLeft = Offset(size.width - thumbWidth.toPx(), top), size = Size(thumbWidth.toPx(), handle),
                cornerRadius = CornerRadius(thumbWidth.toPx() / 2))
        }
        if (dragging) Box(Modifier.align(Alignment.TopEnd)
            .offset { IntOffset(0, (fraction * travel).roundToInt()) }.width(thumbWidth).height(48.dp),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(20.dp), tint = scheme.onPrimary)
        }
    }
}
