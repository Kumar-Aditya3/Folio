package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import com.folio.reader.model.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.*
import com.folio.reader.ui.library.BookGrid
import com.folio.reader.ui.reader.BottomPageBlock
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class ReadingTracePreviewTest {
    private val trace = ReadingTrace(.63f, listOf(ReadingTraceSpan(.03f, .12f, 14f),
        ReadingTraceSpan(.12f, .24f, 22f), ReadingTraceSpan(.23f, .39f, 35f),
        ReadingTraceSpan(.39f, .45f, 12f), ReadingTraceSpan(.44f, .63f, 28f)),
        listOf(ReadingTracePassage("a", "one", 1, .14f, "A passage that stayed with you.", false),
            ReadingTracePassage("b", "two", 3, .37f, "Another passage, with your note.", true)), 5, 111)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, pack: ThemePack, dark: Boolean, width: Int = 393,
        scale: Float = 1f, content: @Composable () -> Unit) {
        val font = requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf"))
        UiFonts.install(File(font.toURI()).parentFile)
        val scene = ImageComposeScene(width = width, height = 960) {
            FolioTheme.AppTheme(AppPalette.byId(pack.appPaletteId(dark))) {
                FolioGlassRoot(GlassCapabilities(true, true, true)) {
                    CompositionLocalProvider(LocalFolioPackId provides pack.id,
                        LocalDensity provides Density(1f, scale), LocalReadingTraces provides mapOf("trace-book" to trace)) {
                        Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) { content() }
                    }
                }
            }
        }
        try {
            repeat(12) { scene.render(it * 150_000_000L).close(); Thread.sleep(10) }
            val image = scene.render(2_000_000_000L)
            try {
                val target = File("build/visual-review/reading-trace/$name.png")
                target.parentFile.mkdirs()
                target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { image.close() }
        } finally { scene.close() }
    }

    @Test fun allNinePairedHomesAndNarrowLargeText() {
        for (pack in ThemePack.ALL) for (dark in listOf(false, true)) {
            home(pack, dark, 393, 1f)
        }
        home(ThemePack.ALL.first { it.id == "blush" }, true, 320, 1.4f)
        home(ThemePack.ALL.first { it.id == "ceramic" }, false, 320, 1.4f)
    }

    private fun home(pack: ThemePack, dark: Boolean, width: Int, scale: Float) {
        val covers = (0..2).map { File("build/visual-review/artwork-fixture-$it.png").takeIf { it.exists() }?.absolutePath }
        render("home-${pack.id}-$dark-$width", pack, dark, width, scale) {
            HomeScreen(HomeUiState(loaded = true, hasBooks = true, readingDesk = true,
                readingNow = listOf(ReadingNowItem(HomeItemKind.BOOK, "trace-book", "Small Worlds", "A. Reader", .63f, coverPath = covers[0]),
                    ReadingNowItem(HomeItemKind.BOOK, "next", "A Place for Us", "A. Reader", .2f, coverPath = covers[1]),
                    ReadingNowItem(HomeItemKind.BOOK, "third", "The City & the City", "A. Reader", .4f, coverPath = covers[2]))),
                onOpenBook = {}, onOpenBookDetail = {}, onImportClick = {}, topInset = 28.dp)
        }
    }

    @Test fun libraryAndReaderCarryTheSameTrace() {
        for (id in listOf("editorial", "blush", "acid")) {
            val pack = ThemePack.ALL.first { it.id == id }
            render("library-$id", pack, true) {
                val book = Book("trace-book", "Small Worlds", authors = listOf("A. Reader"),
                    epubHash = "preview", epubFileSize = 1).apply { updateProgress(.63) }
                BookGrid(listOf(book), {}, {}, {}, emptySet(), false)
            }
            render("reader-$id", pack, true, 320) {
                Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
                    Text("Small Worlds", style = FolioTheme.typography.headlineMedium,
                        modifier = Modifier.padding(24.dp))
                    Text("A reading trace carries your progress, measured sittings and saved passages between surfaces.",
                        style = FolioTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
                    Spacer(Modifier.weight(1f))
                    BottomPageBlock("Chapter four", .63f, Color(0xff151515), Color(0xffeeeeee),
                        "63% read", readingTrace = trace, onSeek = {})
                }
            }
        }
    }
}
