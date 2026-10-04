package app.zcomic.data

import org.junit.Assert.*
import org.junit.Test

class BookFormatTest {
    @Test fun extensionDetectionHandlesUppercaseAndMultipleDots() {
        assertEquals(BookFormat.PDF, BookFormat.fromName("魔男伊奇.第1卷.PDF"))
        assertEquals(BookFormat.MOBI, BookFormat.fromName("book.mobi"))
        assertEquals(BookFormat.EPUB, BookFormat.fromName("book.EPUB"))
        assertNull(BookFormat.fromName("book.pdf.exe"))
        assertNull(BookFormat.fromName("mobi"))
    }

    @Test fun recognizedMimeTypesIncludeBothMobiConventions() {
        assertEquals(BookFormat.MOBI, BookFormat.fromMimeType("application/vnd.amazon.mobi"))
        assertEquals(BookFormat.MOBI, BookFormat.fromMimeType("application/x-mobipocket-ebook"))
        assertEquals(BookFormat.PDF, BookFormat.fromMimeType("Application/PDF; charset=binary"))
        assertNull(BookFormat.fromMimeType("application/octet-stream"))
    }
}
