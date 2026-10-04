package app.zcomic

import kotlin.math.abs

internal enum class ReaderAction { TOOLBAR, PREVIOUS, NEXT }

internal data class ReaderTurnOffsets(val outgoing: Float, val incoming: Float)

// Both opaque pages travel together, one viewport apart, without a brightness overlay.
internal fun readerTurnOffsets(progress: Float, forward: Boolean, mode: String): ReaderTurnOffsets {
    val position = progress.coerceIn(0f, 1f)
    val direction = if (forward == (mode == "从左往右")) -1f else 1f
    return ReaderTurnOffsets(direction * position, -direction * (1f - position))
}

internal fun readerTapAction(x: Float, y: Float, width: Float, height: Float, mode: String): ReaderAction {
    if (x >= width / 3f && x < width * 2f / 3f && y >= height / 3f && y < height * 2f / 3f) {
        return ReaderAction.TOOLBAR
    }
    val nextOnLeft = mode == "从右往左"
    return if ((x < width / 2f) == nextOnLeft) ReaderAction.NEXT else ReaderAction.PREVIOUS
}

internal fun readerSwipeAction(dragX: Float, mode: String): ReaderAction? {
    if (abs(dragX) < 40f) return null
    val nextOnLeftSwipe = mode == "从左往右"
    return if ((dragX < 0f) == nextOnLeftSwipe) ReaderAction.NEXT else ReaderAction.PREVIOUS
}
