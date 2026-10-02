package com.folio.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.components.daylightRimTint
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.DAYLIGHT_RIM_TINT_MAX
import com.folio.reader.ui.theme.DAYLIGHT_WASH_ALPHA_MAX
import com.folio.reader.ui.theme.FolioLampLight
import com.folio.reader.ui.theme.atmosphereFor
import com.folio.reader.ui.theme.daylightAt
import com.folio.reader.ui.theme.fieldColorAtPoint
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.folioLightFor
import com.folio.reader.ui.theme.heroMeshBorrow
import com.folio.reader.ui.theme.heroWashBorrow
import com.folio.reader.ui.theme.lampOf
import com.folio.reader.ui.theme.mixG
import com.folio.reader.ui.theme.paneFill
import com.folio.reader.ui.theme.tameCover
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Visual audit, model space.
 *
 * Not a pass/fail gate — a measurement. It runs the *real* production colour
 * functions (`atmosphereFor`, `lampOf`, `fieldColorAtPoint`, `tameCover`,
 * `heroWashBorrow`/`heroMeshBorrow`, `daylightAt`) for every [AppPalette] and asks
 * one question in numbers: **can a person actually see what each effect does?**
 *
 * Two kinds of number per effect:
 *
 *  - *Realised* ΔE — the difference between two states a user actually meets: book A's
 *    room vs book B's room, the hero of a loud cover vs a quiet one, the field at night
 *    vs at midday. "Can you tell these two apart today?"
 *  - *Envelope* ΔE — the **most** a throttle can ever move a pixel, at full intensity on
 *    the most favourable input. "Is this channel capable of being seen at all, or has it
 *    been dialled below the floor by construction?"
 *
 * ΔE is CIELAB ΔE*ab (the same `lab()` [ThemeSchemeTest] uses). Perceptual bands:
 * `<1.0` invisible · `<2.3` sub-JND · `<5` subtle · `>=5` visible. Anything whose
 * *envelope* ΔE is under ~2.3 cannot be seen no matter the content, so it is a throttle
 * that is safe (indeed necessary) to raise before it earns its keep. The report is
 * written to `folio-visual-audit.md` in the JVM temp dir and echoed to stdout.
 *
 * Run: `./gradlew :shared:desktopTest --tests "com.folio.reader.VisualAuditTest"`
 */
class VisualAuditTest {

    // ── representative covers ────────────────────────────────────────────────
    private val loudWarm = Color.hsv(35f, 0.90f, 0.95f)
    private val loudCool = Color.hsv(210f, 0.85f, 0.90f)
    private val quiet = Color.hsv(205f, 0.12f, 0.92f)

    private val field = IntSize(1080, 2340)
    private val w = 1080f
    private val h = 2340f

    @Test
    fun auditReport() {
        val sb = StringBuilder()
        sb.appendLine("# Folio visual audit — model space")
        sb.appendLine()
        sb.appendLine("ΔE = CIELAB ΔE*ab. Bands: `<1.0` invisible · `<2.3` sub-JND · `<5` subtle · `>=5` visible.")
        sb.appendLine()

        realised(sb)
        envelope(sb)
        verdict(sb)

        val out = File(System.getProperty("java.io.tmpdir"), "folio-visual-audit.md")
        out.writeText(sb.toString())
        println(sb.toString())
        println("VISUAL AUDIT written to: ${out.absolutePath}")
        assertTrue(out.exists(), "audit report was not written")
    }

    // ── realised: can you tell two real states apart? ────────────────────────
    private fun realised(sb: StringBuilder) {
        sb.appendLine("## Realised ΔE — two states a user actually meets")
        sb.appendLine()
        sb.appendLine("| palette | face | room A·B | room·none | lamp A·B | lamp iso | hero A·B | mesh iso | rim-arc | field night·noon |")
        sb.appendLine("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|")

        val roomAB = Agg(); val roomNone = Agg(); val lampAB = Agg(); val lampIso = Agg(); val heroAB = Agg(); val meshIso = Agg(); val rimArc = Agg(); val dayAB = Agg()

        for (p in AppPalette.entries) {
            val atmos = atmosphereFor(p.colors)
            val primary = p.colors.primary
            val str = atmos.fieldTintStrength
            val face = if (p.isDark) "dark" else "paper"

            // room tint: the field a book paints vs another book, and vs no book at all.
            val litA = fieldColors(atmos, loudWarm, str)
            val litB = fieldColors(atmos, loudCool, str)
            val litNone = fieldColors(atmos)
            val cx = 0.5f * w; val cy = 0.55f * h
            val roomABv = de(fieldColorAtPoint(litA, w, h, cx, cy, 0f), fieldColorAtPoint(litB, w, h, cx, cy, 0f))
            val roomNonev = de(fieldColorAtPoint(litA, w, h, cx, cy, 0f), fieldColorAtPoint(litNone, w, h, cx, cy, 0f))

            // lamp: the composited room at the lamp seat for book A vs book B.
            val lampA = lamp(p, loudWarm); val lampB = lamp(p, loudCool)
            val seat = lampA?.center ?: Offset(cx, cy)
            val lampABv = if (lampA != null && lampB != null)
                de(
                    fieldColorAtPoint(litA, w, h, seat.x, seat.y, 0f, lamp = lampA),
                    fieldColorAtPoint(litB, w, h, seat.x, seat.y, 0f, lamp = lampB),
                ) else 0.0

            // lamp isolated: the lamp's OWN per-book colour, over an UNtinted field, so
            // the room tint underneath cannot inflate it — "can you tell two books apart
            // by their light alone?" This is the number LAMP_COVER_SHARE / the emission
            // floor actually govern.
            val lampIsov = if (lampA != null && lampB != null)
                de(
                    fieldColorAtPoint(litNone, w, h, seat.x, seat.y, 0f, lamp = lampA),
                    fieldColorAtPoint(litNone, w, h, seat.x, seat.y, 0f, lamp = lampB),
                ) else 0.0

            // hero: the whole stack a reader sees — lit field, pane, cover wash.
            val heroABv = de(hero(p, atmos, primary, loudWarm, lampA), hero(p, atmos, primary, quiet, lamp(p, quiet)))

            // mesh isolated: the same hero pane with vs without the cover's own mesh
            // pool (heroMeshBorrow), so the dark-hero ceiling work has a number for the
            // mesh channel on its own rather than only inside hero A·B.
            val meshIsov = de(
                hero(p, atmos, primary, loudWarm, lampA),
                heroWithMesh(p, atmos, primary, loudWarm, lampA),
            )

            // rim-arc: paper's (and dark's) time-of-day as a realised rim colour —
            // the raised rim at 03:00 vs 12:00, the production
            // lerp(rim, temperature, DAYLIGHT_RIM_TINT_MAX*intensity). The field column
            // already proves the room moves; this proves the *edge* does, which is the
            // only daylight cue a translucent paper pane carries after the box fix.
            val rimArcv = de(rimAt(atmos, 3), rimAt(atmos, 12))

            // daylight: the field at 03:00 vs 12:00, worst point on a sampled grid,
            // now including the wired day wash (temperature at DAYLIGHT_WASH_ALPHA_MAX x
            // intensity) that folioField/fieldColorAtPoint carry.
            val night = daylightAt(3); val noon = daylightAt(12)
            val sN = night.azimuth * night.intensity; val sD = noon.azimuth * noon.intensity
            val aN = DAYLIGHT_WASH_ALPHA_MAX * night.intensity; val aD = DAYLIGHT_WASH_ALPHA_MAX * noon.intensity
            var dayv = 0.0
            for (iy in 0..4) for (ix in 0..4) {
                val px = (0.1f + ix * 0.2f) * w; val py = (0.1f + iy * 0.2f) * h
                dayv = maxOf(
                    dayv,
                    de(
                        fieldColorAtPoint(litNone, w, h, px, py, sN, dayTint = night.temperature, dayStrength = aN),
                        fieldColorAtPoint(litNone, w, h, px, py, sD, dayTint = noon.temperature, dayStrength = aD),
                    ),
                )
            }

            roomAB.add(roomABv); roomNone.add(roomNonev); lampAB.add(lampABv); lampIso.add(lampIsov); heroAB.add(heroABv); meshIso.add(meshIsov); rimArc.add(rimArcv); dayAB.add(dayv)
            sb.appendLine("| ${p.id} | $face | ${cell(roomABv)} | ${cell(roomNonev)} | ${cell(lampABv)} | ${cell(lampIsov)} | ${cell(heroABv)} | ${cell(meshIsov)} | ${cell(rimArcv)} | ${cell(dayv)} |")
        }
        sb.appendLine("| **median** | | ${cell(roomAB.median())} | ${cell(roomNone.median())} | ${cell(lampAB.median())} | ${cell(lampIso.median())} | ${cell(heroAB.median())} | ${cell(meshIso.median())} | ${cell(rimArc.median())} | ${cell(dayAB.median())} |")
        sb.appendLine("| **max** | | ${cell(roomAB.max())} | ${cell(roomNone.max())} | ${cell(lampAB.max())} | ${cell(lampIso.max())} | ${cell(heroAB.max())} | ${cell(meshIso.max())} | ${cell(rimArc.max())} | ${cell(dayAB.max())} |")
        sb.appendLine()
    }

    // ── envelope: how far can each throttle ever move a pixel? ────────────────
    private fun envelope(sb: StringBuilder) {
        sb.appendLine("## Envelope ΔE — the most each throttle can ever do (full intensity, best input)")
        sb.appendLine()
        sb.appendLine("| palette | face | hero wash | hero mesh | daylight wash | daylight rim |")
        sb.appendLine("|---|---|--:|--:|--:|--:|")

        val wash = Agg(); val mesh = Agg(); val dWash = Agg(); val dRim = Agg()
        // The warmest low sun carries the most chroma, so it is the wash's best case.
        val temp = daylightAt(17).temperature

        for (p in AppPalette.entries) {
            val atmos = atmosphereFor(p.colors)
            val primary = p.colors.primary
            val face = if (p.isDark) "dark" else "paper"
            val base = atmos.paneFill().let { mixG(fieldColors(atmos).top, it, it.alpha) }
            val tamed = tameCover(loudWarm, primary)

            val washv = de(base, mixG(base, tamed, heroWashBorrow(p.isDark)))
            val meshv = de(base, mixG(base, tamed, heroMeshBorrow(p.isDark)))
            val dWashv = de(base, mixG(base, temp, DAYLIGHT_WASH_ALPHA_MAX))
            val dRimv = de(atmos.rimLight, mixG(atmos.rimLight, temp, DAYLIGHT_RIM_TINT_MAX))

            wash.add(washv); mesh.add(meshv); dWash.add(dWashv); dRim.add(dRimv)
            sb.appendLine("| ${p.id} | $face | ${cell(washv)} | ${cell(meshv)} | ${cell(dWashv)} | ${cell(dRimv)} |")
        }
        sb.appendLine("| **median** | | ${cell(wash.median())} | ${cell(mesh.median())} | ${cell(dWash.median())} | ${cell(dRim.median())} |")
        sb.appendLine("| **max** | | ${cell(wash.max())} | ${cell(mesh.max())} | ${cell(dWash.max())} | ${cell(dRim.max())} |")
        sb.appendLine()
    }

    private fun verdict(sb: StringBuilder) {
        sb.appendLine("## How to read it")
        sb.appendLine()
        sb.appendLine("- A **realised** column at sub-JND means two different books (or two times of day) produce the same room — the effect is wired but not felt.")
        sb.appendLine("- An **envelope** column at sub-JND means the throttle caps the effect below perception *by construction*: no cover, no sun angle can make it show. That is the safe set to raise.")
        sb.appendLine("- Raising a throttle is bounded by the contrast floors `FolioLampTest`/`DaylightTest` already pin; this report says where there is headroom, not that any floor may move.")
        sb.appendLine()
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun lamp(p: AppPalette, cover: Color): FolioLampLight? = lampOf(
        atmos = atmosphereFor(p.colors),
        authored = folioLightFor(p),
        themeColor = p.colors.primary,
        cover = cover,
        at = null,
        field = field,
        phase = 1.5707963f,
    )

    private fun hero(p: AppPalette, atmos: com.folio.reader.ui.theme.FolioAtmosphere, primary: Color, cover: Color, lamp: FolioLampLight?): Color {
        val lit = fieldColors(atmos, cover, atmos.fieldTintStrength)
        val pt = fieldColorAtPoint(lit, w, h, 0.5f * w, 0.4f * h, 0f, lamp = lamp)
        val pane = atmos.paneFill()
        val base = mixG(pt, pane, pane.alpha)
        return mixG(base, tameCover(cover, primary), heroWashBorrow(p.isDark))
    }

    /** The hero pane with the cover's own mesh pool laid over the wash. */
    private fun heroWithMesh(p: AppPalette, atmos: com.folio.reader.ui.theme.FolioAtmosphere, primary: Color, cover: Color, lamp: FolioLampLight?): Color =
        mixG(hero(p, atmos, primary, cover, lamp), tameCover(cover, primary), heroMeshBorrow(p.isDark))

    /** The pane's realised daylight hairline at [hour], exactly as `folioRaised` lays
     *  it on a translucent pane — the one time-of-day cue a paper pane carries. */
    private fun rimAt(atmos: com.folio.reader.ui.theme.FolioAtmosphere, hour: Int): Color =
        daylightRimTint(atmos.hairline, daylightAt(hour))

    private fun cell(v: Double): String = "%.2f %s".format(v, band(v))
    private fun band(v: Double): String = when {
        v < 1.0 -> "·"
        v < 2.3 -> "~"
        v < 5.0 -> "+"
        else -> "**"
    }

    private class Agg {
        private val xs = mutableListOf<Double>()
        fun add(v: Double) { xs.add(v) }
        fun median(): Double = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        fun max(): Double = xs.maxOrNull() ?: 0.0
    }

    private fun de(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a.toArgb()); val (l2, a2, b2) = lab(b.toArgb())
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private fun lab(argb: Int): Triple<Double, Double, Double> {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(argb shr 16 and 0xFF); val g = channel(argb shr 8 and 0xFF); val b = channel(argb and 0xFF)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}
