package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.recommendationReason
import com.folio.reader.ui.home.mergeMangaReadingRows
import com.folio.reader.ui.home.MangaContinueItem
import com.folio.reader.ui.statistics.weekIndexAt
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.AppPalette
import kotlin.math.pow
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat

class ReadingRoomBehaviorTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun palettePaperKeepsItsBodyInkLegibleAcrossEveryBuiltInPalette() {
        fun luminance(c: Color): Double {
            fun linear(v: Float): Double = if (v <= .04045f) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        for (palette in AppPalette.entries) {
            var ratio = 0.0
            val scene = ImageComposeScene(20, 20) {
                FolioTheme.AppTheme(palette = palette) {
                    val ink = luminance(FolioTheme.colors.onSurface)
                    ratio = listOf(folioPaperColor(), folioReferenceColor()).minOf { surface ->
                        val fill = luminance(surface)
                        (maxOf(fill, ink) + .05) / (minOf(fill, ink) + .05)
                    }
                    Box(Modifier.fillMaxSize().folioPaper())
                }
            }
            try { scene.render(0).close(); assertTrue(ratio >= 4.5, "$palette body contrast was $ratio") }
            finally { scene.close() }
        }
    }
    @Test fun quickMangaIdentityKeepsResolvedBacklogWithoutReplacingFreshProgress() {
        val fresh = MangaContinueItem("m", "A title", null, null, 1L, "Source", "c", null, .7f, null)
        val complete = fresh.copy(progress = .5f, caption = "5 unread", estimate = "5 unread", horizon = "5 unread")
        val merged = mergeMangaReadingRows(fresh, listOf(complete)).single()
        assertEquals(.7f, merged.progress)
        assertEquals("5 unread", merged.horizon)
        assertEquals("5 unread", merged.estimate)
        assertNull(mergeMangaReadingRows(fresh.copy(chapterId = "new"), listOf(complete)).single().estimate)
        assertEquals(complete, mergeMangaReadingRows(null, listOf(complete)).single())
    }
    @Test fun recommendationReasonsUseActualSeriesAndAuthorLinks() {
        val anchor = Book("a", "First", authors = listOf("Writer"), seriesId = "series", epubHash = "a", epubFileSize = 1)
        val sameSeries = anchor.copy(id = "b", authors = listOf("Other"))
        val sameAuthor = anchor.copy(id = "c", seriesId = null)
        assertEquals("Same series", recommendationReason(anchor, sameSeries))
        assertEquals("By the same author", recommendationReason(anchor, sameAuthor))
        assertNull(recommendationReason(anchor, anchor))
        assertNull(recommendationReason(null, sameAuthor))
        assertNull(recommendationReason(anchor, anchor.copy(id = "d", seriesId = null, authors = emptyList())))
    }

    @Test fun timelineSelectsTheRenderedDayBinsAndClampsInvalidCoordinates() {
        for (count in 1..14) for (width in listOf(120f, 240f, 700f)) {
            for (index in 0 until count) assertEquals(index,
                weekIndexAt(width * (index + .5f) / count, width, count))
            assertEquals(0, weekIndexAt(-1000f, width, count))
            assertEquals(count - 1, weekIndexAt(width + 1000f, width, count))
        }
        assertEquals(0, weekIndexAt(Float.NaN, 100f, 7))
        assertEquals(0, weekIndexAt(20f, 0f, 7))
        assertEquals(0, weekIndexAt(20f, 100f, 0))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun dockDispatchesOnceAndDisabledDockCannotOpenContent() {
        var calls = 0
        var active = Rect.Zero
        var disabled = Rect.Zero
        val scene = ImageComposeScene(360, 400) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                    FolioActionDock("Read", "Chapter seven", { calls++ }, progress = .63f,
                        modifier = Modifier.onGloballyPositioned { active = it.boundsInRoot() })
                    FolioActionDock("Read", "No chapters yet", { calls++ }, enabled = false,
                        modifier = Modifier.onGloballyPositioned { disabled = it.boundsInRoot() })
                }
            }
        }
        try {
            repeat(8) { scene.render(it * 100_000_000L).close() }
            assertTrue(active.height >= 96f)
            scene.sendPointerEvent(PointerEventType.Press, active.center)
            scene.sendPointerEvent(PointerEventType.Release, active.center)
            scene.render(1_000_000_000L).close()
            assertEquals(1, calls)
            scene.sendPointerEvent(PointerEventType.Press, disabled.center)
            scene.sendPointerEvent(PointerEventType.Release, disabled.center)
            scene.render(1_200_000_000L).close()
            assertEquals(1, calls)
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun paperRemainsMatteWithOpticsEnabledAndChangingArtworkBehindIt() {
        fun render(room: Color, dark: Boolean): Int {
            val scene = ImageComposeScene(200, 200) {
                FolioTheme.MaterialTheme(darkTheme = dark) {
                    FolioGlassRoot(GlassCapabilities(true, true, true)) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().folioRoomSource().background(room))
                            Box(Modifier.padding(20.dp).fillMaxSize().folioPaper(layered = true))
                        }
                    }
                }
            }
            try {
                repeat(4) { scene.render(it * 100_000_000L).close() }
                val image = scene.render(500_000_000L)
                try { return ImageIO.read(ByteArrayInputStream(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)).getRGB(100, 100) }
                finally { image.close() }
            } finally { scene.close() }
        }
        for (dark in listOf(false, true)) assertEquals(render(Color.Red, dark), render(Color.Blue, dark),
            "Matte paper unexpectedly refracted the room")
    }
}
