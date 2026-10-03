package app.zcomic.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile

/** ZIP-backed resource index: page image bytes stay on disk until requested. */
internal class EpubArchive(file: File) : Closeable {
    private val zip = ZipFile(file)
    val title: String
    val pages: List<String>

    init {
        try {
            val container = Jsoup.parse(text("META-INF/container.xml"), "", Parser.xmlParser())
            val packagePath = container.select("rootfile").firstOrNull()?.attr("full-path")
                ?.let { resolveEpubPath("", it) } ?: error("EPUB 缺少内容目录")
            val document = Jsoup.parse(text(packagePath), "", Parser.xmlParser())
            title = (document.getElementsByTag("dc:title").firstOrNull()
                ?: document.getElementsByTag("title").firstOrNull())?.text()
                ?.takeIf { it.isNotBlank() } ?: "未命名漫画"
            val manifest = document.select("manifest > item").associateBy { it.attr("id") }
            pages = document.select("spine > itemref").flatMap { reference ->
                val resource = manifest[reference.attr("idref")] ?: return@flatMap emptyList()
                val path = resolveEpubPath(packagePath, resource.attr("href"))
                    ?: return@flatMap emptyList()
                if (zip.getEntry(path) == null) return@flatMap emptyList()
                if (resource.attr("media-type").startsWith("image/") && !path.endsWith(".svg", true)) {
                    listOf(path)
                } else {
                    Jsoup.parse(text(path), "", Parser.xmlParser()).getAllElements()
                        .filter { it.normalName().substringAfter(':') in listOf("img", "image") }
                        .mapNotNull { image ->
                            val source = image.attr("src").ifBlank { image.attr("href") }
                                .ifBlank { image.attr("xlink:href") }
                            resolveEpubPath(path, source)?.takeIf { zip.getEntry(it) != null }
                        }
                }
            }
            require(pages.isNotEmpty()) { "该 EPUB 没有可读取的漫画图页" }
        } catch (error: Exception) {
            zip.close()
            throw error
        }
    }

    fun stream(path: String): InputStream = zip.getInputStream(
        zip.getEntry(path) ?: error("EPUB 图片资源缺失")
    )

    private fun text(path: String): String = stream(path).use { input ->
        val bytes = input.readNBytes(MAX_TEXT_BYTES + 1)
        require(bytes.size <= MAX_TEXT_BYTES) { "EPUB 内容目录过大" }
        bytes.toString(Charsets.UTF_8)
    }

    override fun close() = zip.close()

    private companion object { const val MAX_TEXT_BYTES = 4 * 1024 * 1024 }
}

internal fun resolveEpubPath(base: String, reference: String): String? {
    if (reference.isBlank()) return null
    return try {
        val target = URI(reference.replace(" ", "%20"))
        if (target.isAbsolute || target.rawAuthority != null) return null
        val path = URI(base.replace(" ", "%20")).resolve(target).normalize().path
            ?.removePrefix("/") ?: return null
        path.takeIf { it.isNotBlank() && it != ".." && !it.startsWith("../") }
    } catch (_: Exception) { null }
}
