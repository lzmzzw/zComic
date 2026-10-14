package app.zcomic

import kotlin.math.roundToInt

internal data class ReaderPanBounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

internal data class ReaderScrollPosition(val index: Int, val offset: Int)
internal data class ReaderContinuousTarget(val position: ReaderScrollPosition, val anchorOffset: Float)

internal fun readerZoomAnchorOffset(offset: Float, previousY: Float, currentY: Float, factor: Float): Float =
    (offset + previousY) * factor - currentY

internal fun readerContinuousPanX(pan: Float, previousX: Float, currentX: Float, factor: Float,
    viewportWidth: Float, width: Float): Float {
    val limit = (viewportWidth * (width - 1f) / 2f).coerceAtLeast(0f)
    return (currentX - viewportWidth / 2f - (previousX - viewportWidth / 2f - pan) * factor).coerceIn(-limit, limit)
}

// Normalize across page edges using the same rounded heights as aspectRatio layout.
internal fun readerContinuousPosition(ratios: List<Float>, anchorIndex: Int, offset: Float,
    pageWidth: Float, viewportHeight: Float): ReaderContinuousTarget {
    if (ratios.isEmpty()) return ReaderContinuousTarget(ReaderScrollPosition(0, 0), 0f)
    val prefix = DoubleArray(ratios.size + 1)
    ratios.forEachIndexed { index, raw ->
        val ratio = raw.takeIf { it.isFinite() && it > 0f } ?: 0.72f
        prefix[index + 1] = prefix[index] + (pageWidth / ratio).roundToInt().coerceAtLeast(1)
    }
    val anchor = prefix[anchorIndex.coerceIn(ratios.indices)]
    val absolute = (anchor + offset).coerceIn(0.0, (prefix.last() - viewportHeight).coerceAtLeast(0.0))
    var low = 0
    var high = ratios.lastIndex
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (prefix[middle] <= absolute) low = middle else high = middle - 1
    }
    return ReaderContinuousTarget(ReaderScrollPosition(low, (absolute - prefix[low]).roundToInt()),
        (absolute - anchor).toFloat())
}

internal fun readerLandscapeWidth(width: Float): Float =
    if (width.isFinite()) width.coerceIn(0.2f, 1f) else 2f / 3f

internal fun readerLandscapeGestureWidth(width: Float, zoom: Float): Float =
    (width * zoom).coerceIn(0.2f, 5f)

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
