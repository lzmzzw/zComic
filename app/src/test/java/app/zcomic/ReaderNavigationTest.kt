package app.zcomic

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderNavigationTest {
    @Test fun onlyCenterThirdInBothAxesOpensToolbar() {
        assertEquals(ReaderAction.TOOLBAR, readerTapAction(150f, 300f, 300f, 600f, "从右往左"))
        assertEquals(ReaderAction.NEXT, readerTapAction(50f, 300f, 300f, 600f, "从右往左"))
        assertEquals(ReaderAction.NEXT, readerTapAction(149f, 100f, 300f, 600f, "从右往左"))
        assertEquals(ReaderAction.PREVIOUS, readerTapAction(250f, 300f, 300f, 600f, "从右往左"))
        assertEquals(ReaderAction.NEXT, readerTapAction(250f, 100f, 300f, 600f, "上下连续"))
    }

    @Test fun horizontalSwipeFollowsReadingDirection() {
        assertEquals(ReaderAction.NEXT, readerSwipeAction(-80f, "从左往右"))
        assertEquals(ReaderAction.PREVIOUS, readerSwipeAction(80f, "从左往右"))
        assertEquals(ReaderAction.NEXT, readerSwipeAction(80f, "从右往左"))
        assertEquals(ReaderAction.PREVIOUS, readerSwipeAction(-80f, "从右往左"))
        assertEquals(null, readerSwipeAction(20f, "从左往右"))
    }
}
