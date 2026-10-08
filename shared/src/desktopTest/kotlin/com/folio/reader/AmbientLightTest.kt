package com.folio.reader

import androidx.compose.ui.geometry.Size
import com.folio.reader.ui.components.daylightGradient
import com.folio.reader.ui.theme.AMBIENT_DRIFT_MAX
import com.folio.reader.ui.theme.AMBIENT_PERIODS_MS
import com.folio.reader.ui.theme.AMBIENT_SWING_MAX
import com.folio.reader.ui.theme.FolioAmbient
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.SLOW_MOTION_TICK_MS
import com.folio.reader.ui.theme.TWO_PI
import com.folio.reader.ui.theme.ambientAzimuth
import com.folio.reader.ui.theme.ambientPoolDrift
import com.folio.reader.ui.theme.daylightAt
import com.folio.reader.ui.theme.slowPhaseAt
import com.folio.reader.ui.theme.swungBy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17 living glass — the ambient light's subtlety envelope and identity, pinned
 * the way DaylightTest pins daylight's own. The whole point of the layer is that
 * it is *below notice*: these tests are what keep it that way as it evolves.
 */
class AmbientLightTest {

    @Test
    fun neutralIsTheIdentity() {
        // The still room must leave every consumer byte-for-byte: an un-provided
        // tree (desktop, previews, unit tests) renders today's look exactly.
        for (hour in 0 until 24) {
            val daylight = daylightAt(hour, 17)
            val swung = daylight.swungBy(FolioAmbient.Neutral)
            assertEquals(daylight, swung, "neutral ambient changed hour $hour")
        }
    }

    @Test
    fun swingIsCeilingedAndFollowsTheRoom() {
        // Full swing may add at most AMBIENT_SWING_MAX of azimuth, and only in
        // proportion to daylight intensity — the ambient inherits the night
        // discipline instead of inventing a midnight sun.
        for (hour in 0 until 24) {
            val daylight = daylightAt(hour, 0)
            val swung = daylight.swungBy(FolioAmbient(swing = 1f, breath = 0.5f))
            val delta = swung.azimuth - daylight.azimuth
            val ceiling = AMBIENT_SWING_MAX * daylight.intensity
            assertTrue(
                abs(delta) <= ceiling + 1e-5f,
                "hour $hour: swing delta $delta exceeded its ceiling $ceiling",
            )
            // And the ceiling is real, not vestigial: at full intensity the light
            // genuinely travels (a layer that can have no effect is a bug —
            // Rule 19's spirit).
            if (daylight.intensity > 0.9f) {
                assertTrue(abs(delta) > AMBIENT_SWING_MAX * 0.9f, "hour $hour: swing is vestigial")
            }
            // Colour and the rest of the day pass through untouched: the ambient
            // moves the light, it is not a second one.
            assertEquals(daylight.temperature, swung.temperature, "hour $hour: temperature moved")
            assertEquals(daylight.intensity, swung.intensity, "hour $hour: intensity moved")
        }
    }

    @Test
    fun poolDriftIsCeilinged() {
        for (swing in listOf(-1f, -0.4f, 0f, 0.37f, 1f)) {
            for (breath in listOf(0f, 0.25f, 0.5f, 0.8f, 1f)) {
                val (dx, dy) = ambientPoolDrift(FolioAmbient(swing, breath))
                assertTrue(abs(dx) <= AMBIENT_DRIFT_MAX + 1e-5f, "dx $dx past the ceiling")
                assertTrue(abs(dy) <= AMBIENT_DRIFT_MAX + 1e-5f, "dy $dy past the ceiling")
            }
        }
        // The identity is exactly zero displacement, not displacement to an edge.
        val (nx, ny) = ambientPoolDrift(FolioAmbient.Neutral)
        assertEquals(0f, nx)
        assertEquals(0f, ny)
    }

    @Test
    fun gradientAxisIsNeutralExact() {
        // daylightGradient's default must be the byte-for-byte axis the materials
        // always had — the overload with an explicit neutral equals the legacy
        // call with no ambient at all.
        val daylight = daylightAt(9, 30)
        val size = Size(540f, 1200f)
        for (hour in 0 until 24 step 3) {
            val d = daylightAt(hour, 0)
            assertEquals(
                daylightGradient(size, d),
                daylightGradient(size, d, FolioAmbient.Neutral),
                "hour $hour: neutral ambient moved the gradient axis",
            )
        }
        // And a swung ambient moves it — the one observable this whole layer
        // exists for.
        val still = daylightGradient(size, daylight)
        val swung = daylightGradient(size, daylight, FolioAmbient(swing = 1f, breath = 0.5f))
        assertTrue(still != swung, "full swing did not move the gradient axis")
    }

    @Test
    fun oscillatorsAreSlowAndNeverBeat() {
        // Past ~45s a moving light stops reading as animation and starts reading
        // as a room; distinct periods mean the swing and breath never sync into
        // a visible repeating pattern.
        for (period in AMBIENT_PERIODS_MS) {
            assertTrue(period in 30_000..120_000L, "period $period ms is outside the slow band")
        }
        assertEquals(
            2,
            AMBIENT_PERIODS_MS.distinct().size,
            "the oscillators share a period, so the room pulses",
        )
    }

    @Test
    fun phaseCompletesExactlyOneTurnPerPeriod() {
        // The ramp §17 specified: `tween(periodMs, LinearEasing)` from 0 to 2π, so
        // the phase is the elapsed fraction of a turn and wraps once per period.
        for (period in AMBIENT_PERIODS_MS) {
            assertEquals(0f, slowPhaseAt(0L, period), "period $period: phase does not start at 0")
            assertEquals(
                (PI).toFloat(),
                slowPhaseAt(period / 2, period),
                "period $period: half a period is not half a turn",
            )
            assertEquals(
                (PI / 2).toFloat(),
                slowPhaseAt(period / 4, period),
                "period $period: a quarter period is not a quarter turn",
            )
            // Whole periods wrap to the start, not past it.
            for (turns in 1L..3L) {
                assertEquals(
                    0f,
                    slowPhaseAt(period * turns, period),
                    "period $period: turn $turns did not wrap to 0",
                )
            }
        }
    }

    @Test
    fun slowClockTicksAdvanceThePhaseByASmallFixedStep() {
        // The regression this file was missing. `withFrameNanos` reports
        // *nanoseconds* while every period here is *milliseconds*; feeding the raw
        // value to the modulo made it wrap every 47µs instead of every 47s, so
        // each tick landed on an arbitrary point of the cycle and the light
        // strobed instead of drifting. Pin the step, and pin that it is small.
        for (period in AMBIENT_PERIODS_MS) {
            val expectedStep = TWO_PI * SLOW_MOTION_TICK_MS.toFloat() / period.toFloat()
            assertTrue(
                expectedStep < 0.02f,
                "period $period: a ${SLOW_MOTION_TICK_MS}ms tick is $expectedStep rad, too big a step",
            )

            var previous = slowPhaseAt(0L, period)
            for (tick in 1L..20L) {
                val elapsed = tick * SLOW_MOTION_TICK_MS
                val phase = slowPhaseAt(elapsed, period)
                val step = phase - previous
                // Same small step every tick: no jump, no strobe, no catch-up.
                assertTrue(
                    abs(step - expectedStep) < 1e-4f,
                    "period $period tick $tick: step $step != expected $expectedStep",
                )
                // And in particular the phase moved forward, never backwards or
                // somewhere unrelated on the circle.
                assertTrue(step > 0f, "period $period tick $tick: phase went backwards ($step)")
                previous = phase
            }

            // Sanity on the failure mode itself: the raw nanosecond value that
            // caused the bug must NOT be a valid millisecond elapsed time. If this
            // ever stops holding, the units are no longer the trap they were.
            val oneTickInNanos = SLOW_MOTION_TICK_MS * 1_000_000L
            assertTrue(
                abs(slowPhaseAt(oneTickInNanos, period) - expectedStep) > 0.1f,
                "period $period: nanosecond input is no longer distinguishable — update this test",
            )
        }
    }

    @Test
    fun slowClockNeverHoldsTheFrameLoopOpen() {
        // The whole reason the clock exists: re-sampling a 47s light per vsync
        // invalidated every full-screen surface every frame and the app never
        // idled (measured: ~118 frames / 4s at rest, zero with motion off). So the
        // tick must stay coarse enough to be worth the swap, and still fine enough
        // that no cycle shows its steps.
        assertTrue(
            SLOW_MOTION_TICK_MS >= 50L,
            "a ${SLOW_MOTION_TICK_MS}ms tick is not coarse enough to let the frame loop idle",
        )
        for (period in AMBIENT_PERIODS_MS) {
            val stepsPerCycle = period.toFloat() / SLOW_MOTION_TICK_MS.toFloat()
            assertTrue(stepsPerCycle >= 100f, "period $period: only $stepsPerCycle steps per cycle")
        }
    }
}
