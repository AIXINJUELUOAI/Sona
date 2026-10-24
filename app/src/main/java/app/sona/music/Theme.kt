@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:SuppressLint("RestrictedApi")
package app.sona.music

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SonaTheme(appearance: String, content: @Composable (Boolean, ColorScheme) -> Unit) {
    val dark = when (appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val scheme = remember(dark, context) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else coverColorScheme(0xff73519a.toInt(), dark)
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) {
        // Shared by app controls and nested player themes; keep shape/scale press feedback.
        CompositionLocalProvider(LocalRippleConfiguration provides null) { content(dark, scheme) }
    }
}

@Composable
fun rememberCoverScheme(cover: String?, dark: Boolean, fallback: ColorScheme): ColorScheme {
    val context = LocalContext.current
    val revision = if (cover?.startsWith("content://media/") == true) localArtworkRevision else 0L
    val seed by produceState<Int?>(null, cover, revision) {
        if (cover.isNullOrBlank()) { value = null; return@produceState }
        value = runCatching {
            val result = context.imageLoader.execute(artworkRequest(context, cover, revision).size(128).allowHardware(false).build())
            if (result !is SuccessResult) return@runCatching null
            withContext(Dispatchers.Default) { coverSeed(result.drawable.toBitmap(128, 128)) }
        }.getOrNull()
    }
    // Monochrome artwork has no usable hue; keep the system accent instead of a muddy scheme.
    val target = remember(seed, dark, fallback, cover) {
        if (cover.isNullOrBlank()) fallback else coverColorScheme(seed ?: fallback.primary.toArgb(), dark)
    }
    // Keep the last palette while the next cover loads, then cross-fade every role together.
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = lerpScheme(from, to, progress.value); to = target
        progress.snapTo(0f); progress.animateTo(1f, spec)
    }
    return if (progress.value >= 1f) to else lerpScheme(from, to, progress.value)
}

// Seed selection follows Material You: Celebi quantization plus the official Score ranking.
private fun coverSeed(bitmap: android.graphics.Bitmap): Int? {
    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    val ranked = Score.score(QuantizerCelebi.quantize(pixels, 128), 1, 0, true)
    return ranked.firstOrNull()?.takeIf { it != 0 }
}

/** Official TonalSpot dynamic scheme generated from the cover seed, the same algorithm the system uses for wallpapers. */
fun coverColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    val roles = MaterialDynamicColors()
    fun c(color: DynamicColor) = Color(color.getArgb(scheme))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(roles.primary()), onPrimary = c(roles.onPrimary()),
        primaryContainer = c(roles.primaryContainer()), onPrimaryContainer = c(roles.onPrimaryContainer()),
        inversePrimary = c(roles.inversePrimary()),
        secondary = c(roles.secondary()), onSecondary = c(roles.onSecondary()),
        secondaryContainer = c(roles.secondaryContainer()), onSecondaryContainer = c(roles.onSecondaryContainer()),
        tertiary = c(roles.tertiary()), onTertiary = c(roles.onTertiary()),
        tertiaryContainer = c(roles.tertiaryContainer()), onTertiaryContainer = c(roles.onTertiaryContainer()),
        background = c(roles.background()), onBackground = c(roles.onBackground()),
        surface = c(roles.surface()), onSurface = c(roles.onSurface()),
        surfaceVariant = c(roles.surfaceVariant()), onSurfaceVariant = c(roles.onSurfaceVariant()),
        surfaceTint = c(roles.surfaceTint()), inverseSurface = c(roles.inverseSurface()), inverseOnSurface = c(roles.inverseOnSurface()),
        error = c(roles.error()), onError = c(roles.onError()), errorContainer = c(roles.errorContainer()), onErrorContainer = c(roles.onErrorContainer()),
        outline = c(roles.outline()), outlineVariant = c(roles.outlineVariant()), scrim = c(roles.scrim()),
        surfaceBright = c(roles.surfaceBright()), surfaceDim = c(roles.surfaceDim()),
        surfaceContainer = c(roles.surfaceContainer()), surfaceContainerHigh = c(roles.surfaceContainerHigh()),
        surfaceContainerHighest = c(roles.surfaceContainerHighest()), surfaceContainerLow = c(roles.surfaceContainerLow()),
        surfaceContainerLowest = c(roles.surfaceContainerLowest()),
        primaryFixed = c(roles.primaryFixed()), primaryFixedDim = c(roles.primaryFixedDim()),
        onPrimaryFixed = c(roles.onPrimaryFixed()), onPrimaryFixedVariant = c(roles.onPrimaryFixedVariant()),
        secondaryFixed = c(roles.secondaryFixed()), secondaryFixedDim = c(roles.secondaryFixedDim()),
        onSecondaryFixed = c(roles.onSecondaryFixed()), onSecondaryFixedVariant = c(roles.onSecondaryFixedVariant()),
        tertiaryFixed = c(roles.tertiaryFixed()), tertiaryFixedDim = c(roles.tertiaryFixedDim()),
        onTertiaryFixed = c(roles.onTertiaryFixed()), onTertiaryFixedVariant = c(roles.onTertiaryFixedVariant()))
}

private fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    fun l(x: Color, y: Color) = lerp(x, y, t)
    return b.copy(
        primary = l(a.primary, b.primary), onPrimary = l(a.onPrimary, b.onPrimary),
        primaryContainer = l(a.primaryContainer, b.primaryContainer), onPrimaryContainer = l(a.onPrimaryContainer, b.onPrimaryContainer),
        inversePrimary = l(a.inversePrimary, b.inversePrimary),
        secondary = l(a.secondary, b.secondary), onSecondary = l(a.onSecondary, b.onSecondary),
        secondaryContainer = l(a.secondaryContainer, b.secondaryContainer), onSecondaryContainer = l(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = l(a.tertiary, b.tertiary), onTertiary = l(a.onTertiary, b.onTertiary),
        tertiaryContainer = l(a.tertiaryContainer, b.tertiaryContainer), onTertiaryContainer = l(a.onTertiaryContainer, b.onTertiaryContainer),
        background = l(a.background, b.background), onBackground = l(a.onBackground, b.onBackground),
        surface = l(a.surface, b.surface), onSurface = l(a.onSurface, b.onSurface),
        surfaceVariant = l(a.surfaceVariant, b.surfaceVariant), onSurfaceVariant = l(a.onSurfaceVariant, b.onSurfaceVariant),
        outline = l(a.outline, b.outline), outlineVariant = l(a.outlineVariant, b.outlineVariant),
        surfaceContainer = l(a.surfaceContainer, b.surfaceContainer), surfaceContainerHigh = l(a.surfaceContainerHigh, b.surfaceContainerHigh),
        surfaceContainerHighest = l(a.surfaceContainerHighest, b.surfaceContainerHighest), surfaceContainerLow = l(a.surfaceContainerLow, b.surfaceContainerLow),
        surfaceContainerLowest = l(a.surfaceContainerLowest, b.surfaceContainerLowest),
        primaryFixed = l(a.primaryFixed, b.primaryFixed), primaryFixedDim = l(a.primaryFixedDim, b.primaryFixedDim),
        onPrimaryFixed = l(a.onPrimaryFixed, b.onPrimaryFixed), onPrimaryFixedVariant = l(a.onPrimaryFixedVariant, b.onPrimaryFixedVariant),
        secondaryFixed = l(a.secondaryFixed, b.secondaryFixed), secondaryFixedDim = l(a.secondaryFixedDim, b.secondaryFixedDim),
        onSecondaryFixed = l(a.onSecondaryFixed, b.onSecondaryFixed), onSecondaryFixedVariant = l(a.onSecondaryFixedVariant, b.onSecondaryFixedVariant),
        tertiaryFixed = l(a.tertiaryFixed, b.tertiaryFixed), tertiaryFixedDim = l(a.tertiaryFixedDim, b.tertiaryFixedDim),
        onTertiaryFixed = l(a.onTertiaryFixed, b.onTertiaryFixed), onTertiaryFixedVariant = l(a.onTertiaryFixedVariant, b.onTertiaryFixedVariant))
}
