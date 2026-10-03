package app.zcomic.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun resourcePathsHandleSpacesAndRejectExternalOrEscapingReferences() {
        assertEquals("OPS/images/page one.jpg", resolveEpubPath("OPS/text/1.xhtml", "../images/page one.jpg#page"))
        assertEquals("OPS/images/page one.jpg", resolveEpubPath("OPS/text/1.xhtml", "../images/page%20one.jpg"))
        assertNull(resolveEpubPath("OPS/text/1.xhtml", "https://example.test/image.jpg"))
        assertNull(resolveEpubPath("OPS/text/1.xhtml", "//example.test/image.jpg"))
        assertNull(resolveEpubPath("OPS/text/1.xhtml", "../../../image.jpg"))
        assertNull(resolveEpubPath("OPS/text/1.xhtml", "broken%path"))
    }

    @Test fun spineOrderIsUsedAndBadImageReferencesDoNotHideGoodPages() {
        val file = epub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to """<package><metadata><dc:title>测试漫画 第02卷</dc:title></metadata>
                <manifest><item id="a" href="text/1.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="text/2.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="b"/><itemref idref="a"/></spine></package>""",
            "OPS/text/1.xhtml" to "<img src='../images/page one.jpg'/><img src='https://example.test/no.jpg'/><img src='bad%path'/>",
            "OPS/text/2.xhtml" to "<svg><image xlink:href='../images/two.jpg'/></svg>",
            "OPS/images/page one.jpg" to "first image",
            "OPS/images/two.jpg" to "second image"
        ))
        EpubArchive(file).use { archive ->
            assertEquals("测试漫画 第02卷", archive.title)
            assertEquals(listOf("OPS/images/two.jpg", "OPS/images/page one.jpg"), archive.pages)
            assertEquals("second image", archive.stream(archive.pages.first()).use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test fun archiveWithoutPagesFailsRatherThanPublishingUnreadableBook() {
        val file = epub(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to "<package><manifest/><spine/></package>"
        ))
        assertThrows(IllegalArgumentException::class.java) { EpubArchive(file).close() }
    }

    private fun epub(entries: Map<String, String>): File = folder.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (path, text) ->
                output.putNextEntry(ZipEntry(path))
                output.write(text.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
    }
}
