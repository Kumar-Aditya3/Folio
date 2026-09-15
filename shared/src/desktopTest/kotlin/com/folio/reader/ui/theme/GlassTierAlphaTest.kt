package com.folio.reader.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §16 glass tier, as a contract: what each surface's fill alpha becomes
 * once real blur runs under it. Blur is what fixes the text bleed-through the
 * near-opaque defaults were calibrated against, so the tier steps them down —
 * but the knob's own contract (1f is a lid, the floor is a floor) survives,
 * and without blur nothing changes at all. Pins the three designed points the
 * paint sites rely on, plus the restraints.
 */
class GlassTierAlphaTest {

    @Test
    fun `the designed points step down to their glass tiers`() {
        assertEquals(
            FolioSurfaceOpacity.NAV_GLASS_BLURRED,
            glassTierAlpha(
                FolioSurfaceOpacity.NAV_GLASS,
                FolioSurfaceOpacity.NAV_GLASS,
                FolioSurfaceOpacity.NAV_GLASS_BLURRED,
            ),
            1e-6f,
        )
        assertEquals(
            FolioSurfaceOpacity.PANEL_GLASS_BLURRED,
            glassTierAlpha(
                FolioSurfaceOpacity.PANEL_GLASS,
                FolioSurfaceOpacity.PANEL_GLASS,
                FolioSurfaceOpacity.PANEL_GLASS_BLURRED,
            ),
            1e-6f,
        )
        assertEquals(
            FolioSurfaceOpacity.BAR_GLASS_BLURRED,
            glassTierAlpha(
                FolioSurfaceOpacity.BAR_GLASS,
                FolioSurfaceOpacity.BAR_GLASS,
                FolioSurfaceOpacity.BAR_GLASS_BLURRED,
            ),
            1e-6f,
        )
    }

    @Test
    fun `a lid stays a lid`() {
        // A reader who asked for solid gets solid — blur never punches a hole
        // in a surface the preference set to 1.
        assertEquals(
            1f,
            glassTierAlpha(1f, FolioSurfaceOpacity.NAV_GLASS, FolioSurfaceOpacity.NAV_GLASS_BLURRED),
            0f,
        )
    }

    @Test
    fun `below the design point the knob keeps its proportional meaning`() {
        // Half the designed knob is half the glass point…
        assertEquals(
            FolioSurfaceOpacity.NAV_GLASS_BLURRED / 2f,
            glassTierAlpha(
                FolioSurfaceOpacity.NAV_GLASS / 2f,
                FolioSurfaceOpacity.NAV_GLASS,
                FolioSurfaceOpacity.NAV_GLASS_BLURRED,
            ),
            1e-6f,
        )
        // …and the floor holds: the tier never thins past the slider's floor.
        assertTrue(
            glassTierAlpha(
                FolioSurfaceOpacity.MIN,
                FolioSurfaceOpacity.PANEL_GLASS,
                FolioSurfaceOpacity.PANEL_GLASS_BLURRED,
            ) >= FolioSurfaceOpacity.MIN,
        )
    }

    @Test
    fun `between design and solid the tier rises monotonically to a lid`() {
        var previous = glassTierAlpha(
            FolioSurfaceOpacity.NAV_GLASS,
            FolioSurfaceOpacity.NAV_GLASS,
            FolioSurfaceOpacity.NAV_GLASS_BLURRED,
        )
        var knob = FolioSurfaceOpacity.NAV_GLASS
        while (knob < 1f) {
            knob = (knob + 0.05f).coerceAtMost(1f)
            val next = glassTierAlpha(
                knob,
                FolioSurfaceOpacity.NAV_GLASS,
                FolioSurfaceOpacity.NAV_GLASS_BLURRED,
            )
            assertTrue(next >= previous, "knob=$knob regressed to $next from $previous")
            previous = next
        }
        assertEquals(1f, previous, 1e-6f)
    }

    @Test
    fun `topBarFill applies the masthead tier itself`() {
        // The masthead computes fill and blur together, so its tier lives
        // inside topBarFill rather than at a separate paint site.
        val blurred = FolioSurfaceOpacity.Default.topBarFill(1f, blurred = true)
        assertEquals(FolioSurfaceOpacity.BAR_GLASS_BLURRED, blurred.crown, 1e-4f)
        // …and the un-blurred masthead is the shipped design, untouched.
        val plain = FolioSurfaceOpacity.Default.topBarFill(1f, blurred = false)
        assertEquals(FolioSurfaceOpacity.BAR_GLASS, plain.crown, 1e-4f)
    }

    @Test
    fun `the accessors only tier when the paint site can blur`() {
        val opacity = FolioSurfaceOpacity.Default
        assertEquals(opacity.navBar, opacity.navCapsuleFill(canBlur = false), 0f)
        assertEquals(opacity.panel, opacity.panelFill(canBlur = false), 0f)
        assertEquals(FolioSurfaceOpacity.NAV_GLASS_BLURRED, opacity.navCapsuleFill(canBlur = true), 1e-6f)
        assertEquals(FolioSurfaceOpacity.PANEL_GLASS_BLURRED, opacity.panelFill(canBlur = true), 1e-6f)
    }
}
