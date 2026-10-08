package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.COVER_HALO_PENDING_STRENGTH
import com.folio.reader.ui.components.COVER_HALO_STRENGTH
import com.folio.reader.ui.components.COVER_SAMPLE_PERMITS
import com.folio.reader.ui.components.accentCache
import com.folio.reader.ui.components.cachedOrSampledAccent
import com.folio.reader.ui.components.clearCoverAccentCache
import com.folio.reader.ui.components.coverSampleGate
import com.folio.reader.ui.components.guardCoverContrast
import com.folio.reader.ui.components.rgbToHsv
import com.folio.reader.ui.components.sampleCoverAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §13.3 cover-derived hero accent: the pure sampling and contrast-guard math.
 * The composable side only feeds these ARGB pixels from a 32×32 downsample of
 * the already-cached bitmap; these tests pin the bucketing and the
 * non-negotiable 4.5:1 guard.
 */
class CoverAccentTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (255 shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun hsvConversionBasics() {
        val red = rgbToHsv(argb(255, 0, 0))
        assertEquals(0f, red.first, 0.5f)
        assertEquals(1f, red.second, 0.001f)
        assertEquals(1f, red.third, 0.001f)

        val white = rgbToHsv(argb(255, 255, 255))
        assertEquals(0f, white.second, 0.001f)
        assertEquals(1f, white.third, 0.001f)

        val black = rgbToHsv(argb(0, 0, 0))
        assertEquals(0f, black.third, 0.001f)
    }

    @Test
    fun modalSaturatedBucketWins() {
        // Mostly red, some blue, a few greys — the modal qualifying hue is red.
        val pixels = IntArray(32 * 32) { i ->
            when {
                i < 500 -> argb(220, 30, 30)   // red, qualifies
                i < 600 -> argb(30, 30, 220)   // blue, qualifies
                i < 650 -> argb(128, 128, 128) // grey — saturation ≤ 0.25, filtered
                else -> argb(250, 250, 245)    // paper white — value > 0.9, filtered
            }
        }
        val accent = sampleCoverAccent(pixels)
        assertNotNull(accent)
        val hsv = rgbToHsv(
            argb(
                (accent.red * 255f).toInt(),
                (accent.green * 255f).toInt(),
                (accent.blue * 255f).toInt(),
            )
        )
        assertTrue(hsv.first < 30f || hsv.first > 330f, "expected the red bin, got hue ${hsv.first}")
    }

    @Test
    fun whiteAndBlackCoversYieldNothing() {
        val white = IntArray(32 * 32) { argb(255, 255, 255) }
        assertNull(sampleCoverAccent(white))
        val black = IntArray(32 * 32) { argb(10, 10, 10) }
        assertNull(sampleCoverAccent(black))
    }

    @Test
    fun glowingCoverSpeaksForTheCover() {
        // A glow cover: dark maroon field, bright orange bloom, cream title (too
        // unsaturated to qualify), some blue art, near-black bars. The bloom must
        // win the election by chroma salience — the first pass took the modal
        // bin's flat mean, landed on the dark field colour, failed the guard and
        // fell back to the accent, so the hero never visibly tracked the cover.
        val pixels = IntArray(32 * 32) { i ->
            when {
                i < 600 -> argb(120, 25, 20)   // maroon field, hue ~3°
                i < 900 -> argb(255, 150, 40)  // orange bloom, hue ~31°
                i < 950 -> argb(70, 110, 150)  // blue art
                i < 980 -> argb(245, 230, 200) // cream title — saturation ≤ 0.25
                else -> argb(15, 12, 10)       // near-black bar — value < 0.2
            }
        }
        val accent = sampleCoverAccent(pixels)
        assertNotNull(accent)
        val hsv = rgbToHsv(
            argb(
                (accent.red * 255f).toInt(),
                (accent.green * 255f).toInt(),
                (accent.blue * 255f).toInt(),
            )
        )
        assertTrue(hsv.first in 25f..40f, "expected the orange bloom, got hue ${hsv.first}")
        assertTrue(hsv.third >= 0.9f, "expected the bloom's brightness, got value ${hsv.third}")
        // And it must be bright enough that the guard leaves it untouched — the
        // visible symptom was the guard washing the whole tint into the accent.
        val dark = Color(0xFF101418)
        assertEquals(accent, guardCoverContrast(accent, dark, Color(0xFFE040FB)))
    }

    @Test
    fun guardAlwaysReturnsLegibleColor() {
        val lightSurface = Color(0xFFFAF7F2)
        val darkSurface = Color(0xFF101418)
        val fallback = Color(0xFF4D8FC7)
        val suspects = listOf(
            Color.White, Color.Black, Color(0xFF808080),
            Color(0xFFF5F5F0), Color(0xFF111111), Color(0xFFB0B0B0),
        )
        for (surface in listOf(lightSurface, darkSurface)) {
            for (suspect in suspects) {
                val guarded = guardCoverContrast(suspect, surface, fallback)
                val ratio = contrastRatio(guarded, surface)
                assertTrue(ratio >= 4.5, "guard left ${guarded} at $ratio:1 on $surface")
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

/**
 * The §13.3 sampling gate and the accent cache's read-through, as pure units.
 *
 * There is no Compose runtime and no emulator in `desktopTest`, so the composable
 * half of [com.folio.reader.ui.components.rememberCoverAccent] cannot be driven
 * here. What *is* testable — and what the perf note in that file keeps promising —
 * is the two mechanisms the grid-cover pass leans on: that the gate really bounds
 * concurrency, that bounding it splits a shelf's samples across separate frames,
 * and that one cover path is read once, ever. The gate's own semantics are
 * reproduced with the same [COVER_SAMPLE_PERMITS] constant the composable uses and
 * a virtual-time scheduler, so the assertions are exact and never sleep.
 */
class CoverAccentSamplingGateTest {

    @BeforeTest
    fun resetAccentCache() {
        // Process-lifetime by design, so the only way to key it from a test is to
        // empty it first. Paths are unique per test as well, in case a future
        // runner parallelises these.
        clearCoverAccentCache()
    }

    @Test
    fun theGateTheComposableUsesIsTheWidthTheCommentClaims() {
        assertEquals(
            COVER_SAMPLE_PERMITS,
            coverSampleGate.availablePermits,
            "an untouched gate holds exactly its permit count — if this drifts from the constant, " +
                "someone wrote a literal into the Semaphore() call and the shelf's cost model is a lie",
        )
        assertTrue(
            COVER_SAMPLE_PERMITS in 2..4,
            "3 is reasoned against the ~10ms shelf spike: below 2 a scrolled page trickles in, " +
                "above 4 most of the aligned-frame cost comes back. Change the number and its comment together.",
        )
    }

    @Test
    fun aShelfsSamplesLandOnSeparateFrames() = runTest {
        val gate = Semaphore(COVER_SAMPLE_PERMITS)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val landingFrames = Collections.synchronizedSet(mutableSetOf<Long>())

        repeat(SHELF_SIZE) {
            launch {
                gate.withPermit {
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    delay(SAMPLE_COST_MS)
                    inFlight.decrementAndGet()
                }
                // The frame the sample's state write lands in: what the ~10ms spike
                // actually was, twelve of these stacked on one scheduler instant.
                landingFrames.add(testScheduler.currentTime)
            }
        }

        val expectedRounds = ceil(SHELF_SIZE.toDouble() / COVER_SAMPLE_PERMITS).toInt()
        // The launches above are children of the test scope; without draining the
        // scheduler the counters below are read before a single sample has run.
        testScheduler.advanceUntilIdle()
        assertTrue(
            peak.get() <= COVER_SAMPLE_PERMITS,
            "${peak.get()} sampled at once through a $COVER_SAMPLE_PERMITS-permit gate",
        )
        assertTrue(
            peak.get() > 1,
            "the gate never held more than one sample, so the shelf is serialised rather " +
                "than bounded — ${expectedRounds} rounds' worth of waiting for no reason",
        )
        assertTrue(
            landingFrames.size > 1,
            "the gate exists so a shelf's samples do not land in one frame; they landed in " +
                "${landingFrames.size} at ${landingFrames.sorted()}",
        )
    }

    @Test
    fun oneCoverPathIsSampledOnceForTheLifeOfTheProcess() {
        var calls = 0
        repeat(4) {
            assertEquals(
                Color(0xFFAA0000),
                cachedOrSampledAccent("shelf/red.jpg") { calls++; Color(0xFFAA0000) },
                "every read of a cached cover returns the same raw colour",
            )
        }
        assertEquals(1, calls, "a LazyGrid recycles its slots: re-reading the same cover per pass is the cost the cache carries")
        assertEquals(Color(0xFFAA0000), accentCache["shelf/red.jpg"], "the cache holds the raw sample, so the guard can re-run per theme")
        assertEquals(
            COVER_SAMPLE_PERMITS,
            coverSampleGate.availablePermits,
            "the warm read never reaches the gate — it stays a synchronous map lookup",
        )
    }

    @Test
    fun anUnqualifiedSampleIsNotNegativeCached() {
        var calls = 0
        repeat(3) { assertNull(cachedOrSampledAccent("shelf/white.jpg") { calls++; null }) }
        assertFalse(accentCache.containsKey("shelf/white.jpg"))
        assertEquals(3, calls, "deliberate: null means 'nothing qualified', and a sentinel in a map of trusted colours is worse")
    }

    private companion object {
        /** The shelf the ~10ms spike was measured on: ~a dozen covers decoding together. */
        const val SHELF_SIZE = 12

        /** Stand-in for one stride read. Virtual time, so the value only sets the ratio. */
        const val SAMPLE_COST_MS = 50L
    }
}

/**
 * The halo's arrival ramp, at the one layer that needs no Compose runtime: the two
 * strengths it interpolates between. The endpoints are the visual contract — a
 * pending cover must still read as lit (this pass exists to kill flat covers), and
 * a resolved one must land on the strength every anchored and featured cover has
 * always used, or the shelf silently dims the hero.
 */
class CoverHaloStrengthTest {

    @Test
    fun resolvedGlowIsTheHistoricFullStrength() {
        assertTrue(COVER_HALO_STRENGTH == 0.30f, "coverHalo's own default: the glow must not drift with the ramp")
        assertTrue(
            COVER_HALO_PENDING_STRENGTH < COVER_HALO_STRENGTH && COVER_HALO_PENDING_STRENGTH >= 0.15f,
            "pending ($COVER_HALO_PENDING_STRENGTH) sits under resolved ($COVER_HALO_STRENGTH) but stays " +
                "visible — a glow too faint to see is a flat cover with a promise attached",
        )
    }
}
