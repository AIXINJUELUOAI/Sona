package app.sona.music

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Match the top corners to the content edges, while preserving overflow further down. */
internal fun Modifier.scrollViewport(horizontalInset: Dp = 0.dp): Modifier = drawWithCache {
    val inset = horizontalInset.toPx().coerceIn(0f, size.width / 2f)
    val radius = 28.dp.toPx().coerceAtMost((size.width - inset * 2f) / 2f).coerceAtMost(size.height)
    val outline = Path().apply {
        addRoundRect(RoundRect(Rect(inset, 0f, size.width - inset, size.height),
            topLeft = CornerRadius(radius), topRight = CornerRadius(radius)))
        // Only the top edge is masked; artwork and carousels retain their existing side space.
        addRect(Rect(0f, radius, size.width, size.height))
    }
    onDrawWithContent { clipPath(outline) { this@onDrawWithContent.drawContent() } }
}

@Composable
internal fun LocatePlayingButton(state: LazyListState, songs: List<Song>, currentKey: String?,
    leadingItems: Int = 0, bottomInset: Dp = 0.dp, enabled: Boolean = true,
    alwaysVisible: Boolean = false) {
    val songIndex = remember(songs, currentKey) { songs.indexOfFirst { it.key == currentKey } }
    val index = if (songIndex >= 0) songIndex + leadingItems else -1
    val scope = rememberCoroutineScope()
    val bottomPx = with(LocalDensity.current) { bottomInset.roundToPx() }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(songs, currentKey, leadingItems) { job?.cancel() }
    if (index < 0 && !alwaysVisible) return
    FilledTonalIconButton(onClick = {
        job?.cancel()
        job = scope.launch {
            if (index !in 0 until state.layoutInfo.totalItemsCount) return@launch
            if (abs(index - state.firstVisibleItemIndex) > 40) state.scrollToItem((index - 5).coerceAtLeast(0))
            val usableHeight = (state.layoutInfo.viewportSize.height - bottomPx).coerceAtLeast(0)
            state.animateScrollToItem(index, scrollOffset = -usableHeight / 3)
        }
    }, enabled = enabled && index >= 0, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Rounded.MyLocation, "定位当前播放")
    }
}
