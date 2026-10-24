@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlin.math.roundToInt

@UnstableApi
@Composable
@Suppress("DEPRECATION")
internal fun MediaOutputSheet(playing: Boolean, onPlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    previousEnabled: Boolean, nextEnabled: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val output = (context.applicationContext as MusicApplication).output
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(output, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            output.setVisible(true)
            try { awaitCancellation() } finally { output.setVisible(false) }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val container = scheme.surfaceContainerLow
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = container,
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = scheme.surface.luminance() > .5f,
            isAppearanceLightNavigationBars = container.luminance() > .5f)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let {
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                it.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
                it.isNavigationBarContrastEnforced = false
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
            .testTag("media-output-sheet"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放设备", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(onClick = onPrevious, enabled = previousEnabled, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(26.dp))
                    }
                }
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    PlaybackButton(playing, onPlay, Modifier.size(48.dp), height = 48.dp, iconSize = 28.dp)
                }
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(onClick = onNext, enabled = nextEnabled, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(26.dp))
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp)
                .scrollViewport(),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(output.devices, key = { _, device -> device.id }) { index, device ->
                    val active = output.selection.actualId == device.id
                    val pending = output.selection.targetId == device.id
                    val selected = output.selectedId == device.id
                    val top by animateDpAsState(if (selected) 22.dp else if (index == 0) 20.dp else 5.dp,
                        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "device-top")
                    val bottom by animateDpAsState(if (selected) 22.dp else if (index == output.devices.lastIndex) 20.dp else 5.dp,
                        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "device-bottom")
                    val iconCorner by animateDpAsState(if (selected) 24.dp else 14.dp,
                        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "device-icon")
                    val color by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
                        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "device-color")
                    Surface(color = color, contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                        shape = RoundedCornerShape(topStart = top.coerceAtLeast(0.dp), topEnd = top.coerceAtLeast(0.dp),
                            bottomStart = bottom.coerceAtLeast(0.dp), bottomEnd = bottom.coerceAtLeast(0.dp))) {
                        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                            Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = output.ready,
                                role = Role.RadioButton, onClick = { output.select(device.id) })
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = RoundedCornerShape(iconCorner.coerceAtLeast(0.dp)),
                                    color = if (selected) scheme.primary else scheme.surfaceContainerHighest,
                                    contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(when (device.kind) {
                                            OutputKind.Phone -> Icons.Rounded.PhoneAndroid
                                            OutputKind.Bluetooth -> Icons.Rounded.BluetoothAudio
                                            OutputKind.Headphones -> Icons.Rounded.Headphones
                                            OutputKind.Speaker -> Icons.Rounded.Speaker
                                        }, null, Modifier.size(26.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(when {
                                        pending -> if (output.playing) "正在切换…" else "播放后确认"
                                        active -> if (output.playing) "当前设备" else "上次输出"
                                        selected -> "默认设备"
                                        else -> "轻点切换"
                                    }, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (pending) {
                                    if (output.playing) LoadingIndicator(Modifier.size(24.dp))
                                    else Icon(Icons.Rounded.Schedule, "待播放确认", Modifier.size(24.dp))
                                } else if (selected) Icon(Icons.Rounded.Check, if (active) "已确认输出" else "默认输出", Modifier.size(24.dp))
                            }
                            if (selected && output.selection.targetId == null) {
                                if (output.canAdjustVolume(device.id)) key(device.id, output.volumeMax) {
                                    DeviceVolume(device, output)
                                } else FilledTonalButton(onClick = { openSystemOutput(context) }, shapes = ButtonDefaults.shapes(),
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()) {
                                    Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(if (output.volumeFixed) "在设备上调整音量" else "在系统面板调整音量", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            if (output.devices.isEmpty()) Text("暂未读取到音频输出设备", Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            output.message?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = scheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@UnstableApi
@Composable
private fun DeviceVolume(device: OutputDevice, output: PlaybackOutput) {
    var dragging by remember { mutableStateOf(false) }
    var preview by remember { mutableFloatStateOf(0f) }
    val max = output.volumeMax.coerceAtLeast(1).toFloat()
    val slider = remember(device.id, output.volumeMax) { SliderState(steps = (output.volumeMax - 1).coerceAtLeast(0)) }
    slider.value = ((if (dragging) preview else output.volume.toFloat()) / max).coerceIn(0f, 1f)
    val scheme = MaterialTheme.colorScheme
    Slider(state = slider, onValueChange = { dragging = true; preview = it * max; output.setVolume(device.id, preview) },
        onValueChangeFinished = { dragging = false; output.refresh() },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().heightIn(min = 56.dp)
            .testTag("output-volume").semantics { contentDescription = "${device.name}音量" },
        thumb = {
            Box(Modifier.size(12.dp, 48.dp).zIndex(1f)
                .background(scheme.secondaryContainer, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(4.dp, 48.dp).background(scheme.primary, RoundedCornerShape(2.dp)))
            }
        },
        track = { state ->
            val fraction = state.value.coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceContainerLow)
                .semantics(mergeDescendants = true) {}) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(scheme.primary))
                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (fraction == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        null, Modifier.size(20.dp), tint = if (fraction > .15f) scheme.onPrimary else scheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge,
                        color = if (fraction > .9f) scheme.onPrimary else scheme.onSurfaceVariant)
                }
            }
        })
}

private fun openSystemOutput(context: Context) {
    if (Build.VERSION.SDK_INT >= 34 && runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
    runCatching { context.startActivity(Intent(Settings.Panel.ACTION_VOLUME)) }
}
