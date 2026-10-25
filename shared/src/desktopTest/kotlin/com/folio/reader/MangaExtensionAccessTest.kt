package com.folio.reader

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.manga.BrowseMode
import com.folio.reader.manga.MangaAccessReason
import com.folio.reader.manga.MangaBackend
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaBrowsePage
import com.folio.reader.manga.MangaChallenges
import com.folio.reader.manga.MangaFilter
import com.folio.reader.manga.MangaSiteAccessException
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.manga.BrowseViewModel
import com.folio.reader.ui.manga.MangaBrowseScreen
import com.folio.reader.ui.manga.MangaDetailViewModel
import com.folio.reader.ui.manga.SourceBrowseViewModel
import com.folio.reader.ui.manga.SourceBrowseScreen
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MangaExtensionAccessTest {
    @BeforeTest
    fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/inter_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @AfterTest
    fun reset() {
        MangaChallenges.clear()
        MangaChallenges.solver = null
    }

    @Test
    fun failedTrustWriteIsVisibleRatherThanASilentSuccess() = runBlocking {
        val entry = com.folio.reader.manga.ExtensionEntry(
            "reader.ext", "Reader", "1.4.1", 1, 1.4, "en", false,
            isUntrusted = true, signatureHash = "signer",
        )
        val gate = CompletableDeferred<Unit>()
        val backend = object : MangaBackend by MangaReaderProgressTest.FakeBackend(emptyMap()) {
            override suspend fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {
                gate.await()
                throw IOException("disk unavailable")
            }
        }
        val viewModel = BrowseViewModel(backend, MangaReaderProgressTest.FakeMangaRepo())
        try {
            viewModel.trust(entry)
            assertNull(viewModel.trustError.value)
            gate.complete(Unit)
            val error = withTimeout(5_000) { viewModel.trustError.first { it != null } }
            assertTrue(error.orEmpty().contains("Reader"))
            assertTrue(error.orEmpty().contains("disk unavailable"))
        } finally {
            viewModel.scope.cancel()
        }
    }

    @Test
    fun closingBrowserStateDoesNotAutomaticallyRefetchABlockedSource() {
        val calls = AtomicInteger()
        val source = MangaSourceInfo(ids.incrementAndGet(), "Blocked source", "en", false, "blocked.ext")
        val allowAccess = AtomicBoolean(false)
        val backend = object : MangaBackend by MangaReaderProgressTest.FakeBackend(emptyMap()) {
            override suspend fun fetchBrowse(sourceId: Long, page: Int, mode: BrowseMode, query: String,
                filters: List<MangaFilter>?): MangaBrowsePage {
                calls.incrementAndGet()
                if (!allowAccess.get()) {
                    MangaChallenges.record("https://blocked.test/", reason = MangaAccessReason.SITE_REFUSAL, sourceId = sourceId)
                    throw MangaSiteAccessException(MangaAccessReason.SITE_REFUSAL, 403)
                }
                return MangaBrowsePage(listOf(MangaBrowseItem("/manga", "Loaded manga")), false)
            }
        }
        val viewModel = SourceBrowseViewModel(backend, source,
            MangaReaderProgressTest.FakeMangaRepo(), MangaReaderProgressTest.FakeCategoryRepo())
        try {
            runBlocking { withTimeout(5_000) { viewModel.state.first { !it.loading && it.error != null } } }
            assertEquals(1, calls.get())
            MangaChallenges.solver = { MangaChallenges.clear() }
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene(393, 852) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        SourceBrowseScreen(viewModel, onOpenManga = {}, onBack = {})
                    }
                }
                try {
                    repeat(3) { scene.render(it * 16_000_000L).close() }
                    MangaChallenges.solve()
                    repeat(30) { frame ->
                        Snapshot.sendApplyNotifications()
                        scene.render((frame + 3) * 16_000_000L).close()
                    }
                } finally {
                    scene.close()
                }
            }
            assertNull(MangaChallenges.pending.value)
            assertEquals(1, calls.get(), "Dismissal/closure must not start another blocked request")
            allowAccess.set(true)
            viewModel.reload(BrowseMode.POPULAR, "", null)
            runBlocking { withTimeout(5_000) { viewModel.state.first { !it.loading && it.items.isNotEmpty() } } }
            assertEquals(2, calls.get(), "Only the explicit Retry should verify source access")
        } finally {
            viewModel.scope.cancel()
        }
    }

    @Test
    fun blockedRefreshPreservesSavedChaptersWithoutCallingItOffline() = runBlocking {
        val id = "blocked-refresh"
        val mangaRepo = MangaReaderProgressTest.FakeMangaRepo().apply {
            entries[id] = com.folio.reader.manga.MangaEntry(
                id, 999L, "Blocked source", "/manga", "Saved manga", initialized = true,
            )
        }
        val chapterRepo = MangaReaderProgressTest.FakeChapterRepo().apply {
            chapters["saved"] = com.folio.reader.manga.MangaChapter("saved", id, "/chapter", "Saved chapter")
        }
        val backend = object : MangaBackend by MangaReaderProgressTest.FakeBackend(emptyMap()) {
            override suspend fun fetchMangaDetail(sourceId: Long, mangaUrl: String): com.folio.reader.manga.MangaDetail {
                throw MangaSiteAccessException(MangaAccessReason.SITE_REFUSAL, 403)
            }
        }
        val viewModel = MangaDetailViewModel(backend, mangaRepo, chapterRepo,
            MangaReaderProgressTest.FakeHistoryRepo(), downloadManager = null, downloadRepo = null,
            categoryRepo = MangaReaderProgressTest.FakeCategoryRepo(),
            settingsRepo = MangaReaderProgressTest.FakeSettingsRepo())
        try {
            viewModel.open(id)
            withTimeout(5_000) { viewModel.chapters.first { it.isNotEmpty() } }
            viewModel.refresh()
            val message = withTimeout(5_000) { viewModel.refreshNotice.first { it != null } }.orEmpty()
            assertTrue(message.contains("HTTP 403"))
            assertTrue(message.contains("Showing saved chapters"))
            assertFalse(message.startsWith("Offline"))
            assertEquals("saved", viewModel.chapters.value.single().id)
            assertNull(viewModel.error.value)
        } finally {
            viewModel.scope.cancel()
        }
    }

    @Test
    fun visibleSourceRowsReceiveTapsAtTheirDrawnPositions() {
        val local = MangaSourceInfo(0L, "Local manga", "", isLocal = true)
        val remote = (1L..4L).map { MangaSourceInfo(it, "Source $it", "en", false, "source.$it") }
        val backend = object : MangaBackend by MangaReaderProgressTest.FakeBackend(emptyMap()) {
            override val supportsExtensions = true
            override fun observeSources() = flowOf(listOf(local) + remote)
        }
        val viewModel = BrowseViewModel(backend, MangaReaderProgressTest.FakeMangaRepo())
        try {
            runBlocking { withTimeout(5_000) { viewModel.visibleSources.first { it.size == 5 } } }
            SwingUtilities.invokeAndWait {
                var selected: MangaSourceInfo? = null
                val scene = ImageComposeScene(393, 852) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            MangaBrowseScreen(viewModel, onOpenSource = { source, _ -> selected = source },
                                onOpenManga = {}, onOpenExtensions = {}, onBack = {})
                        }
                    }
                }
                var frame = 0L
                fun render() {
                    repeat(10) {
                        Snapshot.sendApplyNotifications()
                        scene.render(frame++ * 100_000_000L).close()
                    }
                }
                try {
                    render()
                    for (source in remote) {
                        val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                        val row = nodes.single { node ->
                            node.config.contains(SemanticsActions.OnClick) &&
                                node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                                    .any { it.text == source.name }
                        }
                        val center = row.boundsInRoot.center
                        scene.sendPointerEvent(PointerEventType.Press, Offset(center.x, center.y))
                        scene.sendPointerEvent(PointerEventType.Release, Offset(center.x, center.y))
                        render()
                        assertEquals(source, selected, "A drawn source row swallowed its tap: ${source.name}")
                        selected = null
                    }
                } finally {
                    scene.close()
                }
            }
        } finally {
            viewModel.scope.cancel()
        }
    }

    companion object {
        private val ids = AtomicLong(9_987_000L)
    }
}
