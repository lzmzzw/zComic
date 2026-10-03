package app.zcomic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext

class EpubBook private constructor(
    private val archive: EpubArchive,
    private val sourceFile: File,
    private val temporaryFile: File?,
    val pageRatios: List<Float>
) : Closeable {
    val title get() = archive.title
    val pages get() = archive.pages
    val cover: Bitmap? get() = bitmap(0, 512)
    internal val sourceUri: Uri get() = Uri.fromFile(sourceFile)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    @Synchronized
    fun bitmap(page: Int, maxSize: Int = 2048): Bitmap? {
        require(maxSize > 0)
        val path = pages.getOrNull(page) ?: return null
        val key = "$page-$maxSize"
        cache.get(key)?.let { return it }
        val bounds = dimensions(archive, path)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        return archive.stream(path).use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.also { cache.put(key, it) }
    }

    @Synchronized
    override fun close() {
        cache.evictAll()
        try { archive.close() } finally { temporaryFile?.delete() }
    }

    companion object {
        suspend fun open(context: Context, uri: Uri): EpubBook {
            var result: EpubBook? = null
            try {
                return withContext(Dispatchers.IO) {
                    stageBook(context, uri).also { result = it }
                }
            } catch (error: Throwable) {
                result?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
                throw error
            }
        }

        private suspend fun stageBook(context: Context, uri: Uri): EpubBook {
            val temporary = if (uri.scheme == "file") null
                else File.createTempFile("reader-", ".epub", context.cacheDir)
            var archive: EpubArchive? = null
            try {
                val file = temporary ?: File(requireNotNull(uri.path))
                if (temporary != null) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("无法打开 EPUB")
                }
                val opened = EpubArchive(file).also { archive = it }
                var readablePages = 0
                val ratios = opened.pages.map { path ->
                    coroutineContext.ensureActive()
                    val bounds = dimensions(opened, path)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        readablePages++
                        bounds.outWidth.toFloat() / bounds.outHeight
                    } else 0.72f
                }
                require(readablePages > 0) { "该 EPUB 的图片无法读取，可能已损坏或加密" }
                return EpubBook(opened, file, temporary, ratios)
            } catch (error: Throwable) {
                try { archive?.close() } finally { temporary?.delete() }
                throw error
            }
        }

        private fun dimensions(archive: EpubArchive, path: String): BitmapFactory.Options {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            archive.stream(path).use { BitmapFactory.decodeStream(it, null, options) }
            return options
        }
    }
}
