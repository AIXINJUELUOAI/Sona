@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val revision = if (url?.startsWith("content://media/") == true) localArtworkRevision else 0L
    val request = remember(context, url, revision) { artworkRequest(context, url, revision).build() }
    // The previous cover stays on screen until the next one decodes, so changing tracks never
    // flashes the placeholder. Only the very first load shows it.
    var previous by remember { mutableStateOf<Painter?>(null) }
    var missing by remember(url, revision) { mutableStateOf(previous == null || url.isNullOrBlank()) }
    Box(modifier.semantics { if (description != null) contentDescription = description }, contentAlignment = Alignment.Center) {
        if (missing) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer))
            Icon(Icons.Rounded.MusicNote, null,
                Modifier.fillMaxSize(.32f).sizeIn(maxWidth = 64.dp, maxHeight = 64.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (!url.isNullOrBlank()) AsyncImage(model = request, contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            placeholder = previous,
            onLoading = { missing = previous == null }, onError = { missing = true },
            onSuccess = { state ->
                previous = state.painter
                missing = false
            })
    }
}

// A manual re-index can update an album image without changing its MediaStore URI.
internal var localArtworkRevision by mutableLongStateOf(0L)

internal fun artworkRequest(context: Context, url: String?, revision: Long) = ImageRequest.Builder(context).data(url)
    .crossfade(150)
    .apply {
        if (url?.startsWith("content://media/") == true) memoryCacheKey("$url:sona:$revision")
    }

/** Warms the disk cache for the covers the player is about to show. */
internal fun prefetchArtwork(context: Context, covers: List<String>) {
    covers.filter { it.isNotBlank() }.distinct().forEach { url ->
        context.imageLoader.enqueue(artworkRequest(context, url, 0L).build())
    }
}
