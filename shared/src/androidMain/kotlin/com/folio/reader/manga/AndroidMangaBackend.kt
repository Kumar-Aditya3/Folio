package com.folio.reader.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.folio.reader.database.SettingsRepository
import com.folio.reader.platform.FolioFileSystem
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.extension.interactor.TrustStore
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.ExtensionRepo
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionRuntime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * Android manga backend: runs the vendored Mihon runtime (extension loading, catalogue
 * sources, Cloudflare-capable network stack) alongside Folio's built-in local source.
 */
class AndroidMangaBackend(
    context: Context,
    private val settings: SettingsRepository,
    fileSystem: FolioFileSystem,
) : MangaBackend {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val localAdapter = LocalMangaBackendAdapter(LocalMangaSource(fileSystem.mangaLocalDir))

    val localSource: LocalMangaSource
        get() = localAdapter.local

    private val networkHelper = NetworkHelper(appContext)
    private val trustStore = FolioTrustStore(settings, scope)
    private val repos = MutableStateFlow(listOf(DEFAULT_REPO))
    private val showNsfw = MutableStateFlow(false)

    private val extensionApi = ExtensionApi(networkHelper.client) {
        repos.value.map { ExtensionRepo(it.name, it.baseUrl, it.indexUrl, it.signingKey) }
    }
    private val trustExtension = TrustExtension(trustStore)
    private val installer = ExtensionInstaller(appContext, networkHelper)
    val extensionManager = ExtensionManager(appContext, trustExtension, extensionApi, installer)

    private val installedSnapshot = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val untrustedSnapshot = MutableStateFlow<List<Extension.Untrusted>>(emptyList())

    /** Latest FilterList per source; extension filter instances must be reused in place. */
    private val filterCache = mutableMapOf<Long, FilterList>()

    init {
        FolioInjektSetup.register(appContext, networkHelper)
        ExtensionRuntime.trustExtension = trustExtension
        ExtensionRuntime.showNsfwSource = { showNsfw.value }

        scope.launch {
            trustStore.preload()
            val stored = loadRepos()
            if (stored.isNotEmpty()) {
                repos.value = stored
                runCatching {
                    settings.setRaw(
                        KEY_REPOS,
                        json.encodeToString(ListSerializer(MangaRepoInfo.serializer()), stored)
                    )
                }
            }
            showNsfw.value = settings.getRaw(KEY_NSFW) == "1"
        }
        scope.launch {
            extensionApi.repoSigningKeys.collect { keys ->
                trustStore.setRepoKeys(keys)
                if (keys.isNotEmpty()) {
                    untrustedSnapshot.value
                        .filter { it.signatureHash in keys }
                        .forEach { extensionManager.trust(it) }
                }
            }
        }
        scope.launch {
            extensionManager.installedExtensionsFlow.collect { installedSnapshot.value = it }
        }
        scope.launch {
            extensionManager.untrustedExtensionsFlow.collect { untrustedSnapshot.value = it }
        }
    }

    override val supportsExtensions: Boolean = true

    // ---------- Sources ----------

    override fun observeSources(): Flow<List<MangaSourceInfo>> =
        extensionManager.installedExtensionsFlow.map { extensions ->
            val remote = extensions
                .flatMap { ext -> ext.sources.map { it.toInfo(ext) } }
            listOf(localAdapter.sourceInfo) + remote
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    private suspend fun sourceById(sourceId: Long): Source? =
        extensionManager.getInstalledExtensions()
            .flatMap { it.sources }
            .firstOrNull { it.id == sourceId }

    private fun Source.toInfo(extension: Extension.Installed) = MangaSourceInfo(
        id = id,
        name = name,
        lang = lang,
        isLocal = false,
        extensionPkg = extension.pkgName,
        supportsLatest = (this as? CatalogueSource)?.supportsLatest ?: false,
        // Never call extension code (getFilterList) here: this map runs on the
        // collecting thread, and some extensions block inside getFilterList.
        // The browse screen derives filter availability from getFilterTemplate.
        hasFilters = false,
    )

    override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> {
        if (sourceId == LOCAL_SOURCE_ID) return emptyList()
        val source = sourceById(sourceId) ?: return emptyList()
        // Extensions are third-party code; a broken getFilterList must not crash the app.
        val filterList = runCatching { source.getFilterList() }.getOrNull() ?: return emptyList()
        filterCache[sourceId] = filterList
        return filterList.map { it.toCommonFilter() }
    }

    // ---------- Browse / fetch ----------

    override suspend fun fetchBrowse(
        sourceId: Long,
        page: Int,
        mode: BrowseMode,
        query: String,
        filters: List<MangaFilter>?,
    ): MangaBrowsePage {
        if (sourceId == LOCAL_SOURCE_ID) return localAdapter.browse(page, query)

        val source = sourceById(sourceId) as? CatalogueSource
            ?: throw IllegalStateException("Source $sourceId is not a catalogue source")

        val effectiveFilters = filters?.let { states ->
            filterCache[sourceId]?.also { it.applyStates(states) }
        }

        val result = when {
            query.isNotBlank() || effectiveFilters != null ->
                source.getSearchManga(page, query, effectiveFilters ?: FilterList())
            mode == BrowseMode.LATEST -> source.getLatestUpdates(page)
            else -> source.getPopularManga(page)
        }
        return MangaBrowsePage(
            items = result.mangas.map { MangaBrowseItem(it.url, it.title, it.thumbnail_url) },
            hasNextPage = result.hasNextPage,
        )
    }

    override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail {
        if (sourceId == LOCAL_SOURCE_ID) return localAdapter.detail(mangaUrl)

        val source = sourceById(sourceId) ?: throw IllegalStateException("Unknown source $sourceId")
        val stub = SManga.create().apply { url = mangaUrl }
        val update = source.getMangaUpdate(stub, emptyList(), fetchDetails = true, fetchChapters = false)
        val manga = update.manga
        return MangaDetail(
            url = mangaUrl,
            title = runCatching { manga.title }.getOrDefault(""),
            author = manga.author,
            artist = manga.artist,
            description = manga.description,
            genres = manga.genre?.split(", ")?.filter { it.isNotBlank() } ?: emptyList(),
            status = MangaStatus.fromValue(manga.status),
            thumbnailUrl = manga.thumbnail_url,
        )
    }

    override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> {
        if (sourceId == LOCAL_SOURCE_ID) return localAdapter.chapters(mangaUrl)

        val source = sourceById(sourceId) ?: throw IllegalStateException("Unknown source $sourceId")
        val stub = SManga.create().apply { url = mangaUrl }
        val update = source.getMangaUpdate(stub, emptyList(), fetchDetails = false, fetchChapters = true)
        return update.chapters.mapIndexed { index, it ->
            MangaChapterRef(
                url = runCatching { it.url }.getOrDefault(mangaUrl),
                name = runCatching { it.name }.getOrDefault("Chapter ${index + 1}"),
                scanlator = it.scanlator,
                chapterNumber = it.chapter_number,
                dateUpload = it.date_upload,
            )
        }
    }

    override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> {
        if (sourceId == LOCAL_SOURCE_ID) return localAdapter.pageList(chapter.url)

        val source = sourceById(sourceId) ?: throw IllegalStateException("Unknown source $sourceId")
        val sChapter = SChapter.create().apply { url = chapter.url }
        return source.getPageList(sChapter).map { MangaPageRef(it.index, it.url, it.imageUrl) }
    }

    override suspend fun fetchPageImage(
        sourceId: Long,
        chapter: MangaChapterRef,
        page: MangaPageRef,
    ): MangaImageData {
        if (sourceId == LOCAL_SOURCE_ID) return localAdapter.pageImage(chapter.url, page)

        val source = sourceById(sourceId) as? HttpSource
            ?: throw IllegalStateException("Source $sourceId cannot fetch images")
        val sPage = Page(page.index, page.url, page.imageUrl)
        if (sPage.imageUrl == null) {
            sPage.imageUrl = source.getImageUrl(sPage)
        }
        source.getImage(sPage).use { response ->
            return MangaImageData(response.body.bytes(), response.header("Content-Type"))
        }
    }

    override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? {
        if (sourceId == LOCAL_SOURCE_ID) return thumbnailUrl?.let { localAdapter.cover(it) }
        if (thumbnailUrl.isNullOrBlank()) return null

        val source = sourceById(sourceId) as? HttpSource ?: return null
        val request = GET(thumbnailUrl, source.headers)
        source.client.newCall(request).awaitSuccess().use { response ->
            return response.body.bytes()
        }
    }

    override suspend fun sourceWebUrl(sourceId: Long, mangaUrl: String): String? {
        if (sourceId == LOCAL_SOURCE_ID) return null
        val source = sourceById(sourceId) as? HttpSource ?: return null
        return runCatching { source.baseUrl + mangaUrl }.getOrNull()
    }

    // ---------- Extensions ----------

    override fun observeExtensions(): Flow<List<ExtensionEntry>> = combine(
        extensionManager.installedExtensionsFlow,
        extensionManager.availableExtensionsFlow,
        extensionManager.untrustedExtensionsFlow,
    ) { installed, available, untrusted ->
        val entries = mutableListOf<ExtensionEntry>()

        installed.forEach { ext ->
            entries += ExtensionEntry(
                pkgName = ext.pkgName,
                name = ext.name,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                libVersion = ext.libVersion,
                lang = ext.lang,
                isNsfw = ext.isNsfw,
                isInstalled = true,
                hasUpdate = ext.hasUpdate,
                isObsolete = ext.isObsolete,
                sourceNames = ext.sources.map { it.name },
            )
        }

        untrusted.forEach { ext ->
            entries += ExtensionEntry(
                pkgName = ext.pkgName,
                name = ext.name,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                libVersion = ext.libVersion,
                lang = ext.lang,
                isNsfw = ext.isNsfw,
                isUntrusted = true,
                signatureHash = ext.signatureHash,
            )
        }

        available
            .filter { ext ->
                installed.none { it.pkgName == ext.pkgName } &&
                    untrusted.none { it.pkgName == ext.pkgName }
            }
            .forEach { ext ->
                entries += ExtensionEntry(
                    pkgName = ext.pkgName,
                    name = ext.name,
                    versionName = ext.versionName,
                    versionCode = ext.versionCode,
                    libVersion = ext.libVersion,
                    lang = ext.lang,
                    isNsfw = ext.isNsfw,
                    sourceNames = ext.sources.map { it.name },
                )
            }

        entries.sortedWith(
            compareBy<ExtensionEntry> { langGroup(it.lang) }.thenBy { it.name.lowercase() }
        )
    }

    /** Mihon-style language grouping: English first, then multi-lang, then the rest. */
    private fun langGroup(lang: String?): Int = when (lang) {
        "en" -> 0
        "all" -> 1
        else -> 2
    }

    override suspend fun refreshExtensionIndex() {
        extensionManager.findAvailableExtensions()
    }

    override suspend fun getRepos(): List<MangaRepoInfo> = repos.value

    override suspend fun setRepos(newRepos: List<MangaRepoInfo>) {
        val effective = newRepos.ifEmpty { listOf(DEFAULT_REPO) }
        repos.value = effective
        settings.setRaw(KEY_REPOS, json.encodeToString(ListSerializer(MangaRepoInfo.serializer()), effective))
    }

    override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> {
        val available = extensionManager.availableExtensionsFlow.value
            .firstOrNull { it.pkgName == pkgName }
            ?: return flowOf(ExtensionInstallStep.Error)
        return extensionManager.installExtension(available).map { it.toCommon() }
    }

    override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> {
        val installed = installedSnapshot.value
            .firstOrNull { it.pkgName == pkgName }
            ?: return flowOf(ExtensionInstallStep.Error)
        return extensionManager.updateExtension(installed).map { it.toCommon() }
    }

    override fun uninstallExtension(pkgName: String) {
        val installed = installedSnapshot.value
            .firstOrNull { it.pkgName == pkgName }
        val untrusted = untrustedSnapshot.value
            .firstOrNull { it.pkgName == pkgName }
        when {
            installed != null -> extensionManager.uninstallExtension(installed)
            untrusted != null -> extensionManager.uninstallExtension(untrusted)
        }
    }

    override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {
        val untrusted = untrustedSnapshot.value
            .firstOrNull { it.pkgName == pkgName }
            ?: return
        extensionManager.trust(untrusted)
    }

    override suspend fun getExtensionIcon(pkgName: String): ByteArray? {
        val extension = extensionManager.getInstalledExtensions()
            .firstOrNull { it.pkgName == pkgName }
            ?: return null
        return extension.icon?.toPngBytes()
    }

    override suspend fun setShowNsfwSources(enabled: Boolean) {
        showNsfw.value = enabled
        settings.setRaw(KEY_NSFW, if (enabled) "1" else "0")
    }

    override suspend fun getShowNsfwSources(): Boolean = showNsfw.value

    // ---------- Helpers ----------

    private suspend fun loadRepos(): List<MangaRepoInfo> {
        val raw = settings.getRaw(KEY_REPOS) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(MangaRepoInfo.serializer()), raw) }
            .getOrDefault(emptyList())
            .map { repo ->
                if (repo.indexUrl.endsWith("index.min.json")) {
                    repo.copy(indexUrl = repo.indexUrl.removeSuffix("index.min.json") + "index.json")
                } else {
                    repo
                }
            }
    }

    private fun InstallStep.toCommon(): ExtensionInstallStep = when (this) {
        InstallStep.Idle -> ExtensionInstallStep.Idle
        InstallStep.Pending -> ExtensionInstallStep.Pending
        InstallStep.Downloading -> ExtensionInstallStep.Downloading
        InstallStep.Installing -> ExtensionInstallStep.Installing
        InstallStep.Installed -> ExtensionInstallStep.Installed
        InstallStep.Error -> ExtensionInstallStep.Error
    }

    private fun Filter<*>.toCommonFilter(): MangaFilter = when (this) {
        is Filter.Header -> MangaFilter.Header(name)
        is Filter.Separator -> MangaFilter.Separator(name)
        is Filter.Text -> MangaFilter.Text(name, state)
        is Filter.CheckBox -> MangaFilter.CheckBox(name, state)
        is Filter.TriState -> MangaFilter.TriState(name, state)
        is Filter.Select<*> -> MangaFilter.Select(name, values.map { it.toString() }, state)
        is Filter.Sort -> MangaFilter.Sort(
            name,
            values.toList(),
            state?.let { MangaFilter.Sort.SortSelection(it.index, it.ascending) },
        )
        is Filter.Group<*> -> MangaFilter.Group(name, state.map { (it as Filter<*>).toCommonFilter() })
        else -> MangaFilter.Header(name)
    }

    private fun FilterList.applyStates(states: List<MangaFilter>) {
        forEachIndexed { index, filter ->
            states.getOrNull(index)?.let { filter.applyState(it) }
        }
    }

    private fun Filter<*>.applyState(state: MangaFilter) {
        when {
            this is Filter.Text && state is MangaFilter.Text -> this.state = state.state
            this is Filter.CheckBox && state is MangaFilter.CheckBox -> this.state = state.state
            this is Filter.TriState && state is MangaFilter.TriState -> this.state = state.state
            this is Filter.Select<*> && state is MangaFilter.Select -> this.state = state.state
            this is Filter.Sort && state is MangaFilter.Sort ->
                this.state = state.selection?.let { Filter.Sort.Selection(it.index, it.ascending) }
            this is Filter.Group<*> && state is MangaFilter.Group ->
                this.state.forEachIndexed { index, child ->
                    state.filters.getOrNull(index)?.let { (child as Filter<*>).applyState(it) }
                }
        }
    }

    private fun Drawable.toPngBytes(): ByteArray? {
        val width = intrinsicWidth.takeIf { it > 0 } ?: return null
        val height = intrinsicHeight.takeIf { it > 0 } ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        private const val KEY_REPOS = "manga.repos"
        private const val KEY_NSFW = "manga.nsfw"

        /** Default community extension repository (Keiyoushi, full new-format catalog). */
        val DEFAULT_REPO = MangaRepoInfo(
            name = "Keiyoushi",
            baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json",
        )
    }
}

/**
 * Trust decisions persisted through Folio's settings repository. The in-memory sets are
 * authoritative so trust checks during extension loading never race persistence.
 */
private class FolioTrustStore(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : TrustStore {

    private val trusted = MutableStateFlow<Set<String>>(emptySet())
    private val repoKeys = MutableStateFlow<Set<String>>(emptySet())
    private var persistedRepoKeys: Set<String>? = null

    suspend fun preload() {
        val raw = settings.getRaw(KEY_TRUSTED)
        if (!raw.isNullOrBlank()) {
            trusted.value = raw.split("\n").filter { it.isNotBlank() }.toSet()
        }
    }

    fun setRepoKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        repoKeys.value = keys
        persistedRepoKeys = keys
        scope.launch { settings.setRaw(KEY_REPO_KEYS, keys.joinToString("\n")) }
    }

    override suspend fun repoSigningKeys(): Set<String> {
        repoKeys.value.takeIf { it.isNotEmpty() }?.let { return it }
        if (persistedRepoKeys == null) {
            persistedRepoKeys = settings.getRaw(KEY_REPO_KEYS)
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        }
        return persistedRepoKeys!!
    }

    override suspend fun trustedExtensions(): Set<String> = trusted.value

    override fun trust(entry: String, pkgName: String) {
        trusted.value = trusted.value.filterNot { it.startsWith("$pkgName:") }.toSet() + entry
        persist()
    }

    override fun revokeAll() {
        trusted.value = emptySet()
        persist()
    }

    private fun persist() {
        val snapshot = trusted.value.joinToString("\n")
        scope.launch { settings.setRaw(KEY_TRUSTED, snapshot) }
    }

    companion object {
        private const val KEY_TRUSTED = "manga.trusted"
        private const val KEY_REPO_KEYS = "manga.repokeys"
    }
}
