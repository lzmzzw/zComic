package app.zcomic.data

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires a device: Robolectric on Windows has no native PDF engine. */
@RunWith(AndroidJUnit4::class)
class PdfBookDeviceTest {
    @Test fun rendersOriginalPdfPagesAndReleasesDescriptor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("pdf-test-", ".pdf", context.cacheDir)
        try {
            val pdf = PdfDocument()
            try {
                listOf(400 to 600, 600 to 400).forEachIndexed { index, (width, height) ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                    page.canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val book = BookReader.open(context, Uri.fromFile(file))
            try {
                assertEquals(BookFormat.PDF, book.format)
                assertEquals(2, book.pages.size)
                assertEquals(2f / 3f, book.pageRatios[0], 0.001f)
                val bitmap = requireNotNull(book.bitmap(1, 512))
                assertEquals(512, bitmap.width)
                assertEquals(Color.BLUE, bitmap.getPixel(100, 100))
                assertNull(book.bitmap(2)); assertNotNull(book.cover)
            } finally { book.close() }
            book.close()
            assertThrows(IllegalStateException::class.java) { book.bitmap(0) }
        } finally { file.delete() }
    }

    @Test fun installedManifestHandlesPdfAndMobiButNotUnrelatedBinaryFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun resolves(uri: String, type: String?): Boolean =
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), type).setPackage(context.packageName), 0).isNotEmpty()
        assertTrue(resolves("content://documents/document/12345", "application/pdf"))
        assertTrue(resolves("content://documents/document/12345", "application/x-mobipocket-ebook"))
        assertTrue(resolves("content://documents/books/a.b.mobi", "application/octet-stream"))
        assertFalse(resolves("content://documents/books/program.exe", "application/octet-stream"))
        assertTrue(resolves("file:///storage/emulated/0/book.pdf", null))
        assertFalse(resolves("file:///storage/emulated/0/notes.txt", null))
    }
}
