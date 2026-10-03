package app.zcomic.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicMetadataTest {
    @Test fun importedVolumesGroupByTitleAndSortNumerically() {
        assertEquals("魔男伊奇", comicTitle("魔男伊奇 第02卷", "folder"))
        assertEquals("魔男伊奇", comicTitle("魔男伊奇 Vol. 12", "folder"))
        assertEquals("folder", comicTitle("未命名漫画", "folder"))
        assertEquals(2, volumeNumber("魔男伊奇 卷02"))
        assertEquals(12, volumeNumber("魔男伊奇 Vol. 12"))
    }

    @Test fun progressNeverExceedsTheProgressIndicatorRange() {
        assertEquals(0f, downloadProgress(10, 0), .001f)
        assertEquals(1f, downloadProgress(200, 100), .001f)
        assertEquals(0f, downloadProgress(-10, 100), .001f)
        assertEquals(.5f, downloadProgress(50, 100), .001f)
    }
}
