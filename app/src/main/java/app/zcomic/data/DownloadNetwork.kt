package app.zcomic.data

import android.net.Network
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException

/** Only this job's requests use its assigned network, including login and signed-URL lookup. */
internal class DownloadNetwork(initial: Network?) {
    private var network = initial
    private var routed: OkHttpClient? = null
    val transport = RequestTransport(::client)

    @Synchronized
    fun update(next: Network?) {
        if (network == next) return
        routed?.connectionPool?.evictAll()
        routed = null
        network = next
    }

    @Synchronized
    private fun client(base: OkHttpClient): OkHttpClient {
        val assigned = network ?: throw IOException("后台下载网络暂时不可用")
        return routed ?: base.newBuilder()
            .socketFactory(assigned.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = assigned.getAllByName(hostname).toList()
            })
            // No foreground/default-network socket may leak into the job through pooling.
            .connectionPool(ConnectionPool())
            .build().also { routed = it }
    }

    @Synchronized
    fun close() { routed?.connectionPool?.evictAll(); routed = null }
}
