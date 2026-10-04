package app.zcomic.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** System-job ownership; commands and job registration run on Main, transfers run on IO. */
internal class DownloadManager(
    private val context: Context,
    private val db: ComicDatabase,
    private val files: ComicFiles,
    private val site: KmoeClient,
    private val scheduler: DownloadScheduler,
    private val authenticated: suspend (suspend () -> Unit) -> Unit
) {
    private val dao = db.dao()
    private val permits = Semaphore(2)
    private val commands = Mutex()
    private val draining = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    private val transferFiles = ResumableTransfer(File(context.filesDir, "downloads"), site)

    suspend fun restore() = commands.withLock {
        val pending = dao.pendingDownloads()
        if (pending.isEmpty()) scheduler.cancelAll()
        else pending.forEach { scheduler.schedule(it) }
    }

    suspend fun enqueue(detail: ComicDetail, selected: List<OnlineVolume>): Int = commands.withLock {
        var added = 0
        for (volume in selected.distinctBy { it.id }) {
            val task = dao.download(volume.id)
            if (dao.volume(volume.id) != null || task?.status in listOf("queued", "running", "paused")) continue
            dao.putDownload(DownloadRecord(volume.id, detail.comic.id, detail.comic.title,
                volume.title, volume.number, detail.comic.detailUrl))
            revisions[volume.id] = (revisions[volume.id] ?: 0) + 1
            scheduler.schedule(requireNotNull(dao.download(volume.id)))
            added++
        }
        added
    }

    suspend fun pause(id: String) = commands.withLock {
        jobs[id]?.cancelAndJoin()
        dao.download(id)?.takeIf { it.status in listOf("queued", "running") }
            ?.let { dao.updateDownloadState(id, "paused") }
        if (dao.pendingDownloads().isEmpty()) scheduler.cancelAll()
    }

    suspend fun resume(id: String) = commands.withLock {
        jobs[id]?.cancelAndJoin()
        val task = dao.download(id) ?: return@withLock
        if (task.status == "completed") return@withLock
        dao.updateDownloadState(id, "queued")
        revisions[id] = (revisions[id] ?: 0) + 1
        scheduler.schedule(requireNotNull(dao.download(id)))
    }

    suspend fun cancel(id: String) = commands.withLock {
        jobs[id]?.cancelAndJoin()
        // Publication is a short non-cancellable commit. A completed file stays on the shelf.
        if (dao.download(id)?.status != "completed") dao.deleteDownload(id)
        withContext(Dispatchers.IO) { transferFiles.clean(id) }
        withContext(Dispatchers.IO) {
            val name = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            listOf("part", "source", "validator").forEach { File(context.cacheDir, "$name.$it").delete() }
        }
        if (dao.pendingDownloads().isEmpty()) scheduler.cancelAll()
    }

    suspend fun pauseAll() = commands.withLock {
        val active = jobs.toMap()
        val ids = (active.keys + dao.pendingDownloads().map { it.id }).toSet()
        active.values.forEach { it.cancel() }
        active.values.forEach { it.join() }
        ids.forEach { dao.interruptDownload(it, "paused") }
        scheduler.cancelAll()
    }

    /** Finish under the same command lock as enqueue, avoiding a lost last-moment enqueue. */
    suspend fun drain(onFinished: (Boolean) -> Unit) = draining.withLock { drainQueue(onFinished) }

    private suspend fun drainQueue(onFinished: (Boolean) -> Unit) = supervisorScope {
        val attempted = mutableMapOf<String, Long>()
        while (true) {
            val batch = commands.withLock {
                val pending = dao.pendingDownloads().filter { attempted[it.id] != (revisions[it.id] ?: 0) }
                if (pending.isEmpty()) {
                    val retry = dao.pendingDownloads().isNotEmpty()
                    scheduler.finished(hasPending = retry)
                    onFinished(retry)
                }
                pending
            }
            if (batch.isEmpty()) break
            attempted += batch.associate { it.id to (revisions[it.id] ?: 0) }
            val executions = batch.map { task -> async { execute(task.id) } }
            for (execution in executions) {
                try { execution.await() }
                catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // A user's pause/cancel only interrupts this volume, not the queue.
                }
            }
        }
    }

    /** Returns true only for recoverable transport failures, asking JobScheduler to retry. */
    suspend fun execute(id: String): Boolean {
        val own = requireNotNull(currentCoroutineContext()[Job])
        // A replaced/stopped system job must finish flushing before a successor opens its files.
        jobs[id]?.takeIf { it !== own }?.join()
        val registered = commands.withLock {
            val task = dao.download(id)
            if (task?.status !in listOf("queued", "running")) false
            else { jobs[id] = own; true }
        }
        if (!registered) return false
        try {
            permits.withPermit {
                val task = dao.download(id) ?: return@withPermit
                if (task.status !in listOf("queued", "running")) return@withPermit
                dao.updateDownloadState(id, "running")
                var attempts = 0
                while (true) {
                    try {
                        authenticated { withContext(Dispatchers.IO) { transfer(task) } }
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: IOException) {
                        if (!recoverable(error) || ++attempts >= 3) throw error
                        dao.updateDownloadState(id, "running", "连接中断，保留进度并重试")
                        delay(1_000L shl (attempts - 1))
                    }
                }
            }
            return false
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.interruptDownload(id, "queued") }
            throw cancelled
        } catch (error: Exception) {
            val retry = recoverable(error)
            dao.updateDownloadState(id, if (retry) "queued" else "failed",
                if (retry) "连接暂时不可用，保留进度并等待自动重试" else safeFailure(error))
            return retry
        } finally {
            if (jobs[id] === own) jobs.remove(id)
        }
    }

    private fun recoverable(error: Exception): Boolean = when (error) {
        is DownloadHttpException -> error.statusCode in listOf(408, 429) || error.statusCode >= 500
        is DownloadProtocolException -> false
        is DownloadStorageException -> false
        is java.net.ProtocolException -> false
        is IOException -> error.message?.let { message ->
            listOf("登录失败", "登录状态", "权限", "未提供下载地址", "同名文件").none { it in message }
        } != false
        else -> false
    }

    private fun safeFailure(error: Exception): String = when {
        error is DownloadHttpException -> "下载服务器拒绝请求 (${error.statusCode})，请检查登录或稍后重试"
        error.message?.contains("登录状态") == true -> "登录状态已失效，请重新登录后继续"
        error is DownloadProtocolException -> "下载服务器返回了无效的续传响应，请稍后重试"
        error is DownloadStorageException -> "无法保存下载文件，请检查设备空间后继续"
        error.message?.contains("同名文件") == true -> "同名文件已存在，请先扫描文件夹或处理重名文件"
        else -> "卷册处理失败，请检查文件空间、网站权限或重新检索"
    }

    private suspend fun transfer(task: DownloadRecord) {
        val comic = OnlineComic(task.comicId, task.comicTitle, "", task.detailUrl)
        val volume = site.detail(comic).volumes.firstOrNull { it.id == task.id }
            ?: error("网站卷册已改变，请重新检索")
        val sources = listOf(volume.downloadOne, volume.downloadTwo).filter { it.isNotBlank() }.distinct()
        if (sources.isEmpty()) error("没有可用的单卷下载链接")
        val legacyName = task.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        transferFiles.migrateLegacy(task.id, File(context.cacheDir, "$legacyName.part"),
            File(context.cacheDir, "$legacyName.source"), File(context.cacheDir, "$legacyName.validator"))
        val part = transferFiles.download(task.id, sources) { received, total ->
            dao.updateProgress(task.id, received, total)
        }
        val book = try { EpubBook.open(context, Uri.fromFile(part)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            transferFiles.clean(task.id)
            throw DownloadProtocolException("下载文件不是有效的 EPUB，请稍后重试")
        }
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
                    transferFiles.clean(task.id)
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
                    transferFiles.clean(task.id)
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

}
