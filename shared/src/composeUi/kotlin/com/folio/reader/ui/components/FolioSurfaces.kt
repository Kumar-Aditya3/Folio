package com.folio.reader.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.folio.reader.ui.theme.DAYLIGHT_POOLS
import com.folio.reader.ui.theme.DAYLIGHT_RIM_TINT_MAX
import com.folio.reader.ui.theme.DAYLIGHT_WASH_ALPHA_MAX
import com.folio.reader.ui.theme.FolioAmbient
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioGlassLens
import com.folio.reader.ui.theme.FolioGlassTier
import com.folio.reader.ui.theme.FolioPressFocal
import com.folio.reader.ui.theme.folioPressFocalOrigin
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioAmbient
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.LocalFolioGuillocheActive
import com.folio.reader.ui.theme.LocalFolioPackId
import com.folio.reader.ui.theme.LocalFolioSectionAccent
import com.folio.reader.ui.theme.LocalFolioSurfaceDepth
import com.folio.reader.ui.theme.ambientPoolDrift
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.fieldDrift
import com.folio.reader.ui.theme.fieldT
import com.folio.reader.ui.theme.folioGlassLens
import com.folio.reader.ui.theme.folioLiquidGlass
import com.folio.reader.ui.theme.folioSheenGain
import com.folio.reader.ui.theme.rememberShaderSupported
import com.folio.reader.ui.theme.lightDirection
import com.folio.reader.ui.theme.poolAlphaAt
import com.folio.reader.ui.theme.poolCenterAt
import com.folio.reader.ui.theme.panelFill
import com.folio.reader.ui.theme.panelKnobScale
import com.folio.reader.ui.theme.rememberGlassTick
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.swungBy
import com.folio.reader.ui.theme.LocalFolioSurfaceFill
import kotlin.math.abs
import kotlin.math.min

/**
 * Folio's material system.
 *
 * Five materials, each with a job. A screen reads as art-directed when surfaces
 * *disagree* — one thing floats, one thing is pressed in, one thing is only type
 * on the page. Uniform cards are what made the previous build read as scaffolding.
 *
 * | Material | Reads as | Use for |
 * |---|---|---|
 * | [folioRaised] | floating above the page | heroes, the one card that matters |
 * | [folioPanel] | resting on the page | grouped content |
 * | [folioSunken] | pressed into the page | wells: charts, heatmaps, inputs |
 * | [folioVeil] | glass over content | bars, nav, sheets |
 * | *(nothing)* | type directly on the field | most rows and lists |
 *
 * The lighting is consistent: one source, now tracked to the reader's local hour
 * by [FolioDaylight] — above and leading at the day's neutral, swinging to
 * wherever the sun actually is across the day. Raised things catch it on the lit
 * edge and occlude opposite; sunken things do the exact inverse. That single rule
 * makes depth legible without putting a shadow on everything. [FolioDaylight.Neutral]
 * (sun overhead, intensity 0) collapses every surface here back to the fixed
 * top-light they had before the room learned the time, so an un-lit tree — a test,
 * a preview — is unchanged.
 *
 * **Depth cues are claims about the page.** A shadow, a lit rim and an edge bevel
 * all describe the relationship between a surface and the ground behind it, and all
 * three go false when the ground is another surface: the shadow lands on the
 * parent's fill, and each rim traces one more rounded rectangle a few pixels inside
 * the last. [com.folio.reader.ui.theme.LocalFolioSurfaceDepth] carries how deep a
 * node is so these materials can spend only the cues still worth spending —
 * [folioPanel], [folioRaised] and [folioVeil] all read it. [folioSunken] does not,
 * and should not: a well is an *inversion*, so it stays legible as a recess however
 * it is nested, and it carries no border to duplicate.
 */

/** Elevation is a token, not a literal, and scales per atmosphere. */
private fun FolioAtmosphere.scaled(dp: Dp): Dp = dp * shadowScale

/**
 * The same light stripped of its hue: a neutral grey of equal relative luminance,
 * alpha preserved. Used for the reader's calm glass so a light catch keeps its
 * brightness but no longer carries the reading theme's secondary (the "book
 * colour") on the edge. A catch that is already neutral white stays white.
 */
private fun Color.folioNeutralized(): Color {
    val grey = red * 0.2126f + green * 0.7152f + blue * 0.0722f
    return Color(grey, grey, grey, alpha)
}

/**
 * The gradient axis the day's light falls along, as (start, end) offsets in the
 * surface's own pixels: start on the anti-sun edge, end on the sun-facing edge.
 * Taken from [FolioDaylight.lightDirection], whose horizontal push is scaled by
 * elevation, so after dark this is straight bottom→top — the fixed vertical
 * gradient these materials always had. The span is the box's projection onto
 * the direction (its support function), so the ramp runs corner to corner whatever
 * the angle and never foreshortens into a band.
 *
 * [ambient] is §17's living light: it swings the azimuth around wherever the
 * hour put it (see [ambientAzimuth]), which is what makes a resting surface's
 * sheen slowly travel instead of sitting still. The default is the neutral
 * identity, so callers that do not pass it — including every test and preview
 * — get today's byte-for-byte axis.
 */
internal fun daylightGradient(
    size: Size,
    daylight: FolioDaylight,
    ambient: FolioAmbient = FolioAmbient.Neutral,
): Pair<Offset, Offset> {
    val (dx, dy) = daylight.swungBy(ambient).lightDirection()
    val span = size.width * abs(dx) + size.height * abs(dy)
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val reach = Offset(dx * span * 0.5f, dy * span * 0.5f)
    return (center - reach) to (center + reach)
}

/**
 * The surface's own outline as a [Path], so a directional sheen can be painted
 * *inside* the shape rather than over its bounding box.
 *
 * `Shape.createPath` is not public in Compose 1.7 — the interface exposes only
 * [Shape.createOutline] — and `Outline.Rounded`'s own path accessor is internal,
 * so the rounded case is rebuilt from its [RoundRect]. [CacheDrawScope] is a
 * [Density], which is what `createOutline` asks for.
 *
 * Internal rather than private because [folioThemeRim] traces the same outline and
 * must not fork a second copy of this rounded-case workaround (Rule 3).
 */
internal fun CacheDrawScope.shapePath(shape: Shape): Path =
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }


/**
 * How far a pane's fill is lerped toward the caller's accent. Enough that the hue of
 * the place is legible as *colour* across the whole plane (not a tinted grey), while
 * leaving the fill light/dark enough to keep its text at the authored contrast.
 */
private const val FOLIO_PANE_ACCENT_TINT = 0.22f

/**
 * How far a [folioVeil] glass pane pulls its base plane toward the section accent the
 * caller passes. Smaller than the opaque-pane tint above — the veil is translucent glass,
 * so a gentle pull is enough to let different sections read as different surfaces instead
 * of one monotone room shade, without turning the glass into a flat colour chip.
 */
private const val FOLIO_VEIL_ACCENT_TINT = 0.14f

/**
 * How far an *opaque* resting tier ([folioPanel], and an opaque [folioRaised] slab) dyes its
 * whole plane toward the section accent. The planes used to carry the accent only on their 1dp
 * rim, so four different sections read as one near-neutral room shade (the "everything is one
 * colour" bug). A modest plane tint lets each place wear its own hue while keeping the fill light
 * / dark enough to hold its text at the authored contrast.
 */
private const val FOLIO_PANEL_PLANE_TINT = 0.16f

/** The embedded-well plane tint. A well sits inside a panel, so it pulls a touch harder than the
 * panel to stay distinguishable from the plane it is sunk into. */
private const val FOLIO_SUNKEN_PLANE_TINT = 0.18f

/**
 * How much of its fill the plane keeps at its *foot* — the far end of the gentle vertical ramp
 * every resting plane is painted with.
 *
 * P7: the plane used to be a flat `background(fill)`, which means it had exactly one value. A
 * single hard value is what the eye reads as a pasted rectangle, and it is also what a
 * neighbouring tier has to *step* away from rather than blend toward. Falling 6% from top to
 * bottom gives the surface a top and a bottom — the same trick the masthead's fill decay uses —
 * so no single fill alpha exists to be compared against, and the tier boundary becomes a ramp
 * rather than a box edge. Kept tiny on purpose: this is a light gradient, not a two-tone plane.
 */
private const val FOLIO_PLANE_RAMP_FOOT = 0.94f

/**
 * How far a plane's *foot* is pulled toward the page's own lower endpoint
 * ([folioFieldBottom]).
 *
 * §18, and the companion to [FOLIO_PLANE_RAMP_FOOT]: that constant varies the plane's
 * alpha down its height, this one varies its *colour*. The two together are what stop a
 * surface being comparable to the field by a single number.
 *
 * The reason it is needed at all is a cost decision taken elsewhere. `folioGlassEffect`'s
 * KDoc is explicit that a surface's body should come from `bodyTint` — a surface-coloured
 * tint laid *inside* the Haze pass, which "rides the blurred (spatially varying) backdrop
 * rather than sitting on it as a flat lid" — and that raising the external fill instead
 * "only ever buries the blur and reads as a dead square". But `FolioGlassTier.InFlow`
 * surfaces do not run a Haze pass at all, so there is no blur for a body tint to ride,
 * and a flat lid is precisely what they are left with. Lerping the foot toward the field's
 * own ramp is the honest version of that idea available without a blur: the plane stops
 * being one colour, and the direction it varies in is the direction the room varies in.
 *
 * Kept low. Above about a quarter the foot of a card starts reading as a separate band
 * rather than as the plane thinning into the page, which is a box again with softer
 * corners.
 */
private const val FOLIO_PLANE_ROOM_SHARE = 0.14f


/**
 * §19 — the nested division. How much of its hairline a surface keeps when it is drawn *inside*
 * another surface, where the fill is floored to [FOLIO_EMBEDDED_FILL].
 *
 * ## The two regimes this file has now tried
 *
 * **Full fill + full rim** (0.38, then 0.5): the rim was read as **a second rectangle a few
 * pixels inside the first** — reported on the device, and removed. The lesson was real, and it
 * is the reason a nested rim must never again ring a *different material*: back then the
 * nested surface also carried its own tier's full fill (plus, before P7, a shadow and a
 * bevel), so the rim was a third edge cue stacked on a plane change, and the eye read the
 * closed path as a second object.
 *
 * **Inherited fill + no rim** (0.0, P7): the plane change went away and so did the line — and
 * the report that came back was the mirror image: a fill change with no line is two
 * *materials* meeting, which is the nested look, and a fill with no change and no line is not
 * a division at all. Structure inside a container had stopped being expressible.
 *
 * ## The rule now
 *
 * At depth >= 1 an inner surface is **structure in one object**, not a second object: it wears
 * its parent's plane at [FOLIO_EMBEDDED_FILL] of the parent's alpha — a whisper of bed for
 * small type, never a second material — and it carries the division in its rim alone: this
 * fraction of the usual hairline, on the short `Inset` ramp, with no shadow and no bevel
 * (the other two cues that lied about a page that is not there). One glass, one fill, one
 * blur, with a hairline marking the division.
 *
 * Both numbers are single dials and the failure modes are known: rim 0 hides structure again;
 * fill 1 restores the second material; and a rim above ~0.7 starts ringing the box the old
 * report named, so tune down, not up, if the division reads too loud.
 */
private const val FOLIO_EMBEDDED_RIM = 0.6f

/**
 * The fraction of its parent's plane *alpha* a nested surface paints, at depth >= 1. See
 * [FOLIO_EMBEDDED_RIM] — the fill's job inside another surface is a calm bed for small type,
 * so it is a whisper of the parent's plane rather than a full step. `1f` restores the pre-§19
 * full inherited plane (P7's behaviour); `0f` is rim-only.
 */
private const val FOLIO_EMBEDDED_FILL = 0.25f

/**
 * Floating surface. The highest material — at most one or two per screen.
 *
 * [accent] mixes into the light catch, so a hero lit by a book cover reports that
 * cover's colour at its edge. That is what starts making the cover behave like an
 * object in the room rather than a picture in a box. [daylight] then decides
 * *which* edge that is and how warm the catch runs: the rim gradient is laid along
 * the sun's actual direction and tinted a small fraction toward its colour, so a
 * 7am card is caught from the leading edge in amber and a 6pm card from the
 * trailing edge.
 */
@Composable
fun Modifier.folioRaised(
    shape: Shape = FolioShapes.card,
    elevation: Dp = FolioTokens.elevationRaised,
    accent: Color? = LocalFolioSectionAccent.current,
    fill: Color? = null,
    daylight: FolioDaylight = LocalFolioDaylight.current,
    weight: com.folio.reader.ui.theme.FolioGlassWeight =
        com.folio.reader.ui.theme.FolioGlassWeight.Raised,
    /**
     * Which layer this surface belongs to — see [FolioGlassTier]. A raised surface is a
     * *pane* whenever its fill is translucent, and a pane that is an item in a list (a shelf
     * entry, a stats block, a detail header) has nothing behind it but the ground plane, so
     * it belongs in `InFlow`. Only a surface that genuinely floats over content — a modal, a
     * sheet, a hero with the page scrolling under it — keeps the default.
     */
    tier: FolioGlassTier = FolioGlassTier.Overlay,
): Modifier {
    val atmos = FolioTheme.atmosphere
    // The metallic-sheen knob: one gain for every light catch this material paints —
    // the directional sheen below and the lens's specular rim. Shadows, fills and
    // hairlines are untouched; only the light response dims.
    val sheenGain = folioSheenGain()
    // §20: the Cards and Sheets knob reaches the pane family here, once, at the source of
    // every fill this material can paint — the default plane and any caller-named one —
    // so Home's, Stats' and the shelves' raised cards thin with the same scale the More
    // hub's veils thin by. Identity at the knob's rest point, so the designed look is
    // byte-for-byte while the slider sits at its default. See
    // [com.folio.reader.ui.theme.panelKnobScale].
    val base = (fill ?: atmos.raisedFill).let {
        it.copy(alpha = FolioTheme.surfaceOpacity.panelKnobScale(it.alpha))
    }
    val accentLight = if (accent != null) {
        lerp(atmos.rimLight, accent, 0.16f).copy(alpha = atmos.rimLight.alpha)
    } else {
        atmos.rimLight
    }
    // The sun colours the catch; it never replaces it. Capped at
    // DAYLIGHT_RIM_TINT_MAX and scaled by intensity, so the rim stays the one the
    // atmosphere designed — merely warmed or cooled. At intensity 0 (Neutral,
    // night) the tint is skipped outright, so the rim is byte-for-byte the
    // palette's own with no Oklab round-trip to nudge it a sub-step off.
    val tint = DAYLIGHT_RIM_TINT_MAX * daylight.intensity
    val light = if (tint <= 0f) {
        accentLight
    } else {
        lerp(accentLight, daylight.temperature, tint).copy(alpha = accentLight.alpha)
    }
    val shade = atmos.rimShade
    // §17: the living light, read in the draw phase only (FolioShimmer
    // precedent) so the travelling sheen costs one drawing pass, never a
    // recomposition. The still-room default keeps the identity byte-for-byte.
    val ambient = LocalFolioAmbient.current
    // A pane (translucent fill) shows the lit room through itself, and the room plus the
    // caller's themed rim already give it depth. The directional sheen and the 1dp
    // gradient border were drawn for an *opaque* raised slab; stacked on a pane they laid
    // a bright rim and a darker perimeter band just inside it — two nested rounded rects,
    // the "box within the card" that showed on every raised surface. So a pane keeps only
    // its fill, shadow and clip; an opaque raised surface keeps the full treatment.
    //
    // A surface drawn *inside* another surface has the same problem for a different
    // reason: there is no page under it to float above. Its shadow lands on its parent's
    // fill and its gradient border rings a second rect inside the parent's own rim, which
    // is the box-inside-a-box texture every Settings control carried. So embedded, a
    // raised surface takes the pane branch whatever its fill's alpha — fill, one faint
    // uniform rim, nothing else. The accent tint still reads off the *authored*
    // translucency, so a nested slab's colour is byte-for-byte the one it had; only the
    // depth cues move. See LocalFolioSurfaceDepth.
    val embedded = LocalFolioSurfaceDepth.current > 0
    val translucent = base.alpha < 1f
    val pane = translucent || embedded
    // The accent reaches *area*, not just the rim — the direct fix for "every screen is one
    // colour": the accents were spent only on hairline rims and small type, never on the planes
    // that cover the screen. A pane's fill (and now an opaque slab's) is lerped toward the
    // caller's / section's accent so each card wears the hue its place owns. Lightness/alpha are
    // preserved (tint the opaque colour, re-apply the original alpha), so legibility against the
    // fill is unchanged — only the hue moves. An opaque slab pulls a touch harder than a pane
    // because it has no frosted field behind it to carry the hue.
    // §19: inside another surface the raised material's own plane is not honest — there is
    // no page to float above. Wear the parent's plane at the embedded floor instead; a
    // caller that named its own [fill] has decided and still wins.
    val fillColor = if (embedded && fill == null) {
        val plane = LocalFolioSurfaceFill.current ?: base
        plane.copy(alpha = plane.alpha * FOLIO_EMBEDDED_FILL)
    } else if (accent != null) {
        val t = if (translucent) FOLIO_PANE_ACCENT_TINT else FOLIO_PANEL_PLANE_TINT
        lerp(base.copy(alpha = 1f), accent, t).copy(alpha = base.alpha)
    } else {
        base
    }
    // Uniform edge for a pane: the palette hairline, warmed toward the caller's
    // accent exactly as folioPanel does. One flat colour on the silhouette — not a
    // directional gradient — so it defines the glass edge without a nested-rect band.
    val paneRimBase = if (accent != null) {
        lerp(atmos.hairline, accent, 0.30f).copy(alpha = atmos.hairline.alpha + 0.14f)
    } else {
        atmos.hairline
    }
    val paneRim = if (embedded) {
        paneRimBase.copy(alpha = paneRimBase.alpha * FOLIO_EMBEDDED_RIM)
    } else {
        paneRimBase
    }
    // A pane is translucent glass, so it should frost the moving field behind it
    // rather than let it show through sharp as a flat tint — that was the "glass
    // that is only a tint in front of the screen" failure. When the device can blur
    // and the screen has registered a backdrop, prepend the same Haze layer the veil
    // uses, under the fill; the pane's own translucent fill (paneFill ~0.64) then
    // reads as frosted glass over the blur. Opaque raised slabs are lids where the
    // blur is invisible, so they skip it and pay nothing. So does an embedded pane:
    // the backdrop it would sample is its own parent's glass, so the pass costs a
    // second full-surface blur to frost something already frosted.
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    // §20 reach: an in-flow raised pane (Home's blocks, Stats' blocks, shelf items) upgrades
    // to the full overlay glass treatment — blur, lens, pane edge, cast-shadow suppression —
    // when the user has not opted into minimal glass and the device can actually blur. Gated
    // on a live backdrop so the opt-out, and every no-blur device, keep the in-flow look
    // untouched: painted glass, card edge, no refraction of nothing.
    val effectiveTier = if (
        tier == FolioGlassTier.InFlow && LocalGlassInFlowGlass.current && caps.blur && backdrop != null && !embedded
    ) FolioGlassTier.Overlay else tier
    val blurred = pane && !embedded && caps.blur && backdrop != null && effectiveTier == FolioGlassTier.Overlay
    // §20.1: the frost and the body thin with the surface's own knob — the reader
    // sheets answer to the readerChrome knob (they name their fillAlpha),
    // everything else to the panel knob — or a blurred surface ignores the
    // transparency slider from the inside.
    val paneWeightScale = com.folio.reader.ui.theme.glassWeightScale(false)
    val blurLayer = if (blurred) {
        Modifier
            .clip(shape)
            .folioGlassEffect(
                backdrop = backdrop,
                backgroundColor = folioFieldBottom(atmos),
                blurRadius = GlassSpec.Default.blurRadius * weight.blurScale,
                // Chromatic frost toward the caller's accent (falling back to the
                // theme primary): a raised pane picks up its own hue so it floats
                // as a tinted slab rather than a neutral blur. Off when the weight
                // asks for none. §20.1: thins with the panel knob like the fill.
                tint = if (weight.frostTintAlpha > 0f) {
                    (accent ?: FolioTheme.colors.primary).copy(alpha = weight.frostTintAlpha * paneWeightScale)
                } else {
                    Color.Unspecified
                },
                // Frosted body toward the pane's own fill colour, under the accent
                // frost: this is what lets a raised pane be opaque and glass at
                // once. The surface-coloured tint rides the blur so the slab gains
                // the body of a real frosted material while the field still bleeds
                // through — the thickness comes from a tint over the blur, not from
                // a heavier flat fill that would bury it. Off when the weight asks
                // for none.
                bodyTint = if (weight.bodyTintAlpha > 0f) {
                    fillColor.copy(alpha = weight.bodyTintAlpha * paneWeightScale)
                } else {
                    Color.Unspecified
                },
                // §16 cost model: a raised pane is a *pane* — a hero, a card, a tile —
                // so it takes the pane budget, times the device's own. See
                // [FolioGlassCost]: the blur radius scales with the input scale, so this
                // is a ninth of the fill cost at the same visual radius, not a thinner
                // frost.
                inputScale = FolioGlassCost.resolve(FolioGlassCost.pane, caps),
            )
    } else {
        Modifier
    }
    // §16.2 the lens — the term that makes a pane read as glass rather than as a tint over
    // the screen. It is the *displacement* of the real room at the edge, so it only belongs
    // where there is a room showing through: an opaque raised lid has none, and an embedded
    // pane's backdrop is its parent's own glass. See [folioGlassLens].
    //
    // Gated on `specular` with the rest of the material's light response, and on an AGSL
    // platform, so the pref-off and pre-33 chains stay exactly what they were.
    val roomPlate = if (effectiveTier == FolioGlassTier.Overlay) glassRoomPlate() else null
    val lensable = pane && !embedded && caps.specular && rememberShaderSupported()
    val lensing = lensable && roomPlate != null
    // §18 — the edge, off the one ladder. An embedded raised slab takes the shorter
    // `Inset` ramp; an overlay pane (a modal, a hero with the page running under it) takes
    // `Pane`; everything in the page's own flow takes `Card`, which is the rung that
    // matters most here because `folioRaised` is also the material under Home's blocks,
    // Stats' blocks and the library's shelf items.
    val edgeProfile = (
        when {
            embedded -> FolioEdgeRole.Inset
            effectiveTier == FolioGlassTier.Overlay -> FolioEdgeRole.Pane
            else -> FolioEdgeRole.Card
        }
        ).profile
    // Same room-agreeing foot every other plane now carries. An opaque slab's foot keeps
    // its alpha at 1 and only moves in colour, so a lid stays a lid.
    val raisedFoot = lerp(fillColor, folioFieldBottom(atmos), FOLIO_PLANE_ROOM_SHARE)
        .copy(alpha = fillColor.alpha * FOLIO_PLANE_RAMP_FOOT)
    val lensLayer = if (lensing) {
        val (lensLx, lensLy) = daylight.lightDirection()
        Modifier.folioGlassLens(
            plate = roomPlate,
            shape = shape,
            // The pane's **whole** plane, not a fraction of it. This used to be
            // `fillColor.alpha * 0.35f`, on the reasoning that "the full alpha is already
            // painted underneath, and re-applying it inside the lens would mute the one
            // thing the pass exists to show". The premise is wrong: the lens does not
            // composite *over* the plane, it composites over the *room* and then replaces
            // the plane at its own alpha, so a third of the plane inside the band against
            // the whole of it outside is a material step, and the step's boundary is a
            // closed curve — the hero's reported "shape inside the card". Carrying the
            // full plane makes the band the same material as the body, displaced.
            tint = fillColor,
            highlight = light,
            lightX = lensLx,
            lightY = lensLy,
            // The metallic-sheen knob reaches the lens as the specular *catch* only:
            // the refraction, dispersion and far wall are the glass, the catch is the
            // metal, and only the metal dims.
            specular = FolioGlassLens.specular * sheenGain,
        )
    } else {
        Modifier
    }
    return this
        .then(blurLayer)
        .then(
            // A translucent pane lets its *own* elevation shadow show through from
            // behind — Android occludes a shadow by the caster's alpha, so a 55% pane
            // passes ~45% of the shadow, densest just inside the silhouette. That is the
            // darker rounded band hugging the edge — the "box within the card" — and it
            // is on every raised pane because every one is elevated. A pane does not need
            // a cast shadow to float: the room shows through it and the themed rim rings
            // it. Opaque raised surfaces keep their shadow.
            if (pane) Modifier
            else Modifier.shadow(
                elevation = atmos.scaled(elevation),
                shape = shape,
                ambientColor = atmos.shadowAmbient,
                spotColor = atmos.shadowSpot,
            ),
        )
        // §18 — the plane, feathered. This was a flat `background(fillColor, shape)`: one
        // value inside a hard clip, which is the step function the whole §18 note is about.
        // It is also the material behind Home's and Stats' blocks and the library shelf
        // items, so it is the same box at a different scale. See [folioSurfacePlane].
        .folioSurfacePlane(shape, fillColor, edgeProfile, raisedFoot)
        .then(lensLayer)
        .drawWithCache {
            // §18: the catch is laid on the edge stack's core, not on the silhouette — a
            // sheen whose brightest row *is* the surface's outer edge is a second boundary
            // cue painted over the first one.
            val path = folioEdgeCorePath(shape, edgeProfile)
            onDrawBehind {
                // Anti-sun edge occludes, sun-facing edge catches. At neutral daylight
                // the sun is straight overhead, so this is the old top-light / bottom-
                // shade vertical gradient with the transparent stop in the same place.
                // The axis is recomputed here rather than cached so the ambient swing
                // moves it — a few float ops per frame, against a recomposition per
                // frame for anything cached in composition. Opaque surfaces only: a pane
                // gets its light from the room behind it, not a sheen painted on top.
                // P7: one edge per surface. An in-flow surface draws its rim and nothing else —
                // no bevel (already overlay-only), no broad sheen. §Metal: panes now carry a
                // *tight* top catch (the last 14% of the light axis, well inside the core
                // path) instead of none — a pane gets its light from the room behind it, and
                // a metal pane also catches the room's light on its chamfer. The broad wash
                // stays off: a second light next to a rim is still what the eye reads as a
                // nested rectangle.
                if (effectiveTier == FolioGlassTier.Overlay) {
                    val (start, end) = daylightGradient(size, daylight, ambient.value)
                    val sheen = if (pane) {
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.86f to Color.Transparent,
                            1f to light.copy(alpha = light.alpha * 0.40f * sheenGain),
                            start = start,
                            end = end,
                        )
                    } else {
                        Brush.linearGradient(
                            0f to shade.copy(alpha = shade.alpha * 0.5f),
                            0.5f to Color.Transparent,
                            1f to light.copy(alpha = light.alpha * 0.85f * sheenGain),
                            start = start,
                            end = end,
                        )
                    }
                    drawPath(path, sheen)
                }
            }
        }
        .then(
            // Opaque raised slabs keep the directional light→shade gradient border
            // (it reads as a lit top edge and shaded bottom on a solid lid). On a
            // *pane* that same gradient laid a bright-then-dark perimeter just inside
            // the fill — the "box within the card". But a pane with NO edge at all is
            // bodiless: it dissolves into the room with nothing to catch the eye. So a
            // pane gets a single *uniform* hairline — the same rim folioPanel uses,
            // which never reads as a nested rect — giving it an edge and a body without
            // the directional band.
            //
            // §18: both are now ramps rather than outlines, which is the last hard line
            // this file drew. The directional case keeps its brush — the brush varies
            // along the surface's axis and the rim bands vary along its normal, so the
            // two are independent and a lit top edge survives being feathered.
            //
            // **And both are off when the lens runs.** [folioVeil] has carried this rule
            // since the lens landed and states the reason exactly — "an outer glassy rim
            // (the lens band) and an abrupt inner line (this), two edges derived from
            // different lies about the same boundary" — but `folioRaised` never got it,
            // and `folioRaised` is the material under the **home hero**. So the hero was
            // wearing the lens band *and* five painted rim taps peaking at ~0.25 alpha
            // 4.5dp further in, which is precisely the two-contour stack the rule exists
            // to prevent. One edge per surface; where the lens paints, the lens is it.
            when {
                lensing -> Modifier
                pane -> Modifier.folioSurfaceRim(shape, paneRim, edgeProfile)
                else -> Modifier.folioSurfaceRim(
                    shape,
                    Brush.verticalGradient(listOf(light, shade)),
                    edgeProfile,
                )
            },
        )
        .clip(shape)
}

/**
 * The plane a resting surface is painted with — [folioPanel], the shelf plate, and every container
 * that wraps content in a panel.
 *
 * One definition on purpose. A container has to be able to publish *the exact plane it is made of*
 * so the surfaces inside it can inherit it (see [LocalFolioSurfaceFill]), and a container that
 * recomputed it would be a second copy of this arithmetic waiting to drift from the first.
 */
@Composable
internal fun folioPanelPlane(accent: Color?): Color {
    val atmos = FolioTheme.atmosphere
    // §20: the flat panel family follows the Cards and Sheets knob too — identity at the
    // knob's rest point, so the resting panel is byte-for-byte and only a moved slider
    // changes anything. See [com.folio.reader.ui.theme.panelKnobScale].
    val planeAlpha = FolioTheme.surfaceOpacity.panelKnobScale(atmos.panelFill.alpha)
    return if (accent != null) {
        lerp(atmos.panelFill.copy(alpha = 1f), accent, FOLIO_PANEL_PLANE_TINT)
            .copy(alpha = planeAlpha)
    } else {
        atmos.panelFill.copy(alpha = planeAlpha)
    }
}

/**
 * Resting surface: grouped content that should read as *on* the page, not above
 * it. Deliberately quieter than [folioRaised] — a thin rim, a whisper of shadow,
 * no sheen. This is the material most cards should have had all along.
 *
 * The fill is [FolioAtmosphere.panelFill] — a role, not a call-site decision. It used to be
 * `colors.surface` on paper and a dark mix on a dark face, which meant the app's largest
 * grouping surface was painted from whichever palette plane happened to have had its hue
 * removed, and the panel was the one tier that carried no theme at all. Reading the role
 * here is what keeps this material and the ladder in agreement: there is exactly one place
 * that decides what a panel is made of, and this is not it.
 */
@Composable
fun Modifier.folioPanel(
    shape: Shape = FolioShapes.card,
    accent: Color? = LocalFolioSectionAccent.current,
): Modifier {
    val atmos = FolioTheme.atmosphere
    // Whether this panel is resting on the page or sitting inside another surface.
    // Settings is the case that forced the question: the category shell is one large
    // folioVeil, so every panel below it was an opaque slab with a 2dp shadow falling
    // on the glass's own fill and a full-strength hairline ringing a second rounded
    // rect inside the glass's. See LocalFolioSurfaceDepth.
    val embedded = LocalFolioSurfaceDepth.current > 0
    // The accent dyes the *plane*, not only the rim. Panels used to carry the section hue on
    // their 1dp hairline alone, so every panel in the app read as the same near-neutral room
    // shade — the "everything is one colour" bug. The fill is lerped toward the accent at a
    // modest fraction, preserving the panel's lightness/alpha so its text keeps the authored
    // contrast; only the hue moves. Null accent (unwrapped trees: reader, previews) stays
    // byte-for-byte the shipped `panelFill`.
    // §19: inside another surface, the division is a rim over a whisper of the parent's
    // plane — one hairline, one material (see FOLIO_EMBEDDED_FILL /
    // LocalFolioSurfaceFill). P7 wore the parent's plane at full strength and the pair
    // still read as two objects; the floor is what makes them read as one surface with
    // structure in it.
    val inherited = LocalFolioSurfaceFill.current
    val fill = if (embedded) {
        val plane = inherited ?: folioPanelPlane(accent)
        plane.copy(alpha = plane.alpha * FOLIO_EMBEDDED_FILL)
    } else {
        folioPanelPlane(accent)
    }
    val rimBase = if (accent != null) {
        lerp(atmos.hairline, accent, 0.30f).copy(alpha = atmos.hairline.alpha + 0.14f)
    } else {
        atmos.hairline
    }
    // The plane survives nesting — it is what tells the eye these rows are one group,
    // and that is true at any depth. The *depth cues* do not: the drop shadow and the
    // full hairline are both claims about a page that is not there.
    val rim = if (embedded) rimBase.copy(alpha = rimBase.alpha * FOLIO_EMBEDDED_RIM) else rimBase
    // §18 — one rung of the one ladder, same as every other material. A panel is the
    // app's largest grouping surface, so it is also the biggest single box in the
    // screenshots; nested inside a section card it takes the shorter `Inset` ramp because
    // a long feather inside a parent plane reads as a smudge rather than as a division.
    val edgeProfile = (if (embedded) FolioEdgeRole.Inset else FolioEdgeRole.Card).profile
    val planeFoot = lerp(fill, folioFieldBottom(atmos), FOLIO_PLANE_ROOM_SHARE)
        .copy(alpha = fill.alpha * FOLIO_PLANE_RAMP_FOOT)
    return this
        .then(
            if (embedded) {
                Modifier
            } else {
                Modifier.shadow(
                    elevation = atmos.scaled(FolioTokens.elevationPanel),
                    shape = shape,
                    ambientColor = atmos.shadowAmbient,
                    spotColor = atmos.shadowSpot,
                )
            },
        )
        // §18: the plane was `drawPath(shapePath(shape), verticalGradient)` — the ramp ran
        // down the surface's height and stopped dead across its boundary. Now it ramps
        // along the boundary's own normal as well. See [folioSurfacePlane].
        .folioSurfacePlane(shape, fill, edgeProfile, planeFoot)
        // §18: `border(1.dp, rim, shape)` became a five-band inward ramp. See [folioSurfaceRim].
        .folioSurfaceRim(shape, rim, edgeProfile)
        .clip(shape)
}

/**
 * Embedded surface: a well cut into the page. Inverted lighting — the lip occludes
 * on the sun-facing edge and the far wall catches, so which edge is dark follows
 * the sun ([FolioDaylight]) rather than always being the top.
 *
 * Charts, heatmaps and figure fields live here. The inversion is what stops a
 * chart from reading as one more card.
 */
@Composable
fun Modifier.folioSunken(
    shape: Shape = FolioShapes.inset,
    accent: Color? = LocalFolioSectionAccent.current,
    daylight: FolioDaylight = LocalFolioDaylight.current,
    /**
     * Opt out of the §19 embedded rule. The reader's wells sit in glass directly over a
     * native WebView, which Android cannot blur: on the no-blur path a floored well would
     * stack translucencies and let the page's text bleed through the very bed the well
     * exists to provide. The reader stays opaque by design.
     */
    fullMaterial: Boolean = false,
): Modifier {
    val atmos = FolioTheme.atmosphere
    // The metallic-sheen knob, read once like every material reads it: the lip's
    // light catch dims, the occluding shade does not.
    val sheenGain = folioSheenGain()
    // The well wears the section hue on its plane too (see [folioPanel]): a sunken field used to
    // tint only 0.07 toward the accent, so a chart well read as one more neutral tier. It pulls a
    // touch harder than a panel ([FOLIO_SUNKEN_PLANE_TINT]) to stay distinct from the plane it is
    // sunk into. Null accent stays byte-for-byte the shipped `sunkenFill`.
    val tinted = if (accent != null) {
        lerp(atmos.sunkenFill.copy(alpha = 1f), accent, FOLIO_SUNKEN_PLANE_TINT)
            .copy(alpha = atmos.sunkenFill.alpha)
    } else {
        atmos.sunkenFill
    }
    // §19: a well inside another surface is a recess *in that surface*, not a second
    // material cut into a page — the parent's plane at the embedded floor, with the
    // inverted lip below carrying the depth that the fill step used to.
    val embedded = !fullMaterial && LocalFolioSurfaceDepth.current > 0
    val fill = if (embedded) {
        val plane = LocalFolioSurfaceFill.current ?: tinted
        plane.copy(alpha = plane.alpha * FOLIO_EMBEDDED_FILL)
    } else {
        tinted
    }
    // The caught wall takes the sun's temperature at the same rationed fraction as
    // the raised rim; the occluding lip stays the palette's own shade, because a
    // shadow is absence of light and the sun does not colour it. Tint 0 skips the
    // lerp so neutral daylight is byte-for-byte the palette's rim.
    val tint = DAYLIGHT_RIM_TINT_MAX * daylight.intensity
    val light = if (tint <= 0f) {
        atmos.rimLight
    } else {
        lerp(atmos.rimLight, daylight.temperature, tint).copy(alpha = atmos.rimLight.alpha)
    }
    val shade = atmos.rimShade
    // §17 living light, draw-phase read — see folioRaised.
    val ambient = LocalFolioAmbient.current
    // §18 — the well's rung. The shortest in-flow ramp on the ladder, and deliberately so:
    // a recess reads as depth because of its lip, so feathering it to nothing would fill
    // the hole in. Short is still enough to answer the two places the user's screenshots
    // show this material failing — the 38dp icon tiles in the More hub's rows, which were
    // "distinctly visible hard-edged boxes" at small scale, and the quote rows' note wells,
    // where only the rows that *have* a note carry a surface, so the step between a row
    // with one and a row without was itself the visible rectangle.
    //
    // A well also carries no rim at all, which is why it only needs the plane half of the
    // primitive: with no outline to soften, the hard clip was the entire defect.
    val edgeProfile = FolioEdgeRole.Well.profile
    val sunkenFoot = lerp(fill, folioFieldBottom(atmos), FOLIO_PLANE_ROOM_SHARE)
        .copy(alpha = fill.alpha * FOLIO_PLANE_RAMP_FOOT)
    return this
        .folioSurfacePlane(shape, fill, edgeProfile, sunkenFoot)
        .drawWithCache {
            // §18: inside the feather, so the lip lights the recess rather than outlining it.
            val path = folioEdgeCorePath(shape, edgeProfile)
            onDrawBehind {
                // Inverted along the same axis: light at the anti-sun edge, shade at
                // the sun edge. Overhead neutral sun → shade on top, light on bottom.
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val sheen = Brush.linearGradient(
                    // §Metal: light at the anti-sun edge, shade at the sun edge — the
                    // recess's lip is polished too, catching at 0.45 (was 0.30).
                    0f to light.copy(alpha = light.alpha * 0.45f * sheenGain),
                    0.65f to Color.Transparent,
                    1f to shade.copy(alpha = shade.alpha * 0.9f),
                    start = start,
                    end = end,
                )
                drawPath(path, sheen)
            }
        }
        .clip(shape)
}

/**
 * How strong the page wash is behind the chrome bands.
 *
 * **Zero: the page wash is retired.** It is superseded by the global room scrim
 * ([com.folio.reader.ui.theme.FolioRoomScrim]), which is attached once inside the root's ground
 * plane rather than opted into per screen. Two reasons the wash had to go rather than stack
 * under it:
 *
 *  1. **It was opt-in.** Eighteen screens called it and everything else was left bare, so the
 *     app had two different grounds and the reader found the seam.
 *  2. **It was painted in the field's own colour** (`folioFieldBottom`). A veil in the colour of
 *     the thing it is veiling cannot change what a reader can see — an orange wash over an
 *     orange field is still orange-on-orange — and all it actually did was flatten the field's
 *     brightest bands into a slab of flat field colour at the crown and the foot. That is the
 *     "opaque field behind the entire thing" report.
 *
 * Left at the seam rather than deleted: `folioPageWash`'s own `strength <= 0f` guard turns every
 * one of those eighteen call sites into an identity modifier with no edit to eighteen files (and
 * no eighteen gradient fills per frame), and the mechanism survives for any caller that wants to
 * pass an explicit strength. Do not raise this to re-ground a screen — scale
 * [com.folio.reader.ui.theme.FolioRoomScrim.LIGHT_PEAK_ALPHA] /
 * [com.folio.reader.ui.theme.FolioRoomScrim.DARK_PEAK_ALPHA] instead, or give the block its own
 * [folioTypeGround]. Stacking the two is exactly the double-dimming the scrim exists to avoid.
 */
private const val FOLIO_PAGE_WASH_STRENGTH = 0f

/**
 * How much of the wash survives mid-screen, as a fraction of [FOLIO_PAGE_WASH_STRENGTH].
 *
 * Not zero. A wash that fell to nothing in the middle would leave the very type it exists to
 * ground — a section heading at mid-height, a two-line empty state — resting on the raw field,
 * which is the report this answers. So the middle keeps a third of it: enough to be a page, little
 * enough that the animation is unmistakably still moving through it.
 */
private const val FOLIO_PAGE_WASH_MID = 0.35f

/** How far down from the top the wash stays at full strength — roughly the masthead's own band. */
private val FOLIO_PAGE_WASH_TOP_BAND = 132.dp

/** The mirror at the foot: the nav capsule's band plus the air around it. */
private val FOLIO_PAGE_WASH_BOTTOM_BAND = 152.dp

/**
 * §16 P6 — the **page wash**: one screen-level gradient that gives a bare-type screen a page to sit
 * on (issue 4: "no background on detail/library/downloads").
 *
 * The screens this is for paint no surface of their own — they lay type straight onto the living
 * field, on the theory that the field *is* the page. It is, but it is also an animation with a
 * bright lamp in it, and type that happens to land over a bloom loses its contrast to whatever the
 * room is doing that second. The fix is not a background (that would hide the field, which is the
 * whole identity) but a *wash*: the field's own lower endpoint laid over itself, strongest where
 * the chrome bands already darken the screen and weakest in the middle.
 *
 * [folioTypeGround] is the same idea at the scale of one text block. This is the screen-scale
 * version, and the two compose: a block inside a washed page still gets its own feather, so a
 * caption that happens to sit over a bloom is grounded twice rather than not at all.
 *
 * Draw-phase only and one `drawRect` per frame, so it costs a gradient — not a layer, not a
 * composition, and not a second pass over the field.
 */
@Composable
fun Modifier.folioPageWash(
    strength: Float = FOLIO_PAGE_WASH_STRENGTH,
    topBand: Dp = FOLIO_PAGE_WASH_TOP_BAND,
    bottomBand: Dp = FOLIO_PAGE_WASH_BOTTOM_BAND,
): Modifier {
    if (strength <= 0f) return this
    val page = folioFieldBottom()
    val band = page.copy(alpha = (page.alpha * strength).coerceIn(0f, 1f))
    val mid = page.copy(alpha = (page.alpha * strength * FOLIO_PAGE_WASH_MID).coerceIn(0f, 1f))
    return this.drawWithCache {
        val height = size.height
        if (height <= 0f) return@drawWithCache onDrawBehind { }
        // Clamped to a half so a short screen cannot cross the two stops over and produce a
        // gradient that runs backwards.
        val top = (topBand.toPx() / height).coerceIn(0f, 0.5f)
        val bottom = (1f - bottomBand.toPx() / height).coerceIn(0.5f, 1f)
        val brush = Brush.verticalGradient(
            0f to band,
            top to mid,
            bottom to mid,
            1f to band,
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * The plane a [folioVeil] is painted with — published by the containers that wrap content in a
 * veil so their nested surfaces can wear it (see [LocalFolioSurfaceFill]).
 *
 * One definition on purpose, same contract as [folioPanelPlane]: a container that recomputed its
 * own published plane would be a second copy of this arithmetic waiting to drift from the first.
 * The veil containers that host content — the settings category shell, the More hub's sections
 * and the overlay host's pane — call this with the same arguments their own `folioVeil` passes,
 * so what a nested surface inherits is byte-for-byte what its container is made of.
 *
 * The §19 embedded floor applies here too: a veil drawn inside another surface wears the
 * *parent's* plane at [FOLIO_EMBEDDED_FILL] rather than a glass-tier lid, unless the caller named
 * its own [fillColor]/[fillAlpha] (the nav capsule and the reader's sheets have decided).
 */
@Composable
fun folioVeilPlane(
    fillColor: Color? = null,
    fillAlpha: Float? = null,
    accent: Color? = null,
    blurEnabled: Boolean = true,
): Color {
    val atmos = FolioTheme.atmosphere
    val basePlane = fillColor ?: atmos.veilFill
    val tintedPlane = if (accent != null) lerp(basePlane, accent, FOLIO_VEIL_ACCENT_TINT) else basePlane
    val embedded = LocalFolioSurfaceDepth.current > 0
    val inherited = LocalFolioSurfaceFill.current
    if (embedded && fillColor == null && fillAlpha == null && inherited != null) {
        return inherited.copy(alpha = inherited.alpha * FOLIO_EMBEDDED_FILL)
    }
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    val canBlur = caps.blur && backdrop != null && blurEnabled
    return tintedPlane.copy(
        alpha = fillAlpha ?: FolioTheme.surfaceOpacity.panelFill(canBlur || embedded),
    )
}

/**
 * Glass over content: bars, floating navigation, sheets. Near-opaque by default on
 * purpose — Android cannot blur a separate native surface (the reader page), so
 * translucency alone would let text bleed through at full contrast. The glass is
 * carried by the rim and the sheen.
 *
 * [fillAlpha] overrides that for glass that sits over *Compose* content only, where
 * a little transparency is what makes the surface read as glass rather than as a
 * panel: the floating nav capsule uses it so the page is visible through it.
 *
 * [fillColor] lets a surface name the palette role it is made of instead of taking the
 * generic `veilFill`. It exists for the nav capsule, which is *chrome*: it should be made of
 * the palette's chrome plane ([FolioColors.navigationSurface]) and not of the same role as
 * the content scrolling behind it, or the one piece of furniture that is always on screen
 * reads as part of the page. Null keeps the previous behaviour byte-for-byte, so every other
 * caller is untouched.
 *
 * §16 liquid glass, when [LocalGlassCapabilities] allows and the screen provides a
 * [LocalGlassBackdrop]: the blur layer is **additive under** the existing fill —
 * the opacity knobs above still govern the fill alpha on top, so at 100% the
 * surface is a lid and the blur is invisible — but when the veil actually blurs,
 * the panel fill steps down to its glass tier
 * (see [com.folio.reader.ui.theme.glassTierAlpha]), because blur is what fixes
 * the text bleed-through the near-opaque default was calibrated against. Turning
 * the preference off restores the pre-glass modifier chain exactly. The specular
 * sheen follows [FolioDaylight] like every other material (a fixed vertical band
 * otherwise), a deterministic grain kills gradient banding when blur is
 * unavailable, and the hairline gains a light-facing inner rim — the edge bevel
 * that approximates lensing without a shader.
 */
@Composable
fun Modifier.folioVeil(
    shape: Shape = FolioShapes.card,
    elevation: Dp = FolioTokens.elevationVeil,
    fillColor: Color? = null,
    fillAlpha: Float? = null,
    accent: Color? = null,
    glass: GlassSpec = GlassSpec.Default,
    weight: com.folio.reader.ui.theme.FolioGlassWeight =
        com.folio.reader.ui.theme.FolioGlassWeight.Veil,
    /**
     * Which layer this surface belongs to — see [FolioGlassTier]. The default is the
     * expensive one on purpose: a call site that says nothing gets exactly what it had
     * before, and the in-flow sites are moved over deliberately, one at a time.
     */
    tier: FolioGlassTier = FolioGlassTier.Overlay,
    /**
     * §18 — which rung of the [FolioEdgeRole] ladder this surface's *edge* comes from.
     *
     * Null (the default) derives it from what the surface already knows about itself:
     * [FolioEdgeRole.Inset] when embedded, [FolioEdgeRole.Pane] for an overlay and
     * [FolioEdgeRole.Card] in flow. That derivation is right for every call site except
     * the two pieces of app chrome, which are always on screen over live content and
     * want the ladder's longest ramp — so [com.folio.reader.ui.components.FolioChromeMaterial]'s
     * two call sites name [FolioEdgeRole.Chrome] and nothing else has to.
     *
     * It is a role and not a dp because the whole point of the ladder is that no call
     * site may invent a feather distance; see [FolioEdgeRole].
     */
    edge: FolioEdgeRole? = null,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    // The reader asks its always-on-screen chrome and panels for a calmer glass:
    // the light catches are dimmed and the catch colour is stripped of hue so the
    // islands stop reading as glossy and book-edge-tinted. Default false everywhere
    // else, so this is inert for every non-reader surface (Rule 19).
    val calmGlass = com.folio.reader.ui.theme.LocalFolioReaderCalmGlass.current
    // The metallic-sheen knob: one gain for this material's every light catch — the
    // §17 sheen, the in-flow top catch, the bevel's lit rim, the lens's specular and
    // the film's glints. The fill, hairline, blur, wall and shadow are untouched. The
    // calm-reader request folds straight into it so one multiply dims every catch.
    val sheenGain = folioSheenGain() *
        if (calmGlass) com.folio.reader.ui.theme.FOLIO_READER_CALM_SHEEN else 1f
    // The colour every light catch is drawn in. Normally the theme's rimLight, which
    // on dark reading themes carries the secondary hue — that is the "book colour" on
    // the glass edge. Calm strips it to a neutral grey of the same luminance, so the
    // catch stays but is colourless; light themes are already neutral white.
    val catchLight = if (calmGlass) atmos.rimLight.folioNeutralized() else atmos.rimLight
    // Glass inside glass. The backdrop a nested veil would sample is its *parent's*
    // own frosted plane, so the blur pass pays a second full-surface effect to frost
    // something already frosted, the AGSL film re-lenses an engraving that has already
    // been bent once, the grain lays noise over noise, and the two-stroke edge bevel —
    // the strongest edge in the whole material — traces a hard rounded rect a few
    // pixels inside the parent's. That last one is the single biggest contributor to
    // the box-inside-a-box texture Settings carried. Embedded, the veil keeps only what
    // is still honest: the plane, its sheen and a ghost of a rim.
    // See LocalFolioSurfaceDepth.
    val embedded = LocalFolioSurfaceDepth.current > 0
    // glass.blurEnabled lets a caller hold the blur off for the frames where the
    // registered backdrop still describes the screen we just left — see GlassSpec.
    // Everything downstream reads this one boolean, so the blur layer and the fill
    // tier can never disagree about whether the surface is currently glassy.
    val canBlur = caps.blur && backdrop != null && glass.blurEnabled
    // §20 reach: an in-flow veil upgrades to the full overlay glass treatment (blur, lens,
    // sheen, bevel, pane edge) when the user has not opted into minimal glass and the device
    // can actually blur. Gated on a live backdrop so declining the upgrade — or a no-blur
    // device — leaves the in-flow look exactly as it was: painted glass, no second edge. The
    // room (silk field, lamp bloom, drifting pools) is real high-frequency structure to bend,
    // so frosting it here is not the no-op that frosting a smooth page ramp would be.
    val effectiveTier = if (
        tier == FolioGlassTier.InFlow && LocalGlassInFlowGlass.current && canBlur && !embedded
    ) FolioGlassTier.Overlay else tier
    // An in-flow veil keeps the *material* but not the *refraction*: it is an item in a
    // scrolling list, so nothing ever passes behind it but the ground plane, and blurring a
    // smooth ramp is a no-op at full price. See FolioGlassTier.
    val blurred = canBlur && !embedded && effectiveTier == FolioGlassTier.Overlay
    // A caller that names its own alpha has already accounted for its context
    // (the nav capsule, the reader's sheets); everything else is an app panel and
    // takes the panel preference, which is the alpha itself rather than a factor
    // on it — see FolioSurfaceOpacity. When this veil actually blurs, that fill
    // steps down to its glass tier: the blur is what fixes the bleed-through the
    // near-opaque default was calibrated against, so leaving the fill unchanged
    // buries the effect under a 97% lid and only the sheen and grain remain
    // visible — the "invisible glass" failure mode.
    //
    // Keyed on [canBlur], not on [blurred]. An in-flow veil does not blur, but it does not
    // have a page behind it either — only the field — so it takes the same translucent tier
    // and the same legibility. Keying it on `blurred` instead would step an in-flow card back
    // up to the near-opaque lid the moment it stopped paying for a blur it never needed,
    // which is the "you can make out boxes" texture this material exists to avoid. With the
    // preference off, `canBlur` is false everywhere and the shipped alpha returns untouched.
    //
    // [accent], when a section passes one, pulls the base plane toward that hue so
    // panes in different sections read as *different* surfaces instead of one
    // monotone room shade. The pull is small (the glass stays glass), but it is
    // enough to break the all-one-colour look the palette's distinct roles are
    // meant to avoid.
    val basePlane = fillColor ?: atmos.veilFill
    val tintedPlane = if (accent != null) lerp(basePlane, accent, FOLIO_VEIL_ACCENT_TINT) else basePlane
    // One definition (see [folioVeilPlane]): the fill — including the §19 embedded floor,
    // where a veil inside another surface wears the *parent's* plane at [FOLIO_EMBEDDED_FILL]
    // instead of a glass-tier lid — is computed there, so the containers that publish their
    // plane publish exactly what they paint. A caller that named its own
    // [fillColor]/[fillAlpha] (the nav capsule, the reader's sheets) has already decided
    // and still wins.
    val fill = folioVeilPlane(fillColor, fillAlpha, accent, glass.blurEnabled)
    // §18 the edge. One rung of one ladder, derived from what the surface already knows
    // about itself so no call site has to be told twice (see [FolioEdgeRole]). This is
    // the surface that carried the defect at every scale the user named — section cards,
    // all of settings, chips, sheets and the nav capsule are all this one function — so
    // it is also the surface where fixing the edge once fixes it everywhere.
    val edgeProfile = (
        edge ?: when {
            embedded -> FolioEdgeRole.Inset
            effectiveTier == FolioGlassTier.Overlay -> FolioEdgeRole.Pane
            else -> FolioEdgeRole.Card
        }
        ).profile
    // The foot of the plane's ramp, pulled a little toward the page's own lower endpoint.
    //
    // The flat fill is the second half of the "you can make out boxes" report: the field
    // behind a card has a direction and a bloom in it, and a single colour laid over that
    // agrees with the room at exactly one point and disagrees everywhere else. An in-flow
    // card pays for no blur, so it has no `bodyTint` riding a spatially varying backdrop
    // to borrow body from (see folioGlassEffect's KDoc) — but it can at least ramp the
    // same way the room ramps. `folioFieldBottom` is the colour anything that dissolves
    // into the ground plane has to wear; mixing a little of it into the plane's foot is
    // what lets the bottom of a card meet the bottom of the field instead of cutting
    // across it. Small on purpose: this is agreement with the room, not a second gradient.
    val planeFoot = lerp(fill, folioFieldBottom(atmos), FOLIO_PLANE_ROOM_SHARE)
        .copy(alpha = fill.alpha * FOLIO_PLANE_RAMP_FOOT)
    val daylight = LocalFolioDaylight.current
    // §17 living light, draw-phase read — see folioRaised. This is the layer
    // that makes liquid glass read as liquid at rest: the specular highlight
    // travelling across the material is the one thing a still page cannot
    // give the blur, so the veil supplies it itself.
    val ambient = LocalFolioAmbient.current

    // The blur layer sits under the fill: rounded first (the effect draws over
    // its whole node bounds), then the blurred backdrop, then everything the
    // surface itself paints.
    // §20.1 (see folioRaised's note): the frost and the body thin with the
    // surface's own knob.
    val weightScale = com.folio.reader.ui.theme.glassWeightScale(fillAlpha != null)
    val blurLayer = if (blurred) {
        Modifier
            .clip(shape)
            .folioGlassEffect(
                backdrop = backdrop,
                // What to show where the registered source has no content. This was
                // the palette's *unlit* `background`, but the room a veil floats in is
                // the field *after* the lamp and the cover's tint — so a surface over a
                // page that stops short of it (Home's list ends above the capsule)
                // painted a disc of a colour the surroundings disagree with, and read as
                // an opaque pill rather than as glass. The nav shell already dissolves
                // into `folioFieldBottom`; the blur has to fall back to the same ground.
                backgroundColor = folioFieldBottom(atmos),
                // The solidity of a *blurring* surface lives here, not in the plane over
                // it — see FolioBarFill.material. Haze records `backgroundColor` as an
                // opaque floor, composites the sampled backdrop and both tints over it,
                // and draws the whole layer at this alpha; left at the `1f` default the
                // layer is an opaque reproduction of the backdrop and the fill plane on
                // top is the only thing the opacity knob could thin. That is why the
                // reader's side panels (TOC / notes / settings) stayed a solid lid at
                // every transparency setting: their backdrop is a thin dimmed scrim over
                // a WebView page, so an opaque blur floor is all the eye ever sees and the
                // slider's work on the plane above it is invisible. Keyed to the surface's
                // own resolved fill alpha — the already-tiered knob (`readerPanelVeilAlpha`
                // for the reader sheets, the nav/panel preference elsewhere) — so `1f`
                // stays a true lid for a reader who asked for solid, and dragging the knob
                // down thins the whole pane and lets the room behind it show through.
                alpha = fill.alpha,
                blurRadius = glass.blurRadius * weight.blurScale,
                // Chromatic frost: dye the blurred room with the theme's own
                // accent so the glass carries the theme's identity and reads as a
                // real slab absorbing light, not a neutral tint laid in front of
                // the screen. Off (Unspecified) when the weight asks for none, so
                // the neutral-blur look is preserved exactly.
                tint = if (weight.frostTintAlpha > 0f) {
                    (accent ?: FolioTheme.colors.primary).copy(alpha = weight.frostTintAlpha * weightScale)
                } else {
                    Color.Unspecified
                },
                // Frosted body: dye the same blur with the *surface's own* colour,
                // under the accent frost. This is what lets the veil be opaque and
                // glass at once — the pane gains the body of a real frosted
                // material (you cannot see sharply through it) while the backdrop
                // still bleeds, instead of relying on a high flat fill that would
                // bury the blur into a dead square. Rides the blur, so it never
                // flattens. Off when the weight asks for none.
                bodyTint = if (weight.bodyTintAlpha > 0f) {
                    tintedPlane.copy(alpha = weight.bodyTintAlpha * weightScale)
                } else {
                    Color.Unspecified
                },
                // §16 cost model. The class comes from the caller's own [GlassSpec] —
                // one body of code paints the nav capsule, a reader sheet, a search
                // strip and a modal, and those are not the same surface class — and the
                // device's [GlassCapabilities.budget] is spent on top of it. See
                // [FolioGlassCost] for why this is a cost lever and not a look one.
                inputScale = FolioGlassCost.resolve(glass.inputScale, caps),
            )
    } else {
        Modifier
    }

    // §16.2 the lens. This is the pass that was missing, and the reason the whole material
    // read as "a shade of tint": nothing above or below this line *moves* the room. A fill,
    // a hairline, a painted sheen and an additive film are all paint, and paint on a
    // translucent plane is a tint however carefully it is mixed. Glass is a displacement —
    // see [folioGlassLens].
    //
    // It needs the real room, which is what the §16 room rung now captures
    // ([glassRoomPlate]); a null plate means there is nothing behind this surface to bend
    // and the painted bevel below stands in unchanged.
    //
    // Only an overlay asks for it. An in-flow veil sits on the page rather than over content,
    // so there is no room behind it to displace. (The second reason this comment used to give
    // — that `glassRoomPlate` reads `backdrop.areas` *in composition*, so declining to call it
    // spares the subscription — no longer applies: the plate now resolves its rung in the draw
    // phase under `Snapshot.withoutReadObservation` and the composition read is gone. The tier
    // decision stands on the first reason alone, which was always the real one.)
    // See [FolioGlassTier].
    val roomPlate = if (effectiveTier == FolioGlassTier.Overlay) glassRoomPlate() else null
    val lensing = caps.specular && !embedded && rememberShaderSupported() && roomPlate != null
    val lensLayer = if (lensing) {
        val (lensLx, lensLy) = daylight.lightDirection()
        Modifier.folioGlassLens(
            plate = roomPlate,
            shape = shape,
            // The veil's **whole** plane. See the matching note in [folioRaised]: a
            // fraction of it inside the band against all of it outside is a material step
            // on a closed curve, and that curve is the "nested rounded rect" this file has
            // chased across every tier. Full plane, displaced — same material, moved.
            tint = fill,
            highlight = catchLight,
            lightX = lensLx,
            lightY = lensLy,
            // The metallic-sheen knob reaches the lens as the specular catch only;
            // the refraction is the glass and does not dim. See [folioRaised].
            specular = FolioGlassLens.specular * sheenGain,
        )
    } else {
        Modifier
    }

    // Specular 2.0: the highlight tracks the virtual sun along the same axis
    // every other material uses, laid inside the shape rather than over its
    // bounding box. At neutral daylight the axis is bottom→top, which is the
    // fixed vertical sheen this material always had. §17: the axis recomputes
    // per draw against the living light, so the glass catches a highlight that
    // is always a little in transit.
    // Specular 2.0: the highlight tracks the virtual sun along the same axis
    // every other material uses, laid inside the shape rather than over its
    // bounding box. At neutral daylight the axis is bottom→top, which is the
    // fixed vertical sheen this material always had. §17: the axis recomputes
    // per draw against the living light, so the glass catches a highlight that
    // is always a little in transit.
    //
    // §18: laid on the edge stack's *core* rather than on the silhouette. A sheen whose
    // brightest row is the surface's own outer edge is a second boundary cue painted over
    // the first one — and the no-specular branch below was the worse offender, a flat
    // top-down gradient whose peak was the top edge, clipped dead at the shape. Inset by
    // the feather, the catch fades out with the plane it belongs to instead of marking
    // where the plane stops.
    val sheenLayer = if (caps.specular && effectiveTier == FolioGlassTier.Overlay) {
        Modifier.drawWithCache {
            val path = folioEdgeCorePath(shape, edgeProfile)
            onDrawBehind {
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val light = catchLight
                val sheen = Brush.linearGradient(
                    // §Metal: the catch is a tight bright band now, not a broad wash —
                    // the last fifth of the gradient carries it, peaked at 0.85 of the
                    // rim light (was a half-strength catch over the last third).
                    0f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1f to light.copy(alpha = light.alpha * 0.85f * sheenGain),
                    start = start,
                    end = end,
                )
                drawPath(path, sheen)
            }
        }
    } else {
        // The in-flow / no-specular catch. Was `Modifier.background(verticalGradient, shape)`:
        // a flat overlay whose brightest row was the shape's top edge and which was clipped
        // hard at the silhouette, so the very top of every section card in Settings carried a
        // bright band that ended at a hard line. Same brush, painted on the core path, so the
        // card still has a top and the top is no longer an edge.
        Modifier.drawWithCache {
            val path = folioEdgeCorePath(shape, edgeProfile)
            // §Metal: the flat panel's top catch, brighter and tighter — the first
            // 38% of the surface carries it (was a half-strength wash over the whole
            // height), so it reads as a specular strike along the top chamfer.
            val sheen = Brush.verticalGradient(
                0f to catchLight.copy(alpha = catchLight.alpha * 0.72f * sheenGain),
                0.38f to Color.Transparent,
            )
            onDrawBehind { drawPath(path, sheen) }
        }
    }

    // Edge bevel: the glass slab's own thickness, read entirely at the edge so
    // the body stays clear instead of being tinted toward opaque. Two rims are
    // stroked along the *same* outline path, so both hug the rounded corners
    // — it can never box a rectangle inside the surface:
    //   • a lit rim brightest on the sun side (the top edge catching the light);
    //   • a shadowed inner wall on the anti-sun side (the slab's far face
    //     falling into its own depth).
    // Same §17 draw-phase ambient read as the sheen above it. Off when embedded: a
    // 3.5dp lit rim plus a shadowed wall, stroked just inside another surface's own
    // edge, is precisely the nested rectangle this is all trying to stop drawing.
    //
    // Also off when the lens runs, and that is the point of the lens rather than an
    // optimisation. This bevel is a drawn *approximation* of thickness — two gradient
    // strokes along the outline, which is as close as a Brush can get to an edge. The lens
    // derives the same two cues (the lit Fresnel rim and the far wall falling into the
    // slab) from the real silhouette normal, over actually-displaced pixels. Keeping both
    // would stack a painted ring on a refracted one: the nested-rounded-rect texture this
    // file has fought on every other tier, arriving where it would be hardest to attribute.
    //
    // §18 correction: the comment above used to claim the strokes were "clipped by the
    // shape, so the wider stroke's outer half is clipped away". They were not — there is
    // no `clipPath` in this lambda, and the trailing `.clip(shape)` is the innermost
    // modifier, so it only ever clipped *children*. A `Stroke` is centred on its path, so
    // half of a 3.5dp bevel at `glassRimLitAlpha` 0.42 was painting a bright 1.75dp halo
    // *outside* the silhouette of every overlay surface in the app — the nav capsule
    // included, which is where the user reported the pill boundary reading as a hard edge.
    // Struck on the edge stack's core instead, the whole bevel lands inside the feather:
    // the slab keeps its thickness and stops ringing its own outline.
    val bevelLayer = if (caps.specular && !embedded && !lensing && effectiveTier == FolioGlassTier.Overlay) {
        Modifier.drawWithCache {
            val path = folioEdgeCorePath(shape, edgeProfile)
            val rimPx = FolioTokens.glassRimWidth.toPx()
            onDrawBehind {
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val light = catchLight
                val wall = Brush.linearGradient(
                    0f to atmos.shadowSpot.copy(
                        alpha = atmos.shadowSpot.alpha * FolioTokens.glassDepthAlpha,
                    ),
                    0.45f to Color.Transparent,
                    start = start,
                    end = end,
                )
                val rim = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.55f to light.copy(alpha = light.alpha * FolioTokens.glassRimCoreAlpha * sheenGain),
                    1f to light.copy(alpha = light.alpha * FolioTokens.glassRimLitAlpha * sheenGain),
                    start = start,
                    end = end,
                )
                // Depth wall first, lit rim over it: on the sun side the light wins,
                // on the anti-sun side the wall is all that shows, so the two never
                // fight on the same edge.
                drawPath(path, wall, style = Stroke(width = rimPx))
                drawPath(path, rim, style = Stroke(width = rimPx))
            }
        }
    } else {
        Modifier
    }

    // Grain for glass that cannot blur (API floor, pref on, or the reader's
    // chrome over the WebView): a deterministic speckle that breaks up the
    // banding the tint gradients otherwise produce. Blurred glass gets Haze's
    // own noise instead — and an embedded veil inherits its parent's, so a second
    // pass over the same pixels is noise on noise.
    val grainLayer = if (caps.noise && !blurred && !embedded) folioGlassGrain(shape) else Modifier

    // R1: the AGSL film. Sits above the fill, sheen and bevel — the film's rim is the
    // surface's *edge*, so it has to be painted over the fill it belongs to — and under
    // the grain, which is a texture on top of the whole material.
    //
    // Gated on `blurred` for the same reason the blur is: this is the layer that is only
    // worth its second full-surface pass when the material is genuinely glass rather than a
    // near-opaque panel. With the preference off it stays off, so pref-off is
    // byte-for-byte the pre-glass chain — Rule 19.
    //
    // `refractBackdrop` is on here, and this is the surface it was waiting for. The reason
    // it defaulted off was that the hero's backdrop was a smooth ramp with three soft
    // pools, where the three dispersion samples land on the same colour and the band
    // degenerates into a painted rectangle. The guilloche now puts a high-frequency
    // engraved figure on that ramp, so the lens finally has something to bend.
    // `refractBackdrop` is the plate's question, not the veil's. The gel was held off
    // because the hero's backdrop was a smooth ramp with three soft pools, where the three
    // dispersion samples land on the same colour and the band degenerates into a painted
    // rectangle. The guilloche now engraves a figure on that ramp — but *not* on a reading
    // route, where the plate stands down so paper stays paper. So the gel follows the
    // plate exactly: present where the engraving is, absent on the reader's plain page.
    //
    // **And off entirely once the lens runs.** The gel bends a *closed-form reproduction*
    // of the field, which is why it was never convincing: it can only ever bend the ramp,
    // the three pools and the plate, and it cannot see the animated shader field, the lamp,
    // the silk blooms or anything else actually on screen. The lens bends the real capture.
    // Running both puts two differently-wrong rims on one edge, and the gel's own source
    // already records what its rim degenerates into. The film itself — the travelling glint
    // — is kept: that is light *on* the glass, which refraction does not supply.
    val refract = LocalFolioGuillocheActive.current && !lensing
    val filmLayer = if (blurred && caps.specular) {
        val (filmLx, filmLy) = daylight.lightDirection()
        Modifier.folioLiquidGlass(
            // The surface's own fill is the accent: the film's body sheen should be made of
            // the material, not of a second colour laid over it. `rimLight` is the light
            // catch, which on a dark field emits and on paper is white — the same
            // polarity-aware pair the card chain above already uses.
            accent = fill,
            highlight = catchLight,
            lightX = filmLx,
            lightY = filmLy,
            enabled = true,
            refractBackdrop = refract,
            shape = shape,
            // The metallic-sheen knob is the film's own intensity scalar: it scales
            // the body caustic, the rays, the travelling rim and the glint together,
            // and 0 stands the film down without a branch here.
            intensity = sheenGain,
            // The plate behind this veil. Only read when `refract` is true, and `refract`
            // is the plate's own gate — so a veil that has no engraving behind it is handed
            // these and ignores them, which is what keeps a reader's page a page.
            platePackId = LocalFolioPackId.current,
            plateFieldTop = atmos.fieldTop,
            plateFieldBottom = atmos.fieldBottom,
        )
    } else {
        Modifier
    }

    return this
        .then(
            // Nothing to cast onto: an embedded veil's shadow falls on its parent's
            // own fill, where it reads as a dark gasket around the nested shape
            // rather than as elevation.
            //
            // §18 adds the second case, and it is the same case [folioRaised] already
            // documents for a pane: "a translucent pane lets its *own* elevation shadow
            // show through from behind — Android occludes a shadow by the caster's alpha,
            // so a 55% pane passes ~45% of the shadow, densest just inside the silhouette.
            // That is the darker rounded band hugging the edge — the 'box within the card'."
            // An in-flow veil is exactly that pane: `elevationVeil` is 10dp and the in-flow
            // fill is 0.62, so every section card in Settings was passing ~38% of a 10dp
            // shadow through itself as a dark ring just inside its own outline, under a
            // hairline, inside a hard clip. Three box cues on one silhouette. A surface in
            // the page's own flow is not floating over anything by definition, so the cast
            // shadow was never describing a true relationship — the feathered plane and the
            // rim ramp carry the separation instead.
            if (embedded || (effectiveTier != FolioGlassTier.Overlay && fill.alpha < 1f)) {
                Modifier
            } else {
                Modifier.shadow(
                    elevation = atmos.scaled(elevation),
                    shape = shape,
                    ambientColor = atmos.shadowAmbient,
                    spotColor = atmos.shadowSpot,
                )
            },
        )
        .then(blurLayer)
        // §18 — the plane, feathered. This used to be `.background(verticalGradient, shape)`:
        // a fill whose alpha decayed 6% down its height and then stopped dead at the
        // silhouette. The comment it carried was half right — "a single hard fill alpha gives
        // the plane a single hard edge wherever it meets the page — the eye finds that step
        // and reads a rectangle" — but a 6% decay along the *wrong axis* cannot fix a step
        // across the boundary, and the step is what the eye was finding. The ramp now runs
        // along the surface's own normal, so there is no depth at which the boundary can be
        // measured. The vertical decay survives inside it, now also carrying a little of the
        // page's own colour at the foot. See [folioSurfacePlane] and FOLIO_PLANE_ROOM_SHARE.
        .folioSurfacePlane(shape, fill, edgeProfile, planeFoot)
        .then(lensLayer)
        .then(sheenLayer)
        .then(bevelLayer)
        .then(filmLayer)
        .then(grainLayer)
        // §18 — the rim, as a short inward ramp instead of a 1dp outline. A full-perimeter
        // stroke at the atmosphere's hairline (0.42 on paper, 0.30 on a dark face) sitting
        // exactly on the alpha discontinuity is the strongest single "this is a box" cue in
        // the app, and it was on every surface this function draws. Same ink, spread over
        // five abutting bands that start where the feather ends; peak contrast halved and the
        // silhouette carries none of it. See [folioSurfaceRim].
        //
        // **Off when the lens runs.** The lens is this surface's edge wherever it paints — a
        // quarter-round fillet over actually-displaced pixels — and the hairline here is a
        // *painted* contour starting at almost the same depth. The two stacked read as
        // exactly the nested square reported on the nav capsule: an outer glassy rim (the
        // lens band) and an abrupt inner line (this), two edges derived from different lies
        // about the same boundary. One edge per surface — the lens already wins over the
        // bevel and the gel for that reason, and it wins over the painted rim too.
        .folioSurfaceRim(
            shape,
            when {
                embedded -> atmos.hairline.copy(alpha = atmos.hairline.alpha * FOLIO_EMBEDDED_RIM)
                lensing -> Color.Transparent
                else -> atmos.hairline
            },
            edgeProfile,
        )
        .clip(shape)
}

/**
 * The grain tile: 128×128 pixels of deterministic speckle, four gray levels
 * drawn as four grouped point calls so the tile is built exactly once per
 * process and shared by every glass surface. Mid-gray deviations composited
 * with [BlendMode.Overlay] lighten or darken the fill beneath by a couple of
 * percent — enough to break banding, invisible as texture.
 */
private val GLASS_GRAIN_TILE: ImageBitmap by lazy {
    val n = 128
    val bitmap = ImageBitmap(n, n)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    // Seeded LCG (precedent: CoreRandom in StatsCoreSample): the grain must be
    // identical every frame or it becomes motion, which Rule 19 forbids.
    var seed = 0x5F3759DF
    fun next(): Int {
        seed = seed * 1_103_515_245 + 12_345
        return (seed ushr 16) and 0xFF
    }
    val groups = Array(4) { mutableListOf<Offset>() }
    for (y in 0 until n) {
        for (x in 0 until n) {
            groups[next() and 3].add(Offset(x.toFloat(), y.toFloat()))
        }
    }
    listOf(64f, 96f, 160f, 192f).forEachIndexed { level, gray ->
        val paint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(gray / 255f, gray / 255f, gray / 255f)
            strokeWidth = 1f
        }
        canvas.drawPoints(PointMode.Points, groups[level], paint)
    }
    bitmap
}

/**
 * The grain pass for un-blurred glass. Draw-phase only; nothing recomposes.
 *
 * The grain is clipped to [shape]. Without the clip it painted a full-bounds
 * rectangle of noise over a rounded surface — a hard square whose corners stuck
 * out past the rounded body. The trailing `.clip(shape)` on the host surface does
 * not help: it is the innermost modifier, so it only clips children, not this
 * outer draw pass. Clipping here keeps the grain inside the glass outline.
 */
internal fun Modifier.folioGlassGrain(
    shape: Shape = RectangleShape,
    alpha: Float = FolioTokens.glassGrainAlpha,
): Modifier =
    drawWithCache {
        val brush = ShaderBrush(
            ImageShader(GLASS_GRAIN_TILE, TileMode.Repeated, TileMode.Repeated),
        )
        val path = if (shape == RectangleShape) null else shapePath(shape)
        onDrawBehind {
            if (path == null) {
                drawRect(brush, alpha = alpha, blendMode = BlendMode.Overlay)
            } else {
                clipPath(path) {
                    drawRect(brush, alpha = alpha, blendMode = BlendMode.Overlay)
                }
            }
        }
    }

/**
 * The page itself. A vertical field wash plus up to three enormous, very
 * low-alpha colour pools, so the background has a temperature and a direction of
 * light instead of one flat fill.
 *
 * The room is lit by what is being read. [ambient] is the colour of the featured
 * cover, hoisted to the app root because this field is an *ancestor* of every
 * screen — a screen cannot provide a CompositionLocal upward to the ground it sits
 * on, so it writes into this state instead. It moves the whole field's hue and
 * re-weights the pools toward it at [FolioAtmosphere.fieldTintStrength], at pinned
 * luminance: the room may become any colour a cover is, and may only ever deepen
 * downward, so ink keeps every contrast ratio it had. See
 * [fieldColors] for the two invariants that make that true.
 *
 * Geometry comes from [FolioFieldModel], the same closed form the refraction gel
 * samples, so a card bending the page and the page itself cannot drift apart.
 *
 * Static by default: this is atmosphere, not motion. One drawing pass via
 * [drawWithCache]; nothing recomposes per frame. The ambient colour is read inside
 * the cache block, so a tab switch rebuilds the wash once instead of recomposing
 * the whole tree under it. §17's living light keeps its exception — the pools
 * breathe, their centres drifting a few percent of the width on the ambient's slow
 * cycles, read in the draw phase so even that costs no recomposition. Under
 * reduce-motion, or in an un-provided tree, the pools sit exactly where they always
 * did and the field is the palette's own.
 */
@Composable
fun Modifier.folioField(
    /** The colour of what is being read, hoisted from the screens above. */
    ambient: State<Color?>? = null,
    daylight: FolioDaylight = LocalFolioDaylight.current,
    strength: Float = FolioTheme.atmosphere.fieldTintStrength,
): Modifier {
    val atmos = FolioTheme.atmosphere
    // The sun's horizontal pull, scaled by intensity: the whole page's temperature
    // follows the day. At night (intensity ~0) this is 0, so the field is the
    // palette's own with its pools exactly where they always were.
    val shift = daylight.azimuth * daylight.intensity
    // The day's own colour and how much of it the room may wear, read once in
    // composition (daylight steps a minute at a time, so this rebuilds then, not per
    // frame). Bounded by DAYLIGHT_WASH_ALPHA_MAX x intensity — the envelope DaylightTest
    // proves cannot move a WCAG ratio off its floor — so at night it is 0 and at noon it
    // is a whisper of white, warm amber at golden hour, never a filter.
    val dayTemp = daylight.temperature
    val dayAlpha = (DAYLIGHT_WASH_ALPHA_MAX * daylight.intensity).coerceIn(0f, DAYLIGHT_WASH_ALPHA_MAX)
    // §17: the living light, read in the draw phase only.
    val ambientLight = LocalFolioAmbient.current
    return this.drawWithCache {
        // Read here, not in composition: a new featured cover rebuilds the wash and
        // the pool colours once, and nothing under the root invalidates.
        val colors = fieldColors(atmos, ambient?.value, strength)
        val w = size.width
        val h = size.height
        // **Along the theme's own light, not the screen's axes.** See `fieldT`: a direction
        // taken from the screen is the same geometry on every palette, so all twenty themes
        // would ramp identically. The axis runs from the pack's authored light source to its
        // mirror, which is what makes each theme's gradient its own.
        val field = Brush.linearGradient(
            listOf(colors.top, colors.bottom),
            start = Offset(colors.sourceX * w, colors.sourceY * h),
            end = Offset((1f - colors.sourceX) * w, (1f - colors.sourceY) * h),
        )
        val drift = fieldDrift(shift)
        onDrawBehind {
            drawRect(field)
            // The ambient displacement is bounded by AMBIENT_DRIFT_MAX of the
            // width, on top of the sun's own drift, so the two never conspire to
            // walk a pool's centre somewhere the geometry above did not design for.
            val (driftX, driftY) = ambientPoolDrift(ambientLight.value)
            DAYLIGHT_POOLS.forEach { pool ->
                val color = colors.poolColors.getOrNull(pool.index) ?: return@forEach
                // The pool on the sun's side holds at poolAlpha; the far side
                // recedes. Capped, so daylight may dim a pool but never lift it
                // past the field's own alpha ceiling.
                val alpha = poolAlphaAt(colors.poolAlpha, pool.sideX, shift)
                val (cx, cy) = poolCenterAt(pool, drift, driftX, driftY)
                val center = Offset(cx * w, cy * h)
                val radius = pool.fr * w
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            // The day's own light, laid over the whole room as its last pass — the one
            // wire that lets every screen read as a time of day instead of a constant.
            // `fieldColorAtPoint` applies the identical mix, so a sampled tap agrees
            // with the pixel; at intensity 0 (night) this is skipped entirely.
            if (dayAlpha > 0f) drawRect(color = dayTemp.copy(alpha = dayAlpha))
        }
    }
}

/**
 * Opt-in full-surface daylight wash: one very-low-alpha sweep of the sun's colour
 * from its own edge to transparent, for a screen that wants the room's light laid
 * over the whole surface rather than only caught at the rims.
 *
 * Nothing calls it by default — it is a wash, so a surface asks for it or it does
 * not happen, and no existing call site shifts. The ceiling
 * ([DAYLIGHT_WASH_ALPHA_MAX], 0.06) times intensity keeps it in the same register
 * as `folioField`'s "~4%, only as air" gradient: low enough that it cannot move a
 * WCAG ratio off its floor, which `DaylightTest` pins. Under neutral daylight
 * (intensity 0) it is a no-op.
 */
@Composable
fun Modifier.daylightWash(
    daylight: FolioDaylight = LocalFolioDaylight.current,
    strength: Float = DAYLIGHT_WASH_ALPHA_MAX,
): Modifier {
    if (daylight.intensity <= 0f) return this
    val alpha = (strength * daylight.intensity).coerceIn(0f, DAYLIGHT_WASH_ALPHA_MAX)
    return this.drawWithCache {
        val (start, end) = daylightGradient(size, daylight)
        val brush = Brush.linearGradient(
            colors = listOf(daylight.temperature.copy(alpha = alpha), Color.Transparent),
            start = start,
            end = end,
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * Localized ambient light: the glow a bright cover throws onto the surface behind
 * it. Drawn *behind* the content and scaled to the composable's own bounds, so a
 * cover lights its neighbourhood and nothing else — one cover must never recolour
 * the app.
 */
fun Modifier.coverHalo(color: Color, strength: Float = 0.34f): Modifier = drawBehind {
    val radius = min(size.width, size.height) * 1.35f
    val center = Offset(size.width * 0.5f, size.height * 0.42f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * How deep the type ground dissolves at each end. A dp rather than a fraction of the
 * node: the thing the head has to disappear into is the plate's contact shadow, which
 * is a fixed depth — a fraction would feather a three-caption document cell twice as
 * far as a one-line book cell and the shelf would read as two different objects. The
 * call sites pad the block by the same amount at both ends, so the whole dissolve
 * happens in margin and the type itself always sits on the full wash.
 */
private val FOLIO_TYPE_GROUND_FEATHER = 8.dp

/**
 * How much of the page the wash lays down.
 *
 * **Deliberately well under [FolioAtmosphere.paneAlpha]** (0.64 dark / 0.76 light, the
 * hero's pane). The hero is glass that the room *shows through*; this is less than that
 * on purpose, because it is laid over the one thing on the screen the user came to see
 * move. At 0.46 a bloom passing behind a label still passes behind it — it is dimmed by
 * a little under half, not erased — and that is the whole brief: the labels become
 * readable and the Mirage field keeps drifting through them.
 *
 * It buys the legibility it does because of *what* it is made of, not how solid it is:
 * [folioFieldBottom] is the darkest end of the page's own ramp in a dark room (and the
 * lightest in a light one), so even a touch under half strength pulls a bloom back
 * toward the page it is blooming on. Raising this past the pane alpha would make the
 * label a lid and should be rejected as such.
 */
/**
 * **Retired: 0f.** The type ground is off, app-wide, by request.
 *
 * The idea was sound in isolation — a wash of the page's own colour under a label that sits
 * directly on an animated field — but on the device it did not read as "the field is dimmed
 * here". It read as a *shadow behind the text*: a soft dark lozenge trailing every shelf caption
 * (book, manga and document names) and every settings section header, which is the single thing
 * a type ground must not look like. Feathered ends do not save it, because the eye registers the
 * luminance drop under the glyphs long before it registers that the drop has no edge.
 *
 * It is also no longer load-bearing. Two things landed that do this job properly:
 *
 *  - `FolioRoomScrim` now shades the *whole* ground plane, permanently and evenly, so the field
 *    a caption sits on is already calmer than the one this was written against. A second local
 *    dim on top of a global one is the double-dimming the scrim exists to avoid.
 *  - The real defect behind the illegible section headers was never a missing ground: it was
 *    `FolioEyebrow`'s contrast guard measuring the accent against `colors.surface`, a plane the
 *    label is nowhere near, so the guard certified an accent the field never supported. That is
 *    fixed at the call sites by passing the actual backdrop, which darkens the *ink* until it
 *    clears its ratio — the correct lever. Fix the type, not the page behind it.
 *
 * Kept as a parameter default rather than deleted: [folioTypeGround] returns `this` unchanged for
 * any `strength <= 0f`, so all eight call sites become identity modifiers with no edit, and a
 * caller that genuinely wants a wash can still pass one explicitly. Do not raise this back to
 * restore legibility — raise the ink's contrast instead.
 */
private const val FOLIO_TYPE_GROUND_ALPHA = 0f

/**
 * The ground under type that sits **directly on the field**.
 *
 * The material table at the top of this file ends with *(nothing)* — type directly on
 * the field — and the shelf is its biggest customer: a grid cell is deliberately a
 * plate with its name under it, never a card, and `BookCard`/`MangaGridItem`/
 * `DocumentGridItem` all say so in their own comments. That rule assumed a page quiet
 * enough to be read over, and the ground plane stopped being one. `FolioThemeBackground`
 * now paints a per-theme Mirage shader *opaquely* over the silk base, and its blooms
 * are bright, they move, and they land wherever they land — so the same 12sp title is
 * comfortable in one cell and sitting inside a lit bloom in the next. Nothing can be
 * asked of the shader: it is drawn at the app root and knows nothing about the text
 * above it. ([folioGuillocheQuiet] is the matching idea for the engraving, and it is
 * not the answer here — the plate has no call site, so there is nothing to silence.)
 *
 * So the type brings its own ground. **It is a wash, not a slab**, and three things keep
 * it that way:
 *
 *  - *What it is made of.* [folioFieldBottom] — the page's own lower ramp colour,
 *    following the room and whatever book is tinting it. A patch of the page cannot read
 *    as an object on the page, which is also why it carries no rim, no shadow and no
 *    accent: those are what say "surface".
 *  - *How much of it there is.* [FOLIO_TYPE_GROUND_ALPHA], under half, and under the
 *    hero pane's own alpha. The field is still visibly moving behind the label; it is
 *    dimmed there, not replaced.
 *  - *Where it stops.* Nowhere. It dissolves to nothing over
 *    [FOLIO_TYPE_GROUND_FEATHER] at both the head and the foot, so there is no edge to
 *    read as the lip of a card — it emerges from under the plate's foot and fades back
 *    into the page below. Hence no shape and no clip: there is nothing to cut.
 *
 * Applied to the **text block**, not the cell: full cell width, so the wash and the
 * cover plate above it share one column and the label reads as belonging to that plate.
 *
 * Draw-phase only — one cached brush and one rect per cell, no layer, no registration,
 * no recomposition — because the call site is a lazily scrolled grid and anything
 * per-frame here is paid twelve times over.
 */
@Composable
fun Modifier.folioTypeGround(
    strength: Float = FOLIO_TYPE_GROUND_ALPHA,
): Modifier {
    if (strength <= 0f) return this
    val page = folioFieldBottom()
    val fill = page.copy(alpha = (page.alpha * strength).coerceIn(0f, 1f))
    return this.drawWithCache {
        val height = size.height
        if (height <= 0f) return@drawWithCache onDrawBehind { }
        // A third of the block at most, per end: a single-line caption must still
        // reach full wash somewhere, or the dissolve is the whole of it and nothing
        // is grounded.
        val feather = min(FOLIO_TYPE_GROUND_FEATHER.toPx(), height / 3f)
        val head = feather / height
        val brush = Brush.verticalGradient(
            0f to Color.Transparent,
            head to fill,
            1f - head to fill,
            1f to Color.Transparent,
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * Press feedback with weight: the surface sinks toward the page rather than
 * flashing a ripple. 0.976 is deliberately small — enough to feel physical at card
 * scale, not enough to read as a bounce. Honours reduce-motion.
 */
@Composable
fun Modifier.folioPressable(
    interactionSource: MutableInteractionSource,
    scaleTo: Float = 0.976f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion) scaleTo else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * §16 liquid press, for glass surfaces only — plain cards keep
 * [folioPressable]'s scale-only response. Three things happen while pressed:
 *
 *  - the rim **flashes**: the outline brightens with the surface's own rim light
 *    toward ~1.5× the resting bevel and decays on release — the specular a
 *    finger pressing near a pane edge would catch;
 *  - the surface **lights where the finger is** ([focal]): the same rim colour,
 *    gathered into a bloom centred on the press rather than spread evenly round the
 *    outline. This is the term that makes a glass surface answer *where* it was
 *    touched, and it needs no shader, so it runs from API 24 up;
 *  - a **tick** lands on settle ([rememberGlassTick]; nav capsule and sheet
 *    handles only — nowhere else, or every press in the app becomes noise).
 *
 * What is still missing is the fourth term the design asked for — corner softening
 * while pressed — which needs `GraphicsLayer.shape`, and the pinned Compose (1.7.3)
 * does not expose it. It is deliberately not approximated with a recomposing clip.
 *
 * Honours reduce-motion: every term collapses to identity, and so does the focal,
 * whose strength never leaves 0 when motion is off.
 */
@Composable
fun Modifier.folioGlassPress(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    focal: FolioPressFocal? = null,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val caps = LocalGlassCapabilities.current
    // A slight overshoot on release — liquid, not sloppy (damping ≈ 0.75).
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 700f),
        label = "glassPress",
    )
    val atmos = FolioTheme.atmosphere
    // The metallic-sheen knob: the press's light answer dims with every other catch.
    val sheenGain = folioSheenGain()
    return this
        .then(
            if (caps.specular && focal != null) {
                Modifier.folioPressFocalOrigin(focal)
            } else {
                Modifier
            }
        )
        .drawWithCache {
            if (!caps.specular) {
                onDrawBehind { /* un-capable platforms keep today's press */ }
            } else {
                val path = shapePath(shape)
                val rim = atmos.rimLight
                onDrawBehind {
                    val fraction = if (motion) press else 0f
                    // The bloom is clipped for the same reason the rim's bloom is in
                    // FolioThemeRim: this surface is not necessarily clipped itself,
                    // and a radial fill would leak onto its neighbours.
                    val at = focal?.pixelPoint()
                    if (at != null && fraction > 0.001f) {
                        val radius = maxOf(size.width, size.height) * 0.62f
                        clipPath(path) {
                            drawCircle(
                                Brush.radialGradient(
                                    0f to rim.copy(alpha = rim.alpha * 0.42f * fraction * sheenGain),
                                    1f to rim.copy(alpha = 0f),
                                    center = at,
                                    radius = radius,
                                ),
                                radius = radius,
                                center = at,
                            )
                        }
                    }
                    if (fraction > 0.001f) {
                        // 0.45 → ~0.68 alpha at full press: the resting bevel's
                        // sun-side rim, 1.5× brighter.
                        drawPath(
                            path,
                            rim.copy(alpha = rim.alpha * 0.45f * fraction * sheenGain),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        }
        .then(
            if (motion) {
                Modifier.pressTick(interactionSource)
            } else {
                Modifier
            }
        )
}

/** The settle tick, attached once per press. */
@Composable
private fun Modifier.pressTick(interactionSource: MutableInteractionSource): Modifier {
    val tick = rememberGlassTick()
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                tick()
            }
        }
    }
    return this
}

/**
 * §16 shape morph: a glass sheet that enters reading as a capsule — its leading
 * sweep blown out toward [capsuleSweep] — and settles into its resting corners
 * over ~320ms. The construction cost is one modifier-chain rebuild per frame of
 * a one-shot enter transition (never scroll-linked, §13.3); reduce-motion jumps
 * straight to the resting shape.
 */
@Composable
fun rememberFolioSheetMorphShape(resting: RoundedCornerShape): Shape {
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    var value by remember { mutableFloatStateOf(if (motion) 1f else 0f) }
    LaunchedEffect(motion) {
        if (motion) {
            androidx.compose.animation.core.animate(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    320,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            ) { v, _ -> value = v }
        } else {
            value = 0f
        }
    }
    val sweep = androidx.compose.ui.unit.lerp(
        FolioTokens.radiusSheetSweep, FolioTokens.radiusSheetCapsule, value,
    )
    return RoundedCornerShape(
        topStart = if (resting.topStart != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
        bottomStart = if (resting.bottomStart != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
        topEnd = if (resting.topEnd != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
        bottomEnd = if (resting.bottomEnd != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
    )
}

/**
 * §16 elastic drag-to-dismiss for end-edge glass sheets: dragging toward the
 * screen edge follows the finger 1:1 up to the commit threshold, past it with
 * growing rubber-band resistance; release either commits the dismissal (a tick
 * lands, the sheet slides out, [onDismiss] fires) or springs home with a slight
 * overshoot — liquid, not sloppy (damping ≈ 0.75). Horizontal only, so the
 * sheets' vertical scrollers are untouched. Honours reduce-motion: settles snap.
 */
@Composable
fun Modifier.folioSheetDragToDismiss(onDismiss: () -> Unit): Modifier {
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val tick = rememberGlassTick()
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val offset = remember { mutableFloatStateOf(0f) }
    return this
        .graphicsLayer { translationX = offset.floatValue }
        .pointerInput(currentOnDismiss) {
            val maxPx = size.width.toFloat().coerceAtLeast(1f)
            val threshold = maxPx * 0.38f
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (offset.floatValue >= threshold) {
                        tick()
                        scope.launch {
                            androidx.compose.animation.core.animate(
                                initialValue = offset.floatValue,
                                targetValue = maxPx,
                                animationSpec = if (motion) {
                                    androidx.compose.animation.core.tween(160)
                                } else {
                                    androidx.compose.animation.core.snap()
                                },
                            ) { v, _ -> offset.floatValue = v }
                            currentOnDismiss()
                            offset.floatValue = 0f
                        }
                    } else {
                        scope.launch {
                            androidx.compose.animation.core.animate(
                                initialValue = offset.floatValue,
                                targetValue = 0f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = 0.75f,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                ),
                            ) { v, _ -> offset.floatValue = v }
                        }
                    }
                },
            ) { _, dragAmount ->
                val target = offset.floatValue + dragAmount
                offset.floatValue = when {
                    target <= threshold -> target.coerceAtLeast(0f)
                    // Rubber band: past the threshold each extra dp of drag buys
                    // less travel, asymptotically capped at the sheet's width.
                    else -> threshold +
                        (maxPx - threshold) *
                        (1f - 1f / (1f + (target - threshold) / (maxPx - threshold)))
                }
            }
        }
}

@Composable
fun rememberFolioInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * Secondary-button (right-click) trigger for items whose actions live behind the
 * long-press context menu. A mouse cannot hold comfortably, so the platform's own
 * context gesture is the menu's other door; touch never reports the secondary
 * button, so this stays silent on phones and tablets.
 */
@Composable
fun Modifier.folioRightClick(onRightClick: () -> Unit): Modifier {
    val current by rememberUpdatedState(onRightClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Release && event.buttons.isSecondaryPressed) {
                    current()
                }
            }
        }
    }
}

/**
 * A hairline structural rule. Editorial layouts get their order from rules and
 * alignment, not from putting every group in a box.
 */
@Composable
fun FolioRule(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    thickness: Dp = 1.dp,
) {
    val atmos = FolioTheme.atmosphere
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        (accent ?: atmos.hairline).copy(alpha = 0.55f),
                        atmos.hairline.copy(alpha = 0.10f),
                    ),
                ),
            ),
    )
}

