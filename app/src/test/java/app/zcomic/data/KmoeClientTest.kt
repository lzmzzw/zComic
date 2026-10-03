package app.zcomic.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

class KmoeClientTest {
    private val client = KmoeClient()

    @Test fun listParsesRenderedCardCalls() {
        val html = """<script>disp_divinfo("div_info_"+"1", "https://kxo.moe/c/a123.htm", "https://img.example/1.jpg", "#aaa", "", "", "", "", "8.9", "测试漫画", "作者", "", "");</script>"""
        val cards = client.parseList(html)
        assertEquals(1, cards.size)
        assertEquals("a123", cards[0].id)
        assertEquals("测试漫画", cards[0].title)
    }

    @Test fun searchHighlightIsNotShownAsLiteralMarkup() {
        val html = """<script>disp_divinfo("div_info_"+"1", "https://kxo.moe/c/73a59e.htm", "https://img.example/cover.jpg", "#aaa", "", "", "", "", "8.3", "<b>魔男伊奇</b>", "作者", "", "");</script>"""

        val cards = client.parseList(html)

        assertEquals(1, cards.size)
        assertEquals("魔男伊奇", cards[0].title)
    }

    @Test fun detailBuildsEpubEndpointsFromVolumeData() {
        val comic = OnlineComic("a123", "测试漫画", "", "https://kxo.moe/c/a123.htm")
        val page = """<html><script>var bookid = "40210"; data_book("hash123");</script></html>"""
        val json = """{"msgid":0,"voldata":[["99",0,0,"卷",1,"卷 01",0,248,0,0,0,172.3]]}"""
        val detail = client.parseDetail(comic, page, json)
        assertEquals(1, detail.volumes.size)
        assertEquals("a123-99", detail.volumes[0].id)
        assertTrue(detail.volumes[0].downloadTwo.endsWith("/dl/40210/99/1/2/0/"))
        assertTrue(detail.volumes[0].downloadOne.contains("mobi=2&json=1&vip=0"))
    }

    @Test fun loginEstablishesSessionAndSubmitsBrowserForm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("login").addHeader("Set-Cookie", "VLIBSID=test-session; Path=/"))
            server.enqueue(MockResponse().setBody("""{"msgid":"m100"}"""))
            server.start()

            KmoeClient(server.url("/").toString().removeSuffix("/")).login("reader@example.test", "test-password")

            val page = server.takeRequest()
            assertEquals("GET", page.method)
            assertEquals("/login.php", page.path)
            val submit = server.takeRequest()
            assertEquals("POST", submit.method)
            assertEquals("/login_act.php", submit.path)
            assertEquals("KMOE/3.0.0 POST /login.php", submit.getHeader("X-KM-FROM"))
            assertEquals("VLIBSID=test-session", submit.getHeader("Cookie"))
            assertTrue(submit.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data; boundary="))
            val form = submit.body.readUtf8()
            assertTrue(Regex("name=\"email\"[^\\r]*\\r\\n(?:[^\\r]*\\r\\n)*\\r\\nreader@example\\.test").containsMatchIn(form))
            assertTrue(Regex("name=\"passwd\"[^\\r]*\\r\\n(?:[^\\r]*\\r\\n)*\\r\\ntest-password").containsMatchIn(form))
        }
    }

    @Test fun downloadResolvesSignedEpubUrlAndUsesBrowserHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val signedUrl = server.url("/volume.epub?sign=test")
            server.enqueue(MockResponse().setBody("""{"code":200,"url":"$signedUrl"}"""))
            server.enqueue(MockResponse().setBody("epub bytes").addHeader("Content-Type", "application/epub+zip"))

            val root = server.url("/").toString().removeSuffix("/")
            KmoeClient(root).withDownload("$root/getdownurl.php?b=34941&v=1&mobi=2&json=1&vip=0") {
                assertEquals(200, it.code)
                assertEquals("epub bytes", it.body!!.string())
            }

            val resolve = server.takeRequest()
            assertEquals("/getdownurl.php?b=34941&v=1&mobi=2&json=1&vip=0", resolve.path)
            assertEquals("Mozilla/5.0 zComic/1.0.0", resolve.getHeader("User-Agent"))
            val file = server.takeRequest()
            assertEquals("/volume.epub?sign=test", file.path)
            assertEquals("Mozilla/5.0 zComic/1.0.0", file.getHeader("User-Agent"))
            assertEquals("$root/", file.getHeader("Referer"))
            assertEquals("identity", file.getHeader("Accept-Encoding"))
        }
    }

    @Test fun browseUsesEachSiteSortEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(ComicSort.entries.size) { server.enqueue(MockResponse().setBody("<html></html>")) }
            val root = server.url("/").toString().removeSuffix("/")
            val client = KmoeClient(root)

            ComicSort.entries.forEach { sort ->
                client.browse(sort)
                assertEquals("/l/all,all,all,${sort.order},all,all,BL,0,0/", server.takeRequest().path)
            }
        }
    }

    @Test fun sameNameCookiesKeepDistinctPaths() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("").addHeader("Set-Cookie", "sid=root; Path=/")
                .addHeader("Set-Cookie", "sid=list; Path=/l"))
            server.enqueue(MockResponse().setBody(""))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            client.recent()
            client.recent()
            server.takeRequest()
            val cookie = server.takeRequest().getHeader("Cookie").orEmpty()
            assertTrue(cookie.contains("sid=root"))
            assertTrue(cookie.contains("sid=list"))
        }
    }

    @Test fun expiredAndDeletedCookiesAreNotSent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("").addHeader("Set-Cookie", "sid=first; Path=/")
                .addHeader("Set-Cookie", "expired=old; Max-Age=0; Path=/"))
            server.enqueue(MockResponse().setBody("").addHeader("Set-Cookie", "sid=deleted; Max-Age=0; Path=/"))
            server.enqueue(MockResponse().setBody(""))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            repeat(3) { client.recent() }
            server.takeRequest()
            assertEquals("sid=first", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun logoutClearsSession() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("").addHeader("Set-Cookie", "sid=first; Path=/"))
            server.enqueue(MockResponse().setBody(""))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            client.recent()
            client.logout()
            client.recent()
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun cancellingDelayedHeadersCancelsRealCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok").setHeadersDelay(2, TimeUnit.SECONDS))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            val job = launch { client.recent() }
            withContext(Dispatchers.IO) { assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null) }
            val call = client.activeCalls().single()
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(client.activeCalls().isEmpty())
        }
    }

    @Test fun cancellingBodyReadCancelsRealCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("epub bytes").setBodyDelay(2, TimeUnit.SECONDS))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            val bodyStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job = launch {
                client.withDownload(server.url("/book.epub").toString()) {
                    bodyStarted.complete(Unit)
                    it.body!!.string()
                }
            }
            withTimeout(2000) { bodyStarted.await() }
            val call = client.activeCalls().single()
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(client.activeCalls().isEmpty())
        }
    }

    @Test fun logoutCancelsActiveLoginAndDoesNotSubmitForm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("login").setHeadersDelay(2, TimeUnit.SECONDS)
                .addHeader("Set-Cookie", "sid=stale; Path=/"))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            val job = launch { runCatching { client.login("reader@example.test", "test-password") } }
            withContext(Dispatchers.IO) { assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null) }
            val call = client.activeCalls().single()
            client.logout()
            withTimeout(1000) { job.join() }
            assertTrue(call.isCanceled())
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody(""))
            client.recent()
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun successfulEmptyVolumeListIsAllowed() {
        val comic = OnlineComic("a123", "测试漫画", "", "https://kxo.moe/c/a123.htm")
        val page = """<script>var bookid = "40210";</script>"""
        assertTrue(client.parseDetail(comic, page, """{"msgid":0,"voldata":[]}""").volumes.isEmpty())
    }

    @Test fun resumeUsesRangeOnlyWithValidator() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("part"))
            server.enqueue(MockResponse().setBody("full"))
            server.start()
            val client = KmoeClient(server.url("/").toString().removeSuffix("/"))
            val url = server.url("/book.epub").toString()
            client.withDownload(url, 100, "\"version-one\"") { it.body!!.string() }
            client.withDownload(url, 100) { it.body!!.string() }
            val resumed = server.takeRequest()
            assertEquals("bytes=100-", resumed.getHeader("Range"))
            assertEquals("\"version-one\"", resumed.getHeader("If-Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun invalidSignedAddressDoesNotLeakResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"url":"not-a-url?sign=private-value"}"""))
            server.start()
            val root = server.url("/").toString().removeSuffix("/")
            val error = runCatching {
                KmoeClient(root).withDownload("$root/getdownurl.php?b=1") { }
            }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertTrue(!error!!.message.orEmpty().contains("private-value"))
        }
    }

    @Test fun resumedRequestResolvesANewSignedAddressEveryTime() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/").toString().removeSuffix("/")
            val client = KmoeClient(root)
            repeat(2) { attempt ->
                val signed = server.url("/file.epub?sign=version-$attempt")
                server.enqueue(MockResponse().setBody("""{"url":"$signed"}"""))
                server.enqueue(MockResponse().setBody("part"))
                client.withDownload("$root/getdownurl.php?b=1", 100, "\"book\"") { it.body!!.string() }
                assertTrue(server.takeRequest().path.orEmpty().startsWith("/getdownurl.php?"))
                val download = server.takeRequest()
                assertEquals("/file.epub?sign=version-$attempt", download.path)
                assertEquals("bytes=100-", download.getHeader("Range"))
            }
        }
    }

    @Test fun downloadHttpFailuresKeepStatusWithoutSignedUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val error = runCatching {
                KmoeClient(server.url("/").toString().removeSuffix("/"))
                    .withDownload(server.url("/file.epub?sign=private-value").toString()) { }
            }.exceptionOrNull()
            assertTrue(error is DownloadHttpException)
            assertEquals(503, (error as DownloadHttpException).statusCode)
            assertFalse(error.message.orEmpty().contains("private-value"))
        }
    }
}
