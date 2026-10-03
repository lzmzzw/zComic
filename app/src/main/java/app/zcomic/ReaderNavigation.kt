package app.zcomic

import kotlin.math.abs

internal enum class ReaderAction { TOOLBAR, PREVIOUS, NEXT }

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
