package app.zcomic.data

/** Decode MOBI's HUFF/CDIC dictionary without allowing cycles or unbounded expansion. */
internal class MobiHuffman(records: List<ByteArray>) {
    private data class Phrase(val bytes: ByteArray, val literal: Boolean)
    private val lookup: LongArray
    private val minimum: LongArray
    private val maximum: LongArray
    private val phrases = mutableListOf<Phrase>()

    init {
        require(records.sumOf { it.size.toLong() } <= 16 * 1024 * 1024) { "MOBI 压缩字典过大" }
        val huff = records.first()
        require(huff.size >= 24 && huff.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "HUFF") { "MOBI HUFF 表损坏" }
        val first = MobiArchive.number(huff, 8).toInt()
        val second = MobiArchive.number(huff, 12).toInt()
        require(first >= 0 && first.toLong() + 1024 <= huff.size && second >= 0 && second.toLong() + 256 <= huff.size) { "MOBI HUFF 表越界" }
        lookup = LongArray(256) { MobiArchive.number(huff, first + it * 4) }
        minimum = LongArray(33)
        maximum = LongArray(33)
        for (length in 1..32) {
            val shift = 32 - length
            minimum[length] = MobiArchive.number(huff, second + (length - 1) * 8) shl shift
            maximum[length] = ((MobiArchive.number(huff, second + (length - 1) * 8 + 4) + 1) shl shift) - 1
        }
        var total = -1
        var phraseBytes = 0L
        for (cdic in records.drop(1)) {
            require(cdic.size >= 16 && cdic.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "CDIC") { "MOBI CDIC 表损坏" }
            val declared = MobiArchive.number(cdic, 8).toInt()
            val bits = MobiArchive.number(cdic, 12).toInt()
            require(declared in 1..65536 && bits in 1..16 && (total == -1 || total == declared)) { "MOBI 字典数量无效" }
            total = declared
            val count = minOf(1 shl bits, total - phrases.size)
            require(count >= 0 && 16L + count * 2L <= cdic.size) { "MOBI 字典偏移表无效" }
            repeat(count) { index ->
                val offset = 16 + MobiArchive.short(cdic, 16 + index * 2)
                val size = MobiArchive.short(cdic, offset)
                val length = size and 32767
                require(offset.toLong() + 2 + length <= cdic.size) { "MOBI 字典短语被截断" }
                phraseBytes += length
                require(phraseBytes <= 16 * 1024 * 1024) { "MOBI 字典短语总量过大" }
                phrases += Phrase(cdic.copyOfRange(offset + 2, offset + 2 + length), size and 32768 != 0)
            }
        }
        require(phrases.size == total) { "MOBI 压缩字典不完整" }
    }

    fun decode(bytes: ByteArray, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val active = mutableSetOf<Int>()
        var operations = 0
        fun expand(data: ByteArray, depth: Int) {
            require(depth <= 32) { "MOBI 压缩字典递归过深" }
            var bit = 0
            while (bit < data.size * 8) {
                require(++operations <= 1_000_000) { "MOBI 压缩字典展开过于复杂" }
                var window = 0L
                repeat(32) { delta ->
                    val position = bit + delta
                    val value = if (position < data.size * 8) (data[position / 8].toInt() ushr (7 - position % 8)) and 1 else 0
                    window = (window shl 1) or value.toLong()
                }
                val entry = lookup[(window ushr 24).toInt()]
                var length = (entry and 31).toInt()
                require(length in 1..32) { "MOBI HUFF 码长无效" }
                var upper = (((entry ushr 8) + 1) shl (32 - length)) - 1
                if (entry and 128 == 0L) {
                    while (length <= 32 && window < minimum[length]) length++
                    require(length <= 32) { "MOBI HUFF 码无效" }
                    upper = maximum[length]
                }
                if (bit + length > data.size * 8) break
                require(window <= upper) { "MOBI HUFF 引用无效" }
                val symbol = ((upper - window) ushr (32 - length)).toInt()
                require(symbol in phrases.indices) { "MOBI 字典引用越界" }
                val phrase = phrases[symbol]
                if (phrase.literal) {
                    require(output.size().toLong() + phrase.bytes.size <= limit) { "MOBI 解压记录过大" }
                    output.write(phrase.bytes)
                } else {
                    require(active.add(symbol)) { "MOBI 字典存在循环引用" }
                    expand(phrase.bytes, depth + 1)
                    active.remove(symbol)
                }
                bit += length
            }
        }
        expand(bytes, 0)
        return output.toByteArray()
    }
}
