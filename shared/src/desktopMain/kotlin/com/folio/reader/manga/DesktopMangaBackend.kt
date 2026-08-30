package com.folio.reader.manga

import com.folio.reader.platform.FolioFileSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop manga backend: the built-in local source only. Mihon extension APKs are Android
 * packages and cannot run on the desktop JVM, so extension management is a no-op here.
 */
class DesktopMangaBackend(fileSystem: FolioFileSystem) : MangaBackend {

    private val localAdapter = LocalMangaBackendAdapter(LocalMangaSource(fileSystem.mangaLocalDir))

    val localSource: LocalMangaSource
        get() = localAdapter.local

    override val supportsExtensions: Boolean = false

    override fun observeSources(): Flow<List<MangaSourceInfo>> =
        flowOf(listOf(localAdapter.sourceInfo))

    override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> = emptyList()

    override suspend fun fetchBrowse(
        sourceId: Long,
        page: Int,
        mode: BrowseMode,
        query: String,
        filters: List<MangaFilter>?,
    ): MangaBrowsePage {
        requireLocal(sourceId)
        return localAdapter.browse(page, query)
    }

    override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail {
        requireLocal(sourceId)
        return localAdapter.detail(mangaUrl)
    }

    override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> {
        requireLocal(sourceId)
        return localAdapter.chapters(mangaUrl)
    }

    override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> {
        requireLocal(sourceId)
        return localAdapter.pageList(chapter.url)
    }

    override suspend fun fetchPageImage(
        sourceId: Long,
        chapter: MangaChapterRef,
        page: MangaPageRef,
    ): MangaImageData {
        requireLocal(sourceId)
        return localAdapter.pageImage(chapter.url, page)
    }

    override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? {
        requireLocal(sourceId)
        return thumbnailUrl?.let { localAdapter.cover(it) }
    }

    override fun observeExtensions(): Flow<List<ExtensionEntry>> = flowOf(emptyList())

    override suspend fun refreshExtensionIndex() = Unit

    override suspend fun getRepos(): List<MangaRepoInfo> = emptyList()

    override suspend fun setRepos(repos: List<MangaRepoInfo>) = Unit

    override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> =
        flowOf(ExtensionInstallStep.Error)

    override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> =
        flowOf(ExtensionInstallStep.Error)

    override fun uninstallExtension(pkgName: String) = Unit

    override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) = Unit

    override suspend fun getExtensionIcon(pkgName: String): ByteArray? = null

    override suspend fun setShowNsfwSources(enabled: Boolean) = Unit

    override suspend fun getShowNsfwSources(): Boolean = false

    private fun requireLocal(sourceId: Long) {
        require(sourceId == LOCAL_SOURCE_ID) { "Only the local source is available on desktop" }
    }
}
