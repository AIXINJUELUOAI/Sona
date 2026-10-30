@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.sona.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
internal fun LyricColorsScreen(model: MusicViewModel, scroll: ScrollState) {
    val controller = model.getApplication<MusicApplication>().floatingLyrics
    Column(Modifier.fillMaxSize()) {
        PageHeader("歌词颜色", model::back)
        Column(Modifier.weight(1f).scrollViewport(16.dp).verticalScroll(scroll)
            .graphicsLayer { alpha = if (controller.options.enabled) 1f else .38f }
            .padding(horizontal = 16.dp).padding(bottom = contentBottomPadding(model.current != null)),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LyricSettingsGroup("默认颜色") {
                LyricPresetPalette(controller.options.textColor, enabled = controller.options.enabled) { controller.update(controller.options.copy(textColor = it)) }
            }
            LyricSettingsGroup("应用专属颜色") { LyricAppColorSettings(controller) }
        }
    }
}

@UnstableApi
@Composable
private fun LyricAppColorSettings(controller: FloatingLyricsController) {
    val context = LocalContext.current
    val options = controller.options
    var pendingAdd by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LyricColorApp?>(null) }
    LaunchedEffect(options.enabled) { if (!options.enabled) { picking = false; editing = null; pendingAdd = false } }
    val apps by produceState<List<LyricColorApp>?>(null, context) {
        value = withContext(Dispatchers.IO) { runCatching { lyricColorApps(context) }.getOrDefault(emptyList()) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.refreshPermission()
        val continueAdding = pendingAdd && controller.options.enabled && hasLyricUsageAccess(context)
        pendingAdd = false
        if (continueAdding) picking = true
    }
    LyricSettingsRow(Icons.Rounded.Add, "添加应用", enabled = options.enabled) {
        if (hasLyricUsageAccess(context)) picking = true
        else {
            pendingAdd = true
            try {
                permission.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")))
            } catch (_: ActivityNotFoundException) {
                permission.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
    }
    if (options.appColors.isNotEmpty()) {
        options.appColors.forEach { (pkg, color) ->
            val app = apps?.firstOrNull { it.packageName == pkg } ?: LyricColorApp(pkg, pkg)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(4.dp)) {
                Row(Modifier.fillMaxWidth().clickable(enabled = options.enabled) { editing = app }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LyricAppIcon(pkg)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LyricColorSwatch(color)
                    IconButton(enabled = options.enabled, onClick = { controller.update(controller.options.copy(appColors = controller.options.appColors - pkg)) }) {
                        Icon(Icons.Rounded.DeleteOutline, "移除 ${app.label} 的专属颜色")
                    }
                }
            }
        }
    }
    if (picking && options.enabled) {
        var query by remember { mutableStateOf("") }
        val visibleApps = remember(apps, query) { apps.orEmpty().filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 24.dp)) {
                Text("选择应用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(query, { query = it }, label = { Text("搜索应用") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                when {
                    apps == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    visibleApps.isEmpty() -> Text("没有找到应用", Modifier.padding(vertical = 24.dp))
                    else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            ListItem(headlineContent = { Text(app.label) },
                                supportingContent = { Text(if (options.appColors.containsKey(app.packageName)) "已设置专属颜色" else app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { LyricAppIcon(app.packageName) },
                                trailingContent = { options.appColors[app.packageName]?.let { LyricColorSwatch(it) }
                                    ?: Icon(Icons.Rounded.Add, null) },
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { picking = false; editing = app })
                        }
                    }
                }
            }
        }
    }
    editing?.takeIf { options.enabled }?.let { app ->
        LyricsColorDialog(app.label, options.appColors[app.packageName] ?: options.textColor, { editing = null }) { color ->
            controller.update(controller.options.copy(appColors = controller.options.appColors + (app.packageName to color)))
        }
    }
}

@Composable
private fun LyricAppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) Image(bitmap, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
    else Icon(Icons.Rounded.Apps, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
