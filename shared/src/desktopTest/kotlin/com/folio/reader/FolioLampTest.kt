package com.folio.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.theme.LAMP_VALUE_LIFT_DARK
import com.folio.reader.ui.theme.LAMP_VALUE_LIFT_LIGHT
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.DAYLIGHT_WASH_ALPHA_MAX
import com.folio.reader.ui.theme.FOLIO_LIGHTS
import com.folio.reader.ui.theme.FolioFieldColors
import com.folio.reader.ui.theme.FolioLampLight
import com.folio.reader.ui.theme.ThemePack
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.applyLamp
import com.folio.reader.ui.theme.daylightAt
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.fieldColorAtPoint
import com.folio.reader.ui.theme.folioLightFor
import com.folio.reader.ui.theme.heroMeshBorrow
import com.folio.reader.ui.theme.heroWashBorrow
import com.folio.reader.ui.theme.lampColor
import com.folio.reader.ui.theme.lampOf
import com.folio.reader.ui.theme.mixG
import com.folio.reader.ui.theme.paneFill
import com.folio.reader.ui.theme.tameCover
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guards for a light each theme authors.
 *
 * Two promises matter here. A pack may choose where its light sits, how hard it burns
 * and how much of its colour it lends a book — but it may **not** author itself
 * illegible, and it may not have its own colour overwritten unless it agreed to lend
 * it. The first is checked by measuring the composited page at each pack's own
 * ambition; the second by checking that a pack which lends nothing really does.
 */
class FolioLampTest {

    private fun relLum(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = relLum(a)
        val lb = relLum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun chroma(c: Color): Float =
        maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

    private val covers: List<Color> = (0 until 12).map { Color.hsv(it * 30f, 0.85f, 0.95f) } +
        listOf(Color.hsv(205f, 0.12f, 0.92f), Color.hsv(40f, 0.10f, 0.85f))

    private val field = IntSize(1080, 2340)
    private val w = 1080f
    private val h = 2340f

    private fun lampFor(
        palette: AppPalette,
        cover: Color?,
        at: Offset? = null,
        phase: Float = 0f,
        size: IntSize = field,
    ): FolioLampLight? = lampOf(
        atmos = atmosphereFor(palette.colors),
        authored = folioLightFor(palette),
        themeColor = palette.colors.primary,
        cover = cover,
        at = at,
        field = size,
        phase = phase,
    )

    /** Every pack must actually author a light — a typo'd id falls back silently. */
    @Test
    fun everyPackAuthorsALight() {
        val authored = ThemePack.ALL.map { it.id }.toSet()
        assertTrue(
            FOLIO_LIGHTS.keys.containsAll(authored),
            "packs with no authored light: ${authored - FOLIO_LIGHTS.keys} — they would get " +
                "the quiet default and nobody would notice on a device",
        )
        assertTrue(
            FOLIO_LIGHTS.keys == authored,
            "lights authored for packs that no longer exist: ${FOLIO_LIGHTS.keys - authored}",
        )
        for (palette in AppPalette.entries) {
            val light = folioLightFor(palette)
            assertTrue(
                light.sourceX in 0f..1f && light.sourceY in 0f..1f,
                "${palette.id}: the seat is off the page (${light.sourceX}, ${light.sourceY})",
            )
            assertTrue(
                light.lean in 0f..1f && light.hueRange in 0f..1f,
                "${palette.id}: lean ${light.lean} / hueRange ${light.hueRange} outside 0..1",
            )
            assertTrue(
                light.strengthDark > 0f && light.strengthLight > 0f,
                "${palette.id}: authored no light at all",
            )
            assertTrue(
                light.strengthLight <= light.strengthDark,
                "${palette.id}: paper is lit harder than the dark face (${light.strengthLight} " +
                    "vs ${light.strengthDark}) — a bloom that strong on cream is a smudge",
            )
        }
    }

    /**
     * A pack that lends no hue must keep its own colour whatever is open. This is the
     * promise that the light belongs to the theme and not to the shelf.
     */
    @Test
    fun aThemeThatLendsNothingKeepsItsOwnColour() {
        for (palette in AppPalette.entries) {
            val light = folioLightFor(palette)
            if (light.hueRange > 0f) continue
            val atmos = atmosphereFor(palette.colors)
            val own = lampColor(palette.colors.primary, null, 0f, atmos)
            for (cover in covers) {
                assertTrue(
                    lampColor(palette.colors.primary, cover, 0f, atmos) == own,
                    "${palette.id}: lends nothing yet a cover still moved its light",
                )
            }
        }
    }

    /**
     * A pale cover must still make a coloured light — the failure that hid the first
     * lamp. But a pack whose own primary is genuinely grey (Silver, Graphite) is
     * *authored* neutral, and forcing colour on it would invent a hue the theme does
     * not have. So the promise is two-part: the lamp never carries less chroma than
     * the theme it belongs to, and the floor binds for any pack that has a hue to give.
     */
    @Test
    fun aPaleCoverStillMakesAColouredLight() {
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val light = folioLightFor(palette)
            for (cover in listOf(
                Color.hsv(205f, 0.12f, 0.92f), Color.hsv(40f, 0.10f, 0.85f),
            )) {
                val lamp = lampColor(colors.primary, cover, light.hueRange, atmos)
                // The baseline is the colour the pack agreed to light with — its own
                // primary mixed toward the cover by hueRange — not the untouched
                // primary. A pack that lends colour has accepted that a pale cover
                // dilutes the mix before the lamp does anything at all.
                val borrowed = mixG(colors.primary, cover, light.hueRange)
                val own = chroma(borrowed)
                val themeOwn = chroma(colors.primary)
                // Lifting a colour toward white scales its chroma by (1 - lift) —
                // that is what making it glow costs, by construction. Anything below
                // that is colour the pipeline lost and the lift cannot account for.
                val lift = if (palette.isDark) LAMP_VALUE_LIFT_DARK else LAMP_VALUE_LIFT_LIGHT
                if (themeOwn > 0.05f) {
                    assertTrue(
                        chroma(lamp) >= own * (1f - lift) - EPS_CHROMA,
                        "${palette.id} on cover ${cover.hex()}: the lamp washes its own theme " +
                            "out — ${"%.2f".format(own)} of chroma in the borrowed colour, " +
                            "${"%.2f".format(chroma(lamp))} in the light, below the " +
                            "${"%.2f".format(own * (1f - lift))} the lift accounts for",
                    )
                    assertTrue(
                        chroma(lamp) >= LAMP_CHROMA_FLOOR - 0.02f,
                        "${palette.id} on cover ${cover.hex()}: a chromatic theme lit with " +
                            "${"%.2f".format(chroma(lamp))} chroma — a pale light on a dark " +
                            "field reads as nothing",
                    )
                }
            }
        }
    }

    /**
     * The promise. Each pack is asked for its own authored strength — the number a
     * designer wrote — and the page is measured with that light at full burn. The lamp
     * solves for a ceiling, so this must hold for every pack however loud it is.
     */
    @Test
    fun theLitPageKeepsBodyInkLegible() {
        val floor = 7.0
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val ink = colors.onBackground
            for (cover in covers) {
                val lit = lampFor(palette, cover, phase = PI_HALF)
                    ?: error("no lamp for ${palette.id}")
                val worst = worstRatio(fieldColors(atmos, cover, atmos.fieldTintStrength), lit, ink)
                assertTrue(
                    worst >= floor,
                    "${palette.id} on cover ${cover.hex()}: the page under its own lamp puts body " +
                        "ink at ${"%.2f".format(worst)}:1 — authored strength " +
                        "${folioLightFor(palette).strength(atmos.isDark)}, solved alpha " +
                        "${"%.3f".format(lit.alpha)}",
                )
                val plain = lampFor(palette, null, phase = PI_HALF) ?: error("no lamp")
                val plainWorst = worstRatio(fieldColors(atmos), plain, ink)
                assertTrue(
                    plainWorst >= floor,
                    "${palette.id}: the lamp alone, before any cover, took body ink to " +
                        "${"%.2f".format(plainWorst)}:1",
                )
            }
        }
    }

    /**
     * The hero is a pane now, so its author line and its "Read today" sit on the room
     * as much as on the card. Measured the hard way: the pane composited over the
     * worst point the *lit* page can produce, with each pack's own lamp at full burn
     * and the most hostile cover on the shelf. If the pane cannot hold its own text,
     * it does not get to be a pane.
     */
    @Test
    fun theHeroPaneKeepsItsOwnTextLegible() {
        val floor = 7.0
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val pane = atmos.paneFill()
            val ink = colors.onSurface
            assertTrue(
                pane.alpha < 1f,
                "${palette.id}: the pane is opaque again, so the hero shows no room",
            )
            for (cover in covers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                val lamp = lampFor(palette, cover, phase = PI_HALF) ?: error("no lamp")
                var worst = Double.MAX_VALUE
                for (iy in 0..6) for (ix in 0..6) {
                    val page = fieldColorAtPoint(
                        lit, w, h, (0.05f + ix * 0.15f) * w, (0.05f + iy * 0.15f) * h, 0f,
                        lamp = lamp,
                    )
                    worst = minOf(worst, ratio(mixG(page, pane, pane.alpha), ink))
                }
                assertTrue(
                    worst >= floor,
                    "${palette.id} on cover ${cover.hex()}: body ink on the hero pane reads at " +
                        "${"%.2f".format(worst)}:1 over the worst lit point, under the $floor " +
                            "floor (pane alpha ${pane.alpha})",
                )
            }
        }
    }

    /**
     * Phase 2 raised the dark hero's cover ceiling ([HERO_COVER_CEILING_DARK]) so a
     * book actually registers on a dark hero. That amplitude is only affordable if the
     * *whole* cover stack a reader's text sits on still clears §12.3. So compose it the
     * way the hero draws it — lit field → pane → cover wash ([heroWashBorrow]) → cover
     * mesh pool ([heroMeshBorrow]), each in the jacket's own tamed colour at full
     * alpha — over the worst point each pack's lamp and the most hostile cover can
     * produce, and require 7:1. This guard, not taste, sets the ceiling: if it fails,
     * the ceiling is too high.
     */
    @Test
    fun theHeroCoverChannelsKeepTextLegible() {
        val floor = 7.0
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val pane = atmos.paneFill()
            val ink = colors.onSurface
            for (cover in covers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                val lamp = lampFor(palette, cover, phase = PI_HALF) ?: error("no lamp for ${palette.id}")
                val tamed = tameCover(cover, colors.primary)
                var worst = Double.MAX_VALUE
                for (iy in 0..6) for (ix in 0..6) {
                    val page = fieldColorAtPoint(
                        lit, w, h, (0.05f + ix * 0.15f) * w, (0.05f + iy * 0.15f) * h, 0f, lamp = lamp,
                    )
                    val base = mixG(page, pane, pane.alpha)
                    val washed = mixG(base, tamed, heroWashBorrow(atmos.isDark))
                    val meshed = mixG(washed, tamed, heroMeshBorrow(atmos.isDark))
                    worst = minOf(worst, ratio(meshed, ink))
                }
                assertTrue(
                    worst >= floor,
                    "${palette.id} on cover ${cover.hex()}: body ink on the hero's full cover " +
                        "stack reads at ${"%.2f".format(worst)}:1, under $floor — the dark " +
                        "ceiling (wash+mesh ${"%.3f".format(heroWashBorrow(atmos.isDark) + heroMeshBorrow(atmos.isDark))}) is too high",
                )
            }
        }
    }

    /**
     * Daylight is now wired into the field (`folioField` draws the day's temperature
     * as its last pass, `fieldColorAtPoint` mirrors it). It rides the
     * [DAYLIGHT_WASH_ALPHA_MAX] envelope `DaylightTest` already proves cannot move a
     * WCAG ratio off its floor — but that proof is over black and white, and the field
     * is a lit, pooled, lamped surface held to a *margin* above 7:1. So pin the real
     * thing: the lit page under each pack's own lamp, with the brightest, the most
     * saturated and the darkest temperature the day ever draws laid over it at the full
     * ceiling (production scales that down by intensity), still clears the reading floor.
     */
    @Test
    fun theLitPageSurvivesTheDaylightWash() {
        val floor = 7.0
        val temps = listOf(
            daylightAt(12, 0).temperature, // noon white — brightest, worst for light ink
            daylightAt(7, 0).temperature,  // golden-hour amber — most saturated
            daylightAt(0, 0).temperature,  // night blue-black — worst for dark ink
        )
        for (palette in AppPalette.entries) {
            val colors = palette.colors
            val atmos = atmosphereFor(colors)
            val ink = colors.onBackground
            for (cover in covers) {
                val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
                val lamp = lampFor(palette, cover, phase = PI_HALF) ?: error("no lamp for ${palette.id}")
                for (temp in temps) {
                    var worst = Double.MAX_VALUE
                    for (iy in 0..6) for (ix in 0..6) {
                        val page = fieldColorAtPoint(
                            lit, w, h, (0.05f + ix * 0.15f) * w, (0.05f + iy * 0.15f) * h, 0f,
                            dayTint = temp, dayStrength = DAYLIGHT_WASH_ALPHA_MAX, lamp = lamp,
                        )
                        worst = minOf(worst, ratio(page, ink))
                    }
                    assertTrue(
                        worst >= floor,
                        "${palette.id} on cover ${cover.hex()} under the full daylight wash " +
                            "(${temp.hex()}) puts body ink at ${"%.2f".format(worst)}:1, under $floor",
                    )
                }
            }
        }
    }

    private fun worstRatio(field: FolioFieldColors, lamp: FolioLampLight, ink: Color): Double {
        var worst = Double.MAX_VALUE
        for (iy in 0..6) for (ix in 0..6) {
            val fx = 0.05f + ix * 0.15f
            val fy = 0.05f + iy * 0.15f
            val page = fieldColorAtPoint(field, w, h, fx * w, fy * h, 0f, lamp = lamp)
            worst = minOf(worst, ratio(page, ink))
        }
        return worst
    }

    /** The seat is the pack's, and the cover only leans it — never relocates it. */
    @Test
    fun theCoverLeansTheLightAndNeverMovesItSomewhereElse() {
        val palette = AppPalette.HONEY
        val light = folioLightFor(palette)
        val seat = Offset(w * light.sourceX, h * light.sourceY)
        val far = Offset(w * 0.95f, h * 0.9f)
        val leaned = assertNotNull(lampFor(palette, Color.hsv(200f, 0.8f, 0.9f), at = far))
        val dx = leaned.center.x - seat.x
        val dy = leaned.center.y - seat.y
        val travel = kotlin.math.sqrt(dx * dx + dy * dy)
        val reach = kotlin.math.sqrt(
            (far.x - seat.x) * (far.x - seat.x) + (far.y - seat.y) * (far.y - seat.y)
        )
        assertTrue(
            travel <= reach * light.lean + 1f,
            "the cover moved the light ${travel.toInt()}px from its seat, past the " +
                "${light.lean} lean this pack authored (${(reach * light.lean).toInt()}px)",
        )
        assertTrue(
            travel > 0f && light.lean > 0f,
            "this pack leans at all, so the cover should not move the light",
        )
    }

    /** Nothing to light, or no measured field, means no lamp. */
    @Test
    fun anUnmeasuredFieldDrawsNothingNew() {
        assertNull(lampFor(AppPalette.DARK, null, size = IntSize(0, 0)))
        assertNotNull(lampFor(AppPalette.DARK, null))
    }

    /** Reduce-motion is a static form: the breath dims below the cap, never past it. */
    @Test
    fun theBreathModulatesAndNeverRemovesTheLight() {
        val rest = assertNotNull(lampFor(AppPalette.DARK, Color.hsv(120f, 0.8f, 0.9f)))
        val trough = lampFor(
            AppPalette.DARK, Color.hsv(120f, 0.8f, 0.9f),
            at = null, phase = -PI_HALF,
        )
        assertNotNull(trough)
        assertTrue(rest.alpha > 0f && trough.alpha > 0f, "a phase must never extinguish the lamp")
        assertTrue(
            kotlin.math.abs(trough.alpha - rest.alpha) / rest.alpha < 0.2f,
            "the breath swings ${rest.alpha} to ${trough.alpha} — that flickers",
        )
    }

    private fun Color.hex(): String = "#" + (
        (red * 255).toInt() * 0x10000 + (green * 255).toInt() * 0x100 + (blue * 255).toInt()
        ).toString(16).padStart(6, '0')

    private companion object {
        const val PI_HALF = 1.5707963f

        /** Mirrors `LAMP_MIN_CHROMA`, restated so moving it is a deliberate act. */
        const val LAMP_CHROMA_FLOOR = 0.45f

        /** One 8-bit channel quantum — the floor on any channel-wise comparison. */
        const val EPS_CHROMA = 0.004f
    }
}
