package app.zcomic.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComicFilesFormatTest {
    private lateinit var context: Context
    private lateinit var media: TestMediaProvider
    private lateinit var files: ComicFiles
    private lateinit var source: File
    private val content = byteArrayOf(1, 2, 3, 4, 5)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        media = TestMediaProvider()
        media.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", media)
        files = ComicFiles(context)
        source = File.createTempFile("import-", ".bin", context.cacheDir).apply { writeBytes(content) }
    }

    @Test fun pdfAndMobiCopiesKeepTheirFormatAndPublishTheOriginalBytes() = runTest {
        for (format in listOf(BookFormat.PDF, BookFormat.MOBI)) {
            var published: Uri? = null
            val target = files.copy(Uri.fromFile(source), "漫画", "卷1", format) { published = it }
            assertNotNull(target)
            assertEquals(target, published)
            val values = media.rows.getValue(target!!)
            assertEquals("卷1.${format.extension}", values.getAsString(MediaStore.Files.FileColumns.DISPLAY_NAME))
            assertEquals(format.mimeType, values.getAsString(MediaStore.Files.FileColumns.MIME_TYPE))
            assertEquals(0, values.getAsInteger(MediaStore.Files.FileColumns.IS_PENDING))
            assertArrayEquals(content, media.storage.getValue(target).readBytes())
        }
    }

    @Test fun existingDownloadCallWithoutFormatStillSavesEpub() = runTest {
        val target = files.copy(Uri.fromFile(source), "漫画", "卷1")!!
        val values = media.rows.getValue(target)
        assertEquals("卷1.epub", values.getAsString(MediaStore.Files.FileColumns.DISPLAY_NAME))
        assertEquals("application/epub+zip", values.getAsString(MediaStore.Files.FileColumns.MIME_TYPE))
        assertArrayEquals(content, media.storage.getValue(target).readBytes())
    }

    @Test fun outputFailureRemovesTheInsertedPendingDocument() = runTest {
        media.failOutput = true
        try {
            files.copy(Uri.fromFile(source), "漫画", "卷1", BookFormat.PDF)
            fail("Expected output failure")
        } catch (_: IOException) { }
        assertEquals(1, media.deleted.size)
        assertTrue(media.rows.isEmpty())
        assertTrue(media.storage.isEmpty())
    }

    @Test fun cancellationAfterCreatingOutputRemovesThePendingDocument() = runTest {
        val copying = launch {
            val job = currentCoroutineContext()[Job]!!
            media.onOpenOutput = { job.cancel(CancellationException("test interruption")) }
            files.copy(Uri.fromFile(source), "漫画", "卷1", BookFormat.MOBI)
        }
        copying.join()
        assertTrue(copying.isCancelled)
        assertEquals(1, media.deleted.size)
        assertTrue(media.rows.isEmpty())
        assertTrue(media.storage.isEmpty())
    }

    /** A document-provider boundary backed by files; it does not implement the copy logic under test. */
    private class TestMediaProvider : ContentProvider() {
        val rows = linkedMapOf<Uri, ContentValues>()
        val storage = linkedMapOf<Uri, File>()
        val deleted = mutableListOf<Uri>()
        var failOutput = false
        var onOpenOutput: (() -> Unit)? = null
        private var nextId = 1

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = rows[uri]?.getAsString(MediaStore.Files.FileColumns.MIME_TYPE)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection ?: arrayOf(MediaStore.Files.FileColumns._ID))

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val target = Uri.withAppendedPath(uri, (nextId++).toString())
            rows[target] = ContentValues(requireNotNull(values))
            storage[target] = File.createTempFile("media-", ".bin", requireNotNull(context).cacheDir)
            return target
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[uri] ?: return 0
            row.putAll(requireNotNull(values))
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (rows.remove(uri) == null) return 0
            deleted += uri
            storage.remove(uri)?.delete()
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode != "r") {
                if (failOutput) throw IOException("test provider output failure")
                onOpenOutput?.invoke()
            }
            return ParcelFileDescriptor.open(storage.getValue(uri), if (mode == "r")
                ParcelFileDescriptor.MODE_READ_ONLY else
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
}
