package app.zcomic.data

enum class BookFormat(val extension: String, val mimeType: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
    MOBI("mobi", "application/x-mobipocket-ebook");

    companion object {
        fun fromName(name: String?): BookFormat? = entries.firstOrNull {
            name?.substringAfterLast('.', "")?.equals(it.extension, ignoreCase = true) == true
        }

        fun fromMimeType(mimeType: String?): BookFormat? = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "application/epub+zip" -> EPUB
            "application/pdf" -> PDF
            "application/x-mobipocket-ebook", "application/vnd.amazon.mobi" -> MOBI
            else -> null
        }
    }
}
