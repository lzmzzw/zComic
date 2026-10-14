package app.zcomic

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomTest {
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
