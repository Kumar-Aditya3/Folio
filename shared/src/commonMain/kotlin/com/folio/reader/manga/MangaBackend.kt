package com.folio.reader.manga

import kotlinx.coroutines.flow.Flow

/**
 * Platform manga engine. Android's implementation runs the vendored Mihon runtime
 * (extension loading, catalogue sources, downloads); desktop's implementation provides
 * the built-in local source only.
 */
interface MangaBackend {

    val supportsExtensions: Boolean

    /** All currently usable sources, including the built-in local source. */
    fun observeSources(): Flow<List<MangaSourceInfo>>

    suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter>

    suspend fun fetchBrowse(
        sourceId: Long,
        page: Int,
        mode: BrowseMode,
        query: String = "",
        filters: List<MangaFilter>? = null,
    ): MangaBrowsePage

    suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail

    suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef>

    suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef>

    suspend fun fetchPageImage(
        sourceId: Long,
        chapter: MangaChapterRef,
        page: MangaPageRef,
    ): MangaImageData

    /** Download a manga cover (or null when the source has none). */
    suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray?

    /**
     * §11.4 deep link: the source's web page for this manga, or null for local
     * sources and sources without a web base.
     */
    suspend fun sourceWebUrl(sourceId: Long, mangaUrl: String): String? = null

    // ---- Extension management (no-ops / empty on platforms without extensions) ----

    fun observeExtensions(): Flow<List<ExtensionEntry>>

    suspend fun refreshExtensionIndex()

    suspend fun getRepos(): List<MangaRepoInfo>

    suspend fun setRepos(repos: List<MangaRepoInfo>)

    fun installExtension(pkgName: String): Flow<ExtensionInstallStep>

    fun updateExtension(pkgName: String): Flow<ExtensionInstallStep>

    fun uninstallExtension(pkgName: String)

    suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String)

    suspend fun getExtensionIcon(pkgName: String): ByteArray?

    /** Show/hide NSFW-flagged extensions and their sources. */
    suspend fun setShowNsfwSources(enabled: Boolean)

    suspend fun getShowNsfwSources(): Boolean

    /**
     * Source languages the user wants to browse, as lowercase ISO codes.
     *
     * One extension publishes one source *per language* — Webtoons alone ships a
     * dozen — so an unfiltered browse list repeats the same site until it reads as
     * duplicated rows. An empty set means "nothing recorded yet"; callers fall back
     * to their own default (see `BrowseViewModel.defaultSourceLanguages`).
     *
     * Defaulted here because only the extension-capable backend has anything to
     * store: the local source has no language.
     */
    suspend fun getSourceLanguages(): Set<String> = emptySet()

    suspend fun setSourceLanguages(languages: Set<String>) = Unit
}
