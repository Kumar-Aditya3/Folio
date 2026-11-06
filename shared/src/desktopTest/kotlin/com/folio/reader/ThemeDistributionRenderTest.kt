package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.DesktopMangaBackend
import com.folio.reader.manga.MangaHistoryEntry
import com.folio.reader.platform.DesktopFileSystem
import com.folio.reader.ui.components.*
import com.folio.reader.ui.manga.MangaHistoryContent
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

class ThemeDistributionRenderTest {
    private val navigation = listOf(
        FolioNavigationItem("home", Icons.Filled.Home, "Home", outlinedIcon = Icons.Outlined.Home),
        FolioNavigationItem("library", Icons.AutoMirrored.Filled.MenuBook, "Library", outlinedIcon = Icons.AutoMirrored.Outlined.MenuBook),
        FolioNavigationItem("stats", Icons.Filled.BarChart, "Stats", outlinedIcon = Icons.Outlined.BarChart),
        FolioNavigationItem("more", Icons.Filled.MoreHoriz, "More", outlinedIcon = Icons.Outlined.MoreHoriz),
    )
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun elementMaterialsAndHistoryAcrossThemes() {
        val fonts = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        val output = File("build/visual-review/theme-distribution").apply { mkdirs() }
        // Default to the complete matrix; allow changed faces to be rerendered during review.
        val requested = System.getenv("FOLIO_REVIEW_PALETTE_IDS")?.split(',')?.toSet()
        val backend = DesktopMangaBackend(DesktopFileSystem(File(output, "fixtures")))
        val now = System.currentTimeMillis()
        val entries = listOf(
            MangaHistoryEntry("a", "Witch Hat Atelier", chapterName = "Chapter 42 · A new beginning", updatedAt = now - 120_000),
            MangaHistoryEntry("b", "The Summer Hikaru Died — a longer title", chapterName = "Chapter 18", updatedAt = now - 3_600_000),
            MangaHistoryEntry("c", "Frieren: Beyond Journey’s End", chapterName = "Chapter 76", updatedAt = now - 86_400_000),
            MangaHistoryEntry("d", "Skip and Loafer", chapterName = "Chapter 29", updatedAt = now - 4 * 86_400_000),
        )
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                for (palette in AppPalette.entries) {
                    if (requested != null && palette.id !in requested) continue
                    val pack = ThemePack.ALL.first { palette.id in listOf(it.lightAppPaletteId, it.darkAppPaletteId) }
                    for (selected in navigation.indices) for (blur in if (selected == 1) listOf(true, false) else listOf(true)) {
                        val scene = ImageComposeScene(393, 900) {
                            FolioTheme.AppTheme(palette = palette, daylight = FolioDaylight.Neutral) {
                                CompositionLocalProvider(LocalFolioPackId provides pack.id) {
                                    FolioGlassRoot(if (blur) GlassCapabilities(true, true, true) else GlassCapabilities.None) {
                                        Box(Modifier.fillMaxSize()) {
                                            Box(Modifier.matchParentSize().folioRoomSource().folioField())
                                            Column(Modifier.folioBackdropSource().padding(start = 20.dp, end = 20.dp, top = 76.dp),
                                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                                    for (title in listOf("Dune", "City", "Ways")) {
                                                        BookCover(null, title, "A. Reader", Modifier.width(56.dp).height(80.dp), small = true)
                                                    }
                                                }
                                                FolioActionDock("Continue reading", "Small Worlds · Chapter four", {}, progress = .42f)
                                                FolioPaperSheet(accent = FolioTheme.colors.accentAnnotation) {
                                                    Text("A saved passage", style = FolioTheme.typography.labelSmall,
                                                        color = FolioTheme.colors.onSurfaceVariant)
                                                    Text("Some places stay with you long after you have left them.",
                                                        style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
                                                    Text("Small Worlds · Page 42", style = FolioTheme.typography.bodySmall,
                                                        color = FolioTheme.colors.onSurfaceVariant)
                                                }
                                                FolioSection(FolioTheme.colors.accentDiscovery) {
                                                    FolioReferencePanel {
                                                        Text("Reading preferences", style = FolioTheme.typography.titleSmall,
                                                            color = FolioTheme.colors.onSurface)
                                                        FolioInputField("", {}, placeholder = { Text("Find in your library") })
                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                            FolioChip(true, {}, "All")
                                                            FolioChip(false, {}, "Books")
                                                            FolioChip(false, {}, "Manga")
                                                        }
                                                        FolioSlider(.4f, {})
                                                    }
                                                }
                                                FolioReferencePanel {
                                                    Text("This week", style = FolioTheme.typography.titleSmall,
                                                        color = FolioTheme.colors.onSurface)
                                                    com.folio.reader.ui.statistics.SmoothWeekCurve(
                                                        listOf(12f, 35f, 18f, 42f, 28f, 55f, 30f),
                                                        listOf("M", "T", "W", "T", "F", "S", "S"), 64.dp)
                                                }
                                            }
                                            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().height(80.dp)
                                                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                                                    androidx.compose.ui.graphics.Color.Transparent,
                                                    folioFieldBottom().copy(alpha = .80f)))))
                                            FolioNavigationCapsule(navigation, selected, {},
                                                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                                    .padding(bottom = FolioTokens.navFloatInset))
                                            FolioTopBar(if (selected == 0) "Folio" else navigation[selected].label,
                                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                                                actions = {
                                                    IconButton({}) { Icon(Icons.Filled.Search, "Search") }
                                                    if (selected == 1) {
                                                        IconButton({}) { Icon(Icons.Filled.Add, "Import") }
                                                        IconButton({}) { Icon(Icons.Filled.Tune, "Sort and filter") }
                                                        IconButton({}) { Icon(Icons.Filled.MoreVert, "Options") }
                                                    }
                                                })
                                        }
                                    }
                                }
                            }
                        }
                        try {
                            repeat(8) { scene.render(it * 125_000_000L).close() }
                            scene.render(1_000_000_000L).use {
                                val bytes = it.encodeToData(EncodedImageFormat.PNG)!!.bytes
                                File(output, "palette-${palette.id}-nav-$selected${if (blur) "" else "-opaque"}.png").writeBytes(bytes)
                                if (selected == 1 && blur) File(output, "palette-${palette.id}.png").writeBytes(bytes)
                            }
                        } finally { scene.close() }
                    }
                }
                for (pack in ThemePack.ALL) {
                    if (requested != null && pack.lightAppPaletteId !in requested && pack.darkAppPaletteId !in requested) continue
                    val scene = ImageComposeScene(360, 260) {
                        FolioTheme.AppTheme(AppPalette.byId(pack.lightAppPaletteId), daylight = FolioDaylight.Neutral) {
                            Row(Modifier.fillMaxSize().background(FolioTheme.colors.background).padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                com.folio.reader.ui.settings.ThemePackCard(pack, false, true, {})
                                com.folio.reader.ui.settings.ThemePackCard(pack, true, false, {})
                            }
                        }
                    }
                    try {
                        repeat(4) { scene.render(it * 250_000_000L).close() }
                        scene.render(1_000_000_000L).use {
                            File(output, "pack-${pack.id}.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        }
                    } finally { scene.close() }
                }
                for (palette in listOf(AppPalette.WARM, AppPalette.DARK, AppPalette.TEAL, AppPalette.MAGENTA)) {
                    if (requested != null && palette.id !in requested) continue
                    for (width in listOf(320, 393, 900)) for (empty in listOf(false, true)) for (mode in if (width == 393 && !empty) 0..2 else 0..0) {
                        val scene = ImageComposeScene(width, 900, density = Density(1f, if (width == 320) 1.4f else 1f)) {
                            FolioTheme.AppTheme(palette = palette, daylight = FolioDaylight.Neutral) {
                                FolioGlassRoot(if (mode == 0) GlassCapabilities.None else GlassCapabilities(true, true, true),
                                    inFlowGlass = mode != 2) {
                                    Box(Modifier.fillMaxSize()) {
                                        Box(Modifier.matchParentSize().folioRoomSource().folioField())
                                        MangaHistoryContent(backend, if (empty) emptyList() else entries, {}, {}, {})
                                    }
                                }
                            }
                        }
                        try {
                            repeat(4) { scene.render(it * 250_000_000L).close() }
                            scene.render(1_000_000_000L).use {
                                File(output, "history-${palette.id}-$width${if (empty) "-empty" else ""}${when (mode) { 1 -> "-glass"; 2 -> "-minimal"; else -> "" }}.png")
                                    .writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                            }
                        } finally { scene.close() }
                    }
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
