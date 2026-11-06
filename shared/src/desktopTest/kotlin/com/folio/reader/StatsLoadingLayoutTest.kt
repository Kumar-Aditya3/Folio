package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.statistics.Scope
import com.folio.reader.ui.statistics.*
import com.folio.reader.ui.theme.*
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class StatsLoadingLayoutTest {
    private val week = (0..6).map {
        StatDay(LocalDate.fromEpochDays(LocalDate(2026, 10, 26).toEpochDays() + it), 15L + it * 7)
    }
    private val stats = StatisticsUiState(hasData = true, timeThisWeekMs = 14_100_000,
        timeThisYearMs = 532_800_000, streakDays = 12, longestStreakDays = 23,
        activeDaysThisWeek = 5, wordsReadThisYear = 1_240_500, booksFinished = 14,
        todayMinutes = 24, chronotype = "The Night Owl", peakWindow = "9 PM–midnight",
        week = week, heatmap = week, heatmapBooks = week, heatmapManga = week)

    @Test fun loadingAndLoadedSectionsKeepTheirPositionsAcrossThemesAndLargeText() {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
        for (palette in listOf(AppPalette.WARM, AppPalette.byId("copper"))) {
            for ((width, scale) in listOf(393 to 1f, 320 to 1.4f)) {
                for (excluded in listOf(false, true)) check(palette, width, scale, excluded)
            }
        }
    }

    private fun check(palette: AppPalette, width: Int, scale: Float, excluded: Boolean) {
        val loading = mutableStateOf(true)
        val motion = MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED)
        val scene = ImageComposeScene(width, 1700) {
            FolioTheme.AppTheme(palette, daylight = FolioDaylight.Neutral) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale),
                    LocalFolioTopInset provides 74.dp, LocalFolioBarInset provides 92.dp,
                    LocalMotionPreferences provides motion,
                    LocalMotion provides resolveMotion(motion, MotionTier.STANDARD, false)) {
                    Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                        if (loading.value) StatsSkeleton(onOpenExclusions = if (excluded) ({}) else null)
                        else StatisticsContent(stats, rememberLazyListState(),
                            if (excluded) setOf(Scope.BOOK to "excluded") else emptySet(),
                            60, {}, null, null, emptyList(), {}, if (excluded) ({}) else null, {}, null, {})
                    }
                }
            }
        }
        var frame = 0L
        fun settle() = repeat(16) {
            Snapshot.sendApplyNotifications()
            scene.render(frame++ * 150_000_000L).close()
        }
        fun headings(): Map<String, Rect> = scene.semanticsOwners
            .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            .mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                if (text in listOf("READING THIS WEEK", "STREAK", "ACTIVE DAYS", "THIS YEAR", "FINISHED", "Last 7 days", "Your year"))
                    text!! to node.boundsInRoot else null
            }.toMap()
        fun save(suffix: String) {
            val image = scene.render(frame++ * 150_000_000L)
            try {
                val out = File("build/visual-review/stats-loading").apply { mkdirs() }
                File(out, "${palette.id}-$width-$scale-$excluded-$suffix.png")
                    .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { image.close() }
        }
        try {
            settle()
            val before = headings()
            assertTrue(before.size == 7, "Loading must include the visible year calendar: $before")
            val spoken = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
            assertFalse(spoken.contains("00"), "Measuring placeholders must not be announced")
            assertFalse(spoken.contains("The Night Owl"), "Loading must not announce a guessed reading rhythm")
            save("loading")
            loading.value = false
            settle()
            val after = headings()
            for ((label, bounds) in before) {
                val loaded = requireNotNull(after[label]) { "Missing loaded $label" }
                assertTrue(abs(bounds.left - loaded.left) <= 1f && abs(bounds.top - loaded.top) <= 1f,
                    "$label moved at ${palette.id}, $width, fontScale=$scale, exclusions=$excluded: $bounds -> $loaded")
            }
            save("loaded")
        } finally { scene.close() }
    }
}
