package app.zcomic

internal data class ReaderPanBounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

// Bound translation using the rendered image, including Fit's letterboxing.
internal fun readerPanBounds(width: Float, height: Float, ratio: Float, scale: Float, fillWidth: Boolean,
    left: Float = 0f, top: Float = 0f, right: Float = width, bottom: Float = height): ReaderPanBounds {
    val imageWidth = if (fillWidth) width else minOf(width, height * ratio)
    val imageHeight = imageWidth / ratio
    val minX = right - (width + imageWidth * scale) / 2f
    val maxX = left - (width - imageWidth * scale) / 2f
    val minY = bottom - (height + imageHeight * scale) / 2f
    val maxY = top - (height - imageHeight * scale) / 2f
    return ReaderPanBounds(if (minX <= maxX) minX else 0f, if (minX <= maxX) maxX else 0f,
        if (minY <= maxY) minY else 0f, if (minY <= maxY) maxY else 0f)
}
