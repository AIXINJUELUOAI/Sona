package app.sona.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SongRowStatus(song: Song, showSource: Boolean = true) {
    if (!showSource) return
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp),
        color = if (song.isLocal) scheme.secondary else scheme.primary,
        contentColor = if (song.isLocal) scheme.onSecondary else scheme.onPrimary) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(if (song.isLocal) Icons.Rounded.Smartphone else Icons.Rounded.Cloud,
                "来源：${song.sourceLabel}", Modifier.size(16.dp))
        }
    }
}
