package app.zcomic.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared account and queue ownership survives Activity/ViewModel destruction. */
internal class DownloadRuntime private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = ComicDatabase.open(context)
    val files = ComicFiles(context)
    val site = KmoeClient()
    private val account = Credentials(context)
    private val session = Mutex()
    private var initialized = false
    private var generation = 0
    private val _saved = MutableStateFlow<Pair<String, String>?>(null)
    val saved = _saved.asStateFlow()
    private val _loginStatus = MutableStateFlow("未登录")
    val loginStatus = _loginStatus.asStateFlow()
    val scheduler = DownloadScheduler(context)
    val queue = DownloadManager(context, db, files, site, scheduler, ::authenticated)

    suspend fun restoreSession() = session.withLock {
        if (!initialized) {
            val version = generation
            val saved = withContext(Dispatchers.IO) { account.read() }
            if (version != generation) return@withLock
            _saved.value = saved
            initialized = true
            if (saved != null) signIn(saved.first, saved.second)
        }
    }

    suspend fun login(email: String, password: String, remember: Boolean) = session.withLock {
        signIn(email, password)
        initialized = true
        if (remember) {
            val version = generation
            withContext(Dispatchers.IO) { account.save(email, password) }
            if (version == generation) _saved.value = email to password
        }
    }

    private suspend fun signIn(email: String, password: String) {
        val version = generation
        _loginStatus.value = "正在登录"
        try {
            site.login(email, password)
            if (version != generation) throw CancellationException("登录已取消")
            generation++
            _loginStatus.value = "已登录"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _loginStatus.value = "登录失败"; throw error }
    }

    fun logout() {
        generation++
        initialized = true
        site.logout()
        account.clear()
        _saved.value = null
        _loginStatus.value = "未登录"
    }

    suspend fun <T> authenticated(action: suspend () -> T): T {
        restoreSession()
        val version = generation
        try { return action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (error.message?.contains("登录状态") != true &&
                (error !is DownloadHttpException || error.statusCode !in listOf(401, 403))) throw error
            session.withLock {
                if (version == generation) {
                    val saved = _saved.value ?: throw error
                    signIn(saved.first, saved.second)
                }
            }
            return action()
        }
    }

    companion object {
        @Volatile private var instance: DownloadRuntime? = null
        fun get(context: Context): DownloadRuntime = instance ?: synchronized(this) {
            instance ?: DownloadRuntime(context.applicationContext).also { instance = it }
        }
    }
}
