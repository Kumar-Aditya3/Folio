package com.folio.reader

import com.folio.reader.ui.theme.FOLIO_FIELD_UNIFIED
import com.folio.reader.ui.theme.FolioFieldCost
import com.folio.reader.ui.theme.FolioFieldKind
import com.folio.reader.ui.theme.SLOW_MOTION_TICK_MS
import com.folio.reader.ui.theme.fieldRenderScale
import com.folio.reader.ui.theme.slowTickDelayMs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ground plane's per-frame pixel budget, as a contract.
 *
 * The field is the one layer in the app whose clock is **not** the app's: Mirage drives it from
 * `withFrameNanos` inside the library, so it repaints a full-screen AGSL pass at the display's
 * refresh rate and no call-site parameter can slow it (`startFrame` is seeded by an unkeyed
 * `remember`; `speed = 0` freezes the field outright rather than pacing it). The only lever left
 * is *area*, which is what [fieldRenderScale] spends — so these tests pin the table, because a
 * family that silently returns to 1:1 is a regression nobody would see until a device drops
 * frames.
 */
class FolioFieldCostTest {

    @Test
    fun `every family has a budget inside the allowed band`() {
        // The `when` in fieldRenderScale is exhaustive, so a new family cannot compile without a
        // budget; this pins that the budget is a *usable* one rather than, say, 0.
        for (kind in FolioFieldKind.entries) {
            val scale = kind.fieldRenderScale()
            assertTrue(
                scale >= FolioFieldCost.minScale && scale <= 1f,
                "$kind renders at $scale, outside ${FolioFieldCost.minScale}..1",
            )
        }
    }

    @Test
    fun `device-pixel-pitched families are never downsampled`() {
        // Dithering pixelises straight off fragCoord (`pxSize = u_pxSize * u_pixelRatio`) at
        // size = 2f — a two-device-pixel Bayer cell — and DotGrid pitches its dots on
        // gapX/gapY = 34f through getPatternUV. Downsampling either moves the design rather than
        // softening it, so both must stay 1:1 however the other tiers are tuned.
        assertEquals(1f, FolioFieldKind.Dithering.fieldRenderScale(), "Dithering")
        assertEquals(1f, FolioFieldKind.DotGrid.fieldRenderScale(), "DotGrid")
    }

    @Test
    fun `the families the lag report named are actually downsampled`() {
        // "Perlin cloud, grain gradient … add more to the lag". Each must be paying strictly
        // less than full resolution, or the fix did not reach the report. (Paper texture was
        // the report's headline; the family is removed outright — see MeshField.android.kt.)
        for (kind in listOf(
            FolioFieldKind.PerlinNoise,
            FolioFieldKind.GrainGradient,
        )) {
            assertTrue(kind.fieldRenderScale() < 1f, "$kind is still rendered 1:1")
        }
    }

    @Test
    fun `the unified default stays within the half-resolution budget`() {
        // DotOrbit carries a pattern-space term and uses the grain tier. The previous
        // expectation referred to MeshGradient, which is no longer the selected default.
        assertTrue(FOLIO_FIELD_UNIFIED.fieldRenderScale() <= FolioFieldCost.scaleGrain,
            "the default field exceeded the half-resolution budget")
    }

    @Test
    fun `new fields retain fine material detail without paying full-screen fragment cost`() {
        assertEquals(FolioFieldCost.scaleSoft, FolioFieldKind.MeshGradient.fieldRenderScale())
        assertEquals(FolioFieldCost.scaleSoft, FolioFieldKind.PerlinNoise.fieldRenderScale())
        for (kind in listOf(
            FolioFieldKind.LiquidMetal, FolioFieldKind.Metaballs, FolioFieldKind.Water,
            FolioFieldKind.Heatmap, FolioFieldKind.SmokeRing, FolioFieldKind.ColorPanels,
        )) {
            assertEquals(0.5f, kind.fieldRenderScale(), "$kind loses its fine material features")
        }
    }

    @Test
    fun `the budget is a real saving at phone resolutions`() {
        // The claim the whole change rests on: fragment work falls with the square of the scale.
        // 1080x2400 is the device the lag was reported from.
        val w = 1080
        val h = 2400
        val full = w.toLong() * h.toLong()
        for (kind in FolioFieldKind.entries) {
            val s = kind.fieldRenderScale()
            if (s >= 1f) continue
            val lw = ceil(w * s).toInt()
            val lh = ceil(h * s).toInt()
            val cut = full.toDouble() / (lw.toLong() * lh.toLong()).toDouble()
            assertTrue(cut > 3.0, "$kind only cuts fragments ${cut}x — not worth the layer")
        }
    }

    @Test
    fun `the magnified layer always covers the field`() {
        // ceil() on the way down and exactly 1/scale on the way up, so a rounding remainder can
        // never leave a hairline of unpainted ground at the right or bottom edge. Checked across
        // awkward sizes, not just round ones.
        val sizes = listOf(1080, 1079, 1440, 2400, 2399, 411, 1)
        val scales = listOf(
            FolioFieldCost.scaleSoft,
            FolioFieldCost.scaleGrain,
            FolioFieldCost.minScale,
            FolioFieldCost.scaleSoft * FolioFieldCost.constrainedScale,
        )
        for (size in sizes) {
            for (s in scales) {
                val small = ceil(size * s).toInt().coerceIn(1, size.coerceAtLeast(1))
                assertTrue(
                    small * (1f / s) >= size.toFloat(),
                    "size=$size scale=$s magnifies to ${small * (1f / s)}, short of $size",
                )
            }
        }
    }

    @Test
    fun `a constrained device never falls below the floor`() {
        for (kind in FolioFieldKind.entries) {
            val constrained = (kind.fieldRenderScale() * FolioFieldCost.constrainedScale)
                .coerceIn(FolioFieldCost.minScale, 1f)
            assertTrue(
                constrained >= FolioFieldCost.minScale,
                "$kind drops to $constrained on a constrained device",
            )
            assertTrue(
                constrained <= kind.fieldRenderScale(),
                "$kind costs *more* on a constrained device",
            )
        }
    }

    // ── the shared tick grid ────────────────────────────────────────────────

    @Test
    fun `clocks that share a tick wake on the same instant`() {
        // The coalescing claim. Every `rememberSlowPhases` call site is its own loop, and the
        // ground plane is the §16 room rung, so each loop's wakeup costs a full room re-record
        // and a re-blur of every pane. Scattered wakeups therefore multiply the slow clock by
        // the number of glass surfaces on screen — which is what the grid removes: whatever
        // instant inside a tick a loop happens to observe, it must arrive at the *same* next
        // grid point as every other loop on that tick.
        for (tick in listOf(SLOW_MOTION_TICK_MS, 50L, 16L, 1L)) {
            // Two loops that entered the composition at unrelated moments inside one tick.
            val base = 1_234_567L - (1_234_567L % tick)
            for (a in 0 until tick) {
                for (b in 0 until tick) {
                    val landingA = base + a + slowTickDelayMs(base + a, tick)
                    val landingB = base + b + slowTickDelayMs(base + b, tick)
                    assertEquals(
                        landingA,
                        landingB,
                        "tick=$tick offsets $a and $b land on different instants",
                    )
                }
            }
        }
    }

    @Test
    fun `the grid can neither spin nor skip a tick`() {
        // delay(0) in a `while (true)` is a busy loop, and delay(>tick) drops a sample. Checked
        // across a negative frame-clock origin too: the frame time's absolute value is
        // platform-defined and only its differences are meaningful.
        for (tick in listOf(100L, 50L, 16L, 1L)) {
            for (now in listOf(-5_000L, -1L, 0L, 1L, 99L, 100L, 101L, 1_234_567_890L)) {
                val d = slowTickDelayMs(now, tick)
                assertTrue(d in 1L..tick, "tick=$tick now=$now gave delay $d")
                assertEquals(0L, (now + d) % tick, "tick=$tick now=$now did not land on the grid")
            }
        }
    }

    @Test
    fun `a nonsense tick is clamped rather than dividing by zero`() {
        for (tick in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(1L, slowTickDelayMs(12_345L, tick), "tick=$tick")
        }
    }
}
