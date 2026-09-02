package com.folio.reader

import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.components.finishEstimate
import com.folio.reader.ui.components.finishHorizon
import com.folio.reader.ui.components.readingPaceWordsPerDay
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * §5.1 guards: the shelf caption ([finishHorizon]) and the book-detail sentence
 * ([finishEstimate]) must derive from the same projection, and both must return
 * null rather than a placeholder when no projection is possible — the spec
 * forbids rendering an empty caption row.
 */
class ReadingPaceTest {

    private fun session(daysAgo: Long, words: Long, minutes: Long = 30): ReadingSession {
        val started = Clock.System.now() - daysAgo.days
        return ReadingSession(
            id = "s-$daysAgo-$words-$minutes",
            bookId = "book-1",
            cycleId = null,
            deviceId = "test-device",
            startedAt = started,
            endedAt = started,
            durationMs = minutes * 60_000L,
            startPosition = ReadingPosition(
                bookId = "book-1",
                deviceId = "test-device",
                chapterId = "c0",
                spineIndex = 0,
                contentLocator = ""
            ),
            wordsRead = words,
            isActive = false
        )
    }

    @Test
    fun `pace is null below the credibility floor`() {
        // 50 words across the 7-day window is ~7 words/day, under the 100 floor.
        assertNull(readingPaceWordsPerDay(listOf(session(1, 50))))
    }

    @Test
    fun `pace averages the trailing week`() {
        val pace = readingPaceWordsPerDay(listOf(session(1, 3_500), session(2, 3_500)))
        assertNotNull(pace)
        assertEquals(1_000.0, pace, 0.001)
    }

    @Test
    fun `sessions older than the window do not count toward pace`() {
        assertNull(readingPaceWordsPerDay(listOf(session(30, 100_000))))
    }

    @Test
    fun `horizon and estimate agree on the same projection`() {
        val sessions = listOf(session(1, 3_500), session(2, 3_500))
        val horizon = finishHorizon(50_000, 0.5, sessions, sessions)
        val estimate = finishEstimate(50_000, 0.5, sessions, sessions)
        assertNotNull(horizon)
        assertNotNull(estimate)
        // 25k words left at 1000/day = 25 days; both forms must say so.
        assertEquals("~25 days left", horizon)
        assertTrue(
            estimate.contains("~25 days"),
            "estimate must reuse the horizon phrase, was: $estimate"
        )
    }

    @Test
    fun `no projection for an unstarted book`() {
        val sessions = listOf(session(1, 3_500))
        assertNull(finishHorizon(50_000, 0.0, sessions, sessions))
        assertNull(finishEstimate(50_000, 0.0, sessions, sessions))
    }

    @Test
    fun `no projection for a finished book`() {
        val sessions = listOf(session(1, 3_500))
        assertNull(finishHorizon(50_000, 1.0, sessions, sessions))
        assertNull(finishEstimate(50_000, 1.0, sessions, sessions))
    }

    @Test
    fun `no projection without any session history`() {
        assertNull(finishHorizon(50_000, 0.4, emptyList(), emptyList()))
        assertNull(finishEstimate(50_000, 0.4, emptyList(), emptyList()))
    }

    @Test
    fun `long horizons are phrased in months or years, never hidden`() {
        // A modest pace against a barely-started book projects far out; the spec
        // requires the estimate to render rather than blank.
        val slow = listOf(session(1, 3_500))
        val horizon = finishHorizon(500_000, 0.01, slow, slow)
        assertNotNull(horizon)
        assertTrue(
            horizon.contains("month") || horizon.contains("year"),
            "long horizon must be phrased in months/years, was: $horizon"
        )
    }

    @Test
    fun `books without a word count fall back to a time-based projection`() {
        // No totalWords, but real measured time: the fallback still projects.
        val sessions = listOf(session(1, 0, minutes = 60), session(2, 0, minutes = 60))
        val horizon = finishHorizon(0, 0.5, sessions, sessions)
        assertNotNull(horizon)
        assertTrue(horizon.endsWith("left"), "was: $horizon")
    }
}
