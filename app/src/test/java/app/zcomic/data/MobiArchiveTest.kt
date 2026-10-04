package app.zcomic.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class MobiArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun parsesARealPublicDomainMobiBookWithoutDroppingItsBody() {
        val file = folder.newFile().also { target ->
            requireNotNull(javaClass.getResourceAsStream("/books/frankenstein-mobi7.mobi")).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        MobiArchive(file).use { archive ->
            assertTrue(archive.title.contains("Frankenstein", ignoreCase = true))
            val text = archive.parts.filterIsInstance<MobiArchive.Part.Text>().joinToString("\n") { it.value }
            assertTrue(text.contains("Letter 1"))
            assertTrue(text.contains("Chapter 24"))
            assertTrue(text.length > 300_000)
            assertTrue(archive.parts.any { it is MobiArchive.Part.Image })
        }
    }

    @Test fun parsesUtf8TextAndImageReferencesInReadingOrder() {
        val html = "<html><body><p>第一段</p><img recindex='2'/><p>第二段</p><img recindex='1'/></body></html>".toByteArray()
        val file = mobi(html, images = listOf(byteArrayOf(1), byteArrayOf(2)))
        MobiArchive(file).use {
            assertEquals("测试漫画", it.title)
            assertEquals(listOf(MobiArchive.Part.Text("第一段"), MobiArchive.Part.Image(3),
                MobiArchive.Part.Text("第二段"), MobiArchive.Part.Image(2)), it.parts)
            assertArrayEquals(byteArrayOf(2), it.record(3))
        }
        assertEquals(BookFormat.MOBI, BookReader.detect(file))
    }

    @Test fun palmDocSupportsOverlappingBackreferencesAndSpacePairs() {
        // abc + (distance=3,length=6) + space/A -> abcabcabc A
        val compressed = byteArrayOf(97, 98, 99, 0x80.toByte(), 0x1b, 0xc1.toByte())
        assertEquals("abcabcabc A", MobiArchive.palmDoc(compressed, 100).toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0, 0x80.toByte()), MobiArchive.palmDoc(byteArrayOf(2, 0, 0x80.toByte()), 10))
        assertThrows(IllegalArgumentException::class.java) { MobiArchive.palmDoc(byteArrayOf(0x80.toByte(), 0), 10) }
        assertThrows(IllegalArgumentException::class.java) { MobiArchive.palmDoc(byteArrayOf(97, 0xc1.toByte()), 1) }
        val html = "<p>压缩文字</p>".toByteArray()
        val literal = html.toList().chunked(8).flatMap { listOf(it.size.toByte()) + it }.toByteArray()
        MobiArchive(mobi(literal, compression = 2, textLength = html.size)).use {
            assertEquals(listOf(MobiArchive.Part.Text("压缩文字")), it.parts)
        }
    }

    @Test fun rejectsDrmKf8AndTruncatedRecordsInsteadOfPublishingUnreadableBooks() {
        assertThrows(IllegalArgumentException::class.java) { MobiArchive(mobi("test".toByteArray(), drm = 2)) }
        assertThrows(IllegalArgumentException::class.java) { MobiArchive(mobi("test".toByteArray(), version = 8)) }
        val file = mobi("<img recindex='9'/>".toByteArray(), images = listOf(byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { MobiArchive(file) }
        val broken = mobi("text".toByteArray()).also { it.writeBytes(it.readBytes().copyOf(80)) }
        assertThrows(IllegalArgumentException::class.java) { MobiArchive(broken) }
        assertThrows(IllegalStateException::class.java) { BookReader.detect(folder.newFile().also { it.writeText("fake.pdf") }) }
    }

    @Test fun removesVariableLengthAndMultibyteTrailers() {
        assertEquals("abc", MobiArchive.stripTail(byteArrayOf(97,98,99,0,0x82.toByte()), 2).toString(Charsets.UTF_8))
        assertEquals("abc", MobiArchive.stripTail(byteArrayOf(97,98,99,0,1), 1).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { MobiArchive.stripTail(byteArrayOf(0xff.toByte()), 2) }
    }

    @Test fun deeplyNestedHtmlFailsWithAnOrdinaryReadableError() {
        val html = ("<div>".repeat(500) + "text" + "</div>".repeat(500)).toByteArray()
        val error = assertThrows(IllegalArgumentException::class.java) { MobiArchive(mobi(html)) }
        assertTrue(error.message!!.contains("嵌套过深"))
    }

    @Test fun huffCdicExpandsLiteralAndRecursivePhrasesAndRejectsCycles() {
        val huff = ByteBuffer.allocate(1304).apply {
            put("HUFF".toByteArray()); putInt(24); putInt(24); putInt(1048)
            for (index in 0..255) putInt(24 + index * 4, (1 shl 8) or 128 or 1)
        }.array()
        fun cdic(recursive: Boolean) = ByteBuffer.allocate(29).apply {
            put("CDIC".toByteArray()); putInt(16); putInt(2); putInt(1)
            putShort(4); putShort(7)
            putShort(if (recursive) 1 else 0x8001.toShort()); put(if (recursive) 0 else 65)
            putShort(0x8001.toShort()); put(66)
        }.array()
        assertEquals("AAAAAAAA", MobiHuffman(listOf(huff, cdic(false))).decode(byteArrayOf(0xff.toByte()), 10).toString(Charsets.UTF_8))
        MobiArchive(mobi(byteArrayOf(0xff.toByte()), compression = 17480, textLength = 8,
            dictionary = listOf(huff, cdic(false)))).use {
            assertEquals(listOf(MobiArchive.Part.Text("AAAAAAAA")), it.parts)
        }
        assertEquals("B".repeat(15), MobiHuffman(listOf(huff, cdic(true))).decode(byteArrayOf(0x80.toByte()), 16).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { MobiHuffman(listOf(huff, cdic(false))).decode(byteArrayOf(0xff.toByte()), 1) }
        val cycle = cdic(true).also { it[22] = 0xff.toByte() }
        assertThrows(IllegalArgumentException::class.java) { MobiHuffman(listOf(huff, cycle)).decode(byteArrayOf(0xff.toByte()), 100) }
    }

    private fun mobi(text: ByteArray, compression: Int = 1, textLength: Int = text.size,
        drm: Int = 0, version: Int = 6, images: List<ByteArray> = emptyList(), dictionary: List<ByteArray> = emptyList()): File {
        val title = "测试漫画".toByteArray()
        val header = ByteBuffer.allocate(264 + title.size).apply {
            putShort(compression.toShort()); putShort(0); putInt(textLength); putShort(1); putShort(4096)
            putShort(drm.toShort()); putShort(0); put("MOBI".toByteArray()); putInt(248)
            putInt(28, 65001); putInt(84, 264); putInt(88, title.size); putInt(104, version)
            putInt(108, if (images.isEmpty()) -1 else 2 + dictionary.size)
            if (dictionary.isNotEmpty()) { putInt(112, 2); putInt(116, dictionary.size) }
            position(264); put(title)
        }.array()
        val records = listOf(header, text) + dictionary + images
        val tableSize = 78 + records.size * 8
        val output = ByteBuffer.allocate(tableSize + records.sumOf { it.size })
        output.position(60); output.put("BOOKMOBI".toByteArray()); output.position(76); output.putShort(records.size.toShort())
        var offset = tableSize
        records.forEach { output.putInt(offset); output.putInt(0); offset += it.size }
        records.forEach(output::put)
        return folder.newFile().also { it.writeBytes(output.array()) }
    }
}
