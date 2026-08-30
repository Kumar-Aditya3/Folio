package com.folio.reader.manga

/**
 * MangaBackend operations for the built-in local source, shared by both platforms.
 * The local source id is [LOCAL_SOURCE_ID]; manga urls are series folder names and
 * chapter urls are "<series>/<relative path>".
 */
class LocalMangaBackendAdapter(val local: LocalMangaSource) {

    val sourceInfo = MangaSourceInfo(
        id = LOCAL_SOURCE_ID,
        name = "Local manga",
        lang = "other",
        isLocal = true,
        supportsLatest = false,
        hasFilters = false,
    )

    fun browse(page: Int, query: String): MangaBrowsePage {
        val series = local.listSeries()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        return MangaBrowsePage(
            items = series.map { MangaBrowseItem(url = it.name, title = it.name) },
            hasNextPage = false,
        )
    }

    fun detail(mangaUrl: String): MangaDetail =
        MangaDetail(url = mangaUrl, title = mangaUrl)

    fun chapters(mangaUrl: String): List<MangaChapterRef> =
        local.chaptersFor(mangaUrl).map {
            MangaChapterRef(url = "$mangaUrl/${it.relativePath}", name = it.name)
        }

    suspend fun pageList(chapterUrl: String): List<MangaPageRef> {
        val (series, relativePath) = splitChapterUrl(chapterUrl)
        return local.pageEntryNames(series, relativePath).mapIndexed { index, entry ->
            MangaPageRef(index = index, url = entry)
        }
    }

    suspend fun pageImage(chapterUrl: String, page: MangaPageRef): MangaImageData {
        val (series, relativePath) = splitChapterUrl(chapterUrl)
        val bytes = local.readPage(series, relativePath, page.url)
        return MangaImageData(bytes, mimeTypeFor(page.url))
    }

    suspend fun cover(seriesName: String): ByteArray? = local.coverBytes(seriesName)

    private fun splitChapterUrl(chapterUrl: String): Pair<String, String> =
        chapterUrl.substringBefore("/") to chapterUrl.substringAfter("/", "")

    private fun mimeTypeFor(name: String): String = when {
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".webp", ignoreCase = true) -> "image/webp"
        name.endsWith(".gif", ignoreCase = true) -> "image/gif"
        name.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
        name.endsWith(".avif", ignoreCase = true) -> "image/avif"
        else -> "image/jpeg"
    }
}
