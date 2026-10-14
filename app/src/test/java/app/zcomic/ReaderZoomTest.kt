package app.zcomic

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomTest {
    @Test fun landscapeStartsAtTwoThirdsAndNormalWidthsStayInsideViewport() {
        assertEquals(2f / 3f, readerLandscapeWidth(Float.NaN), 0f)
        assertEquals(2f / 3f, readerLandscapeWidth(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.8f, readerLandscapeWidth(0.8f), 0f)
        assertEquals(1f, readerLandscapeWidth(1.2f), 0f)
        assertEquals(0.2f, readerLandscapeWidth(0.01f), 0f)
    }

    @Test fun landscapePinchCanCrossAndReturnThroughFullScreenWidth() {
        val wider = readerLandscapeGestureWidth(2f / 3f, 1.2f)
        assertEquals(0.8f, wider, 0.00001f)
        val zoomed = readerLandscapeGestureWidth(wider, 1.5f)
        assertEquals(1.2f, zoomed, 0.00001f)
        assertEquals(0.6f, readerLandscapeGestureWidth(zoomed, 0.5f), 0.00001f)
        assertEquals(5f, readerLandscapeGestureWidth(1f, 10f), 0f)
    }

    @Test fun reducedContinuousWidthHasNoPanningUntilItExceedsViewport() {
        assertEquals(ReaderPanBounds(0f, 0f, 0f, 0f),
            readerPanBounds(900f, 1200f, 0.5f, 1f, true, widthFraction = 2f / 3f))
        assertEquals(ReaderPanBounds(-90f, 90f, -180f, 180f),
            readerPanBounds(900f, 1800f, 0.5f, 1.2f, true))
    }

    @Test fun fitBoundsAccountForLetterboxing() {
        assertEquals(ReaderPanBounds(0f, 0f, 0f, 0f), readerPanBounds(300f, 600f, 1f, 1f, false))
        assertEquals(ReaderPanBounds(-150f, 150f, 0f, 0f), readerPanBounds(300f, 600f, 1f, 2f, false))
        assertEquals(ReaderPanBounds(-300f, 300f, -150f, 150f), readerPanBounds(300f, 600f, 1f, 3f, false))
    }

    @Test fun wideViewportAllowsPanningTheFullTallPage() {
        assertEquals(ReaderPanBounds(-300f, 300f, -1050f, 1050f), readerPanBounds(600f, 300f, 0.5f, 2f, true))
        assertEquals(ReaderPanBounds(-75f, 75f, -600f, 600f), readerPanBounds(600f, 300f, 0.5f, 5f, false))
    }

    @Test fun zoomingAtBottomOfLongContinuousPageCanReachBothEnds() {
        val bounds = readerPanBounds(1000f, 4000f, 0.25f, 2f, true, top = 3000f, bottom = 4000f)
        assertEquals(-2000f, bounds.minY, 0f)
        assertEquals(5000f, bounds.maxY, 0f)
        // Image top/bottom align with the visible viewport at opposite limits.
        assertEquals(3000f, -2000f + bounds.maxY, 0f)
        assertEquals(4000f, 6000f + bounds.minY, 0f)
    }
}
