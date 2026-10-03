package app.zcomic.data

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Request-local routing survives dispatcher switches without changing the UI's network. */
internal class RequestTransport(val client: (OkHttpClient) -> OkHttpClient) :
    AbstractCoroutineContextElement(Key) {
    private val downloaded = AtomicLong()
    val downloadedBytes: Long get() = downloaded.get()

    fun count(body: ResponseBody): ResponseBody = object : ResponseBody() {
        private val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) downloaded.addAndGet(it)
            }
        }.buffer()
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source() = counted
    }

    companion object Key : CoroutineContext.Key<RequestTransport>
}
