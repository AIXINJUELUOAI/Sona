@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/** Single account entry: local identity, NetEase sign-in and sign-out all live here. */
@Composable
internal fun AccountSheet(model: MusicViewModel, onDismiss: () -> Unit, onLogin: () -> Unit) {
    val profile = model.profile
    val scheme = MaterialTheme.colorScheme
    var editingName by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.saveLocalAvatar(uri)
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BottomSheetDefaults.ContainerColor) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(model)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(displayName(model), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(connected = profile != null)
                                Text(if (profile == null) "未连接网易云" else "网易云音乐",
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (profile == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            AccountItem(0, 2, Icons.Rounded.AddAPhoto,
                                scheme.secondaryContainer, scheme.onSecondaryContainer, "更换头像") {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            AccountItem(1, 2, Icons.Rounded.Edit,
                                scheme.primaryContainer, scheme.onPrimaryContainer, "修改昵称",
                                value = model.localName.ifBlank { "未设置" }) { editingName = true }
                        }
                    } else {
                        AccountItem(0, 1, Icons.Rounded.Refresh,
                            scheme.secondaryContainer, scheme.onSecondaryContainer, "刷新资料",
                            value = null) { model.refreshProfile() }
                    }
                }
            }
            if (profile == null) {
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(56.dp),
                    shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Login, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("登录网易云音乐", fontWeight = FontWeight.SemiBold)
                }
                Text("本地头像和昵称只保存在这台设备", Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                    border = BorderStroke(1.dp, scheme.error.copy(alpha = .45f))) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (editingName) NameDialog(model.localName, onDismiss = { editingName = false }) { name ->
        model.saveLocalName(name)
        editingName = false
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text("退出登录？") },
        text = { Text("退出后将停止播放云端歌曲；本地收藏和收听记录不受影响。") },
        confirmButton = {
            TextButton(onClick = { confirmLogout = false; model.logout() }) { Text("退出登录") }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

private fun displayName(model: MusicViewModel): String =
    model.profile?.name?.takeIf { it.isNotBlank() } ?: model.localName.ifBlank { "你的音乐日常" }

@Composable
private fun AccountAvatar(model: MusicViewModel) {
    val scheme = MaterialTheme.colorScheme
    val remote = model.profile?.avatarUrl.orEmpty()
    val local = model.localAvatar
    Box(Modifier.size(64.dp)) {
        when {
            remote.isNotBlank() -> AsyncImage(model = remote, contentDescription = "头像",
                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            local.isNotBlank() -> AsyncImage(model = File(local), contentDescription = "头像",
                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else -> ShapeBadge(Icons.Rounded.Person, MaterialShapes.Cookie9Sided, 64.dp,
                color = scheme.secondaryContainer, content = scheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp),
        color = if (connected) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (connected) scheme.onPrimaryContainer else scheme.onSurfaceVariant) {
        Text(if (connected) "已连接" else "本地模式", style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun AccountItem(index: Int, count: Int, icon: ImageVector, container: Color, content: Color,
    title: String, value: String? = null, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count).copy(shape = segmentedOuterShape(index, count)),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            Surface(shape = CircleShape, color = container, contentColor = content) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
            }
        },
        trailingContent = {
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Icon(Icons.Rounded.ChevronRight, null)
        }) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("昵称") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(24) }, singleLine = true,
                label = { Text("你的昵称") }, placeholder = { Text("你的音乐日常") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
