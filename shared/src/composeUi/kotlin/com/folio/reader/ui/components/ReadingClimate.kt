package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.folio.reader.ui.theme.FolioTheme

/**
 * The reading weather.
 *
 * Home reads like a room, and a room has a climate: a reader tearing through a
 * streak is in a warm spell, a reader two quiet days deep is in fair weather,
 * and a week untouched is a drought. The bands below turn the same numbers the
 * ledger already shows — today's minutes, the goal, the streak — into that
 * weather, so the anchor's lighting, the ledger's mark and the week well's
 * forecast all speak one meteorological dialect instead of three separate
 * statistics.
 *
 * Pure on purpose: the banding and phrasing are unit-testable without Compose;
 * only [tint] reads the live palette.
 */

/** The four bands, warm to cold. */
enum class ReadingClimate { WARM, CLEAR, FAIR, DROUGHT }

/** Days of silence before the weather turns. */
private const val DROUGHT_DAYS = 3

/** A streak this long is warm regardless of today's goal state. */
private const val WARM_STREAK_DAYS = 3

/** A working week's worth of goal-sized days counts as on pace. */
private const val ON_PACE_DAYS = 5L

/**
 * Today's band, or null when there is no session history at all — a fresh
 * reader has no weather yet, not a drought. [daysSinceLastRead] is 0 when a
 * sitting happened today; drought takes precedence over everything because a
 * streak cannot survive it anyway.
 */
fun readingClimate(
    todayMinutes: Long,
    goalMinutes: Int,
    streakDays: Int,
    daysSinceLastRead: Int?,
): ReadingClimate? {
    if (daysSinceLastRead == null) return null
    return when {
        daysSinceLastRead >= DROUGHT_DAYS -> ReadingClimate.DROUGHT
        goalMinutes > 0 && todayMinutes >= goalMinutes -> ReadingClimate.WARM
        streakDays >= WARM_STREAK_DAYS -> ReadingClimate.WARM
        streakDays >= 1 -> ReadingClimate.CLEAR
        else -> ReadingClimate.FAIR
    }
}

/**
 * How brightly the anchor's climate gradient burns. The plane's default light
 * is 0.30f (§13.3's calibrated hero tint); warm spells lift it and droughts
 * dim it, so the room itself reports the weather before any words do.
 */
fun ReadingClimate.lightFraction(): Float = when (this) {
    ReadingClimate.WARM -> 0.38f
    ReadingClimate.CLEAR -> 0.30f
    ReadingClimate.FAIR -> 0.22f
    ReadingClimate.DROUGHT -> 0.13f
}

/** The weather's own hue, from the palette's streak/progress accents. */
@Composable
fun ReadingClimate.tint(): Color {
    val colors = FolioTheme.colors
    return when (this) {
        ReadingClimate.WARM -> colors.accentStreak
        ReadingClimate.CLEAR -> colors.accentProgress
        ReadingClimate.FAIR -> lerp(colors.accentProgress, colors.onSurfaceVariant, 0.45f)
        ReadingClimate.DROUGHT -> lerp(colors.accentProgress, colors.onSurfaceVariant, 0.70f)
    }
}

/**
 * The ledger's weather line. [streakDays] and [daysSinceLastRead] rephrase the
 * same numbers the figures above already state, in the weather's voice.
 */
fun ReadingClimate.phrase(streakDays: Int, daysSinceLastRead: Int): String = when (this) {
    ReadingClimate.WARM ->
        if (streakDays >= WARM_STREAK_DAYS) "Warm spell · $streakDays-day streak"
        else "Warm spell · goal met"
    ReadingClimate.CLEAR ->
        if (daysSinceLastRead <= 0) "Clear · read today"
        else "Clear · read yesterday"
    ReadingClimate.FAIR -> "Fair · no streak going"
    ReadingClimate.DROUGHT ->
        "Dry spell · $daysSinceLastRead days since your last sitting"
}

/**
 * The week well's forecast. [onPace] compares the trailing week's total against
 * five goal-sized days — a working week, not seven, because a pace that demands
 * the goal every single day calls every honest week "behind". [trendRising]
 * compares the back half of the window against the front.
 */
data class WeekForecast(
    val onPace: Boolean,
    val trendRising: Boolean,
)

/**
 * Null when there is nothing to say: no goal set, too few days, or a window
 * with no reading at all (a forecast for silence would be confident nonsense).
 */
fun weekForecast(minutesPerDay: List<Long>, goalMinutes: Int): WeekForecast? {
    if (goalMinutes <= 0 || minutesPerDay.size < 4) return null
    if (minutesPerDay.all { it == 0L }) return null
    val onPace = minutesPerDay.sum() >= goalMinutes * ON_PACE_DAYS
    val half = minutesPerDay.size / 2
    val trendRising =
        minutesPerDay.drop(half).average() > minutesPerDay.take(half).average()
    return WeekForecast(onPace, trendRising)
}

/** The forecast line, in the same dialect as [ReadingClimate.phrase]. */
fun WeekForecast.phrase(): String = when {
    onPace && trendRising -> "Forecast: warming up"
    onPace -> "Forecast: steady week ahead"
    trendRising -> "Forecast: behind, but rising"
    else -> "Forecast: cooling off"
}
