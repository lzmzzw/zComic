package app.zcomic

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PatternMatcher
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookOpenIntentTest {
    private fun canOpen(uri: String, mimeType: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mimeType)
            .addCategory(Intent.CATEGORY_DEFAULT)
        return RuntimeEnvironment.getApplication().packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == MainActivity::class.java.name }
    }

    // Robolectric's legacy manifest parser drops pathSuffix. Read the actual manifest and
    // use Android's IntentFilter matching so negative extension checks remain meaningful.
    private val manifestFilters: List<IntentFilter> by lazy {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile } ?: error("Cannot locate application manifest")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(manifest)
        val namespace = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity")
        val activity = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(namespace, "name") in listOf(".MainActivity", "app.zcomic.MainActivity") }
        val filters = activity.getElementsByTagName("intent-filter")
        (0 until filters.length).map { index ->
            IntentFilter().apply {
                val children = filters.item(index).childNodes
                for (childIndex in 0 until children.length) {
                    val child = children.item(childIndex) as? Element ?: continue
                    fun attr(name: String): String? = child.getAttributeNS(namespace, name).takeIf { it.isNotEmpty() }
                    when (child.tagName) {
                        "action" -> addAction(requireNotNull(attr("name")))
                        "category" -> addCategory(requireNotNull(attr("name")))
                        "data" -> {
                            attr("scheme")?.let(::addDataScheme)
                            attr("mimeType")?.let(::addDataType)
                            attr("host")?.let { addDataAuthority(it, attr("port")) }
                            attr("path")?.let { addDataPath(it, PatternMatcher.PATTERN_LITERAL) }
                            attr("pathPrefix")?.let { addDataPath(it, PatternMatcher.PATTERN_PREFIX) }
                            attr("pathPattern")?.let { addDataPath(it, PatternMatcher.PATTERN_SIMPLE_GLOB) }
                            attr("pathAdvancedPattern")?.let { addDataPath(it, PatternMatcher.PATTERN_ADVANCED_GLOB) }
                            attr("pathSuffix")?.let { addDataPath(it, PatternMatcher.PATTERN_SUFFIX) }
                        }
                    }
                }
            }
        }
    }

    private fun manifestCanOpen(uri: String, mimeType: String?): Boolean {
        val data = Uri.parse(uri)
        return manifestFilters.any {
            it.match(Intent.ACTION_VIEW, mimeType, data.scheme, data,
                setOf(Intent.CATEGORY_DEFAULT), "BookOpenIntentTest") >= 0
        }
    }

    @Test fun fileManagerCanOpenDeclaredBookMimeTypesWithOpaqueContentUris() {
        for (mime in listOf("application/epub+zip", "application/pdf",
            "application/x-mobipocket-ebook", "application/vnd.amazon.mobi")) {
            assertTrue(mime, canOpen("content://documents/document/12345", mime))
        }
    }

    @Test fun genericBinaryHandlerIsRestrictedToBookExtensions() {
        assertTrue(manifestCanOpen("content://documents/books/volume.1.mobi", "application/octet-stream"))
        assertTrue(manifestCanOpen("content://documents/books/volume.PDF", "application/octet-stream"))
        assertFalse(manifestCanOpen("content://documents/books/program.exe", "application/octet-stream"))
        assertFalse(manifestCanOpen("content://documents/document/12345", "application/octet-stream"))
    }

    @Test fun fileUriWithoutMimeTypeCanOpenBooksButNotUnrelatedFiles() {
        assertTrue(manifestCanOpen("file:///storage/emulated/0/book.mobi", null))
        assertFalse(manifestCanOpen("file:///storage/emulated/0/notes.txt", null))
    }
}
