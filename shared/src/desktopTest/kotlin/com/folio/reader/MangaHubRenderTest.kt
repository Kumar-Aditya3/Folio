package com.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.manga.*
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

class MangaHubRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun moreBrowseAndCatalogStayCoherentAtNarrowAndLargeTextSizes() {
        val fonts = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                val output = File("build/visual-review/manga-hub").apply { mkdirs() }
                for (dark in listOf(false, true)) for (width in listOf(320, 360, 393)) for (page in listOf("More", "Browse", "Extensions")) {
                    val entries = listOf(
                        ExtensionEntry("fixture.dex", "MangaDex", "1.4.7", 1, 1.4, "en", false),
                        ExtensionEntry("fixture.web", "Webtoon", "1.4.3", 1, 1.4, "en", false, isInstalled = true, hasUpdate = true),
                        ExtensionEntry("fixture.reader", "A source with a longer name", "1.4.1", 1, 1.4, "en", false, isUntrusted = true),
                        ExtensionEntry("fixture.retry", "MangaReader", "1.4.8", 1, 1.4, "en", false),
                    )
                    val scene = ImageComposeScene(width, 1200, density = Density(1f, if (width == 320) 1.4f else 1f)) {
                        FolioTheme.AppTheme(palette = if (dark) AppPalette.TEAL else AppPalette.COOL) {
                            FolioGlassRoot(GlassCapabilities.None) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(Modifier.matchParentSize().folioRoomSource().folioField())
                                    val accent = FolioTheme.colors.tertiary
                                    val topInset = folioBarTopInset(if (page == "Extensions") 48.dp else 0.dp)
                                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                                        start = FolioTokens.gutter, end = FolioTokens.gutter,
                                        top = topInset + FolioTokens.space2, bottom = 24.dp)) {
                                        when (page) {
                                            "More" -> {
                                                item { FolioHubSection("Reading", FolioTheme.colors.accentProgress) {
                                                    FolioHubRow(Icons.Filled.Headphones, "Narration", "Offline voice, listening and sleep timer", FolioTheme.colors.accentProgress, onClick = {})
                                                    FolioHubRow(Icons.Filled.Notifications, "Widgets & notifications", "Your reading rhythm, beyond the app", FolioTheme.colors.accentProgress, last = true, onClick = {})
                                                } }
                                                item { FolioHubSection("Manga", accent) {
                                                    FolioHubRow(Icons.Filled.Search, "Browse", "Find manga from your sources", accent, onClick = {})
                                                    FolioHubRow(Icons.Filled.Extension, "Extensions", "Install and manage manga sources", accent, last = true, onClick = {})
                                                } }
                                            }
                                            "Browse" -> {
                                                item { BrowseGroupHeading("On this device", first = true) }
                                                item { FolioHubGroupItem(true, true) { SourceRow(MangaSourceInfo(0, "Local manga", "all", isLocal = true), onClick = {}) } }
                                                item { BrowseGroupHeading("English") }
                                                listOf("MangaDex", "Webtoon", "MangaReader").forEachIndexed { index, name ->
                                                    item { FolioHubGroupItem(index == 0, index == 2) {
                                                        SourceRow(MangaSourceInfo(index + 1L, name, "en", supportsLatest = index == 0), last = index == 2, onClick = {})
                                                    } }
                                                }
                                                item { Spacer(Modifier.height(FolioTokens.spaceMovement)); FolioHubSection("Extensions", accent) {
                                                    FolioHubRow(Icons.Filled.Extension, "Manage extensions", "3 installed · add or update sources", accent, last = true, onClick = {})
                                                } }
                                            }
                                            else -> {
                                                item { BrowseGroupHeading("English", first = true) }
                                                entries.forEachIndexed { index, entry -> item {
                                                    FolioHubGroupItem(index == 0, index == entries.lastIndex) {
                                                        ExtensionRow(entry, if (index == 3) ExtensionInstallStep.Error else null,
                                                            {}, {}, {}, {}, last = index == entries.lastIndex)
                                                    }
                                                } }
                                                item { Spacer(Modifier.height(FolioTokens.spaceMovement)); FolioHubSection("Repositories", accent) {
                                                    FolioHubRow(Icons.Filled.Extension, "My repository", "example.com/extensions", accent, onClick = {})
                                                    FolioHubRow(Icons.Filled.Add, "Add repository", "Add a catalog of manga sources", accent, last = true, onClick = {})
                                                } }
                                            }
                                        }
                                    }
                                    FolioTopBar(title = page, modifier = Modifier.align(Alignment.TopCenter),
                                        navigationIcon = if (page == "More") null else ({ IconButton({}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }),
                                        actions = {
                                            IconButton({}) { Icon(Icons.Filled.Search, "Search") }
                                            if (page != "More") IconButton({}) { Icon(Icons.Filled.MoreVert, "Options") }
                                        },
                                        rail = {
                                            if (page == "Extensions") ExtensionsRail(false, "", {}, 1, {})
                                        })
                                }
                            }
                        }
                    }
                    try {
                        repeat(4) { scene.render(it * 250_000_000L).close() }
                        scene.render(1_000_000_000L).use {
                            File(output, "${page.lowercase()}-${if (dark) "dark" else "light"}-$width.png")
                                .writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        }
                    } finally { scene.close() }
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
