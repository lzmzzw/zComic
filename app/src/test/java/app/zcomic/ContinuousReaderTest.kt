package app.zcomic

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousReaderTest {
    @Test fun zoomAndReverseKeepCrossPageContentUnderFingerMidpoint() {
        val offset = readerZoomAnchorOffset(1100f, 100f, 100f, 2f)
        val zoomed = readerContinuousPosition(listOf(0.5f, 0.5f), 0, offset, 1200f, 600f)
        assertEquals(ReaderScrollPosition(0, 2300), zoomed.position)
        val restored = readerZoomAnchorOffset(zoomed.anchorOffset, 100f, 100f, 0.5f)
        assertEquals(ReaderScrollPosition(0, 1100),
            readerContinuousPosition(listOf(0.5f, 0.5f), 0, restored, 600f, 600f).position)
    }

    @Test fun shrinkingLongPageBottomTracksContentWithoutOldOffsetOverflow() {
        val offset = readerZoomAnchorOffset(1100f, 100f, 100f, 0.45f)
        assertEquals(440f, offset, 0.001f)
        assertEquals(ReaderScrollPosition(0, 440),
            readerContinuousPosition(listOf(0.5f, 0.5f), 0, offset, 270f, 600f).position)
    }

    @Test fun dragCrossesBothPageEdgesAndClampsAtBookEnds() {
        val ratios = listOf(0.5f, 0.5f, 0.5f)
        assertEquals(ReaderScrollPosition(1, 100), readerContinuousPosition(ratios, 0, 1300f, 600f, 600f).position)
        assertEquals(ReaderScrollPosition(0, 1100), readerContinuousPosition(ratios, 1, -100f, 600f, 600f).position)
        val start = readerContinuousPosition(ratios, 1, -5000f, 600f, 600f)
        assertEquals(ReaderScrollPosition(0, 0), start.position)
        assertEquals(-1200f, start.anchorOffset, 0f)
        val end = readerContinuousPosition(ratios, 0, 5000f, 600f, 600f)
        assertEquals(ReaderScrollPosition(2, 600), end.position)
        assertEquals(3000f, end.anchorOffset, 0f)
        // Reversal after reaching the end responds immediately, without an overscroll debt.
        assertEquals(ReaderScrollPosition(2, 590),
            readerContinuousPosition(ratios, 0, end.anchorOffset - 10f, 600f, 600f).position)
    }

    @Test fun horizontalAnchorFollowsMidpointAndStaysWithinColumnEdges() {
        assertEquals(125f, readerContinuousPanX(0f, 200f, 200f, 1.5f, 900f, 1.5f), 0f)
        assertEquals(225f, readerContinuousPanX(0f, 450f, 1000f, 1f, 900f, 1.5f), 0f)
        assertEquals(0f, readerContinuousPanX(200f, 450f, 500f, 0.5f, 900f, 0.8f), 0f)
    }

    @Test fun shortBookAndInvalidRatioStillHaveValidPosition() {
        assertEquals(ReaderScrollPosition(0, 0), readerContinuousPosition(listOf(1f), 0, 100f, 300f, 600f).position)
        assertEquals(ReaderScrollPosition(0, 10), readerContinuousPosition(listOf(Float.NaN), 0, 10f, 720f, 100f).position)
    }
}
