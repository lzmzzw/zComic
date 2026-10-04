package app.zcomic.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import java.io.File

/** Image records stay on disk; text is paginated at a stable size for durable positions. */
internal class MobiBook(file: File) : ComicBook {
    private sealed interface Page {
        data class Image(val record: Int) : Page
        data class Text(val value: String) : Page
    }
    private val archive = MobiArchive(file)
    private val content: List<Page>
    override val title = archive.title
    override val sourceUri = Uri.fromFile(file)
    override val format = BookFormat.MOBI
    override val pages: List<String>
    override val pageRatios: List<Float>
    override val cover get() = bitmap(0, 512)
    private var closed = false
    private val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Color.BLACK }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    init {
        try {
            content = buildList {
                for (part in archive.parts) when (part) {
                    is MobiArchive.Part.Image -> add(Page.Image(part.record))
                    is MobiArchive.Part.Text -> {
                        // Bound individual layout work and split at line boundaries, never truncating text.
                        for (chunk in part.value.chunked(12000)) {
                            val layout = layout(chunk)
                            var line = 0
                            while (line < layout.lineCount) {
                                val first = line
                                val top = layout.getLineTop(first)
                                while (line < layout.lineCount && layout.getLineBottom(line) - top <= HEIGHT - 2 * MARGIN) line++
                                if (line == first) line++
                                add(Page.Text(chunk.substring(layout.getLineStart(first), layout.getLineEnd(line - 1))))
                            }
                        }
                    }
                }
            }
            require(content.isNotEmpty()) { "MOBI 没有可读取的页面" }
            pages = content.indices.map(Int::toString)
            pageRatios = content.map { page ->
                when (page) {
                    is Page.Text -> WIDTH.toFloat() / HEIGHT
                    is Page.Image -> {
                        val bytes = archive.record(page.record)
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth > 0 && options.outHeight > 0) { "MOBI 图片损坏或编码不支持" }
                        options.outWidth.toFloat() / options.outHeight
                    }
                }
            }
        } catch (error: Throwable) { archive.close(); throw error }
    }
    private fun layout(text: String): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, WIDTH - 2 * MARGIN)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(8f, 1f).build()

    @Synchronized override fun bitmap(page: Int, maxSize: Int): Bitmap? {
        require(maxSize > 0)
        check(!closed) { "阅读文件已关闭" }
        val source = content.getOrNull(page) ?: return null
        val size = maxSize.coerceAtMost(2048)
        val key = "$page-$size"
        cache.get(key)?.let { return it }
        val bitmap = when (source) {
            is Page.Image -> {
                val bytes = archive.record(source.record)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > size || bounds.outHeight / sample > size) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            is Page.Text -> {
                val scale = size.toFloat() / HEIGHT
                Bitmap.createBitmap((WIDTH * scale).toInt().coerceAtLeast(1), size, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    val canvas = Canvas(it)
                    canvas.scale(scale, scale); canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
                    layout(source.value).draw(canvas)
                }
            }
        }
        return bitmap?.also { cache.put(key, it) }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; cache.evictAll(); archive.close()
    }
    companion object { private const val WIDTH = 900; private const val HEIGHT = 1280; private const val MARGIN = 48 }
}
