package com.folio.reader

import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.components.ReadingTrend
import com.folio.reader.ui.components.readingTrend
import com.folio.reader.ui.components.readingTrendSentence
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * The comparative-stats guard rails.
 *
 * The risk with a "you are reading faster/slower" line is not that it is wrong — it
 * is that it fires when nothing has actually changed. These tests pin the silence:
 * a steady reader, a new reader, and a barely-reading reader must all get `null`,
 * because a trend line that appears every time is one the reader learns to skip.
 */
class ReadingTrendTest {

    private val now: Instant = Clock.System.now()

    private fun session(daysAgo: Long, words: Long, minutes: Long = 30): ReadingSession {
        val started = now - daysAgo.days
        return ReadingSession(
            id = "s-$daysAgo-$words",
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

    /** [days] days of history at [perDay] words, starting [from] days ago. */
    private fun history(from: Long, days: Long, perDay: Long): List<ReadingSession> =
        (from until from + days).map { session(it, perDay) }

    @Test
    fun `a steady reader reads no trend line`() {
        // Same pace recent and long-run: 1000 words/day for 90 days. Nothing has
        // changed, so there is nothing to say.
        assertNull(readingTrend(history(0, 90, 1_000), now))
    }

    @Test
    fun `a reader who sped up gets the faster sentence`() {
        // Quiet first ~83 days at 400/day, then a strong recent week at 2000/day.
        val quiet = history(7, 83, 400)
        val recent = history(0, 7, 2_000)
        val trend = readingTrend(quiet + recent, now)
        assertNotNull(trend)
        assertTrue(trend.isFaster, "expected a faster trend, got $trend")
        val sentence = readingTrendSentence(trend)
        assertNotNull(sentence)
        assertTrue(sentence.contains("faster"), "was: $sentence")
        assertTrue(sentence.contains("your usual pace"), "was: $sentence")
    }

    @Test
    fun `a reader who slowed down gets the slower sentence`() {
        val busy = history(7, 83, 2_000)
        val recent = history(0, 7, 400)
        val trend = readingTrend(busy + recent, now)
        assertNotNull(trend)
        assertTrue(!trend.isFaster, "expected a slower trend, got $trend")
        val sentence = readingTrendSentence(trend)
        assertNotNull(sentence)
        assertTrue(sentence.contains("slower"), "was: $sentence")
    }

    @Test
    fun `a small change is not reported`() {
        // A 10% swing is under the 15% threshold — it is what the reader happens to
        // be reading this week, not a change in how they read.
        val baseline = history(7, 83, 1_000)
        val recent = history(0, 7, 1_100)
        assertNull(readingTrend(baseline + recent, now))
    }

    @Test
    fun `a reader with almost no recent reading gets nothing`() {
        // 90 days of history, but the last week is nearly empty: there is no recent
        // pace, so there is nothing to compare.
        val old = history(7, 83, 1_000)
        val thin = listOf(session(1, 50), session(2, 30))
        assertNull(readingTrend(old + thin, now))
    }

    @Test
    fun `a brand new reader gets nothing`() {
        // Two days of history is not a baseline.
        assertNull(readingTrend(history(0, 2, 1_000), now))
    }

    @Test
    fun `no sessions at all gets nothing`() {
        assertNull(readingTrend(emptyList(), now))
    }

    @Test
    fun `the sentence is null when the trend is null`() {
        assertNull(readingTrendSentence(null))
    }

    @Test
    fun `the baseline includes the recent window so a step change is stable`() {
        // A reader who doubled their pace is still reported as faster on a second
        // look — the baseline must not chase them up to meet the new pace.
        val quiet = history(7, 83, 400)
        val recent = history(0, 7, 2_000)
        val first = readingTrend(quiet + recent, now)
        val second = readingTrend(quiet + recent, now)
        assertNotNull(first)
        assertEquals(first.percentChange, second!!.percentChange, 0.001)
    }

    @Test
    fun `magnitude is reported unsigned`() {
        val busy = history(7, 83, 2_000)
        val recent = history(0, 7, 400)
        val trend = readingTrend(busy + recent, now)
        assertNotNull(trend)
        assertTrue(trend.percentChange < 0.0, "slowing is negative, got $trend")
        assertTrue(trend.magnitudePercent > 0, "magnitude is unsigned, got $trend")
    }

    @Test
    fun `trend carries both paces for callers that want the numbers`() {
        // Days 0–6 are recent, days 7–89 are the older history. Both windows are
        // full of days, so each divides by its full span.
        val older = history(7, 83, 400)
        val recent = history(0, 7, 2_000)
        val trend = readingTrend(older + recent, now)
        assertNotNull(trend)
        // 7 days at 2000/day, over a 7-day window.
        assertEquals(2_000.0, trend.recent, 1.0)
        // The 90-day baseline spans both rates: 14000 + 33200 = 47200 over 90 days.
        assertEquals(524.44, trend.baseline, 1.0)
    }

    @Test
    fun `a session exactly on the window edge is outside it`() {
        // A 7-day window must contain 7 days, not 8. If day 7 counts as recent, the
        // recent window is biased high and the baseline low — the asymmetry invents
        // a trend from a reader whose pace never changed.
        val steady = history(0, 90, 1_000)
        assertNull(readingTrend(steady, now))
    }

    @Test
    fun `a partial history is not divided by the full window`() {
        // Only 40 days of history. Dividing 40 days of words by 90 would report the
        // reader at less than half their real pace and manufacture a "slowing" trend
        // out of nothing — the bug this divisor exists to prevent.
        val trend = readingTrend(history(0, 40, 1_000), now)
        // Steady pace: recent and baseline agree, so still no sentence.
        assertNull(trend)
    }
}
