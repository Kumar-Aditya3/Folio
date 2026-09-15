package com.folio.reader

import com.folio.reader.ui.components.ReadingClimate
import com.folio.reader.ui.components.WeekForecast
import com.folio.reader.ui.components.lightFraction
import com.folio.reader.ui.components.phrase
import com.folio.reader.ui.components.readingClimate
import com.folio.reader.ui.components.weekForecast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reading weather: bands, phrases and the week forecast. Pure logic — the
 * same contract the Home ledger, anchor lighting and week well render from.
 */
class ReadingClimateTest {

    // ── Banding ────────────────────────────────────────────────────────────

    @Test
    fun noHistoryIsNoWeatherNotADrought() {
        assertNull(readingClimate(todayMinutes = 0, goalMinutes = 60, streakDays = 0, daysSinceLastRead = null))
    }

    @Test
    fun droughtTakesPrecedence() {
        // Three silent days is a drought even if the streak counter has not
        // caught up (a streak cannot survive it anyway).
        assertEquals(
            ReadingClimate.DROUGHT,
            readingClimate(todayMinutes = 0, goalMinutes = 60, streakDays = 5, daysSinceLastRead = 3),
        )
    }

    @Test
    fun goalMetIsWarm() {
        assertEquals(
            ReadingClimate.WARM,
            readingClimate(todayMinutes = 60, goalMinutes = 60, streakDays = 1, daysSinceLastRead = 0),
        )
    }

    @Test
    fun longStreakIsWarmEvenBeforeTheGoal() {
        assertEquals(
            ReadingClimate.WARM,
            readingClimate(todayMinutes = 10, goalMinutes = 60, streakDays = 3, daysSinceLastRead = 0),
        )
    }

    @Test
    fun oneDayStreakIsClear() {
        assertEquals(
            ReadingClimate.CLEAR,
            readingClimate(todayMinutes = 10, goalMinutes = 60, streakDays = 1, daysSinceLastRead = 0),
        )
    }

    @Test
    fun noStreakNoGoalMetIsFair() {
        assertEquals(
            ReadingClimate.FAIR,
            readingClimate(todayMinutes = 10, goalMinutes = 60, streakDays = 0, daysSinceLastRead = 1),
        )
    }

    @Test
    fun zeroGoalNeverManufacturesWarmth() {
        // No goal set: only the streak can warm the room.
        assertEquals(
            ReadingClimate.FAIR,
            readingClimate(todayMinutes = 120, goalMinutes = 0, streakDays = 0, daysSinceLastRead = 0),
        )
        assertEquals(
            ReadingClimate.WARM,
            readingClimate(todayMinutes = 120, goalMinutes = 0, streakDays = 3, daysSinceLastRead = 0),
        )
    }

    @Test
    fun lightFractionsAreMonotonicWarmToCold() {
        val warmToCold = listOf(
            ReadingClimate.WARM,
            ReadingClimate.CLEAR,
            ReadingClimate.FAIR,
            ReadingClimate.DROUGHT,
        ).map { it.lightFraction() }
        assertTrue(
            warmToCold.zipWithNext().all { pair: Pair<Float, Float> -> pair.first > pair.second },
            "light must fall monotonically warm→cold: $warmToCold",
        )
    }

    // ── Phrases ────────────────────────────────────────────────────────────

    @Test
    fun phrasesAreNonEmptyAndDistinguishBands() {
        val phrases = ReadingClimate.entries.map {
            it.phrase(streakDays = 2, daysSinceLastRead = 0)
        }
        assertTrue(phrases.all { it.isNotBlank() })
        assertEquals(ReadingClimate.entries.size, phrases.toSet().size)
    }

    @Test
    fun droughtPhraseNamesTheSilence() {
        val phrase = ReadingClimate.DROUGHT.phrase(streakDays = 0, daysSinceLastRead = 4)
        assertTrue("4 days" in phrase, "drought must say how long: $phrase")
    }

    // ── Week forecast ──────────────────────────────────────────────────────

    @Test
    fun forecastNeedsAGoal() {
        assertNull(weekForecast(listOf(60L, 60L, 60L, 60L), goalMinutes = 0))
    }

    @Test
    fun forecastNeedsEnoughDays() {
        assertNull(weekForecast(listOf(60L, 60L), goalMinutes = 30))
    }

    @Test
    fun forecastNeedsSomeReading() {
        assertNull(weekForecast(List(7) { 0L }, goalMinutes = 30))
    }

    @Test
    fun workingWeekIsOnPace() {
        // Five goal-sized days out of seven — the on-pace bar.
        val minutes = listOf(60L, 60L, 60L, 0L, 60L, 60L, 0L)
        val forecast = weekForecast(minutes, goalMinutes = 60)!!
        assertTrue(forecast.onPace)
    }

    @Test
    fun twoGoalDaysIsBehind() {
        val minutes = listOf(60L, 0L, 0L, 60L, 0L, 0L, 0L)
        val forecast = weekForecast(minutes, goalMinutes = 60)!!
        assertTrue(!forecast.onPace)
    }

    @Test
    fun trendComparesBackHalfToFrontHalf() {
        val rising = weekForecast(listOf(0L, 0L, 10L, 10L, 40L, 50L, 60L), goalMinutes = 30)!!
        assertTrue(rising.trendRising)
        val falling = weekForecast(listOf(60L, 50L, 40L, 10L, 10L, 0L, 0L), goalMinutes = 30)!!
        assertTrue(!falling.trendRising)
    }

    @Test
    fun forecastPhrasesCoverTheFourOutcomes() {
        val outcomes = listOf(
            WeekForecast(onPace = true, trendRising = true),
            WeekForecast(onPace = true, trendRising = false),
            WeekForecast(onPace = false, trendRising = true),
            WeekForecast(onPace = false, trendRising = false),
        ).map { it.phrase() }
        assertEquals(4, outcomes.toSet().size)
        assertTrue(outcomes.all { it.startsWith("Forecast:") })
    }
}
