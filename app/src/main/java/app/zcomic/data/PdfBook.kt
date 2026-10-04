package app.zcomic.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.roundToInt

/** Android renderer keeps only one native page open at a time. */
internal class PdfBook(file: File) : ComicBook {
    private val renderer: PdfRenderer
    override val title = file.nameWithoutExtension
    override val sourceUri = Uri.fromFile(file)
    override val format = BookFormat.PDF
    override val pages: List<String>
    override val pageRatios: List<Float>
    override val cover get() = bitmap(0, 512)
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { renderer = PdfRenderer(descriptor) }
        catch (error: Exception) { descriptor.close(); throw IllegalArgumentException("PDF 无法读取，可能已加密或损坏", error) }
        try {
            require(renderer.pageCount > 0) { "PDF 没有可读取的页面" }
            pages = List(renderer.pageCount) { it.toString() }
            pageRatios = pages.indices.map { index ->
                renderer.openPage(index).use { page -> page.width.toFloat() / page.height }
            }
        } catch (error: Throwable) { renderer.close(); throw error }
    }

    @Synchronized override fun bitmap(page: Int, maxSize: Int): Bitmap? {
        require(maxSize > 0)
        check(!closed) { "阅读文件已关闭" }
        if (page !in pages.indices) return null
        val size = maxSize.coerceAtMost(2048)
        val key = "$page-$size"
        cache.get(key)?.let { return it }
        return renderer.openPage(page).use { source ->
            val scale = size.toFloat() / maxOf(source.width, source.height)
            Bitmap.createBitmap((source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                source.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, it)
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        renderer.close()
    }
}
