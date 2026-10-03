package app.zcomic.data

private val volumeSuffix = Regex(
    "[\\s　_-]*(?:第\\s*\\d+\\s*卷|卷\\s*\\d+|\\d+\\s*卷|Vol\\.?\\s*\\d+).*$",
    RegexOption.IGNORE_CASE
)

internal fun comicTitle(metadata: String, fallback: String): String = metadata
    .takeUnless { it == "未命名漫画" }.orEmpty().replace(volumeSuffix, "").trim()
    .ifBlank { fallback.ifBlank { "未分组" } }

internal fun volumeNumber(title: String): Int = Regex("\\d+").findAll(title)
    .lastOrNull()?.value?.toIntOrNull() ?: 0

internal fun downloadProgress(received: Long, total: Long): Float =
    if (total <= 0) 0f else (received.toDouble() / total).toFloat().coerceIn(0f, 1f)
