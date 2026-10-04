package app.zcomic

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.zcomic.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

class ComicViewModel(application: Application) : AndroidViewModel(application) {
    private val _message = MutableStateFlow("")
    private val operations = CoroutineScope(viewModelScope.coroutineContext + CoroutineExceptionHandler { _, error ->
        _message.value = error.message ?: "操作失败，请重试"
    })
    private val runtime = DownloadRuntime.get(application)
    private val db = runtime.db
    private val dao = db.dao()
    private val files = runtime.files
    private val site = runtime.site
    private val queue = runtime.queue
    private val resolver = application.contentResolver
    private val localFiles = Mutex()
    private var loginJob: Job? = null
    private var browseJob: Job? = null
    private var detailJob: Job? = null
    private var fileCheckJob: Job? = null

    private val _volumesLoaded = MutableStateFlow(false)
    val volumesLoaded = _volumesLoaded.asStateFlow()
    val volumes = dao.volumes().onEach { _volumesLoaded.value = true }
        .stateIn(operations, SharingStarted.Eagerly, emptyList())
    val downloads = dao.downloads().stateIn(operations, SharingStarted.Eagerly, emptyList())
    private val _online = MutableStateFlow<List<OnlineComic>>(emptyList())
    val online = _online.asStateFlow()
    private val _selectedSort = MutableStateFlow<ComicSort?>(ComicSort.COMPREHENSIVE)
    val selectedSort = _selectedSort.asStateFlow()
    private val _detail = MutableStateFlow<ComicDetail?>(null)
    val detail = _detail.asStateFlow()
    private val _loginBusy = MutableStateFlow(false)
    val loginBusy = _loginBusy.asStateFlow()
    private val _browseBusy = MutableStateFlow(false)
    val browseBusy = _browseBusy.asStateFlow()
    private val _detailBusy = MutableStateFlow(false)
    val detailBusy = _detailBusy.asStateFlow()
    val message = _message.asStateFlow()
    val loginStatus = runtime.loginStatus
    val downloadLastStop = runtime.diagnostics.lastStop
    val savedCredentials = runtime.saved
    private val _importedVolume = MutableStateFlow<VolumeRecord?>(null)
    val importedVolume = _importedVolume.asStateFlow()

    init {
        operations.launch { runtime.restoreSession() }
    }

    fun restoreDownloads() = operations.launch { queue.restore() }

    fun dismissMessage(expected: String) {
        if (_message.value == expected) _message.value = ""
    }

    fun consumeImportedVolume() { _importedVolume.value = null }

    fun login(email: String, password: String, remember: Boolean = true): Job {
        loginJob?.cancel()
        _loginBusy.value = true
        return operations.launch {
            try {
                runtime.login(email, password, remember)
                _message.value = "登录成功"
                queue.restore()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = "登录失败，请检查账号或网络" }
            finally {
                if (loginJob === kotlinx.coroutines.currentCoroutineContext()[Job]) _loginBusy.value = false
            }
        }.also { loginJob = it }
    }

    fun logout() {
        loginJob?.cancel()
        browseJob?.cancel()
        detailJob?.cancel()
        runtime.logout()
        runtime.scope.launch { queue.pauseAll() }
        _loginBusy.value = false
        _message.value = "已退出登录"
    }

    private suspend fun <T> authenticated(action: suspend () -> T): T = runtime.authenticated(action)

    fun recent() = browse(ComicSort.RECENT)

    fun browse(sort: ComicSort) = loadList(sort) { site.browse(sort) }
    fun search(text: String): Job {
        val keyword = text.trim()
        return if (keyword.isEmpty()) browse(ComicSort.COMPREHENSIVE)
            else loadList(null) { site.search(keyword) }
    }

    private fun loadList(sort: ComicSort?, action: suspend () -> List<OnlineComic>): Job {
        browseJob?.cancel()
        _browseBusy.value = true
        return operations.launch {
            try {
                val result = authenticated(action)
                _online.value = result
                _selectedSort.value = sort
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.message ?: "网站访问失败" }
            finally {
                if (browseJob === kotlinx.coroutines.currentCoroutineContext()[Job]) _browseBusy.value = false
            }
        }.also { browseJob = it }
    }

    fun openDetail(comic: OnlineComic): Job {
        detailJob?.cancel()
        _detail.value = null
        _detailBusy.value = true
        return operations.launch {
            try { _detail.value = authenticated { site.detail(comic) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.message ?: "卷册读取失败" }
            finally {
                if (detailJob === kotlinx.coroutines.currentCoroutineContext()[Job]) _detailBusy.value = false
            }
        }.also { detailJob = it }
    }

    fun queue(selected: List<OnlineVolume>) = operations.launch {
        val current = _detail.value ?: return@launch
        val added = queue.enqueue(current, selected)
        _message.value = if (added > 0) "已加入 $added 卷" else "所选卷册已在书架或队列中"
    }

    fun pause(id: String) = operations.launch { queue.pause(id) }
    fun resume(id: String) = operations.launch { queue.resume(id) }
    fun cancel(id: String) = operations.launch { queue.cancel(id) }
    fun clearCompleted() = operations.launch { dao.clearCompleted() }

    fun import(uri: Uri) = operations.launch(Dispatchers.IO) {
        localFiles.withLock {
            try {
                BookReader.open(getApplication(), uri).use { book ->
                    val id = files.contentId(book.sourceUri)
                    dao.findVolume(id, uri.toString())?.let {
                        _importedVolume.value = it
                        _message.value = "这本书已在书架中"
                        return@withLock
                    }
                    val filename = files.name(uri).substringBeforeLast('.')
                    val comic = comicTitle(book.title, "未分组")
                    var cover = ""
                    try {
                        cover = files.saveCover(id, book.cover)
                        val target = files.copy(book.sourceUri, comic, filename, book.format) { target ->
                            val volume = VolumeRecord(id, comic, comic, filename, volumeNumber(filename),
                                target.toString(), coverUri = cover, pageCount = book.pages.size, contentHash = id)
                            val stored = storeLocalVolume(volume)
                            if (stored.uri != target.toString()) {
                                runCatching { files.delete(target) }
                                if (stored.coverUri != cover) runCatching { files.deleteCover(cover) }
                            }
                            _importedVolume.value = stored
                            _message.value = if (stored.id == volume.id && stored.uri == volume.uri)
                                "已导入 $filename" else "这本书已在书架中"
                        }
                        if (target == null) {
                            files.deleteCover(cover)
                            _message.value = "同名文件已存在，未覆盖"
                        }
                    } catch (error: Exception) {
                        withContext(NonCancellable) {
                            if (dao.volume(id) == null) runCatching { files.deleteCover(cover) }
                        }
                        throw error
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.message ?: "导入失败" }
        }
    }

    fun scan(tree: Uri) = operations.launch(Dispatchers.IO) {
        localFiles.withLock {
            try {
                try {
                    resolver.takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: SecurityException) {
                    resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val items = files.scanTree(tree)
                var added = 0
                var unreadable = 0
                for (item in items) {
                    try {
                        val id = files.contentId(item.uri)
                        if (dao.findVolume(id, item.uri.toString()) != null) continue
                        BookReader.open(getApplication(), item.uri).use { book ->
                            val title = files.name(item.uri).substringBeforeLast('.')
                            val comic = comicTitle(book.title, item.parentName)
                            val volume = VolumeRecord(id, comic, comic, title, volumeNumber(title),
                                item.uri.toString(), coverUri = files.saveCover(id, book.cover),
                                pageCount = book.pages.size, contentHash = id)
                            val stored = storeLocalVolume(volume)
                            if (stored.uri == volume.uri && stored.id == volume.id) added++
                            else if (stored.coverUri != volume.coverUri) files.deleteCover(volume.coverUri)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { unreadable++ }
                }
                _message.value = "已检查 ${items.size} 个文件，新增 $added 卷" +
                    if (unreadable > 0) "，$unreadable 个无法读取" else ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.message ?: "扫描失败" }
        }
    }

    fun updatePage(id: String, page: Int, count: Int) = operations.launch {
        if (count > 0) dao.updateReading(id, page.coerceIn(0, count - 1), count, System.currentTimeMillis())
    }

    private suspend fun storeLocalVolume(volume: VolumeRecord): VolumeRecord = db.withTransaction {
        dao.findVolume(volume.contentHash, volume.uri) ?: volume.also { dao.putVolume(it) }
    }

    fun deleteVolume(volume: VolumeRecord) = operations.launch(Dispatchers.IO) {
        localFiles.withLock {
            try { removeVolume(volume); _message.value = "已从书架移除卷册和阅读位置" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.message ?: "删除失败" }
        }
    }

    fun deleteBook(books: List<VolumeRecord>) = operations.launch(Dispatchers.IO) {
        localFiles.withLock {
            var deleted = 0
            for (volume in books) {
                try { removeVolume(volume); deleted++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep entries whose files could not be removed. */ }
            }
            _message.value = if (deleted == books.size) "已删除整本漫画"
                else "已删除 $deleted / ${books.size} 卷，部分文件删除失败"
        }
    }

    private suspend fun removeVolume(volume: VolumeRecord) = withContext(NonCancellable) {
        val uri = Uri.parse(volume.uri)
        // Downloads/imports own a MediaStore copy; folder scans only reference source documents.
        if (uri.authority == "media" && !files.delete(uri)) error("文件删除失败")
        dao.deleteVolume(volume.id)
        files.deleteCover(volume.coverUri)
    }

    fun checkFiles() {
        if (fileCheckJob?.isActive == true) return
        fileCheckJob = operations.launch(Dispatchers.IO) {
            localFiles.withLock {
                var inaccessible = false
                dao.allVolumes().forEach { volume ->
                    try {
                        resolver.openInputStream(Uri.parse(volume.uri))?.close()
                            ?: throw FileNotFoundException()
                        if (volume.contentHash.isBlank()) {
                            val hash = if (volume.id.matches(Regex("[a-f0-9]{64}"))) volume.id
                                else files.contentId(Uri.parse(volume.uri))
                            dao.updateContentHash(volume.id, hash)
                        }
                    } catch (_: FileNotFoundException) {
                        dao.deleteVolume(volume.id)
                        files.deleteCover(volume.coverUri)
                    } catch (_: SecurityException) {
                        inaccessible = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { inaccessible = true }
                }
                if (inaccessible) _message.value = "部分文件暂时无法访问，请重新授权对应文件夹"
            }
        }
    }
}
