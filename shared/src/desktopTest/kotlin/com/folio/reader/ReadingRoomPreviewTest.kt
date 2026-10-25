package com.folio.reader

import com.folio.reader.manga.*
import com.folio.reader.ui.manga.ChapterRow
import com.folio.reader.ui.manga.FeaturedMangaShelfEntry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Quote
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.book.BookHeaderSection
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.*
import com.folio.reader.ui.library.BookGrid
import com.folio.reader.ui.quotes.QuoteCard
import com.folio.reader.ui.quotes.QuoteDisplayItem
import com.folio.reader.ui.search.*
import com.folio.reader.ui.settings.FolioMaterialsPreview
import com.folio.reader.ui.statistics.StatDay
import com.folio.reader.ui.statistics.StatsGlassCard
import com.folio.reader.ui.statistics.WeekChart
import com.folio.reader.ui.theme.*
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class ReadingRoomPreviewTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderNativeScreensInBothThemesAndNarrowLargeType() {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val directory = File("build/visual-review/reading-room").apply { mkdirs() }
        val image = java.awt.image.BufferedImage(240, 360, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { canvas ->
            canvas.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(36, 88, 111), 240f, 360f, java.awt.Color(205, 157, 91))
            canvas.fillRect(0, 0, 240, 360)
            canvas.color = java.awt.Color(235, 197, 115); canvas.fillOval(20, 30, 160, 160)
            canvas.color = java.awt.Color(24, 53, 72); canvas.fillRect(0, 220, 240, 140)
            canvas.color = java.awt.Color.WHITE; canvas.font = java.awt.Font("Serif", 0, 25)
            canvas.drawString("SMALL WORLDS", 10, 285); canvas.dispose()
        }
        val cover = File(directory, "cover.png").also { javax.imageio.ImageIO.write(image, "png", it) }.absolutePath
        val books = (0..11).map { index -> Book("room-$index", if (index == 0) "Small Worlds" else "The Long Way Home",
            authors = listOf("A. Reader"), epubHash = "$index", epubFileSize = 1, coverPath = cover.takeIf { index % 2 == 0 },
            totalWords = 80_000, chapterCount = 14, description = "A story about places, memory, and finding a way home.")
            .apply { updateProgress(if (index == 0) .63 else .2) } }
        val book = books.first()
        val brightImage = java.awt.image.BufferedImage(240, 360, java.awt.image.BufferedImage.TYPE_INT_RGB)
        brightImage.createGraphics().let { canvas ->
            canvas.color = java.awt.Color.WHITE; canvas.fillRect(0, 0, 240, 360); canvas.dispose()
        }
        val brightCover = File(directory, "white-cover.png").also { javax.imageio.ImageIO.write(brightImage, "png", it) }.absolutePath
        val brightBook = book.copy(id = "bright", coverPath = brightCover).apply { updateProgress(.63) }
        val week = (0..6).map { StatDay(LocalDate(2026, 10, 15 + it), listOf(12L, 35L, 0L, 65L, 24L, 40L, 20L)[it]) }
        val quote = QuoteDisplayItem(Quote("q", book.id, "ch", "hl",
            "Some places stay with you long after you have left them. A room becomes a world when there is a story in it.",
            deviceId = "preview"), book, null, null, null, emptyList())
        val backend = PreviewMangaBackend(File(cover).readBytes())
        val manga = MangaEntry("m-preview", 901L, "Preview source", "preview", "Small Worlds",
            author = "A. Reader", coverPath = cover, thumbnailUrl = "room-cover")
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "bright-book" to { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BookHeaderSection(brightBook, null, emptyList(), emptyList(), 4, 40_000, 3, 2, 1,
                    {}, {}, {}, {}, onCoverClick = {})
            } },
            "book" to { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BookHeaderSection(book, null, emptyList(), emptyList(), 4, 40_000, 3, 2, 1,
                    {}, {}, {}, {}, onCoverClick = {})
            } },
            "library" to { BookGrid(books, {}, {}, {}, emptySet(), false) },
            "search" to { BookSearchResultsList("room", SearchScope.CONTENT, books.take(1),
                listOf(BookHit(book, 2, "Chapter seven", "A <<room>> becomes a world when there is a story in it.")),
                emptyList(), {}, {}) },
            "passage" to { Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
                SearchPassage(book, "Chapter seven", "A <<room>> becomes a world when there is a story in it.", true, {}, {})
            } },
            "quote" to { Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
                QuoteCard(quote, {}, expanded = true)
            } },
            "stats" to { Column(Modifier.fillMaxWidth().padding(top = 24.dp)) { StatsGlassCard { WeekChart(week) } } },
            "allocation" to { Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                StatsGlassCard { com.folio.reader.ui.statistics.WhereYourTimeWentCard(
                    listOf(com.folio.reader.ui.statistics.ReadingTimeEntry(manga.id, "Small Worlds: Manga", "A. Artist", cover,
                        95, true, manga.sourceId, manga.thumbnailUrl),
                        com.folio.reader.ui.statistics.ReadingTimeEntry(book.id, book.title, book.displayAuthor, cover, 45)),
                    0, 2, 12, {}, mangaBackend = backend)
                }
            } },
            "stats-transparency" to { Box(Modifier.fillMaxWidth().height(380.dp)) {
                Box(Modifier.matchParentSize().folioRoomSource().background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(FolioTheme.colors.accentDiscovery.copy(alpha = .35f),
                        FolioTheme.colors.background, FolioTheme.colors.accentProgress.copy(alpha = .3f)))))
                StatsGlassCard { WeekChart(week) }
            } },
            "transparency" to { androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().folioBackdropSource(), contentPadding = PaddingValues(20.dp)) {
                item { FolioGlassPanel {
                    com.folio.reader.ui.settings.TransparencySettingsPanel(ReaderSettings(), {})
                } }
            } },
            "settings" to { Column(Modifier.fillMaxWidth().padding(24.dp)) { FolioMaterialsPreview(ReaderSettings()) } },
            "arrivals" to { Column(Modifier.fillMaxWidth().padding(24.dp)) {
                NewChaptersCard((1..3).map { MangaNewChapterBadge("m$it", "Small Worlds $it", 901L,
                    "room-cover", cover, it + 1, kotlinx.datetime.Clock.System.now()) }, backend, {})
            } },
            "discover" to { Column(Modifier.fillMaxWidth().padding(24.dp)) {
                DiscoverCard((1..3).map { MangaDiscoverItem(901L, "Preview source", "book$it",
                    "Small Worlds $it", "room-cover") }, backend, {})
            } },
            "manga-library" to { Column(Modifier.fillMaxWidth().padding(24.dp)) {
                FeaturedMangaShelfEntry(manga, backend, 5, .63f, false, false, false,
                    {}, {}, {}, {}, {})
            } },
            "chapters" to { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { index -> ChapterRow(MangaChapter("ch$index", manga.id, "preview", "Chapter ${index + 1}",
                    read = index == 0, chapterNumber = (index + 1).toFloat()), null, false,
                    index == 2, false, {}, {}, {}, {}, {}, {}, {}, {}) }
            } },
            "home" to { HomeScreen(HomeUiState(loaded = true, hasBooks = true, readingDesk = true,
                todayMinutes = 32, streakDays = 12, week = week, candidates = books.takeLast(2),
                becauseFinishedTitle = "A Place for Us", becauseFinishedBook = books.last(),
                recommendationReasons = books.takeLast(2).associate { it.id to "By the same author" },
                readingNow = books.map { ReadingNowItem(HomeItemKind.BOOK, it.id, it.title, it.displayAuthor,
                    it.normalizedProgress.toFloat(), coverPath = it.coverPath) }), {}, {}, {}) }
        )
        for ((name, content) in screens) for (dark in listOf(false, true)) for (width in listOf(320, 393)) {
            val scene = ImageComposeScene(width, 1100, density = Density(1f, if (width == 320) 1.4f else 1f)) {
                FolioTheme.AppTheme(palette = if (dark) AppPalette.DARK else AppPalette.LIGHT) {
                    FolioGlassRoot(GlassCapabilities(true, true, true)) {
                        CompositionLocalProvider(LocalFolioAmbientTint provides rememberFolioAmbientTint()) {
                            Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) { content() }
                        }
                    }
                }
            }
            try {
                repeat(12) { scene.render(it * 150_000_000L).close(); Thread.sleep(20) }
                val rendered = scene.render(2_000_000_000L)
                try { File(directory, "$name-${if (dark) "dark" else "light"}-$width.png")
                    .writeBytes(rendered.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                finally { rendered.close() }
                if (name == "home") {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 400f), scrollDelta = Offset(0f, 720f))
                    repeat(15) { scene.render(2_100_000_000L + it * 150_000_000L).close() }
                    val lower = scene.render(4_500_000_000L)
                    try { File(directory, "home-lower-${if (dark) "dark" else "light"}-$width.png")
                        .writeBytes(lower.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                    finally { lower.close() }
                }
            } finally { scene.close() }
        }
    }
    private class PreviewMangaBackend(private val image: ByteArray) : MangaBackend {
        override val supportsExtensions: Boolean = false
        override fun observeSources(): Flow<List<MangaSourceInfo>> = flowOf(emptyList())
        override suspend fun getFilterTemplate(sourceId: Long): List<MangaFilter> = emptyList()
        override suspend fun fetchBrowse(
            sourceId: Long,
            page: Int,
            mode: BrowseMode,
            query: String,
            filters: List<MangaFilter>?,
        ): MangaBrowsePage {
            return MangaBrowsePage(emptyList(), false)
        }

        override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): MangaDetail =
            MangaDetail(url = mangaUrl, title = "Stub")
        override suspend fun fetchChapterList(sourceId: Long, mangaUrl: String): List<MangaChapterRef> = emptyList()
        override suspend fun fetchPageList(sourceId: Long, chapter: MangaChapterRef): List<MangaPageRef> = emptyList()
        override suspend fun fetchPageImage(
            sourceId: Long,
            chapter: MangaChapterRef,
            page: MangaPageRef,
        ): MangaImageData = MangaImageData(ByteArray(0), "image/png")
        override suspend fun fetchCover(sourceId: Long, thumbnailUrl: String?): ByteArray? = image
        override fun observeExtensions(): Flow<List<ExtensionEntry>> = flowOf(emptyList())
        override suspend fun refreshExtensionIndex() {}
        override suspend fun getRepos(): List<MangaRepoInfo> = emptyList()
        override suspend fun setRepos(repos: List<MangaRepoInfo>) {}
        override fun installExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun updateExtension(pkgName: String): Flow<ExtensionInstallStep> = flowOf()
        override fun uninstallExtension(pkgName: String) {}
        override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {}
        override suspend fun getExtensionIcon(pkgName: String): ByteArray? = null
        override suspend fun setShowNsfwSources(enabled: Boolean) {}
        override suspend fun getShowNsfwSources(): Boolean = false
    }

}
