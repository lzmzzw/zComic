package app.zcomic

internal data class ReaderPanBounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

internal fun readerLandscapeWidth(width: Float): Float =
    if (width.isFinite()) width.coerceIn(0.2f, 1f) else 2f / 3f

internal fun readerLandscapeGestureWidth(width: Float, zoom: Float): Float =
    (width * zoom).coerceIn(0.2f, 5f)

// Keep the receiving LazyColumn item mounted while a pinch previews a smaller width.
internal fun readerLandscapeLayoutWidth(displayWidth: Float, gestureStartWidth: Float?): Float =
    (gestureStartWidth ?: displayWidth).coerceAtMost(1f)

// Bound translation using the rendered image, including Fit's letterboxing.
internal fun readerPanBounds(width: Float, height: Float, ratio: Float, scale: Float, fillWidth: Boolean,
    left: Float = 0f, top: Float = 0f, right: Float = width, bottom: Float = height,
    widthFraction: Float = 1f): ReaderPanBounds {
    val imageWidth = if (fillWidth) width * widthFraction else minOf(width * widthFraction, height * ratio)
    val imageHeight = imageWidth / ratio
    val minX = right - (width + imageWidth * scale) / 2f
    val maxX = left - (width - imageWidth * scale) / 2f
    val minY = bottom - (height + imageHeight * scale) / 2f
    val maxY = top - (height - imageHeight * scale) / 2f
    return ReaderPanBounds(if (minX <= maxX) minX else 0f, if (minX <= maxX) maxX else 0f,
        if (minY <= maxY) minY else 0f, if (minY <= maxY) maxY else 0f)
}
