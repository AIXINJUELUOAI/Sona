@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.sona.music

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay

// The exact seven shapes Material 3's indeterminate loading indicator morphs through
// (LoadingIndicatorDefaults.IndeterminateIndicatorPolygons), reused as cover masks.
internal val coverPolygons: List<RoundedPolygon>
    get() = listOf(MaterialShapes.SoftBurst, MaterialShapes.Cookie9Sided, MaterialShapes.Pentagon,
        MaterialShapes.Pill, MaterialShapes.Sunny, MaterialShapes.Cookie4Sided, MaterialShapes.Oval)

// Stable per track, so recomposition, buffering and reopening never change the crop.
internal fun artworkShape(trackId: Long): Shape = MorphShape(coverPolygons[Math.floorMod(trackId, coverPolygons.size.toLong()).toInt()])

// Draws a normalized polygon (or a morph between two) scaled to the component bounds.
internal class MorphShape(private val start: RoundedPolygon, private val morph: Morph? = null, private val progress: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph?.toPath(progress) ?: start.toPath()
        path.transform(android.graphics.Matrix().apply { setScale(size.width, size.height) })
        return Outline.Generic(path.asComposePath())
    }
    override fun equals(other: Any?) = other is MorphShape && other.start === start && other.morph === morph && other.progress == progress
    override fun hashCode() = (start.hashCode() * 31 + morph.hashCode()) * 31 + progress.hashCode()
}

@Composable
internal fun PlaybackButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier,
    height: Dp, capsule: Boolean = false, iconSize: Dp = 32.dp, onPress: (Boolean) -> Unit = {},
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary) {
    MorphingControl(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        if (playing) "暂停" else "播放", onClick, modifier, height, round = capsule || !playing, iconSize = iconSize,
        onPress = onPress, containerColor = containerColor, contentColor = contentColor)
}

// Round at rest, squarer while pressed or playing: the expressive shape-morph feedback.
@Composable
internal fun MorphingControl(icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier, height: Dp, primary: Boolean = false, round: Boolean = true,
    iconSize: Dp = 32.dp, onPress: (Boolean) -> Unit = {},
    containerColor: androidx.compose.ui.graphics.Color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
    contentColor: androidx.compose.ui.graphics.Color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var click by remember { mutableIntStateOf(0) }
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(click) { if (click > 0) { pulse = true; delay(220); pulse = false } }
    val latestOnPress by rememberUpdatedState(onPress)
    LaunchedEffect(pressed, pulse) { latestOnPress(pressed || pulse) }
    val radius by animateDpAsState(height * when {
        pressed || pulse -> if (round) .30f else .22f
        round -> .50f
        else -> .32f
    }, MaterialTheme.motionScheme.fastSpatialSpec(), label = "control-corner")
    FilledIconButton(onClick = { click++; onClick() }, interactionSource = interaction,
        shape = RoundedCornerShape(radius.coerceAtLeast(0.dp)), modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor, contentColor = contentColor)) {
        Icon(icon, description, Modifier.size(iconSize))
    }
}

/** Shared top bar for secondary pages: tonal back button plus a single-line title. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}

internal val outerCornerShape = RoundedCornerShape(24.dp)

internal fun segmentedOuterShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 4.dp,
    topEnd = if (index == 0) 24.dp else 4.dp,
    bottomStart = if (index == count - 1) 24.dp else 4.dp,
    bottomEnd = if (index == count - 1) 24.dp else 4.dp)

/** Bottom content padding that clears the gesture/three-button bar and the floating mini player. */
@Composable
internal fun contentBottomPadding(playerVisible: Boolean): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (playerVisible) 96.dp else 24.dp

/** Icon set inside an expressive shape, used for empty states and the profile badge. */
@Composable
internal fun ShapeBadge(icon: ImageVector, polygon: RoundedPolygon, size: Dp, modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Surface(modifier.size(size), shape = remember(polygon) { MorphShape(polygon) }, color = color, contentColor = content) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * .45f)) }
    }
}

internal val pillShape = CircleShape
