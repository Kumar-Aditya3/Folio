package com.folio.reader.ui.components

import com.folio.reader.model.ReadingSession
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.time.Duration.Companion.days

/**
 * How the reader's recent pace compares to their own longer-run baseline.
 *
 * Stats cards report numbers. A number on its own is inert: "412 wpm" is neither
 * good nor bad, fast nor slow, and the reader has no way to place it. What is
 * *legible* is a comparison — and the only honest thing to compare against is the
 * reader themselves, because a baseline borrowed from other people would make this
 * app a scoreboard instead of a mirror.
 *
 * So this compares a short recent window against a longer trailing window, and
 * returns a [ReadingTrend] only when the difference is large enough to be worth a
 * sentence. Everything else is null: a reader whose pace is steady should read no
 * trend line, not a trend line saying "steady". Silence is the default state, and
 * that is deliberate — a signal that fires every time is noise (Rule 18).
 */
data class ReadingTrend(
    /** Recent pace, in the same unit as [baselineValue]. */
    val recent: Double,
    /** The reader's own longer-run pace, the thing [recent] is measured against. */
    val baseline: Double,
    /** Signed percentage change, e.g. -32.0 for "slowed 32%". */
    val percentChange: Double,
) {
    /** True when the reader is going faster than their own baseline. */
    val isFaster: Boolean get() = percentChange > 0

    /** Absolute change, rounded, for the sentence ("32%"). */
    val magnitudePercent: Int get() = abs(percentChange).toInt()
}

/**
 * Sensitivity of the comparison, as a fraction. A change smaller than this is not
 * reported at all.
 *
 * 0.15 is a judgement call, not a measurement: day-to-day pace moves by ten percent
 * or so from book to book (dense literary fiction versus a thriller) without meaning
 * anything about the reader. A threshold under that would fire constantly and teach
 * the reader to ignore the line; well above it and the signal only ever shows up in
 * a crisis. Fifteen percent is roughly where a change becomes a *pattern* in the
 * data rather than a property of what they happen to be reading.
 */
private const val MEANINGFUL_CHANGE = 0.15

/**
 * Minimum recent-window pace, in words per day, before a comparison is offered.
 *
 * Shares [readingPaceWordsPerDay]'s credibility floor: below it the recent window is
 * too thin to call a pace at all, so comparing it to anything would be comparing
 * noise to a number and reading a trend into it.
 */
private const val MIN_RECENT_WPM = 100.0

/**
 * Words-per-day over the last [days] days, counting only sessions inside the window.
 *
 * The window is half-open — a session must have started strictly after `now - days`
 * — so "7 days" contains 7 days and not 8. The obvious `>=` spelling silently makes
 * every window one day too wide, which biases the recent window (short, so one extra
 * day is a large fraction) more than the baseline (long, so it barely moves). That
 * asymmetry invents a trend out of a steady reader, and the whole value of this
 * module is that it stays quiet when nothing has changed.
 *
 * The divisor is the window's *observed* span, not the nominal one: a reader with 30
 * days of history has no data for days 31–90, and dividing their words by 90 would
 * report them at a third of their real pace — the bug that makes a new reader look
 * like a slowing one. The span is clamped to the oldest session actually in the
 * window and never allowed above [days].
 *
 * Distinct from [readingPaceWordsPerDay], which is hardwired to a 7-day window: the
 * baseline here needs the *same* arithmetic over a *longer* span, so the window is a
 * parameter. Keeping the two next to each other is what stops them drifting into two
 * different definitions of "pace" — the failure this module exists to avoid at the
 * level of surfaces is just as bad at the level of functions.
 */
private fun wordsPerDay(sessions: List<ReadingSession>, days: Int, now: Instant): Double {
    val since = now - days.days
    val inWindow = sessions.filter { it.startedAt > since }
    if (inWindow.isEmpty()) return 0.0
    val oldest = inWindow.minOf { it.startedAt }
    // `+ 1` because a session that started 6 days ago is inside day 7 of the window,
    // counting that day rather than the moment it began (whole days, not hours).
    val observedDays = ((now - oldest).inWholeDays + 1).coerceIn(1L, days.toLong()).toDouble()
    return inWindow.sumOf { it.wordsRead } / observedDays
}

/**
 * Compare the reader's last week against their own trailing quarter.
 *
 * Returns null — the common case — when the comparison would not be informative:
 *
 * - fewer than [MIN_RECENT_WPM] words/day recently (no recent pace to speak of),
 * - no baseline yet (the reader has not been reading long enough to have one),
 * - a change under [MEANINGFUL_CHANGE] (this is just what they read, not how).
 *
 * @param sessions the reader's session history. Not required to be pre-sorted;
 *        it is filtered by timestamp, not sliced by position.
 * @param now injectable for tests.
 */
fun readingTrend(
    sessions: List<ReadingSession>,
    now: Instant = Clock.System.now(),
    recentDays: Int = 7,
    baselineDays: Int = 90,
): ReadingTrend? {
    if (sessions.isEmpty()) return null

    val recent = wordsPerDay(sessions, recentDays, now)
    if (recent < MIN_RECENT_WPM) return null

    // The baseline deliberately *includes* the recent window. Excluding it would
    // make the baseline unstable for a reader with only a few weeks of history,
    // and would turn a genuine step change into an ever-moving target: the more
    // the reader reads at the new pace, the more the baseline would follow them.
    // Including the recent week in a 90-day span moves it by at most ~8%, which is
    // under the reporting threshold anyway.
    val baseline = wordsPerDay(sessions, baselineDays, now)
    if (baseline < MIN_RECENT_WPM) return null

    val change = (recent - baseline) / baseline
    if (abs(change) < MEANINGFUL_CHANGE) return null

    return ReadingTrend(
        recent = recent,
        baseline = baseline,
        percentChange = change * 100.0,
    )
}

/**
 * The trend as a sentence, or null when there is nothing worth saying.
 *
 * Phrased as the reader's own movement rather than a verdict: "Reading 32% faster
 * than your usual pace" describes, it does not congratulate. There is no "great job"
 * here and no warning on the slow side — slowing down is a fact about a season of
 * someone's life, not a failure, and an app that scolds a reader for it is an app
 * they will stop opening.
 */
fun readingTrendSentence(trend: ReadingTrend?): String? = when {
    trend == null -> null
    trend.isFaster -> "Reading ${trend.magnitudePercent}% faster than your usual pace."
    else -> "Reading ${trend.magnitudePercent}% slower than your usual pace."
}
