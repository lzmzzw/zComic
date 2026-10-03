package app.zcomic.data

import okhttp3.Cookie
import okhttp3.Call
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MultipartBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OnlineComic(val id: String, val title: String, val coverUrl: String, val detailUrl: String)
data class OnlineVolume(val id: String, val title: String, val number: Int, val size: String, val downloadOne: String, val downloadTwo: String)
data class ComicDetail(val comic: OnlineComic, val description: String, val volumes: List<OnlineVolume>)

enum class ComicSort(val label: String, val order: String) {
    COMPREHENSIVE("综合排序", "sortpoint"),
    RATING("评价排名", "score"),
    POPULARITY("热度排序", "count_push"),
    RECENT("最近更新", "lastupdate")
}

class KmoeClient(private val root: String = "https://kxo.moe") {
    private val rootUrl = root.toHttpUrl()
    private val userAgent = "Mozilla/5.0 zComic/1.0.0"
    private val cookies = mutableListOf<Cookie>()
    private val sessionLock = Any()
    private var generation = 0L
    private data class Session(val generation: Long)
    private val requestSession = ThreadLocal<Session>()
    private val calls = mutableSetOf<Call>()

    fun logout() {
        val active = synchronized(sessionLock) {
            generation++
            cookies.clear()
            calls.toList()
        }
        active.forEach { it.cancel() }
    }

    internal fun activeCalls(): List<Call> = synchronized(sessionLock) { calls.toList() }

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, received: List<Cookie>) {
                synchronized(sessionLock) {
                    if (requestSession.get()?.generation != generation) return
                    val now = System.currentTimeMillis()
                    cookies.removeAll { previous -> previous.expiresAt <= now || received.any {
                        it.name == previous.name && it.domain == previous.domain && it.path == previous.path
                    } }
                    cookies.addAll(received.filter { it.expiresAt > now })
                }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> = synchronized(sessionLock) {
                if (requestSession.get()?.generation != generation) return@synchronized emptyList()
                cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
                cookies.filter { it.matches(url) }
            }
        })
        .addInterceptor { chain ->
            requestSession.set(chain.request().tag(Session::class.java))
            try { chain.proceed(chain.request()) } finally { requestSession.remove() }
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun <T> request(request: Request, sessionGeneration: Long? = null, block: suspend (Response) -> T): T = coroutineScope {
        val call = synchronized(sessionLock) {
            if (sessionGeneration != null && sessionGeneration != generation)
                throw CancellationException("登录已取消")
            client.newCall(request.newBuilder().tag(Session::class.java, Session(generation)).build())
                .also { calls += it }
        }
        // A separate dispatcher keeps cancellation responsive while IO reads block.
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    currentCoroutineContext().ensureActive()
                    call.execute().use { response ->
                        currentCoroutineContext().ensureActive()
                        val result = block(response)
                        currentCoroutineContext().ensureActive()
                        if (call.isCanceled() || synchronized(sessionLock) {
                            call.request().tag(Session::class.java)?.generation != generation
                        }) throw CancellationException("请求已取消")
                        result
                    }
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (call.isCanceled()) throw CancellationException("请求已取消").apply { initCause(error) }
                    throw error
                }
            }
        } finally {
            cancellation.cancel()
            synchronized(sessionLock) { calls -= call }
        }
    }

    suspend fun login(email: String, password: String) {
        val loginGeneration = synchronized(sessionLock) { generation }
        request(Request.Builder().url("$root/login.php")
            .header("User-Agent", userAgent).build(), loginGeneration) { response ->
            if (!response.isSuccessful) throw IOException("无法访问登录页 (${response.code})")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("email", email).addFormDataPart("passwd", password).build()
        val submission = Request.Builder().url("$root/login_act.php").post(body)
            .header("Referer", "$root/login.php")
            .header("User-Agent", userAgent)
            .header("X-KM-FROM", "KMOE/3.0.0 POST /login.php").build()
        request(submission, loginGeneration) { response ->
            if (!response.isSuccessful) throw IOException("登录请求失败 (${response.code})")
            val raw = response.body?.string().orEmpty()
            val result = try { JSONObject(raw) } catch (_: Exception) {
                throw IOException("网站返回了非登录响应，请稍后重试")
            }
            if (result.optString("msgid") != "m100") throw IOException("登录失败，请检查账号或网站登录限制")
        }
    }

    private suspend fun html(url: String): String {
        val target = url.toHttpUrl()
        require(target.scheme == rootUrl.scheme && target.host == rootUrl.host && target.port == rootUrl.port) { "不支持的站点地址" }
        return request(Request.Builder().url(target).header("User-Agent", userAgent)
            .header("Referer", "$root/").build()) {
            if (!it.isSuccessful) throw IOException("网站请求失败 (${it.code})")
            if (it.request.url.encodedPath.endsWith("login.php")) throw IOException("登录状态已失效")
            it.body?.string().orEmpty()
        }
    }

    suspend fun browse(sort: ComicSort): List<OnlineComic> = withContext(Dispatchers.IO) {
        parseList(html("$root/l/all,all,all,${sort.order},all,all,BL,0,0/"))
    }

    suspend fun recent(): List<OnlineComic> = browse(ComicSort.RECENT)
    suspend fun search(text: String): List<OnlineComic> = withContext(Dispatchers.IO) {
        parseList(html("$root/list.php?s=${java.net.URLEncoder.encode(text, "UTF-8")}"))
    }

    internal fun parseList(source: String): List<OnlineComic> {
        val found = mutableListOf<OnlineComic>()
        val calls = Regex("disp_divinfo\\s*\\((.*?)\\);", RegexOption.DOT_MATCHES_ALL)
        val quoted = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        for (call in calls.findAll(source)) {
            val args = quoted.findAll(call.groupValues[1]).map { it.groupValues[1].replace("\\/", "/") }.toList()
            if (args.size < 11) continue
            val url = args[2]
            if (!url.startsWith("$root/c/")) continue
            val title = Jsoup.parseBodyFragment(args[10]).text()
            found += OnlineComic(url.substringAfterLast('/').substringBefore('.'), title, args[3], url)
        }
        return found.distinctBy { it.id }
    }

    suspend fun detail(comic: OnlineComic): ComicDetail = withContext(Dispatchers.IO) {
        val source = html(comic.detailUrl)
        val hash = Regex("data_book\\(\\s*\"([a-zA-Z0-9]+)\"").find(source)?.groupValues?.get(1)
            ?: error("网站详情接口已改变")
        parseDetail(comic, source, html("$root/data_book.php?h=$hash"))
    }

    internal fun parseDetail(comic: OnlineComic, source: String, volumeJson: String): ComicDetail {
        val document = Jsoup.parse(source, comic.detailUrl)
        val description = document.select("meta[name=description]").attr("content").ifBlank {
            document.select(".book_intro, .comic_intro").text()
        }
        val bookId = Regex("var\\s+bookid\\s*=\\s*\"(\\d+)\"").find(source)?.groupValues?.get(1)
            ?: error("网站作品编号缺失")
        val result = parseJson(volumeJson, "网站返回了无效的卷册响应")
        val data = result.optJSONArray("voldata") ?: error("无法读取网站卷册列表")
        val volumes = (0 until data.length()).mapNotNull { index ->
            val row = data.optJSONArray(index) ?: return@mapNotNull null
            val volumeId = row.optString(0)
            if (volumeId.isBlank()) return@mapNotNull null
            val title = row.optString(5).ifBlank { "卷 %02d".format(index + 1) }
            val number = Regex("\\d+").find(title)?.value?.toIntOrNull() ?: index + 1
            val size = row.optString(11).takeIf { it != "0" }.orEmpty()
            val first = "$root/getdownurl.php?b=$bookId&v=$volumeId&mobi=2&json=1&vip=0"
            val second = "$root/dl/$bookId/$volumeId/1/2/0/"
            OnlineVolume("${comic.id}-$volumeId", title, number, if (size.isBlank()) "" else "${size} MB",
                first, second)
        }.sortedBy { it.number }
        if (volumes.isEmpty() && result.optString("msgid") !in listOf("", "0")) throw IOException("无法读取卷册，请检查登录状态或网站权限")
        return ComicDetail(comic, description, volumes)
    }

    private fun parseJson(value: String, message: String): JSONObject = try {
        JSONObject(value)
    } catch (_: Exception) { throw IOException(message) }

    private fun parseUrl(value: String): okhttp3.HttpUrl = try {
        value.toHttpUrl()
    } catch (_: IllegalArgumentException) { throw IOException("网站提供了无效的下载地址") }

    suspend fun <T> withDownload(url: String, from: Long = 0, validator: String = "",
        block: suspend (Response) -> T): T {
        try {
            val target = withContext(Dispatchers.IO) {
                if (url.startsWith("$root/getdownurl.php?")) {
                    val result = request(Request.Builder().url(url).header("User-Agent", userAgent)
                        .header("Referer", "$root/").build()) { response ->
                        if (!response.isSuccessful) throw DownloadHttpException(response.code, "下载地址获取失败 (${response.code})")
                        if (response.request.url.encodedPath.endsWith("login.php"))
                            throw DownloadHttpException(401, "登录状态已失效")
                        parseJson(response.body?.string().orEmpty(), "网站返回了无效的下载响应")
                    }
                    val resolved = result.optString("url")
                    if (resolved.isBlank()) throw IOException("网站未提供下载地址，可能需要人工验证或权限不足")
                    parseUrl(resolved)
                } else parseUrl(url)
            }
            require(target.isHttps || (rootUrl.scheme == "http" && target.host == rootUrl.host && target.port == rootUrl.port)) { "下载链接必须使用 HTTPS" }
            val downloadRequest = Request.Builder().url(target).header("Referer", "$root/")
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .apply {
                    if (from > 0 && validator.isNotBlank()) {
                        header("Range", "bytes=$from-")
                        header("If-Range", validator)
                    }
                }.build()
            return request(downloadRequest) { response ->
                if (response.request.url.encodedPath.endsWith("login.php"))
                    throw DownloadHttpException(401, "登录状态已失效")
                if (response.code !in listOf(200, 206, 416))
                    throw DownloadHttpException(response.code, "下载请求失败 (${response.code})")
                block(response)
            }
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DownloadHttpException) {
            throw error
        } catch (error: DownloadProtocolException) {
            throw error
        } catch (error: DownloadStorageException) {
            throw error
        } catch (_: IOException) {
            // OkHttp errors can contain the signed URL; never retain them as a cause or message.
            throw IOException("下载连接中断，请稍后重试")
        }
    }
}
