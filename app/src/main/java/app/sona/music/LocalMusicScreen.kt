@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.sona.music

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun LocalMusicScreen(model: MusicViewModel, preview: Boolean = false) {
    val context = LocalContext.current
    var requested by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val source = model.localSongs
    val sort = model.localSort
    val descending = model.localSortDescending
    val order by produceState<LocalSongOrder?>(null, source, sort, descending) {
        value = withContext(Dispatchers.Default) { orderLocalSongs(source, sort, descending) }
        listState.scrollToItem(0)
    }
    val songs = order?.songs.orEmpty()
    val bottomPadding = contentBottomPadding(model.current != null)
    val leadingItems = (if (model.localLoading || order == null) 1 else 0) +
        (if (model.localRefreshMessage != null) 1 else 0) + (if (model.localError != null) 1 else 0)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshLocalMusic()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("本地音乐", model::back, subtitle = if (model.localPermission) "共 ${source.size} 首" else "设备音乐库") {
            if (model.localPermission) LocatePlayingButton(listState, songs, model.current?.key, leadingItems, bottomPadding, enabled = !preview, alwaysVisible = true)
            if (model.localPermission) FilledTonalButton(onClick = { showOptions = true }, enabled = !preview,
                modifier = Modifier.height(48.dp), shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Sort, "列表选项", Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(sort.label, style = MaterialTheme.typography.titleSmall)
            }
        }
        Box(Modifier.weight(1f)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().scrollViewport(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            if (!model.localPermission) {
                item {
                    EmptyState("把手机里的音乐带进来", "允许读取音频后，歌曲就会出现在这里。无需登录，也可以离线播放。",
                        Icons.Rounded.LibraryMusic, "读取本地音乐") {
                        if (!preview) { requested = true; permission.launch(audioPermission) }
                    }
                    if (requested) TextButton(onClick = {
                        if (!preview) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("在设置中管理音乐权限") }
                }
            } else {
                if (model.localLoading || order == null) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                model.localRefreshMessage?.let { message -> item {
                    Text(message, Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                model.localError?.let { message -> item { EmptyState("扫描未完成", message, Icons.Rounded.Refresh, "重试", model::forceRefreshLocalMusic) } }
                if (songs.isEmpty() && order != null && !model.localLoading && model.localError == null) item {
                    EmptyState("还没找到本地音乐", "将音频保存到手机的 Music 文件夹，再重新扫描。这里读取系统音乐库中的歌曲。", Icons.Rounded.MusicNote)
                }
                itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                    SongRow(song, song.key == model.current?.key, index, songs.size, showSource = false) {
                        if (!preview) model.play(songs, index, source = "local-library")
                    }
                }
            }
        }
        if (model.localPermission && !preview) LocalMusicScrollbar(listState, order?.labels.orEmpty(),
            leadingItems,
            Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp, bottom = bottomPadding))
        }
    }
    if (showOptions && !preview) ModalBottomSheet(onDismissRequest = { showOptions = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        val arrowRotation by animateFloatAsState(if (descending) 180f else 0f,
            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "local-sort-direction")
        Text("列表选项", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).scrollViewport(24.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                LocalSongSort.entries.forEachIndexed { index, option ->
                    val isSelected = option == sort
                    SegmentedListItem(onClick = { model.sortLocalSongs(option, descending) },
                        modifier = Modifier.semantics { selected = isSelected; role = Role.RadioButton },
                        shapes = ListItemDefaults.segmentedShapes(index, LocalSongSort.entries.size)
                            .copy(shape = segmentedOuterShape(index, LocalSongSort.entries.size)),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface),
                        trailingContent = { RadioButton(isSelected, onClick = null) }) {
                        Text(option.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { model.sortLocalSongs(sort, !descending) },
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
                    Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(20.dp).rotate(arrowRotation))
                    Spacer(Modifier.width(6.dp))
                    Text(if (descending) "降序" else "升序", fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = { showOptions = false; model.forceRefreshLocalMusic() }, enabled = !model.localLoading,
                    modifier = Modifier.weight(2f).fillMaxHeight().heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (model.localLoading) "正在扫描…" else "重新扫描")
                }
            }
        }
    }
}
