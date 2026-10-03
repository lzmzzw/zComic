package app.zcomic.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit

class ResumableTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
    private val etag = "\"book-version-1\""

    private fun response(bytes: ByteArray = payload, tag: String = etag) = MockResponse()
        .setBody(Buffer().write(bytes)).addHeader("ETag", tag)
        .addHeader("Content-Type", "application/epub+zip")

    private fun partial(directory: File) = directory.walkTopDown().single { it.extension == "part" }
    private fun metadata(directory: File) = directory.walkTopDown().single { it.extension == "properties" }
    private fun client(server: MockWebServer) = KmoeClient(server.url("/").toString().removeSuffix("/"))
    private fun resumed(start: Long, tag: String = etag) = response(payload.copyOfRange(start.toInt(), payload.size), tag)
        .setResponseCode(206).addHeader("Content-Range", "bytes $start-${payload.lastIndex}/${payload.size}")

    private suspend fun interrupt(server: MockWebServer, directory: File, source: String) {
        server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val error = runCatching { ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> } }
            .exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(partial(directory).length() in 1 until payload.size.toLong())
        server.takeRequest()
    }

    @Test fun interruptedTransferResumesAfterEngineRestartAndFlushesProgress() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val start = partial(directory).length()
            server.enqueue(resumed(start))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { bytes, total ->
                assertTrue(partial(directory).length() >= bytes)
                assertEquals(payload.size.toLong(), total)
            }
            assertArrayEquals(payload, file.readBytes())
            val request = server.takeRequest()
            assertEquals("bytes=$start-", request.getHeader("Range"))
            assertEquals(etag, request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }

    @Test fun serverReturning200ReplacesPartialInsteadOfAppending() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val changed = ByteArray(payload.size) { 42 }
            server.enqueue(response(changed, "\"new-version\""))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> }
            assertArrayEquals(changed, file.readBytes())
            assertTrue(server.takeRequest().getHeader("Range") != null)
        }
    }

    @Test fun malformed206DoesNotChangeExistingPartialOrMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val oldBytes = partial(directory).readBytes()
            val oldMetadata = metadata(directory).readBytes()
            server.enqueue(response(byteArrayOf(1, 2)).setResponseCode(206)
                .addHeader("Content-Range", "bytes ${oldBytes.size + 1}-${oldBytes.size + 2}/${payload.size}"))
            val error = runCatching { ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> } }
                .exceptionOrNull()
            assertTrue(error is DownloadProtocolException)
            assertArrayEquals(oldBytes, partial(directory).readBytes())
            assertArrayEquals(oldMetadata, metadata(directory).readBytes())
        }
    }

    @Test fun inconsistent206LengthAndOverflowingRangeAreRejectedBeforeWriting() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val oldBytes = partial(directory).readBytes()
            val start = oldBytes.size
            val responses = listOf(
                response(byteArrayOf(1, 2)).setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-${start + 2}/${payload.size}"),
                response(byteArrayOf(1, 2)).setResponseCode(206)
                    .addHeader("Content-Range", "bytes $start-999999999999999999999/999999999999999999999")
            )
            responses.forEach { response ->
                server.enqueue(response)
                val error = runCatching { ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> } }
                    .exceptionOrNull()
                assertTrue(error is DownloadProtocolException)
                assertArrayEquals(oldBytes, partial(directory).readBytes())
            }
        }
    }

    @Test fun changedValidatorOn206RequiresFreshRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val start = partial(directory).length()
            val replacement = ByteArray(payload.size) { 13 }
            server.enqueue(resumed(start, "\"new-version\""))
            server.enqueue(response(replacement, "\"new-version\""))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> }
            assertArrayEquals(replacement, file.readBytes())
            assertTrue(server.takeRequest().getHeader("Range") != null)
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun equalLength416RequiresMatchingValidator() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val oldBytes = partial(directory).readBytes()
            val values = Properties().apply { metadata(directory).inputStream().use { load(it) }; setProperty("total", "0") }
            metadata(directory).outputStream().use { values.store(it, null) }
            server.enqueue(MockResponse().setResponseCode(416).addHeader("ETag", etag)
                .addHeader("Content-Range", "bytes */${oldBytes.size}"))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> }
            assertArrayEquals(oldBytes, file.readBytes())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun incompatible416SafelyRestartsAndReplacesPartial() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            server.enqueue(MockResponse().setResponseCode(416).addHeader("ETag", "\"new-version\"")
                .addHeader("Content-Range", "bytes */${payload.size}"))
            server.enqueue(response())
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> }
            assertArrayEquals(payload, file.readBytes())
            assertTrue(server.takeRequest().getHeader("Range") != null)
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun failedPrimaryDoesNotDiscardFallbackAndNextRunPrefersFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val first = server.url("/primary.epub").toString()
            val second = server.url("/backup.epub").toString()
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val engine = ResumableTransfer(directory, client(server))
            assertTrue(runCatching { engine.download("task", listOf(first, second)) { _, _ -> } }.isFailure)
            assertEquals("/primary.epub", server.takeRequest().path)
            assertEquals("/backup.epub", server.takeRequest().path)
            val start = partial(directory).length()
            server.enqueue(resumed(start))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(first, second)) { _, _ -> }
            assertEquals("/backup.epub", server.takeRequest().path)
            assertArrayEquals(payload, file.readBytes())
        }
    }

    @Test fun partialsFromDifferentSourcesNeverMix() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val first = server.url("/primary.epub").toString()
            val second = server.url("/backup.epub").toString()
            val replacement = ByteArray(payload.size) { 77 }
            server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(response(replacement, "\"different-file\""))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(first, second)) { _, _ -> }
            assertArrayEquals(replacement, file.readBytes())
            assertNull(server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals(2, directory.walkTopDown().count { it.extension == "part" })
        }
    }

    @Test fun missingOrWeakValidatorRestartsRatherThanAppending() = runBlocking {
        listOf("", "W/\"weak-version\"").forEach { tag ->
            MockWebServer().use { server ->
                server.start()
                val directory = temporary.newFolder()
                val source = server.url("/book.epub").toString()
                server.enqueue(response(tag = tag).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                val engine = ResumableTransfer(directory, client(server))
                assertTrue(runCatching { engine.download("task", listOf(source)) { _, _ -> } }.isFailure)
                server.takeRequest()
                server.enqueue(response())
                val file = engine.download("task", listOf(source)) { _, _ -> }
                assertArrayEquals(payload, file.readBytes())
                assertNull(server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test fun completeFileIsReusedWithoutNetworkAndCleanupIsTaskScoped() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub?sign=private-sign").toString()
            server.enqueue(response())
            val engine = ResumableTransfer(directory, client(server))
            val file = engine.download("task", listOf(source)) { _, _ -> }
            val otherTask = File(directory, "unrelated-task").apply { mkdirs() }
            File(otherTask, "keep").writeText("unchanged")
            val reused = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> }
            assertEquals(file, reused)
            assertEquals(1, server.requestCount)
            directory.walkTopDown().filter { it.isFile && it.extension != "part" }.forEach {
                assertFalse(it.readText().contains("private-sign"))
            }
            engine.clean("task")
            assertFalse(file.exists())
            assertTrue(File(otherTask, "keep").exists())
        }
    }

    @Test fun cancellationKeepsFlushedPartialAndResumeWorks() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            server.enqueue(response().throttleBody(1024, 40, TimeUnit.MILLISECONDS))
            val engine = ResumableTransfer(directory, client(server))
            val progress = CompletableDeferred<Unit>()
            val download = launch {
                engine.download("task", listOf(source)) { bytes, _ ->
                    if (bytes > 0) progress.complete(Unit)
                }
            }
            withTimeout(3000) { progress.await() }
            withTimeout(1500) { download.cancelAndJoin() }
            server.takeRequest()
            val start = partial(directory).length()
            assertTrue(start in 1 until payload.size.toLong())
            server.enqueue(resumed(start))
            val file = ResumableTransfer(directory, client(server)).download("task", listOf(source)) { _, _ -> }
            assertArrayEquals(payload, file.readBytes())
            assertEquals("bytes=$start-", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun lastModifiedAllowsSameSourceResumeWithoutStrongEtag() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
            server.enqueue(response(tag = "W/\"weak-version\"").addHeader("Last-Modified", modified)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val engine = ResumableTransfer(directory, client(server))
            assertTrue(runCatching { engine.download("task", listOf(source)) { _, _ -> } }.isFailure)
            server.takeRequest()
            val start = partial(directory).length()
            server.enqueue(resumed(start, "W/\"weak-version\"").addHeader("Last-Modified", modified))
            assertArrayEquals(payload, engine.download("task", listOf(source)) { _, _ -> }.readBytes())
            val request = server.takeRequest()
            assertEquals(modified, request.getHeader("If-Range"))
            assertEquals("bytes=$start-", request.getHeader("Range"))
        }
    }

    @Test fun legacyCacheMigratesWithoutPersistingUrlAndResumesAtExactOffset() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Windows reverse DNS may name MockWebServer's loopback kubernetes.docker.internal.
            // Use an explicit loopback URL in both source and client without broadening production rules.
            val root = server.url("/").newBuilder().host("127.0.0.1").build()
            assertEquals("127.0.0.1", root.host)
            assertEquals("http", root.scheme)
            val directory = temporary.newFolder()
            val cache = temporary.newFolder()
            val source = root.resolve("book.epub?sign=private-legacy-sign")!!.toString()
            val oldPart = File(cache, "task.part").apply { writeBytes(payload.copyOfRange(0, 1024)) }
            val oldSource = File(cache, "task.source").apply { writeText(source) }
            val oldValidator = File(cache, "task.validator").apply { writeText(etag) }
            val engine = ResumableTransfer(directory, KmoeClient(root.toString().removeSuffix("/")))
            engine.migrateLegacy("task", oldPart, oldSource, oldValidator)
            assertEquals("Migration should create exactly one durable partial", 1,
                directory.walkTopDown().count { it.extension == "part" })
            assertEquals(1024L, partial(directory).length())
            assertFalse(oldPart.exists())
            assertFalse(oldSource.exists())
            assertFalse(oldValidator.exists())
            directory.walkTopDown().filter { it.isFile && it.extension != "part" }.forEach {
                assertFalse(it.readText().contains("private-legacy-sign"))
            }
            server.enqueue(resumed(1024))
            assertArrayEquals(payload, engine.download("task", listOf(source)) { _, _ -> }.readBytes())
            val request = server.takeRequest()
            assertEquals("bytes=1024-", request.getHeader("Range"))
            assertEquals(etag, request.getHeader("If-Range"))
        }
    }

    @Test fun migrationDoesNotOverwriteExistingNewPartialOrDeleteOldHelpers() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val cache = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            interrupt(server, directory, source)
            val before = partial(directory).readBytes()
            val oldPart = File(cache, "task.part").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val oldSource = File(cache, "task.source").apply { writeText(source) }
            val oldValidator = File(cache, "task.validator").apply { writeText(etag) }
            ResumableTransfer(directory, client(server)).migrateLegacy("task", oldPart, oldSource, oldValidator)
            assertArrayEquals(before, partial(directory).readBytes())
            assertTrue(oldPart.exists())
            assertTrue(oldSource.exists())
            assertTrue(oldValidator.exists())
        }
    }

    @Test fun invalidLegacyValidatorIsLeftUntouched() {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val cache = temporary.newFolder()
            val oldPart = File(cache, "task.part").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val oldSource = File(cache, "task.source").apply { writeText(server.url("/book.epub").toString()) }
            val oldValidator = File(cache, "task.validator").apply { writeText("W/\"weak-version\"") }
            ResumableTransfer(directory, client(server)).migrateLegacy("task", oldPart, oldSource, oldValidator)
            assertEquals(0, directory.walkTopDown().count { it.extension == "part" })
            assertTrue(oldPart.exists())
            assertTrue(oldSource.exists())
            assertTrue(oldValidator.exists())
        }
    }

    @Test fun localWriteFailureKeepsStorageCategoryAndDoesNotTryAnotherSource() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val directory = temporary.newFolder()
            val source = server.url("/book.epub").toString()
            server.enqueue(response())
            val error = runCatching {
                ResumableTransfer(directory, client(server)).download("task", listOf(source, server.url("/backup.epub").toString())) { _, _ ->
                    val part = partial(directory)
                    assertTrue(part.delete())
                    assertTrue(part.mkdir())
                }
            }.exceptionOrNull()
            assertTrue(error is DownloadStorageException)
            assertEquals(1, server.requestCount)
        }
    }
}
