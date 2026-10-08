package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.lerp

/**
 * The **room scrim**: one permanent, soft, translucent shade laid over the ground plane and
 * under every screen in the app.
 *
 * ## The problem it answers
 *
 * Most of the app's interstitial type has no surface under it. Section headers, empty states,
 * captions and rail labels are laid straight onto the living field on the theory that the field
 * *is* the page. It is — but it is also an animation with a lamp bloom and three drifting silk
 * blooms moving through it, so a small-caps header that happens to land on a bloom loses its
 * ground to whatever the room is doing that second. On the warm faces the worst case is a
 * section header struck in one of the destination accents sitting on a field of nearly the same
 * hue: accent-on-field, with the bloom passing underneath.
 *
 * ## Why this is not the previous attempt
 *
 * The first answer was [com.folio.reader.ui.components.folioPageWash] — and it failed twice
 * over, which is worth recording because both failures are easy to repeat:
 *
 *  1. **It was opt-in.** Eighteen screens called it; everything else was left bare, so the app
 *     had two grounds and the reader found the seam. This one is attached **once**, inside the
 *     root's ground plane, so a screen cannot be left out by forgetting to call it and a new
 *     screen inherits it for free.
 *  2. **It was painted in the field's own colour** (`folioFieldBottom`). A wash in the colour of
 *     the thing it is washing changes nothing a reader can use: an orange veil over an orange
 *     field is still orange-on-orange. It only ever flattened the field's *brightest* bands into
 *     a flat slab of field colour — the "opaque field behind the entire thing" report.
 *
 * So this scrim is painted in **a deepened form of the field's own floor, carrying a measured
 * share of the chrome's tint hue** ([SCRIM_DEPTH], [SCRIM_CHROME_AFFINITY]). Three things follow,
 * and each one is a requirement rather than a nicety:
 *
 *  - **It darkens.** Driven to [SCRIM_DEPTH] the hue is far below anything in the room, so it
 *    shades instead of flattening. That is the part the wash got wrong.
 *  - **It does not go bland.** A scrim composites `a·S + (1-a)·F`. A *neutral* `S` — which is what
 *    [FolioAtmosphere.shadowAmbient] carries on a light face, `deepen(onSurfaceVariant, 0.35f)`,
 *    a low-chroma grey — *adds grey* to the field and visibly mutes it; over the warm faces it
 *    greys the teal bloom out. An `S` in the field's own family multiplies instead, so the room
 *    gets darker without losing its colour. This is why the obvious role is the wrong role: a
 *    neutral shadow colour is right under an 8dp card and wrong across the whole window.
 *  - **It belongs to the furniture.** [SCRIM_CHROME_AFFINITY] of [FolioAtmosphere.chromeAccent] —
 *    the hue the bars and the capsule are tinted with — means the back screen and the chrome read
 *    as the same world. That is the cheap, correct form of "make the bars and the background the
 *    same thing": share the colour family, not the material. Sharing the *material* would mean a
 *    full-screen blur pane, the single most expensive surface the app could own, and it would
 *    smooth away the very field structure the glass exists to refract.
 *
 * Grain is deliberately **not** part of this layer. Unifying the chrome's noise with the back
 * screen was considered and rejected: several field families are already grain
 * ([FolioFieldKind.GrainGradient], [FolioFieldKind.PaperTexture]), so a second noise pass over the
 * whole window would be redundant texture bought at full-screen cost, against two open
 * frame-time issues. The field's own texture also dithers this gradient, so the usual
 * anti-banding reason does not apply either.
 *
 * ## The shape is the design
 *
 * A uniform fill at any alpha is a dead rectangle, which is precisely the thing that "looks
 * really bad". So the scrim is a **continuous eased vertical ramp** with no flat region anywhere:
 * strongest at the crown (under the status icons and the masthead), easing over nearly half the
 * viewport to its floor at the optical centre, then rising again — a hair softer — toward the
 * foot where the nav capsule sits. The asymmetry is deliberate and matches the atmosphere's own
 * lighting model (one source above and slightly leading): a room is darker at its ceiling-line
 * and its floor than through the middle, and that reads as depth rather than as a sheet.
 *
 * The floor is **not** zero. A ramp that fell to nothing mid-screen would abandon the very type
 * it exists to ground — a header at mid-height, a two-line empty state — which is the report
 * this answers. [WAIST_FRACTION] keeps a documented fraction of the peak everywhere.
 *
 * ## Cost
 *
 * One `drawRect` with one `Brush.verticalGradient` per frame, on a node that already draws. No
 * graphics layer, no composition, no second pass over the field, and nothing read in composition
 * that moves per frame — [FolioTheme.atmosphere] is remembered on `(colors, packId)`, and both
 * roles the shade is built from are palette-derived: [FolioAtmosphere.fieldBottom] from the two
 * page planes and [FolioAtmosphere.chromeAccent] from the accent roles. Neither tracks the
 * ambient cover tint, so a scroll or a book change cannot recompose this. (That is the reason the
 * shade is built from `atmos.fieldBottom` and **not** from the
 * `com.folio.reader.ui.components.folioFieldBottom()` *composable*, which does read
 * `LocalFolioAmbientTint` and would recompose per book.) It is a strict **reduction**: it replaces
 * eighteen per-screen gradient fills with one.
 *
 * ## Contrast trade-off, stated honestly
 *
 * A darkening scrim raises contrast for light ink on a dark field (the dark faces, where a bloom
 * under white type is the real legibility failure) and lowers it slightly for ink that is already
 * darker than the field. Measured on a representative light face that is a move from roughly
 * 7.7:1 to 6.7:1 for body ink on the field — still well clear of the AA floor — in exchange for
 * the field's chroma variance under the glyph dropping, which is the part a contrast ratio cannot
 * see. Mid-luminance **accent** type on a light field is not fixed by a scrim in either
 * direction; that needs its own ground
 * ([com.folio.reader.ui.components.folioTypeGround]) or an ink role, and is out of this layer's
 * scope by construction.
 */
object FolioRoomScrim {
    /**
     * Peak alpha on a **light** face, at the crown.
     *
     * Paper shows a veil readily, so this is the larger of the two and still leaves body ink on
     * the field comfortably over its AA floor. Tune with [DARK_PEAK_ALPHA], not alone: the pair
     * is what keeps the shade reading as the same depth on both polarities.
     */
    const val LIGHT_PEAK_ALPHA = 0.14f

    /**
     * Peak alpha on a **dark** face, at the crown.
     *
     * Lower than [LIGHT_PEAK_ALPHA] on purpose. A dark field has far less headroom before the
     * shader's drift and the lamp's bloom — the theme's whole identity — are muted into a flat
     * dark plane, and the field is what the glass refracts.
     */
    const val DARK_PEAK_ALPHA = 0.10f

    /**
     * What survives at the **waist** — the optical centre — as a fraction of the crown.
     *
     * The floor of the ramp, and deliberately not zero: a wash that fell to nothing mid-screen
     * would abandon the very type it exists to ground. At the shipped peaks this lands the middle
     * of the screen at 0.084 (light) and 0.060 (dark). Named for the same crown/waist/foot band
     * the masthead's own fill profile uses ([FolioBarFill]).
     */
    const val WAIST_FRACTION = 0.60f

    /**
     * The foot's peak, as a fraction of the crown's.
     *
     * Just under parity: the room is lit from above, so its floor catches a little more light
     * than its ceiling-line. A symmetric ramp reads as a vignette pasted on the window; this
     * reads as a room.
     */
    const val FOOT_FRACTION = 0.94f

    /** The shipped peak alpha for a face of this polarity. */
    fun peakAlphaFor(isDark: Boolean): Float =
        if (isDark) DARK_PEAK_ALPHA else LIGHT_PEAK_ALPHA

    /**
     * How far the field's own floor colour is driven toward black before it is used as the shade.
     *
     * This is the token that separates this scrim from [com.folio.reader.ui.components.folioPageWash],
     * and the distinction is the whole reason the wash failed. The wash painted `fieldBottom` *at
     * its own lightness*: a colour with nearly zero luminance distance from the thing it was laid
     * over, which is why it flattened the field's bright bands instead of shading them. Taken to
     * 0.78 the same hue becomes a genuine shade — far darker than any part of the field, so it
     * darkens — while remaining unmistakably **the field's hue** rather than a neutral.
     *
     * That last part is the point. A scrim is composited `a·S + (1-a)·F`, so a *neutral* S adds
     * grey to F and visibly mutes it; a S in F's own family multiplies it and leaves the chroma
     * reading. Low enough to still be a shade, high enough to still be the theme.
     */
    const val SCRIM_DEPTH = 0.78f

    /**
     * How much of the chrome's own tint hue is mixed into the shade before it is deepened.
     *
     * Small, and deliberately not zero. The bars and the nav capsule are tinted from
     * [FolioAtmosphere.chromeAccent] — "the crown's own family" — and giving the back screen a
     * measured helping of that same hue is what makes the furniture read as belonging to the room
     * instead of being pasted onto it. It is the cheap, correct form of "make the bars and the
     * background the same thing": share the **colour family**, not the material. Sharing the
     * material would mean a full-screen blur pane, which is the single most expensive surface the
     * app could own and would smooth the field's structure away — the two things this release is
     * trying to fix.
     *
     * Mixed with [mixG], whose invariant is that hue moves and luminance does not, so the affinity
     * is a pure hue decision and [SCRIM_DEPTH] remains the only thing setting the shade's depth.
     */
    const val SCRIM_CHROME_AFFINITY = 0.22f
}

/** One eased stop on the ramp: [base] at `lerp(from, to, t)`, clamped. */
private fun scrimStop(base: Color, from: Float, to: Float, t: Float): Color =
    base.copy(alpha = lerp(from, to, t).coerceIn(0f, 1f))

/**
 * Paints the [FolioRoomScrim] behind this node's content.
 *
 * **Attach it once, as the last thing inside the app's ground plane** — inside
 * `Modifier.folioRoomSource()`'s subtree, over the field and the lamp, under everything else.
 * See `MainActivity` for the one call site and the reasoning: inside the room rung means in-flow
 * glass frosts and refracts the room *as the reader sees it* rather than the raw field, so a card
 * cannot read brighter than the screen it is sitting on.
 *
 * This is a pure draw-phase gradient — no Haze effect, no glass, no page — so attaching it inside
 * the room rung does not touch that rung's rule that nothing glassy may be placed in its subtree.
 *
 * [peakAlpha] is the one knob; pass 0 to disable the layer entirely (the modifier becomes an
 * identity and nothing is drawn).
 */
@Composable
fun Modifier.folioRoomScrim(
    atmos: FolioAtmosphere = FolioTheme.atmosphere,
    peakAlpha: Float = FolioRoomScrim.peakAlphaFor(atmos.isDark),
): Modifier {
    if (peakAlpha <= 0f) return this
    // The shade's colour. Not `shadowAmbient`, and not a raw field stop — see SCRIM_DEPTH and
    // SCRIM_CHROME_AFFINITY. In short: the field's own floor, nudged toward the hue the chrome is
    // tinted with, then driven down to something genuinely darker than anything in the room.
    val base = deepen(
        mixG(atmos.fieldBottom, atmos.chromeAccent, FolioRoomScrim.SCRIM_CHROME_AFFINITY),
        FolioRoomScrim.SCRIM_DEPTH,
    )
    val crown = peakAlpha.coerceIn(0f, 1f)
    val waist = crown * FolioRoomScrim.WAIST_FRACTION
    val foot = crown * FolioRoomScrim.FOOT_FRACTION
    return this.drawWithCache {
        // Size-independent: `verticalGradient`'s default endY resolves against the draw size, so
        // this brush is built once per theme rather than once per frame or per layout.
        //
        // Eight stops and no flat run anywhere: the crown eases over nearly half the viewport to
        // the waist, then rises again to a foot a hair softer than the crown. That continuity is
        // the whole difference between atmospheric depth and a grey sheet.
        val brush = Brush.verticalGradient(
            0.00f to scrimStop(base, crown, waist, 0.00f),
            0.10f to scrimStop(base, crown, waist, 0.30f),
            0.24f to scrimStop(base, crown, waist, 0.68f),
            0.40f to scrimStop(base, crown, waist, 0.92f),
            0.56f to scrimStop(base, crown, waist, 1.00f),
            0.74f to scrimStop(base, waist, foot, 0.30f),
            0.88f to scrimStop(base, waist, foot, 0.72f),
            1.00f to scrimStop(base, waist, foot, 1.00f),
        )
        onDrawBehind { drawRect(brush) }
    }
}
