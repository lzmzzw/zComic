package app.zcomic.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookReaderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun mobiUsesImageReferenceOrderAndKeepsTextContent() = runTest {
        fun image(color: Int): ByteArray = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
            }
        }.toByteArray()
        val text = "<p>开始文字</p><img recindex='2'/><img recindex='1'/><p>结束文字</p>".toByteArray()
        val title = "测试漫画".toByteArray()
        val header = ByteBuffer.allocate(264 + title.size).apply {
            putShort(1); putShort(0); putInt(text.size); putShort(1); putShort(4096)
            putShort(0); putShort(0); put("MOBI".toByteArray()); putInt(248)
            putInt(28, 65001); putInt(84, 264); putInt(88, title.size); putInt(104, 6); putInt(108, 2)
            position(264); put(title)
        }.array()
        val records = listOf(header, text, image(Color.RED), image(Color.BLUE))
        val table = 78 + records.size * 8
        val bytes = ByteBuffer.allocate(table + records.sumOf { it.size }).apply {
            position(60); put("BOOKMOBI".toByteArray()); position(76); putShort(records.size.toShort())
            var offset = table
            records.forEach { putInt(offset); putInt(0); offset += it.size }; records.forEach { put(it) }
        }.array()
        val file = folder.newFile("漫画.mobi").also { it.writeBytes(bytes) }
        BookReader.open(RuntimeEnvironment.getApplication(), Uri.fromFile(file)).use { book ->
            assertEquals(BookFormat.MOBI, book.format)
            assertEquals(4, book.pages.size)
            assertEquals("测试漫画", book.title)
            assertEquals(Color.BLUE, requireNotNull(book.bitmap(1)).getPixel(10, 10))
            assertEquals(Color.RED, requireNotNull(book.bitmap(2)).getPixel(10, 10))
            assertNotNull(book.bitmap(0)); assertNotNull(book.bitmap(3)); assertNotNull(book.cover)
        }
    }

    @Test fun epubStillReadsItsSpineAndDamagedPdfCannotOpen() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val bad = folder.newFile("bad.pdf").also { it.writeText("%PDF-1.7\nbroken") }
        try { BookReader.open(context, Uri.fromFile(bad)); fail("Damaged PDF was accepted") }
        catch (_: IllegalArgumentException) { }
        val image = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
        }.toByteArray()
        val epub = folder.newFile("legacy.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            val entries = mapOf("META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>".toByteArray(),
                "book.opf" to "<package><metadata><title>旧漫画</title></metadata><manifest><item id='p' href='page.png' media-type='image/png'/></manifest><spine><itemref idref='p'/></spine></package>".toByteArray(), "page.png" to image)
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        BookReader.open(context, Uri.fromFile(epub)).use {
            assertEquals(BookFormat.EPUB, it.format); assertEquals("旧漫画", it.title); assertNotNull(it.bitmap(0))
        }
    }
}
