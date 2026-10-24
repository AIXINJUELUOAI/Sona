@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val revision = if (url?.startsWith("content://media/") == true) localArtworkRevision else 0L
    val request = remember(context, url, revision) { artworkRequest(context, url, revision).build() }
    var missing by remember(url, revision) { mutableStateOf(true) }
    Box(modifier.semantics { if (description != null) contentDescription = description }, contentAlignment = Alignment.Center) {
        if (missing) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
            Box(Modifier.fillMaxSize(.72f).aspectRatio(1f)
                .background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided.toShape()))
        }
        if (!url.isNullOrBlank()) AsyncImage(model = request, contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            onLoading = { missing = true }, onSuccess = { missing = false }, onError = { missing = true })
    }
}

// A manual re-index can update an album image without changing its MediaStore URI.
internal var localArtworkRevision by mutableLongStateOf(0L)

internal fun artworkRequest(context: Context, url: String?, revision: Long) = ImageRequest.Builder(context).data(url).apply {
    if (url?.startsWith("content://media/") == true) memoryCacheKey("$url:sona:$revision")
}
