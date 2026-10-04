package app.zcomic.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** Bounded, disk-backed MOBI7 reader. Dual-format files use their MOBI7 section. */
internal class MobiArchive(file: File) : Closeable {
    sealed interface Part {
        data class Image(val record: Int) : Part
        data class Text(val value: String) : Part
    }
    private val input = RandomAccessFile(file, "r")
    private val offsets: List<Long>
    val title: String
    val parts: List<Part>

    init {
        try {
            require(input.length() >= 78) { "MOBI 文件头损坏" }
            input.seek(60)
            require(String(ByteArray(8).also(input::readFully), Charsets.US_ASCII) == "BOOKMOBI") { "不是 MOBI 文件" }
            input.seek(76)
            val count = input.readUnsignedShort()
            require(count > 1 && 78L + count * 8L <= input.length()) { "MOBI 记录目录损坏" }
            offsets = List(count) {
                val offset = input.readInt().toLong() and 0xffffffffL
                input.skipBytes(4)
                offset
            } + input.length()
            require(offsets.first() >= 78L + count * 8L && offsets.zipWithNext().all { (a, b) -> a <= b }) { "MOBI 记录偏移无效" }
            val header = record(0)
            require(header.size >= 132 && header.copyOfRange(16, 20).toString(Charsets.US_ASCII) == "MOBI") { "MOBI 内容头损坏" }
            val headerSize = number(header, 20)
            require(headerSize in 116..(header.size - 16)) { "MOBI 内容头长度无效" }
            require(short(header, 12) == 0 && (headerSize < 160 || number(header, 172) == 0L)) { "不支持受 DRM 保护的 MOBI" }
            require(number(header, 104) < 8) { "此文件为 KF8 / AZW3，请转换为 EPUB 后导入" }
            val encoding = when (number(header, 28)) {
                65001L -> Charsets.UTF_8
                1252L -> Charset.forName("windows-1252")
                else -> error("不支持此 MOBI 的文字编码")
            }
            val nameOffset = number(header, 84).toInt()
            val nameLength = number(header, 88).toInt()
            title = if (nameLength > 0 && nameOffset >= 0 && nameOffset.toLong() + nameLength <= header.size)
                header.copyOfRange(nameOffset, nameOffset + nameLength).toString(encoding).trim().ifBlank { file.nameWithoutExtension }
                else file.nameWithoutExtension
            val textLength = number(header, 4)
            require(textLength in 1..MAX_TEXT.toLong()) { "MOBI 文字内容为空或过大" }
            val textCount = short(header, 8)
            require(textCount in 1 until count) { "MOBI 文字记录数无效" }
            val compression = short(header, 0)
            val huffman = if (compression == 17480) {
                val start = number(header, 112).toInt()
                val amount = number(header, 116).toInt()
                require(start > 0 && amount in 2..256 && start.toLong() + amount <= count) { "MOBI 压缩字典目录损坏" }
                require(offsets[start + amount] - offsets[start] <= MAX_TEXT) { "MOBI 压缩字典过大" }
                MobiHuffman((start until start + amount).map(::record))
            } else null
            val flags = if (headerSize >= 228) short(header, 242) else 0
            val text = java.io.ByteArrayOutputStream()
            for (index in 1..textCount) {
                val compressed = stripTail(record(index), flags)
                val remaining = MAX_TEXT - text.size()
                val decoded = when (compression) {
                    1 -> compressed
                    2 -> palmDoc(compressed, remaining.coerceAtMost(65536))
                    17480 -> requireNotNull(huffman).decode(compressed, remaining.coerceAtMost(65536))
                    else -> error("不支持此 MOBI 的压缩方式")
                }
                require(decoded.size <= remaining) { "MOBI 解压内容过大" }
                text.write(decoded)
            }
            require(text.size().toLong() >= textLength) { "MOBI 文字内容不完整" }
            val html = text.toByteArray().copyOf(textLength.toInt()).toString(encoding)
            val firstImage = number(header, 108)
            var coverRecord: Int? = null
            if (number(header, 128) and 64 != 0L) {
                val exth = 16 + headerSize.toInt()
                require(exth.toLong() + 12 <= header.size && header.copyOfRange(exth, exth + 4).toString(Charsets.US_ASCII) == "EXTH") { "MOBI 元数据损坏" }
                val end = exth.toLong() + number(header, exth + 4)
                require(end <= header.size && end >= exth + 12) { "MOBI 元数据长度无效" }
                val entries = number(header, exth + 8)
                require(entries <= 65536) { "MOBI 元数据项过多" }
                var position = exth + 12
                repeat(entries.toInt()) {
                    val type = number(header, position)
                    val length = number(header, position + 4)
                    require(length >= 8 && position + length <= end) { "MOBI 元数据项越界" }
                    if (type == 201L && length >= 12) {
                        val target = firstImage + number(header, position + 8)
                        if (target in 1 until count.toLong()) coverRecord = target.toInt()
                    }
                    position += length.toInt()
                }
            }
            val document = Jsoup.parse(html)
            document.select("script,style,head").remove()
            val ordered = mutableListOf<Part>()
            val words = StringBuilder()
            fun flush() {
                val value = words.toString().trim()
                if (value.isNotEmpty()) ordered += Part.Text(value)
                words.setLength(0)
            }
            fun walk(node: Node, depth: Int = 0) {
                require(depth <= 128) { "MOBI 正文嵌套过深" }
                when (node) {
                    is TextNode -> words.append(node.text())
                    is Element -> {
                        if (node.normalName() == "img") {
                            flush()
                            val relative = node.attr("recindex").toIntOrNull()
                            require(relative != null && relative > 0 && firstImage < count) { "MOBI 图片引用无效" }
                            val target = firstImage + relative - 1
                            require(target in 1 until count.toLong()) { "MOBI 图片资源缺失" }
                            ordered += Part.Image(target.toInt())
                        } else {
                            if (node.normalName() == "br" || node.isBlock) words.append('\n')
                            node.childNodes().forEach { walk(it, depth + 1) }
                            if (node.isBlock) words.append('\n')
                        }
                    }
                }
            }
            walk(document.body()); flush()
            coverRecord?.let { record ->
                if (ordered.none { it is Part.Image && it.record == record }) ordered.add(0, Part.Image(record))
            }
            require(ordered.isNotEmpty()) { "MOBI 没有可读取的内容" }
            parts = ordered
        } catch (error: Throwable) { input.close(); throw error }
    }

    @Synchronized fun record(index: Int): ByteArray {
        require(index >= 0 && index < offsets.lastIndex) { "MOBI 记录引用无效" }
        val length = offsets[index + 1] - offsets[index]
        require(length in 0..MAX_RECORD.toLong()) { "MOBI 单条记录过大" }
        input.seek(offsets[index])
        return ByteArray(length.toInt()).also(input::readFully)
    }
    override fun close() = input.close()

    companion object {
        private const val MAX_RECORD = 16 * 1024 * 1024
        private const val MAX_TEXT = 16 * 1024 * 1024
        fun short(bytes: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset.toLong() + 2 <= bytes.size) { "MOBI 字段被截断" }
            return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        }
        fun number(bytes: ByteArray, offset: Int): Long =
            (short(bytes, offset).toLong() shl 16) or short(bytes, offset + 2).toLong()

        internal fun stripTail(bytes: ByteArray, flags: Int): ByteArray {
            var end = bytes.size
            // Trailing entries are stripped backwards, highest bit first.
            for (bit in 15 downTo 1) if (flags and (1 shl bit) != 0) {
                var size = 0L
                var shift = 0
                var cursor = end
                do {
                    require(cursor > 0 && shift < 28) { "MOBI 尾部数据损坏" }
                    val value = bytes[--cursor].toInt() and 255
                    size = size or ((value and 127).toLong() shl shift)
                    shift += 7
                } while (value and 128 == 0)
                require(size >= end - cursor && size <= end) { "MOBI 尾部长度无效" }
                end -= size.toInt()
            }
            if (flags and 1 != 0) {
                require(end > 0) { "MOBI 尾部数据缺失" }
                end -= (bytes[end - 1].toInt() and 3) + 1
            }
            require(end >= 0) { "MOBI 尾部越界" }
            return bytes.copyOf(end)
        }

        internal fun palmDoc(bytes: ByteArray, limit: Int): ByteArray {
            val output = ByteArray(limit)
            var size = 0
            var index = 0
            fun put(value: Byte) { require(size < limit) { "MOBI 解压记录过大" }; output[size++] = value }
            while (index < bytes.size) {
                val value = bytes[index++].toInt() and 255
                when {
                    value in 1..8 -> {
                        require(index + value <= bytes.size) { "MOBI 压缩文本被截断" }
                        repeat(value) { put(bytes[index++]) }
                    }
                    value < 128 -> put(value.toByte())
                    value >= 192 -> { put(32); put((value xor 128).toByte()) }
                    else -> {
                        require(index < bytes.size) { "MOBI 压缩引用被截断" }
                        val pair = (value shl 8) or (bytes[index++].toInt() and 255)
                        val distance = (pair ushr 3) and 2047
                        require(distance in 1..size) { "MOBI 压缩引用无效" }
                        repeat((pair and 7) + 3) { put(output[size - distance]) }
                    }
                }
            }
            return output.copyOf(size)
        }
    }
}
