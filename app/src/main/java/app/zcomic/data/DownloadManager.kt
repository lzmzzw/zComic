package app.zcomic.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Main-scope job ownership; file/network work runs on IO, permits suspend while queued. */
internal class DownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: ComicDatabase,
    private val files: ComicFiles,
    private val site: KmoeClient
) {
    private val dao = db.dao()
    private val permits = Semaphore(2)
    private val commands = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    suspend fun restore() = commands.withLock {
        dao.pendingDownloads().forEach { start(it.id) }
    }

    suspend fun enqueue(detail: ComicDetail, selected: List<OnlineVolume>): Int = commands.withLock {
        var added = 0
        for (volume in selected.distinctBy { it.id }) {
            val task = dao.download(volume.id)
            if (dao.volume(volume.id) != null || task?.status in listOf("queued", "running", "paused")) continue
            dao.putDownload(DownloadRecord(volume.id, detail.comic.id, detail.comic.title,
                volume.title, volume.number, detail.comic.detailUrl))
            start(volume.id)
            added++
        }
        added
    }

    suspend fun pause(id: String) = commands.withLock {
        jobs[id]?.cancelAndJoin()
        dao.download(id)?.takeIf { it.status in listOf("queued", "running") }
            ?.let { dao.updateDownloadState(id, "paused") }
    }

    suspend fun resume(id: String) = commands.withLock {
        jobs[id]?.cancelAndJoin()
        val task = dao.download(id) ?: return@withLock
        if (task.status == "completed") return@withLock
        dao.updateDownloadState(id, "queued")
        start(id)
    }

    suspend fun cancel(id: String) = commands.withLock {
        jobs[id]?.cancelAndJoin()
        // Publication is a short non-cancellable commit. A completed file stays on the shelf.
        if (dao.download(id)?.status != "completed") dao.deleteDownload(id)
        withContext(Dispatchers.IO) { cleanPartial(id) }
    }

    suspend fun pauseAll() = commands.withLock {
        val active = jobs.toMap()
        val ids = (active.keys + dao.pendingDownloads().map { it.id }).toSet()
        active.values.forEach { it.cancel() }
        active.values.forEach { it.join() }
        ids.forEach { dao.interruptDownload(it, "paused") }
    }

    private fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                permits.withPermit {
                    val task = dao.download(id) ?: return@withPermit
                    if (task.status !in listOf("queued", "running")) return@withPermit
                    dao.updateDownloadState(id, "running")
                    withContext(Dispatchers.IO) { transfer(task) }
                }
            } catch (cancelled: CancellationException) {
                val status = if (currentCoroutineContext().isActive) "paused" else "queued"
                withContext(NonCancellable) { dao.interruptDownload(id, status) }
                throw cancelled
            } catch (error: Exception) {
                dao.updateDownloadState(id, "failed", error.message ?: "下载失败")
            } finally {
                if (jobs[id] === currentCoroutineContext()[Job]) jobs.remove(id)
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun transfer(task: DownloadRecord) {
        val comic = OnlineComic(task.comicId, task.comicTitle, "", task.detailUrl)
        val volume = site.detail(comic).volumes.firstOrNull { it.id == task.id }
            ?: error("网站卷册已改变，请重新检索")
        val sources = listOf(volume.downloadOne, volume.downloadTwo).filter { it.isNotBlank() }.distinct()
        if (sources.isEmpty()) error("没有可用的单卷下载链接")
        var last: Exception? = null
        for (source in sources) {
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    fetch(task, source)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    last = error
                    if (attempt < 2) delay(500L * (attempt + 1))
                }
            }
        }
        throw last ?: IOException("下载失败")
    }

    private suspend fun fetch(task: DownloadRecord, source: String) {
        val part = partial(task.id, "part")
        val marker = partial(task.id, "source")
        val validatorFile = partial(task.id, "validator")
        if (marker.takeIf { it.exists() }?.readText() != source) {
            part.delete()
            validatorFile.delete()
        }
        marker.writeText(source)
        val validator = validatorFile.takeIf { it.exists() }?.readText().orEmpty()
        if (part.length() > 0 && validator.isBlank()) part.delete()
        val start = part.length()
        dao.updateProgress(task.id, start, 0)
        site.withDownload(source, start, validator) { response ->
            val body = response.body ?: throw IOException("下载响应没有文件")
            if (!response.isSuccessful) {
                if (response.code == 416) cleanPartial(task.id)
                throw IOException("下载请求失败 (${response.code})")
            }
            if (response.header("Content-Type").orEmpty().contains("text/html", true))
                throw IOException("该下载线路返回网页，可能需要人工验证")
            val append = start > 0 && response.code == 206 &&
                response.header("Content-Range").orEmpty().startsWith("bytes $start-")
            if (response.code == 206 && !append) {
                cleanPartial(task.id)
                throw IOException("下载续传范围不匹配，正在重新下载")
            }
            if (!append) {
                part.delete()
                val currentValidator = response.header("ETag") ?: response.header("Last-Modified")
                if (currentValidator.isNullOrBlank()) validatorFile.delete()
                else validatorFile.writeText(currentValidator)
            }
            val length = body.contentLength()
            val total = if (length > 0) length + if (append) start else 0 else 0
            var received = if (append) start else 0L
            var lastUpdate = 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 500_000_000L) {
                            dao.updateProgress(task.id, received, total)
                            lastUpdate = now
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (total > 0 && received != total) throw IOException("下载文件不完整，请重试")
            dao.updateProgress(task.id, received, total)
        }
        val book = try { EpubBook.open(context, Uri.fromFile(part)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { cleanPartial(task.id); throw error }
        book.use {
            currentCoroutineContext().ensureActive()
            val hash = files.contentId(Uri.fromFile(part))
            val existing = dao.findVolume(hash, "")
            if (existing != null) {
                withContext(NonCancellable) {
                    db.withTransaction {
                        dao.associateSource(existing.id, task.id)
                        dao.putDownload(task.copy(status = "completed", received = part.length(),
                            total = part.length(), error = "该卷已在书架中"))
                    }
                    cleanPartial(task.id)
                }
                return
            }
            var cover = ""
            try {
                cover = files.saveCover(task.id, book.cover)
                val uri = files.copy(Uri.fromFile(part), task.comicTitle, task.volumeTitle) { target ->
                    val duplicate = db.withTransaction {
                        val canonical = dao.findVolume(hash, "")
                        if (canonical == null) {
                            dao.putVolume(VolumeRecord(task.id, task.comicId, task.comicTitle, task.volumeTitle,
                                task.number, target.toString(), coverUri = cover, pageCount = book.pages.size,
                                contentHash = hash, sourceId = task.id))
                        } else dao.associateSource(canonical.id, task.id)
                        dao.putDownload(task.copy(status = "completed", received = part.length(),
                            total = part.length(), error = if (canonical == null) "" else "该卷已在书架中"))
                        canonical
                    }
                    if (duplicate != null) {
                        runCatching { files.delete(target) }
                        if (duplicate.coverUri != cover) runCatching { files.deleteCover(cover) }
                    }
                    cleanPartial(task.id)
                }
                if (uri == null) throw IOException("同名文件已存在，请先扫描文件夹或处理重名文件")
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    if (dao.volume(task.id) == null) runCatching { files.deleteCover(cover) }
                }
                throw error
            }
        }
    }

    private fun partial(id: String, extension: String): File =
        File(context.cacheDir, "${id.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.$extension")

    private fun cleanPartial(id: String) {
        listOf("part", "source", "validator").forEach { partial(id, it).delete() }
    }
}
