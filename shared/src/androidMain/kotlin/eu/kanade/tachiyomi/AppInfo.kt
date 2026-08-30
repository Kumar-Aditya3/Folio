package eu.kanade.tachiyomi

/**
 * Host-application info consumed by Mihon extensions (vendored FQCN contract).
 */
@Suppress("UNUSED")
object AppInfo {
    fun getVersionCode(): Int = 12

    fun getVersionName(): String = "1.0.12"

    fun getSupportedImageMimeTypes(): List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/bmp",
        "image/avif",
    )
}
