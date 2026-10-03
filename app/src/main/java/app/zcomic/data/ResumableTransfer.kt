package app.zcomic.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class DownloadHttpException(val statusCode: Int, message: String) : IOException(message)
class DownloadProtocolException(message: String) : IOException(message)
class DownloadStorageException(message: String = "下载文件写入失败，请检查存储空间及目录权限") : IOException(message)

/** Durable, independent partials per source. The caller validates/imports the returned EPUB. */
class ResumableTransfer(private val workDirectory: File, private val client: KmoeClient) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private data class Metadata(val validator: String = "", val total: Long = 0, val complete: Boolean = false)
    private data class Range(val start: Long, val end: Long, val total: Long)

    suspend fun download(id: String, sources: List<String>,
        onProgress: suspend (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        locks.getOrPut(id) { Mutex() }.withLock {
            val directory = directory(id)
            if (!directory.isDirectory && !directory.mkdirs()) throw DownloadStorageException("无法创建下载目录，请检查存储空间及目录权限")
            val available = sources.filter { it.isNotBlank() }.distinct()
            if (available.isEmpty()) throw IOException("没有可用的下载线路")
            val preferred = storage { File(directory, "active").takeIf { it.isFile }?.readText().orEmpty() }
            val ordered = available.sortedBy { if (key(it) == preferred) 0 else 1 }
            // A crash between the final flush and completion marker still leaves a reusable complete file.
            ordered.forEach { source ->
                val part = File(directory, "${key(source)}.part")
                val meta = readMetadata(File(directory, "${key(source)}.properties"))
                if (part.isFile && meta.total > 0 && part.length() == meta.total) {
                    onProgress(meta.total, meta.total)
                    return@withLock part
                }
            }
            var last: IOException? = null
            for (source in ordered) {
                currentCoroutineContext().ensureActive()
                try {
                    return@withLock transfer(directory, source, onProgress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    // Each failed source retains its own partial, so fallback never overwrites it.
                    if (error is DownloadStorageException) throw error
                    if (error is DownloadHttpException && error.statusCode in listOf(401, 403)) throw error
                    last = error
                }
            }
            throw last ?: IOException("下载失败")
        }
    }

    /** Call only after cancelling/joining the task, or after successful publication. */
    fun clean(id: String) {
        val directory = directory(id)
        if (directory.exists() && !directory.deleteRecursively())
            throw DownloadStorageException("无法清理下载文件，请检查目录权限")
    }

    /** Upgrade the former cache files before starting this task; never overwrite a new partial. */
    fun migrateLegacy(id: String, part: File, source: File, validator: File) {
        if (!part.isFile || part.length() == 0L || !source.isFile || !validator.isFile) return
        if (source.length() > 64 * 1024 || validator.length() > 4096) return
        val directory = directory(id)
        if (directory.listFiles().orEmpty().any { it.extension == "part" }) return
        val originalSource = storage { source.readText() }.trim()
        val originalValidator = validValidator(storage { validator.readText() }.trim())
        val address = originalSource.toHttpUrlOrNull() ?: return
        if (originalValidator.isBlank() || address.username.isNotEmpty() || address.password.isNotEmpty() ||
            address.fragment != null || (!address.isHttps && address.host !in listOf("localhost", "127.0.0.1", "::1"))) return
        if (!directory.isDirectory && !directory.mkdirs()) throw DownloadStorageException("无法创建下载目录，请检查存储空间及目录权限")
        val name = key(originalSource)
        val destination = File(directory, "$name.part")
        val temporary = File(directory, "$name.migration.tmp")
        storage {
            FileOutputStream(temporary).use { output ->
                part.inputStream().use { it.copyTo(output) }
                output.flush()
                output.fd.sync()
            }
        }
        writeMetadata(File(directory, "$name.properties"), Metadata(originalValidator, total = 0))
        storage {
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
        }
        atomicWrite(File(directory, "active"), name.toByteArray(Charsets.UTF_8))
        // The durable copy and metadata are now present; obsolete cache helpers are safe to remove.
        part.delete()
        source.delete()
        validator.delete()
    }

    private suspend fun transfer(directory: File, source: String,
        onProgress: suspend (Long, Long) -> Unit): File {
        val part = File(directory, "${key(source)}.part")
        val marker = File(directory, "${key(source)}.properties")
        var fresh = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val previous = readMetadata(marker)
            val canResume = !fresh && part.length() > 0 && previous.validator.isNotBlank() &&
                (previous.total == 0L || part.length() <= previous.total)
            val start = if (canResume) part.length() else 0L
            var retryFresh = false
            var finished = false
            client.withDownload(source, start, if (canResume) previous.validator else "") { response ->
                if (response.code == 416) {
                    val total = Regex("bytes \\*/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    if (canResume && total == start && (previous.total == 0L || total == previous.total) &&
                        validator(response) == previous.validator) {
                        writeMetadata(marker, previous.copy(total = start, complete = true))
                        onProgress(start, start)
                        finished = true
                    } else if (start > 0) retryFresh = true
                    else throw DownloadProtocolException("下载服务器返回了无效的文件范围")
                    return@withDownload
                }
                if (response.code !in listOf(200, 206))
                    throw DownloadHttpException(response.code, "下载请求失败 (${response.code})")
                if (response.header("Content-Type").orEmpty().contains("text/html", true))
                    throw DownloadProtocolException("下载线路返回网页，请检查登录状态或网站验证")
                if (response.header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity", true) })
                    throw DownloadProtocolException("下载服务器返回了不支持的压缩响应")
                val body = response.body ?: throw DownloadProtocolException("下载响应没有文件")
                val length = body.contentLength()
                val currentValidator = validator(response)
                val range = if (response.code == 206) parseRange(response.header("Content-Range")) else null
                if (response.code == 206) {
                    requireProtocol(range != null && range.start == start && range.end >= range.start &&
                        range.end < range.total, "下载续传范围不匹配")
                    val expected = range!!.end - range.start + 1
                    requireProtocol(length < 0 || length == expected, "下载续传长度不匹配")
                    requireProtocol(start == 0L || previous.total == 0L || previous.total == range.total,
                        "下载文件总长度已改变")
                    if (start > 0 && currentValidator != previous.validator) {
                        retryFresh = true
                        return@withDownload
                    }
                }
                val append = response.code == 206 && start > 0
                val total = range?.total ?: length.coerceAtLeast(0)
                val expected = range?.let { it.end - it.start + 1 } ?: length
                val offset = if (append) start else 0L
                val meta = Metadata(currentValidator, total)
                // Validate every header before touching the old partial or its metadata.
                if (!append) storage { FileOutputStream(part).use { it.fd.sync() } }
                writeMetadata(marker, meta)
                atomicWrite(File(directory, "active"), key(source).toByteArray(Charsets.UTF_8))
                onProgress(offset, total)
                var received = offset
                var lastUpdate = System.nanoTime()
                val output = storage { FileOutputStream(part, append) }
                try {
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (expected >= 0 && received - offset > expected - count) {
                                storage { output.flush() }
                                // An oversized response is a protocol violation; undo this response's append.
                                storage { RandomAccessFile(part, "rw").use { it.setLength(offset) } }
                                throw DownloadProtocolException("下载响应超过声明的长度")
                            }
                            storage { output.write(buffer, 0, count) }
                            received += count
                            val now = System.nanoTime()
                            if (now - lastUpdate >= 500_000_000L) {
                                storage { output.flush() }
                                onProgress(received, total)
                                lastUpdate = now
                            }
                        }
                    }
                    storage { output.flush(); output.fd.sync() }
                } finally {
                    storage { output.close() }
                }
                currentCoroutineContext().ensureActive()
                requireProtocol(expected < 0 || received - offset == expected, "下载文件不完整，请重试")
                finished = total == 0L || received == total
                if (finished) writeMetadata(marker, meta.copy(total = received, complete = true))
                onProgress(received, if (finished) received else total)
                if (!finished && meta.validator.isBlank())
                    throw DownloadProtocolException("下载服务器未提供继续续传所需的文件标识")
            }
            if (finished) return part
            fresh = retryFresh
        }
    }

    private fun parseRange(value: String?): Range? {
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(value.orEmpty()) ?: return null
        val numbers = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        return Range(numbers[0], numbers[1], numbers[2])
    }

    private fun validator(response: Response): String {
        return validValidator(response.header("ETag").orEmpty()).ifBlank {
            validValidator(response.header("Last-Modified").orEmpty())
        }
    }

    private fun validValidator(value: String): String {
        val etag = value
        if (etag.startsWith('"') && etag.endsWith('"') && etag.length >= 2 &&
            etag.substring(1, etag.length - 1).all { it.code == 33 || it.code in 35..126 || it.code >= 128 }) return etag
        val modified = value
        return if (runCatching { ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess)
            modified else ""
    }

    private fun directory(id: String) = File(workDirectory, key(id))
    private fun key(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun readMetadata(file: File): Metadata {
        if (!file.isFile) return Metadata()
        val values = try {
            storage { Properties().apply { file.inputStream().use { load(it) } } }
        } catch (_: IllegalArgumentException) { return Metadata() }
        return Metadata(validValidator(values.getProperty("validator", "")),
            values.getProperty("total", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0,
            values.getProperty("complete", "false").toBoolean())
    }

    private fun writeMetadata(file: File, meta: Metadata) {
        val values = Properties().apply {
            setProperty("validator", meta.validator)
            setProperty("total", meta.total.toString())
            setProperty("complete", meta.complete.toString())
        }
        val bytes = java.io.ByteArrayOutputStream().apply { values.store(this, null) }.toByteArray()
        atomicWrite(file, bytes)
    }

    private fun atomicWrite(file: File, bytes: ByteArray) = storage {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.flush(); it.fd.sync() }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun requireProtocol(condition: Boolean, message: String) {
        if (!condition) throw DownloadProtocolException(message)
    }

    private inline fun <T> storage(block: () -> T): T = try { block() }
    catch (error: DownloadStorageException) { throw error }
    catch (_: IOException) { throw DownloadStorageException() }
    catch (_: SecurityException) { throw DownloadStorageException() }
}
