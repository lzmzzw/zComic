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
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScannedComicFile(val uri: Uri, val parentName: String)

data class BookFileDeletion(val deleted: Boolean, val directoryCleanupFailed: Boolean = false)

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

    fun deleteBookFile(uri: Uri): BookFileDeletion {
        // Read the location before deleting the MediaStore row; never infer it from a title.
        var locationUnavailable = false
        val parent = try {
            resolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { path -> File(path).parentFile } else null
            }.also { if (it == null) locationUnavailable = true }
        } catch (_: Exception) { locationUnavailable = true; null }
        if (!delete(uri)) return BookFileDeletion(false)
        val root = File(Environment.getExternalStorageDirectory(), "${Environment.DIRECTORY_DOCUMENTS}/zComic")
        val cleaned = if (parent == null) !locationUnavailable else removeEmptyBookDirectory(parent, root)
        return BookFileDeletion(true, directoryCleanupFailed = !cleaned)
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

    private fun create(comic: String, volume: String, format: BookFormat): Uri? {
        val path = "${Environment.DIRECTORY_DOCUMENTS}/zComic/${clean(comic)}/"
        val filename = "${clean(volume)}.${format.extension}"
        val existing = resolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
            arrayOf(path, filename), null)?.use { it.moveToFirst() }
            ?: throw IOException("无法检查目标文件")
        if (existing) return null
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename)
            put(MediaStore.Files.FileColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, path)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw IOException("无法创建 ${format.name} 文件")
    }

    private fun publish(uri: Uri) {
        if (resolver.update(uri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }, null, null) != 1)
            throw IOException("无法发布文件")
    }

    suspend fun copy(source: Uri, comic: String, volume: String, format: BookFormat = BookFormat.EPUB,
        onPublished: suspend (Uri) -> Unit = {}): Uri? = copyMutex.withLock {
        currentCoroutineContext().ensureActive()
        val target = create(comic, volume, format) ?: return@withLock null
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
                    child.isFile && (BookFormat.fromName(child.name) != null || BookFormat.fromMimeType(child.type) != null) ->
                        results += ScannedComicFile(child.uri, parentName)
                }
            }
        }
        return results
    }
}

internal fun removeEmptyBookDirectory(directory: File, root: File): Boolean {
    return try {
        val managedRoot = root.canonicalFile
        val target = directory.canonicalFile
        // Only direct book directories belong to this cleanup. Do not follow a redirected path.
        if (target.parentFile != managedRoot || target != directory.absoluteFile) return true
        try {
            val path = target.toPath()
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            // Deleting a directory is non-recursive and atomically rejects any remaining entry.
            Files.delete(path)
            true
        } catch (_: NoSuchFileException) { true }
        catch (_: DirectoryNotEmptyException) { true }
    } catch (_: Exception) { false }
}
