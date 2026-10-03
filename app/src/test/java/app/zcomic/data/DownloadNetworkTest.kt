package app.zcomic.data

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** A real HTTP exchange with an otherwise unresolvable host tests both DNS and sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [DownloadNetworkTest.AssignedNetworkShadow::class])
class DownloadNetworkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun interruptedRoutedDownloadResumesOnChangedJobNetworkWithExactBytes() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val root = "http://job-network.invalid:${server.port}"
            val source = "$root/book.epub"
            val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
            val directory = temporary.newFolder()
            val transfer = ResumableTransfer(directory, KmoeClient(root))
            val network = DownloadNetwork(network(101))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).addHeader("ETag", "\"v1\"")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            withContext(network.transport) {
                val error = runCatching { transfer.download("volume", listOf(source)) { _, _ -> } }.exceptionOrNull()
                assertTrue(error is java.io.IOException)
            }
            server.takeRequest()
            val part = directory.walkTopDown().single { it.extension == "part" }
            val offset = part.length()
            assertTrue(offset in 1 until payload.size.toLong())
            network.update(network(202))
            server.enqueue(MockResponse().setResponseCode(206)
                .setBody(Buffer().write(payload, offset.toInt(), payload.size - offset.toInt()))
                .addHeader("ETag", "\"v1\"")
                .addHeader("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}"))
            withContext(network.transport) {
                assertArrayEquals(payload, transfer.download("volume", listOf(source)) { _, _ -> }.readBytes())
            }
            val resumed = server.takeRequest()
            assertEquals("bytes=$offset-", resumed.getHeader("Range"))
            assertEquals("\"v1\"", resumed.getHeader("If-Range"))
            assertEquals(payload.size.toLong(), network.transport.downloadedBytes)
            network.close()
        }
    }

    @Test
    fun jobNetworkRoutesLoginAndDownloadAcrossIoDispatcherWithoutChangingForegroundClient() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("login").addHeader("Set-Cookie", "VLIBSID=job-session; Path=/"))
            server.enqueue(MockResponse().setBody("""{"msgid":"m100"}"""))
            server.enqueue(MockResponse().setBody("download"))
            val root = "http://job-network.invalid:${server.port}"
            val client = KmoeClient(root)
            val network = DownloadNetwork(network(101))
            AssignedNetworkShadow.dnsCalls.set(0)
            AssignedNetworkShadow.socketCalls.set(0)
            withContext(network.transport + Dispatchers.IO) {
                client.login("reader@example.test", "test-password")
                assertEquals("download", client.withDownload("$root/book.epub") { it.body!!.string() })
            }
            assertTrue(AssignedNetworkShadow.dnsCalls.get() > 0)
            assertTrue(AssignedNetworkShadow.socketCalls.get() > 0)
            assertEquals("""{"msgid":"m100"}""".length.toLong() + 8, network.transport.downloadedBytes)
            assertEquals("/login.php", server.takeRequest().path)
            assertEquals("/login_act.php", server.takeRequest().path)
            assertEquals("VLIBSID=job-session", server.takeRequest().getHeader("Cookie"))

            // The same account client outside the job must use ordinary routing, not our fake DNS.
            val before = AssignedNetworkShadow.dnsCalls.get()
            try { client.withDownload("$root/book.epub") { it.body!!.string() }; fail("默认网络不应解析测试域名") }
            catch (_: java.io.IOException) { }
            assertEquals(before, AssignedNetworkShadow.dnsCalls.get())
            network.close()
        }
    }

    @Test
    fun changedNetworkUsesNewDnsSocketsAndPoolWhileRetainingJobByteCounter() {
        val routing = DownloadNetwork(network(101))
        val base = okhttp3.OkHttpClient()
        val first = routing.transport.client(base)
        routing.update(network(202))
        val second = routing.transport.client(base)
        assertNotSame(first, second)
        assertNotSame(first.connectionPool, second.connectionPool)
        assertNotSame(base.connectionPool, first.connectionPool)
        assertSame(base.cookieJar, second.cookieJar)
        assertNotSame(base.dns, second.dns)
        routing.close()
    }

    @Test
    fun missingAssignedNetworkFailsInsteadOfSilentlyUsingDefaultRoute() {
        val routing = DownloadNetwork(null)
        assertThrows(java.io.IOException::class.java) { routing.transport.client(okhttp3.OkHttpClient()) }
    }

    private fun network(id: Int) = ReflectionHelpers.callConstructor(Network::class.java,
        ClassParameter.from(Int::class.javaPrimitiveType!!, id))

    @Implements(Network::class)
    class AssignedNetworkShadow {
        @Implementation
        fun getSocketFactory(): SocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket { socketCalls.incrementAndGet(); return Socket() }
            override fun createSocket(host: String, port: Int) = Socket(host, port)
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = Socket(host, port, local, localPort)
            override fun createSocket(host: InetAddress, port: Int) = Socket(host, port)
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = Socket(host, port, local, localPort)
        }
        @Implementation
        fun getAllByName(host: String): Array<InetAddress> {
            dnsCalls.incrementAndGet()
            check(host == "job-network.invalid")
            return arrayOf(InetAddress.getByName("127.0.0.1"))
        }
        companion object {
            val dnsCalls = AtomicInteger()
            val socketCalls = AtomicInteger()
        }
    }
}
