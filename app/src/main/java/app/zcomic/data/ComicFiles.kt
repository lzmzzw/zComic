package app.zcomic.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScannedComicFile(val uri: Uri, val parentName: String)

class ComicFiles(private val context: Context) {
    private val resolver = context.contentResolver
    private val copyMutex = Mutex()

    fun name(uri: Uri): String {
        if (uri.scheme == "file") return File(requireNotNull(uri.path)).name
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "import.epub"
    }

    fun delete(uri: Uri): Boolean = when (uri.scheme) {
        "file" -> File(requireNotNull(uri.path)).delete()
        "content" -> resolver.delete(uri, null, null) > 0
        else -> throw IOException("不支持的文件地址")
    }

    fun deleteCover(coverUri: String) {
        if (coverUri.isBlank()) return
        val uri = Uri.parse(coverUri)
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val directory = File(context.filesDir, "covers").canonicalFile
        val target = File(path).canonicalFile
        if (target.parentFile == directory && target.isFile) target.delete()
    }

    private fun clean(text: String): String = text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "未命名" }

    suspend fun contentId(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        } ?: error("无法读取文件")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun saveCover(id: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        val directory = java.io.File(context.filesDir, "covers").apply { mkdirs() }
        val target = java.io.File(directory, "${id.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.jpg")
        val temporary = File.createTempFile("cover-", ".jpg", directory)
        try {
            temporary.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("无法保存封面")
            }
            if (!temporary.renameTo(target)) throw IOException("无法保存封面")
            return Uri.fromFile(target).toString()
        } finally { temporary.delete() }
    }

    private fun create(comic: String, volume: String): Uri? {
        val path = "${Environment.DIRECTORY_DOCUMENTS}/zComic/${clean(comic)}/"
        val filename = "${clean(volume)}.epub"
        val existing = resolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
            arrayOf(path, filename), null)?.use { it.moveToFirst() }
            ?: throw IOException("无法检查目标文件")
        if (existing) return null
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, path)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw IOException("无法创建 EPUB 文件")
    }

    private fun publish(uri: Uri) {
        if (resolver.update(uri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }, null, null) != 1)
            throw IOException("无法发布 EPUB 文件")
    }

    suspend fun copy(source: Uri, comic: String, volume: String,
        onPublished: suspend (Uri) -> Unit = {}): Uri? = copyMutex.withLock {
        currentCoroutineContext().ensureActive()
        val target = create(comic, volume) ?: return@withLock null
        var committed = false
        try {
            resolver.openInputStream(source).use { input ->
                resolver.openOutputStream(target, "w").use { output ->
                    requireNotNull(input); requireNotNull(output)
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                publish(target)
                onPublished(target)
                committed = true
            }
            target
        } catch (error: Exception) {
            if (!committed) {
                try {
                    if (!delete(target)) error.addSuppressed(IOException("无法清理未完成文件"))
                } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    suspend fun scanTree(tree: Uri): List<ScannedComicFile> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("无法读取所选目录")
        val pending = ArrayDeque<DocumentFile>().apply { add(root) }
        val visited = mutableSetOf<Uri>()
        val results = mutableListOf<ScannedComicFile>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = pending.removeLast()
            if (!visited.add(folder.uri)) continue
            val parentName = folder.name.orEmpty()
            for (child in folder.listFiles()) {
                currentCoroutineContext().ensureActive()
                when {
                    child.isDirectory -> pending.add(child)
                    child.isFile && child.name?.endsWith(".epub", ignoreCase = true) == true ->
                        results += ScannedComicFile(child.uri, parentName)
                }
            }
        }
        return results
    }
}
