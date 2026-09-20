package com.folio.reader.work

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the selector that clears a backfill row left poisoned by an older build.
 *
 * This has been wrong three times, each time in the same way — a selector that could not match the
 * row it existed to clear — so the arithmetic is asserted here rather than described in a comment:
 *
 *  1. It filtered `state == ENQUEUED`, but WorkManager reports queued work in an `APPEND` chain as
 *     `BLOCKED`, excluding the continuation rows it was meant to catch.
 *  2. It enumerated the three unique-work names the *current* source uses, but a row written by an
 *     older build can carry a name that no longer exists in the tree. Fixed by selecting on the
 *     tag, which every enqueue path in every build has set.
 *  3. It required `runAttemptCount > 20`, calibrated against the **linear 1-minute** backoff the
 *     current code declares. The row on the device was written by a build that omitted
 *     `setBackoffCriteria`, so it carried WorkManager's default **exponential 30-second** policy,
 *     under which a 3-hour delay is only ~10 attempts. The threshold silently excluded the entire
 *     population it was written for.
 *
 * The third is the one these tests guard, because it is the one a reader would not spot: the
 * comment said "20 sits well clear of legitimate behaviour" and that was true, and irrelevant.
 */
class EmbeddingBackfillRunawayTest {

    private val threshold = EmbeddingBackfillScheduler.runawayAttemptThresholdForTest()

    @Test
    fun `the threshold is above what the current worker can produce`() {
        // Below this, the repair would cancel a row that is legitimately retrying a transient
        // fault — throwing away a run the worker is entitled to make.
        val legitimateMax = EmbeddingBackfillWorker.maxErrorAttemptsForTest()
        assertTrue(
            threshold > legitimateMax,
            "threshold ($threshold) must exceed MAX_ERROR_ATTEMPTS ($legitimateMax), or the " +
                "repair cancels rows the current worker produced",
        )
    }

    @Test
    fun `the threshold catches an exponential-backoff row that is hours out`() {
        // The observed stale row: `Minimum latency: +3h7m15s837ms`, `initial=+30s0ms`, EXPONENTIAL.
        // WorkManager's exponential delay is `base * 2^(attempts-1)`, so the attempt count at which
        // a 30-second base reaches three hours is:
        //
        //   30000ms * 2^(n-1) = 11235000ms   ->   2^(n-1) = 374.5   ->   n ≈ 9.55
        //
        // i.e. about 10. A threshold of 20 — the value that was there — cannot match this, which is
        // why the row survived the repair on a real device.
        val base = 30_000.0
        val observedDelay = 3 * 3600_000.0 + 7 * 60_000.0 + 15_000.0
        val attempts = (kotlin.math.log2(observedDelay / base) + 1).toInt()
        assertTrue(
            attempts > threshold,
            "a row at $attempts attempts (the observed +3h7m at a 30s exponential base) must be " +
                "caught by a threshold of $threshold, or the repair is inert on exactly the row " +
                "the device showed",
        )
        // And the relationship stated positively, so the intent is legible without the arithmetic.
        assertTrue(threshold <= 12, "threshold must stay low enough for an exponential row: $threshold")
    }

    @Test
    fun `the threshold does not depend on the backoff policy`() {
        // A linear 1-minute base reaches 3 hours at ~180 attempts, and an exponential 30-second
        // base at ~10. A single threshold has to clear the *lower* of the two, because the point is
        // to catch rows written by unknown older builds whose policy cannot be read back from
        // `WorkInfo`. So the bound is stated against what our worker produces, not against a delay.
        val exponentialAttempts = (kotlin.math.log2((3 * 3600_000.0) / 30_000.0) + 1).toInt()
        val linearAttempts = (3 * 3600_000.0 / 60_000.0).toInt()
        assertTrue(
            exponentialAttempts < linearAttempts,
            "sanity: an exponential base must reach a given delay in far fewer attempts",
        )
        assertTrue(
            threshold > EmbeddingBackfillWorker.maxErrorAttemptsForTest() && threshold < exponentialAttempts,
            "the threshold must sit between the worker's legitimate maximum and the fewest " +
                "attempts an hours-long delay can be reached in",
        )
    }
}
