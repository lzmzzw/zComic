package app.zcomic

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderNavigationTest {
    @Test fun pageTurnKeepsPagesAdjacentAndEndsAtRestInBothDirections() {
        for (mode in listOf("从左往右", "从右往左")) {
            for (forward in listOf(true, false)) {
                val direction = if (forward == (mode == "从左往右")) -1f else 1f
                for (step in 0..100) {
                    val progress = step / 100f
                    val offsets = readerTurnOffsets(progress, forward, mode)
                    assertEquals(direction * progress, offsets.outgoing, 0.00001f)
                    // The shared page edge must not expose a gap or overlap during the turn.
                    assertEquals(-direction, offsets.incoming - offsets.outgoing, 0.00001f)
                }
                assertEquals(0f, readerTurnOffsets(0f, forward, mode).outgoing, 0f)
                assertEquals(0f, readerTurnOffsets(1f, forward, mode).incoming, 0f)
                assertEquals(readerTurnOffsets(0f, forward, mode), readerTurnOffsets(-0.1f, forward, mode))
                assertEquals(readerTurnOffsets(1f, forward, mode), readerTurnOffsets(1.1f, forward, mode))
            }
        }
    }

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
