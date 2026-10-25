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
        val blurred = FolioSurfaceOpacity.Default.topBarFill(blurred = true)
        assertEquals(FolioSurfaceOpacity.BAR_GLASS_BLURRED, blurred.crown, 1e-4f)
        // …and the un-blurred masthead is the shipped design, untouched.
        val plain = FolioSurfaceOpacity.Default.topBarFill(blurred = false)
        assertEquals(FolioSurfaceOpacity.BAR_GLASS, plain.crown, 1e-4f)
    }

    @Test
    fun `the blurred pane follows the knob rather than saturating`() {
        // Regression: the blur layer's alpha used to be `presence`, which tops out
        // at 1 from the designed glass point upward. Haze records an *opaque*
        // backgroundColor into that layer, so every setting from BAR_GLASS to 100%
        // painted the same opaque lid and the top-bar slider did nothing across two
        // thirds of its travel. `material` has to stay strictly increasing instead.
        var previous = -1f
        // Persisted values below MIN are deliberately clamped to the same value.
        for (step in (FolioSurfaceOpacity.MIN * 20).toInt()..20) {
            val knob = step / 20f
            val fill = FolioSurfaceOpacity(topBar = knob).topBarFill(blurred = true)
            assertTrue(
                fill.material > previous,
                "material must rise with the knob at $knob (was $previous, now ${fill.material})",
            )
            previous = fill.material
        }
        assertEquals(1f, FolioSurfaceOpacity(topBar = 1f).topBarFill(blurred = true).material, 1e-6f)
    }

    @Test
    fun `the masthead's crown is the knob itself, at every value`() {
        // The contract behind "make it uniform": the top-bar preference is the alpha of
        // the bar, not a factor on a designed one and not a value gated by how far the
        // screen behind it happens to be scrolled. Nothing but the knob may move it —
        // there is no collapse input any more, so the same preference paints the same
        // glass on Home, on Library, on More and on a pushed settings category.
        var knob = FolioSurfaceOpacity.MIN
        while (knob <= 1f) {
            assertEquals(
                knob,
                FolioSurfaceOpacity(topBar = knob).topBarFill().crown,
                1e-6f,
                "the crown drifted from the knob at $knob",
            )
            knob += 0.05f
        }
    }

    @Test
    fun `the decay thins toward the foot and flattens to a lid`() {
        // Shipped glass is a gradient, not a slab: crown > waist > foot, so the bar reads
        // as the page's field thickening under the status icons.
        val glass = FolioSurfaceOpacity(topBar = FolioSurfaceOpacity.BAR_GLASS).topBarFill()
        assertTrue(
            glass.crown > glass.waist && glass.waist > glass.foot,
            "the masthead stopped thinning toward its foot: $glass",
        )
        // …and a reader who asked for a lid gets one: no decay at all, or the page shows
        // through the bottom edge of a bar that was asked to be solid.
        val lid = FolioSurfaceOpacity(topBar = 1f).topBarFill()
        assertEquals(lid.crown, lid.waist, 1e-6f)
        assertEquals(lid.crown, lid.foot, 1e-6f)
        assertEquals(1f, lid.presence, 1e-6f)
    }

    @Test
    fun `absent a platform that blurs the knob is taken as written`() {
        // The tier is a paint-site decision, so off the blur path the fill is the
        // preference exactly — no remap, however far down the slider is dragged.
        val opacity = FolioSurfaceOpacity(topBar = 0.2f)
        assertEquals(0.2f, opacity.topBarFill(blurred = false).crown, 0f)
        // On the blur path the same knob is remapped proportionally, never padded up.
        assertTrue(
            opacity.topBarFill(blurred = true).crown >= FolioSurfaceOpacity.MIN,
            "the blurred tier fell below the slider's floor",
        )
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
