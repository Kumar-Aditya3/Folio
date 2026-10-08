package com.folio.reader.ml

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The arithmetic of the sweep's rate gate, with no sweep, no model and no device.
 *
 * [SweepPace.pauseMsFor] is the whole policy, and the two ends of it are both mistakes the feature
 * has already made once: a threshold that can never be reached is a throttle that is decorative
 * (the flat 256 MB headroom floor that never fired), and a threshold that fires on a merely-busy
 * device is a stall (the 0.35 fraction that made indexing crawl). So both bounds are asserted here
 * against the pass durations actually measured on the test device, ~15-100 ms.
 */
class SweepPaceTest {

    @Test
    fun `a sweep the reader is not watching never pauses`() {
        // The away case must be exactly zero, not merely small: away is the state the width gate
        // made safe, and the reader's "it only works when I'm on the app" report is the evidence
        // that pausing here would be a stall rather than a protection.
        assertEquals(0L, SweepPace.pauseMsFor(passMs = 100L, readerInApp = false))
        assertEquals(0L, SweepPace.pauseMsFor(passMs = 0L, readerInApp = false))
    }

    @Test
    fun `an in-app pass is paid back at one for one`() {
        // 50% duty: the sweep holds its cores for as long as it worked, then lets go for the same
        // again. Anything tighter is a throughput cut the reader did not ask for; anything looser
        // stops being a gap the UI can use.
        assertEquals(15L, SweepPace.pauseMsFor(passMs = 15L, readerInApp = true))
        assertEquals(100L, SweepPace.pauseMsFor(passMs = 100L, readerInApp = true))
    }

    @Test
    fun `an instant pass still opens a usable gap`() {
        // Below the scheduler's granularity a sleep re-runs the same thread before anything else
        // gets a turn, so a floor is what stops a fast device pausing into a no-op.
        assertEquals(SweepPace.MIN_PAUSE_MS, SweepPace.pauseMsFor(passMs = 0L, readerInApp = true))
        assertEquals(SweepPace.MIN_PAUSE_MS, SweepPace.pauseMsFor(passMs = 1L, readerInApp = true))
        assertTrue(
            SweepPace.MIN_PAUSE_MS <= 15L,
            "the floor must stay under a real pass, or the fast end of the sweep is unthrottled",
        )
    }

    @Test
    fun `a runaway pass cannot stall a slice`() {
        assertEquals(SweepPace.MAX_PAUSE_MS, SweepPace.pauseMsFor(passMs = 60_000L, readerInApp = true))
        assertTrue(
            SweepPace.MAX_PAUSE_MS > 100L,
            "the ceiling has to sit above a normal pass — below it, the slowest devices get the " +
                "shortest gap, which is the opposite of what the gate is for",
        )
        assertTrue(
            SweepPace.MAX_PAUSE_MS * 100 < 90_000L,
            "one pause must not spend a large share of the 90 s slice budget sleeping",
        )
    }

    @Test
    fun `the pause never goes negative and never shrinks as passes slow down`() {
        // A clock that jumped backwards would otherwise ask for a negative sleep.
        assertEquals(SweepPace.MIN_PAUSE_MS, SweepPace.pauseMsFor(passMs = -5_000L, readerInApp = true))
        val passes = listOf(0L, 1L, 8L, 16L, 64L, 128L, 512L, 4_096L)
        val pauses = passes.map { SweepPace.pauseMsFor(it, readerInApp = true) }
        assertEquals(pauses.sorted(), pauses, "a longer pass may never get a shorter pause")
    }

    @Test
    fun `None never sleeps and still hands back the pass result`() = runBlocking {
        var slept = 0L
        val pace = SweepPace(readerInApp = { false }, sleep = { slept += it })
        assertEquals(42, pace.embedPass { 42 })
        assertEquals(0L, slept)
        assertEquals(0L, SweepPace.pauseMsFor(100L, readerInApp = false))
    }

    @Test
    fun `every paced pass takes exactly one pause`() = runBlocking {
        val pauses = mutableListOf<Long>()
        val pace = SweepPace(readerInApp = { true }, sleep = { pauses.add(it) })
        repeat(5) { pace.embedPass { it } }
        assertEquals(5, pauses.size, "one pause per pass — none skipped, none doubled")
        assertTrue(pauses.all { it >= SweepPace.MIN_PAUSE_MS })
    }

    @Test
    fun `a pass that throws unwinds without pausing`() {
        // There is no `finally` sleep on purpose: a throwing pass is already unwinding its flush,
        // and `flushGroups` answers the failure by re-embedding, not by waiting. `SweepPace` must
        // not swallow or delay the exception the failure attribution depends on.
        var slept = 0L
        val pace = SweepPace(readerInApp = { true }, sleep = { slept += it })
        assertFailsWith<IllegalStateException> {
            runBlocking { pace.embedPass { throw IllegalStateException("simulated forward-pass failure") } }
        }
        assertEquals(0L, slept)
    }
}
