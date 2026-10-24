package app.sona.music

internal const val PageMotionDuration = 320

internal data class PageTransform(val scale: Float, val translationX: Float)

internal fun pageTransform(width: Float, minMargin: Float, progress: Float, direction: Int, travel: Float = 0f): PageTransform {
    val preview = progress.coerceIn(0f, 1f)
    // The opposite edge retains a margin; the swiped edge gets the remaining free space.
    val shift = (width * 0.05f - minMargin).coerceAtLeast(0f) * preview * direction
    // Navigation always moves right on exit, including a preview started at the right edge.
    return PageTransform(1f - 0.1f * preview, shift + width * travel.coerceIn(0f, 1f))
}
