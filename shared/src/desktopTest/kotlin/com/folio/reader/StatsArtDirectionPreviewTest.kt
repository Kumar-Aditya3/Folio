package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.statistics.*
import com.folio.reader.ui.theme.*
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

/** Native layout review with real-shaped data, including long titles and large text. */
class StatsArtDirectionPreviewTest {
    private val year = (0..364).map { day -> StatDay(LocalDate.fromEpochDays(20_000 + day),
        if (day % 5 == 0 || day % 7 == 0) 0 else (day * 17L % 110L) + 1) }
    private val books = year.map { it.copy(minutes = it.minutes * 2 / 3) }
    private val manga = year.map { it.copy(minutes = it.minutes / 3) }
    private val state = StatisticsUiState(hasData = true, timeThisWeekMs = 14_100_000,
        timeThisYearMs = 532_800_000, streakDays = 12, longestStreakDays = 23,
        activeDaysThisWeek = 5, wordsReadThisYear = 1_240_500, averageSessionMinutes = 32.0,
        averageSpeedWpm = 245.0, mostReadDay = "Saturday", mostReadHour = "10 PM",
        chronotype = "The Night Owl", peakWindow = "9 PM–midnight", booksFinished = 14,
        sourceDevices = 2, week = year.takeLast(7), heatmap = year, heatmapBooks = books,
        heatmapManga = manga, todayMinutes = 24, longestSessionMinutes = 118,
        hourTotals = (0..23).map { if (it in 19..23) (it - 18) * 340L else if (it in 7..10) 180L else 0L },
        currentlyReading = listOf(ReadingInProgress("a", "Small Worlds", "A. Reader", .63f,
            "At this pace, about 3 more sittings"), ReadingInProgress("b", "The City & the City", "A. Reader", .28f,
            "About 6 more sittings")),
        readingTime = listOf(ReadingTimeEntry("a", "Small Worlds", "A. Reader", null, 370),
            ReadingTimeEntry("b", "The City & the City", "A. Reader", null, 190),
            ReadingTimeEntry("c", "A Very Long Manga Title That Wraps Across the Shelf", "A. Artist", null, 125, true)),
        readingTimeTotalMinutes = 725, readingTitlesOpened = 9, readingLibrarySize = 48,
        genres = listOf(TagSlice("Literary fiction", 240), TagSlice("Science fiction", 190), TagSlice("Manga", 125)))
    private val directory = File("build/visual-review/reading-trace/stats")

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun reviewOpenChartsLedgerAndFingerprint() {
        installFonts()
        for (id in listOf("editorial", "blush", "acid")) for (dark in listOf(false, true)) {
            for (section in listOf(0, 3, 5, 7)) render(id, dark, 393, 1f, section)
        }
        for ((id, dark) in listOf("ceramic" to false, "copper" to true))
            for (section in listOf(0, 3, 5, 7)) render(id, dark, 320, 1.4f, section)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(id: String, dark: Boolean, width: Int, scale: Float, section: Int) {
        val pack = ThemePack.ALL.first { it.id == id }
        val scene = ImageComposeScene(width, 1100) {
            FolioTheme.AppTheme(AppPalette.byId(pack.appPaletteId(dark))) {
                FolioGlassRoot(GlassCapabilities(true, true, true)) {
                    CompositionLocalProvider(LocalFolioPackId provides pack.id, LocalDensity provides Density(1f, scale)) {
                        Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                            StatisticsContent(state, rememberLazyListState(initialFirstVisibleItemIndex = section),
                                emptySet(), 60, {}, null, null,
                                listOf(RecentQuote("q", "Some places stay with you long after you have left them.", "Small Worlds", "a")),
                                {}, null, {}, null, {})
                        }
                    }
                }
            }
        }
        try {
            repeat(15) { scene.render(it * 150_000_000L).close(); Thread.sleep(10) }
            save(scene, "$id-$dark-$width-$section")
        } finally { scene.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun calendarStaysOpenAndFilterChangesItsActualActivity() {
        installFonts()
        val scene = ImageComposeScene(393, 500) {
            FolioTheme.AppTheme(AppPalette.DARK) {
                val prefs = MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED)
                CompositionLocalProvider(LocalMotionPreferences provides prefs,
                    LocalMotion provides resolveMotion(prefs, MotionTier.STANDARD, false)) {
                    Box(Modifier.fillMaxSize().background(Color(0xff142739))) {
                        ActivityHeatmap(year.takeLast(49), books.takeLast(49), manga.takeLast(49))
                    }
                }
            }
        }
        try {
            repeat(12) { scene.render(it * 150_000_000L).close() }
            val before = save(scene, "calendar-filter-all")
            // Native filter row, 48dp targets. The activity field has no tinted panel underneath it.
            val decoded = javax.imageio.ImageIO.read(before.inputStream())
            assertEquals(0xff142739.toInt(), decoded.getRGB(26, 110), "The calendar must retain the actual room behind it")
            scene.sendPointerEvent(PointerEventType.Press, Offset(214f, 84f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(214f, 84f))
            repeat(12) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(6_000_000_000L + it * 150_000_000L).close() }
            val after = save(scene, "calendar-filter-manga")
            assertFalse(before.contentEquals(after), "Changing the format must update the actual year marks")
        } finally { scene.close() }
    }

    private fun installFonts() {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun save(scene: ImageComposeScene, name: String): ByteArray {
        val image = scene.render()
        return try {
            val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            directory.mkdirs(); File(directory, "$name.png").writeBytes(bytes); bytes
        } finally { image.close() }
    }
}
