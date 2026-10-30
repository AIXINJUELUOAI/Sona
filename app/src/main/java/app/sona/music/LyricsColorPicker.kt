@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.sona.music

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal fun lyricColorHex(color: Int) = "%06X".format(color and 0xffffff)

@Composable
internal fun rememberLyricPreviewScheme(): ColorScheme {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    return remember(context, dark) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
    }
}

@Composable
internal fun LyricColorSwatch(color: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(28.dp).clip(CircleShape).background(Color(color))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

internal val lyricColorPresets = listOf("白色" to -1, "黑色" to 0xff111111.toInt(),
    "珊瑚红" to 0xffff6b6b.toInt(), "暖黄色" to 0xffffd166.toInt(), "薄荷绿" to 0xff82e0aa.toInt(),
    "青色" to 0xff66d9ef.toInt(), "蓝色" to 0xff82aaff.toInt(), "紫色" to 0xffcba6f7.toInt())

@Composable
internal fun LyricPresetPalette(color: Int, enabled: Boolean = true, change: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        lyricColorPresets.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (name, preset) ->
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color(preset))
                        .border(if (color == preset) 3.dp else 1.dp,
                            if (color == preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .semantics { contentDescription = name; stateDescription = if (color == preset) "已选择" else "未选择" }
                        .clickable(enabled = enabled, role = Role.RadioButton) { change(preset) }, contentAlignment = Alignment.Center) {
                        if (color == preset) Icon(Icons.Rounded.Check, null,
                            tint = if (preset == 0xff111111.toInt()) Color.White else Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LyricsColorDialog(title: String, color: Int, dismiss: () -> Unit, change: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LyricPresetPalette(color) { change(it); dismiss() }
        }
    }
}
