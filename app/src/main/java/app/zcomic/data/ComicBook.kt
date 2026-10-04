package app.zcomic.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

interface ComicBook : Closeable {
    val title: String
    val pages: List<String>
    val pageRatios: List<Float>
    val cover: Bitmap?
    val sourceUri: Uri
    val format: BookFormat
    fun bitmap(page: Int, maxSize: Int = 2048): Bitmap?
}

/** Inspect bytes rather than trusting provider MIME types or opaque content URI names. */
object BookReader {
    suspend fun open(context: Context, uri: Uri): ComicBook {
        var opened: ComicBook? = null
        var staged: File? = null
        try {
            return withContext(Dispatchers.IO) {
                require(uri.scheme in listOf("file", "content")) { "不支持的文件地址" }
                val file = if (uri.scheme == "file") File(requireNotNull(uri.path)) else {
                    File.createTempFile("book-", ".source", context.cacheDir).also { target ->
                        staged = target
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: error("无法读取文件")
                    }
                }
                opened = when (detect(file)) {
                    BookFormat.EPUB -> EpubBook.open(context, Uri.fromFile(file))
                    BookFormat.PDF -> PdfBook(file)
                    BookFormat.MOBI -> MobiBook(file)
                }
                val title = if (opened?.format == BookFormat.PDF) ComicFiles(context).name(uri).substringBeforeLast('.') else requireNotNull(opened).title
                StagedBook(requireNotNull(opened), staged, title).also { opened = it; staged = null }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close(); staged?.delete() }
            throw error
        }
    }

    internal fun detect(file: File): BookFormat = RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(minOf(1024L, input.length()).toInt())
        input.readFully(header)
        when {
            header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() -> BookFormat.EPUB
            header.toString(Charsets.ISO_8859_1).contains("%PDF-") -> BookFormat.PDF
            header.size >= 68 && header.copyOfRange(60, 68).toString(Charsets.US_ASCII) == "BOOKMOBI" -> BookFormat.MOBI
            else -> error("不支持或已损坏的文件，请选择 EPUB、PDF 或 MOBI")
        }
    }

    private class StagedBook(private val delegate: ComicBook, private val temporary: File?, override val title: String) : ComicBook by delegate {
        override fun close() { try { delegate.close() } finally { temporary?.delete() } }
    }
}
