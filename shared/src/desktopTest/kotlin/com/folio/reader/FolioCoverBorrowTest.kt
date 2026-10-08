package com.folio.reader

import com.folio.reader.ui.components.CLIMATE_LIGHT_MAX
import com.folio.reader.ui.theme.HERO_COVER_CEILING_DARK
import com.folio.reader.ui.theme.HERO_COVER_CEILING_PAPER
import com.folio.reader.ui.theme.HERO_MESH_SHARE
import com.folio.reader.ui.theme.HERO_WASH_SHARE
import com.folio.reader.ui.theme.HERO_COVER_HEADROOM
import com.folio.reader.ui.theme.chromaSpread
import com.folio.reader.ui.theme.heroMeshBorrow
import com.folio.reader.ui.theme.heroWashBorrow
import com.folio.reader.ui.theme.tameCover
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hero's cover budget.
 *
 * Every individual channel here was defensible; the complaint was the sum, and the
 * sum is exactly what nothing was checking. These guards exist so that adding a
 * channel, widening a share, or re-deriving an allowance from a bigger input has to
 * go through this file and change a number that is visibly part of a total.
 *
 * The currency is the fraction of a finished hero pixel that is the jacket's own
 * colour — see [HERO_COVER_CEILING_DARK] and [HERO_COVER_CEILING_PAPER].
 */
class FolioCoverBorrowTest {

    @Test
    fun theSharesAddUpToTheCeilingAndNoMore() {
        val shares = HERO_WASH_SHARE + HERO_MESH_SHARE + HERO_COVER_HEADROOM
        assertTrue(
            kotlin.math.abs(shares - 1f) < 1e-4f,
            "the hero's channel shares sum to $shares, not 1 — headroom is either " +
                "unaccounted for or the ceiling has been overspent on paper",
        )
    }

    @Test
    fun noChannelMaySupplyMoreThanItsOwnCeilingAlone() {
        // A single channel eating the whole budget would make the others' shares a
        // lie, and is the shape a careless ceiling edit takes.
        for (dark in listOf(true, false)) {
            val ceiling = if (dark) HERO_COVER_CEILING_DARK else HERO_COVER_CEILING_PAPER
            for ((name, allowance) in listOf(
                "wash" to heroWashBorrow(dark),
                "mesh" to heroMeshBorrow(dark),
            )) {
                assertTrue(
                    allowance in 0f..ceiling,
                    "on a ${if (dark) "dark" else "paper"} face the hero $name allowance " +
                        "$allowance is outside 0..$ceiling",
                )
            }
        }
    }

    @Test
    fun theChannelsTogetherStayUnderTheCeiling() {
        // The wash and the mesh both land on the same plane and both draw in the
        // jacket's own colour, so their alphas are their cover fractions and they add.
        // This is the assertion that would have caught the violet slab.
        for (dark in listOf(true, false)) {
            val ceiling = if (dark) HERO_COVER_CEILING_DARK else HERO_COVER_CEILING_PAPER
            val spent = heroWashBorrow(dark) + heroMeshBorrow(dark)
            assertTrue(
                spent <= ceiling + 1e-6f,
                "on a ${if (dark) "dark" else "paper"} face wash + mesh supply $spent " +
                    "of a hero pixel, over the $ceiling ceiling",
            )
        }
    }

    /**
     * The invariant that took five builds to find.
     *
     * A ceiling stated as a fraction of the finished pixel is not a fraction of what
     * the eye sees. Paper's `raisedFill` is `liftG(surface, 0.55f)` — already 55% white
     * — so it absorbs a bright jacket. A dark `raisedFill` sits near (25,22,30), and
     * eleven percent of an amber like #D4A017 pulls it to about (45,37,29): the same
     * allowance, several times the shift, and the result is a brown wash over the whole
     * card. One number for both faces is why every previous value was validated against
     * the face that hides the problem.
     */
    @Test
    fun theDarkFaceIsAllowedFarLessTintThanPaper() {
        assertTrue(
            HERO_COVER_CEILING_DARK < HERO_COVER_CEILING_PAPER / 2f,
            "the dark ceiling $HERO_COVER_CEILING_DARK is not clearly below paper's " +
                "$HERO_COVER_CEILING_PAPER — a shared allowance is what produced the " +
                "brown wash this guard exists to prevent",
        )
        assertTrue(
            heroWashBorrow(dark = true) < heroWashBorrow(dark = false),
            "the dark wash is not the smaller allowance it is meant to be",
        )
    }

    @Test
    fun theClimateCannotScaleAboveItsAllowanceAtTheBrightEnd() {
        // The wash scales by climateLight / CLIMATE_LIGHT_MAX. If the band's top ever
        // moved below a real lightFraction the wash would exceed its share.
        val brightest = listOf(0.38f, 0.30f, 0.22f, 0.13f).max()
        assertTrue(
            brightest <= CLIMATE_LIGHT_MAX + 1e-6f,
            "a climate burns at $brightest but CLIMATE_LIGHT_MAX is $CLIMATE_LIGHT_MAX — " +
                "the wash's normalization now overshoots the budget it was scaled into",
        )
        for (dark in listOf(true, false)) {
            assertEquals(
                heroWashBorrow(dark),
                heroWashBorrow(dark) * (brightest / CLIMATE_LIGHT_MAX),
                absoluteTolerance = 1e-5f,
            )
        }
    }

    @Test
    fun tamingCapsChromaWithoutMovingHueOrLightness() {
        val palette = Color(0xFF1A1A2E)
        val neon = Color(0xFFFF00FF)
        val tamed = tameCover(neon, palette)
        val envelope = chromaSpread(palette) * 1.15f
        // An sRGB Color is 8 bits a channel, so every clamp lands on a 1/255 step and
        // nothing computed through three of them can be asserted tighter. The clamp is
        // exact in the reals; the colour it returns is not.
        val quantum = 1f / 255f

        assertTrue(
            chromaSpread(tamed) <= envelope + quantum,
            "a neon jacket still supplies ${chromaSpread(tamed)} of chroma, over the " +
                "palette's own envelope $envelope",
        )
        // Hue direction: which channel leads and which lags is the whole of what the
        // book is allowed to say. Only the intensity may be edited.
        assertEquals(
            neon.red - neon.green > 0f,
            tamed.red - tamed.green > 0f,
            "taming flipped the cover's hue direction",
        )
        // Lightness is the mean of the channels, and it is what the ink's contrast
        // was measured against; a tame that moved it would trade colour for legibility.
        assertEquals(
            (neon.red + neon.green + neon.blue) / 3f,
            (tamed.red + tamed.green + tamed.blue) / 3f,
            absoluteTolerance = quantum,
        )
    }

    @Test
    fun aCoverAlreadyInsideTheEnvelopeIsLeftAlone() {
        // The guard against a mechanism that always acts: a quiet jacket should reach
        // the hero exactly as it was sampled, or "budget" is just "dimmer".
        val palette = Color(0xFF1A1A2E)
        val quiet = Color(0xFF2E2A3C)
        assertTrue(
            chromaSpread(quiet) <= chromaSpread(palette) * 1.15f,
            "fixture is no longer quiet — the test proves nothing",
        )
        assertEquals(quiet, tameCover(quiet, palette))
    }

    @Test
    fun aGreyCoverSurvivesTheDivide() {
        // A colour with no spread has no direction to scale, and the clamp divides by
        // it. A plain white jacket is a real cover, not a divide-by-zero.
        val grey = Color(0xFF808080)
        assertEquals(grey, tameCover(grey, Color(0xFF1A1A2E)))
    }
}
