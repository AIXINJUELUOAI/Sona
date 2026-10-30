@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.sona.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToInt

@UnstableApi
@Composable
private fun rememberOverlayPermission(controller: FloatingLyricsController): (() -> Unit) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pending; pending = null
        controller.refreshPermission()
        if (Settings.canDrawOverlays(context)) action?.invoke()
    }
    return { action ->
        if (Settings.canDrawOverlays(context)) action()
        else {
            pending = action
            try { launcher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            catch (_: ActivityNotFoundException) { launcher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }
}

@Composable
private fun LyricSwitch(checked: Boolean, enabled: Boolean = true, change: ((Boolean) -> Unit)? = null) {
    Switch(checked, change, enabled = enabled, thumbContent = {
        Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize))
    })
}

@UnstableApi
@Composable
internal fun FloatingLyricsEntry(model: MusicViewModel, open: () -> Unit) {
    val controller = model.getApplication<MusicApplication>().floatingLyrics
    val permission = rememberOverlayPermission(controller)
    val options = controller.options
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = open).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.Subtitles, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("悬浮歌词", style = MaterialTheme.typography.titleMedium)
                    Text(if (options.enabled) "${options.style.label} · 已开启" else "在其他应用上方显示",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, "打开悬浮歌词设置", Modifier.size(20.dp))
            }
            VerticalDivider(Modifier.height(32.dp))
            Box(Modifier.padding(horizontal = 12.dp)) {
                LyricSwitch(options.enabled) { enabled ->
                    if (enabled) permission { controller.update(controller.options.copy(enabled = true)) }
                    else controller.update(controller.options.copy(enabled = false))
                }
            }
        }
    }
}

@UnstableApi
@Composable
internal fun FloatingLyricsSettingsScreen(model: MusicViewModel, scroll: ScrollState, navigationPreview: Boolean = false) {
    val controller = model.getApplication<MusicApplication>().floatingLyrics
    val options = controller.options
    var selector by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(options.enabled) { if (!options.enabled) { selector = null; menu = false } }
    if (!navigationPreview) DisposableEffect(controller) { onDispose { controller.stopPreview() } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("悬浮歌词", model::back, actions = {
            Box {
                IconButton(enabled = options.enabled, onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "更多设置") }
                DropdownMenu(menu && options.enabled, { menu = false }) {
                    DropdownMenuItem(enabled = options.enabled, text = { Text("恢复显示设置") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) }, onClick = {
                        controller.update(FloatingLyricsConfig(enabled = options.enabled, appColors = options.appColors))
                        menu = false
                    })
                }
            }
        })
        Column(Modifier.weight(1f).scrollViewport(16.dp).verticalScroll(scroll)
            .graphicsLayer { alpha = if (options.enabled) 1f else .38f }
            .padding(horizontal = 16.dp).padding(bottom = contentBottomPadding(model.current != null)),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            controller.message?.let { message ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = options.enabled, onClick = controller::retry) { Text("重试") }
                    }
                }
            }
            LyricSettingsGroup("显示方式") {
                LyricSettingsRow(Icons.Rounded.ViewCarousel, "歌词样式", if (options.style == FloatingLyricsStyle.Default)
                    "默认歌词 · 在固定位置显示" else "弹幕歌词 · 在屏幕上半区滚动播放", enabled = options.enabled) { selector = "style" }
                if (options.style == FloatingLyricsStyle.Default) {
                    LyricSettingsRow(Icons.AutoMirrored.Rounded.Subject, "显示行数", if (options.fixedLines == 1) "一条" else "两条", enabled = options.enabled) { selector = "lines" }
                } else {
                    LyricSettingsToggle(Icons.Rounded.Translate, "显示译文", options.showTranslation, enabled = options.enabled) {
                        controller.update(controller.options.copy(showTranslation = it))
                    }
                    LyricsSlider("移动速率", "${"%.1f".format(options.rate)} 倍", options.rate, .5f..2f, enabled = options.enabled) {
                        controller.update(controller.options.copy(rate = it))
                    }
                }
            }
            LyricSettingsGroup("文字与颜色") {
                LyricsSlider("字体大小", "${options.fontSize.roundToInt()} sp", options.fontSize, 12f..32f, steps = 19, enabled = options.enabled) {
                    controller.update(controller.options.copy(fontSize = it.roundToInt().toFloat()))
                }
                LyricsSlider("不透明度", "${(options.opacity * 100).roundToInt()}%", options.opacity, .25f..1f, enabled = options.enabled) {
                    controller.update(controller.options.copy(opacity = it))
                }
                LyricSettingsRow(Icons.Rounded.Palette, "歌词颜色",
                    enabled = options.enabled, trailing = { LyricColorSwatch(options.textColor) }) { model.page = Page.LyricColors }
            }

        }
    }
    if (selector != null && options.enabled) {
        val style = selector == "style"
        LyricChoiceSheet(if (style) "歌词样式" else "显示行数", if (style) listOf("默认 · 固定显示", "弹幕") else listOf("一条", "两条"),
            if (style) options.style.ordinal else options.fixedLines - 1, { selector = null }) { index ->
            controller.update(if (style) controller.options.copy(style = FloatingLyricsStyle.entries[index])
                else controller.options.copy(fixedLines = index + 1))
        }
    }
}

@Composable
internal fun LyricSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
            verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun LyricSettingsRow(icon: ImageVector, title: String, description: String? = null, enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null, click: () -> Unit) {
    Surface(enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(4.dp), onClick = click) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.invoke() ?: Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LyricSettingsToggle(icon: ImageVector, title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        LyricSwitch(checked, enabled = enabled)
    }
}

@Composable
private fun LyricsSlider(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, enabled: Boolean = true, change: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, enabled = enabled, onValueChange = change, valueRange = range, steps = steps)
    }
}

@Composable
private fun LyricChoiceSheet(title: String, choices: List<String>, selected: Int, dismiss: () -> Unit, change: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            choices.forEachIndexed { index, label ->
                Surface(onClick = { change(index); dismiss() }, shape = RoundedCornerShape(24.dp),
                    color = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (selected == index) Icon(Icons.Rounded.Check, "已选择")
                    }
                }
            }
        }
    }
}
