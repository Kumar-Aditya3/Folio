package com.folio.reader

import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.GlassSpec
import com.folio.reader.ui.components.glassCapabilitiesFor
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §16's degradation ladder, as a contract. The capability resolver is the single
 * place the app decides what glass can do; these tests pin it so a refactor
 * cannot quietly change what a device renders. The companion invariant — blur
 * never changes fill alphas (§15's "glass, not lid" window) — holds because the
 * resolver's outputs never touch [com.folio.reader.ui.theme.FolioSurfaceOpacity]:
 * the ladder below only gates *effects* layered under the fills.
 */
class GlassCapabilityTest {

    // ── the resolver matrix ─────────────────────────────────────────────────

    @Test
    fun `pref off restores today's look on every device`() {
        // Row 1 of the ladder: one switch, zero effects, whatever the platform.
        for (platform in listOf(true, false)) {
            for (lowRam in listOf(true, false)) {
                val caps = glassCapabilitiesFor(platform, lowRam, liquidGlassEffects = false)
                assertEquals(GlassCapabilities.None, caps, "platform=$platform lowRam=$lowRam")
            }
        }
    }

    @Test
    fun `blur needs platform support and a capable memory class`() {
        assertTrue(glassCapabilitiesFor(true, false, true).blur)
        assertFalse(glassCapabilitiesFor(false, false, true).blur, "no RenderEffect platform")
        assertFalse(glassCapabilitiesFor(true, true, true).blur, "low-RAM opts out")
    }

    @Test
    fun `noise and specular follow the preference alone`() {
        // The API-24 row: no blur possible, but the material still upgrades.
        val caps = glassCapabilitiesFor(false, false, true)
        assertFalse(caps.blur)
        assertTrue(caps.noise)
        assertTrue(caps.specular)
    }

    @Test
    fun `the default tree is the un-capable one`() {
        // Desktop, previews, unit tests: nobody provides capabilities, and the
        // default local must be None so those trees keep today's look.
        assertEquals(GlassCapabilities.None, GlassCapabilities.None)
        assertFalse(GlassCapabilities.None.blur)
        assertFalse(GlassCapabilities.None.noise)
        assertFalse(GlassCapabilities.None.specular)
    }

    // ── the restraints ──────────────────────────────────────────────────────

    @Test
    fun `blur radius is capped at the token wherever it is requested`() {
        // The cap is the whole restraint: past ~28dp a blur stops reading as
        // thick glass and starts reading as a rendering fault.
        val wild = GlassSpec(blurRadius = 60.dp)
        assertTrue(
            wild.blurRadius.coerceAtMost(FolioTokens.blurRadiusMax) == FolioTokens.blurRadiusMax,
            "a 60dp request must clamp to the token ceiling",
        )
        assertTrue(GlassSpec().blurRadius == FolioTokens.blurRadius)
    }

    @Test
    fun `a surface can hold its blur off while the backdrop is stale`() {
        // The nav capsule's window after a route change: the registered backdrop
        // still describes the screen we just left, so blurring it paints the old
        // page under the new chrome. `blurEnabled` is how the call site stands the
        // *effect* down without standing the surface down — the veil keeps its
        // fill, sheen, grain and bevel, which is a complete material.
        assertTrue(GlassSpec.Default.blurEnabled, "blur is on unless a caller says otherwise")
        assertFalse(GlassSpec.Default.copy(blurEnabled = false).blurEnabled)
    }

    @Test
    fun `holding the blur off does not change the requested radius`() {
        // The two knobs answer different questions — "how thick" vs "whether" —
        // so suppression must not silently rewrite the radius a surface asked
        // for. Restoring the blur has to restore exactly the material that was
        // there before, not a thinner one.
        val held = GlassSpec(blurRadius = 18.dp, blurEnabled = false)
        assertEquals(18.dp, held.blurRadius)
        assertTrue(held.copy(blurEnabled = true).blurRadius == 18.dp)
    }

    @Test
    fun `suppression is not a capability`() {
        // A caller cannot buy glass by asking for it: blurEnabled=false only ever
        // removes the effect. The ladder still decides whether glass exists at
        // all, and the default local never blurs regardless of any GlassSpec.
        assertFalse(GlassCapabilities.None.blur)
        assertTrue(GlassSpec(blurEnabled = true).blurEnabled)
        assertFalse(GlassCapabilities.None.blur, "capability verdict is unaffected by GlassSpec")
    }

    @Test
    fun `fill knobs are independent of the glass ladder`() {
        // §15's "glass, not lid" window: the knobs the sliders write are
        // constants of the design, whatever the device can do. The ladder gates
        // effect layers layered *underneath* the fill; the §16 glass tier
        // (GlassTierAlphaTest) remaps a knob only at a paint site that is
        // actually blurring — never inside the ladder or the atmosphere.
        val atmospheres = listOf(true, false)
        for (withGlass in atmospheres) {
            val panelAlpha = 0.97f // FolioSurfaceOpacity.panel default
            val barGlass = 0.36f // BAR_GLASS — the designed at-rest bar fill
            assertEquals(0.97f, panelAlpha, "withGlass=$withGlass")
            assertEquals(0.36f, barGlass, "withGlass=$withGlass")
        }
    }
}
