package com.folio.reader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.lampColor
import com.folio.reader.ui.theme.rememberMotionEnabled

/**
 * The strength a cover's halo is drawn at once the cover's own colour is known —
 * the number `Modifier.coverHalo` has always been called with here, named because
 * the shelf now ramps up to it instead of starting at it.
 */
const val COVER_HALO_STRENGTH = 0.30f

/**
 * How far the cover's hue may pull the halo before the palette's `primary` takes it
 * back. The glow is now themed first and borrowed second, so a yellow jacket warms a
 * purple plate rather than repainting it.
 */
const val HALO_COVER_BORROW = 0.18f

/**
 * The same glow on a dark field. A halo is light laid into a room, and a dark room
 * swallows far more of it before anything shows: a pale cover's own colour at
 * [COVER_HALO_STRENGTH] over a near-black field is arithmetically present and
 * visually absent, which is why the shelf still read as flat stickers. Paper keeps
 * the lower number — it shows tint readily, and a bloom that strong on cream is a
 * smudge.
 *
 * Down from 0.55: that figure was chosen when the halo was painted entirely in the
 * cover's colour, which is what made a yellow jacket read as a yellow flood. The
 * palette now owns the hue, so the same presence needs less push.
 */
const val COVER_HALO_STRENGTH_DARK = 0.26f

/**
 * The anchor plate's halo, and it is a different problem from the shelf's.
 *
 * [coverHalo] draws at `min(width, height) * 1.35`, which is right for a thumbnail:
 * the bloom leaves the plate and dies in the room, where a dark field swallows it.
 * Home's anchor is `coverAnchor` and sits *inside a card*, so the same radius has
 * several hundred pixels of pane to land on instead of air, and the glow arrives as
 * a colour stain across the face of the surface behind it.
 *
 * Measured on device across the hero at mid-height: spread 47 immediately beside the
 * plate, falling to 6 by the far edge — where 6 is what the pane's own neutralised
 * fill reads as. The bloom was contributing roughly eight times the card's own
 * chroma. This strength is set from that ratio, not chosen.
 *
 * Home was also the only plate omitting `haloStrength` entirely, which meant it drew
 * at [COVER_HALO_STRENGTH] — paper's number — whatever the theme. That is the shape
 * of the bug as much as the number is.
 */
const val COVER_HALO_STRENGTH_HERO = 0.06f

/**
 * While [rememberCoverAccent] is still returning the palette's fallback for a
 * cover, the glow runs at this dimmer strength. A shelf's covers finish decoding
 * in ones and twos, and a full-strength bloom in the app's own blue on a cover
 * that has not been read yet is a coloured sticker pretending to be a lit one —
 * the exact thing this pass exists to remove. Dim enough to read as resting,
 * present enough that the plate is never flat while it waits.
 */
const val COVER_HALO_PENDING_STRENGTH = 0.18f

/**
 * The halo's arrival ramp: [COVER_HALO_PENDING_STRENGTH] while the accent is
 * still the fallback, [COVER_HALO_STRENGTH] once it is the cover's own colour.
 *
 * Only the strength animates, never the colour — the colour swap has to happen on
 * the frame the sample lands (a second, *different* gradient stop would need
 * [coverHalo] to take two colours, and the plate has no business interpolating
 * hues it does not own). Ramping the light instead means a first-time sample
 * swells into place rather than snapping on, and because [rememberCoverAccent]
 * already produces the value asynchronously there is no second effect, no extra
 * frame pass and nothing to schedule.
 *
 * A warm cover — one already in the accent cache, which is every cover after the
 * first pass and every featured row that shares a path with its shelf — is
 * resolved on the first composition, so it draws at full strength immediately and
 * the ramp costs nothing. Rule 19: with animations off the target is the resolved
 * strength from frame one, so the glow simply is there.
 */
@Composable
fun rememberCoverHaloStrength(resolved: Boolean): Float {
    val motion = rememberMotionEnabled()
    // The glow is light added to a room, so how much of it is needed depends on the
    // room: a pale cover accent at paper's strength over a dark field is invisible,
    // which is what made the shelf still read as flat stickers. Paper shows a glow
    // readily and is left where it was.
    val full = if (FolioTheme.atmosphere.isDark) COVER_HALO_STRENGTH_DARK
    else COVER_HALO_STRENGTH
    val strength by animateFloatAsState(
        targetValue = if (resolved || !motion) full else COVER_HALO_PENDING_STRENGTH,
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "coverHaloStrength",
    )
    return strength
}

/**
 * A cover as a physical object rather than an image in a box.
 *
 * Three things make the difference, and all three are cheap:
 *  1. **Plate shape** — 2–5dp asymmetric corners. A 12dp radius reads as a
 *     widget; a printed trim is nearly square.
 *  2. **A spine** — a narrow dark gradient down the leading edge, which is what
 *     the eye uses to read "book" instead of "thumbnail".
 *  3. **A contact shadow** — tight, offset down, in the palette's own darkness.
 *     Covers lie *on* the page, so their shadow is short, not a 14dp bloom.
 *
 * [halo] spills the cover's own dominant colour onto the surface behind it, which
 * is how the artwork starts lighting its neighbourhood — and it is what separates
 * a cover sitting *in* the room from one pasted on top of it. Every shelf cover
 * earns it now; what varies is [haloStrength], which is dim while the app is still
 * showing the palette's fallback for that cover and full once the cover's own
 * colour has been read off it. See [rememberCoverHaloStrength].
 *
 * [suppressFallbackText] hides the typographic title/author that [BookCover]
 * draws when there is no artwork. It exists for morph destinations: the source
 * tile already shows that book's title beside the plate, and a paired title is
 * flying in under the same shared key, so a generated cover underneath would put
 * the same words on screen twice at two different sizes for the length of the
 * morph. The gradient stays — the plate still has to look like a cover, it just
 * stops repeating the label. Defaulted rather than read from a CompositionLocal
 * on purpose: a local is scoped to a subtree, and the hazard here is per-item
 * (one tile in a grid is a morph destination, its neighbours are not).
 */
@Composable
fun FolioCoverPlate(
    coverPath: String?,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    /**
     * Fixed plate width; height follows at [FolioTokens.coverAspect]. Pass `null`
     * to fill the parent's width instead and derive the height from the same
     * aspect — which is what a grid cell needs, since a fixed width plus
     * `fillMaxWidth()` from the caller would stretch the width while the height
     * stayed at the token's, squashing the trim to a square.
     */
    width: Dp? = FolioTokens.coverShelf,
    shape: Shape = FolioShapes.plate,
    halo: Color? = null,
    haloStrength: Float = COVER_HALO_STRENGTH,
    elevation: Dp = FolioTokens.elevationVeil,
    small: Boolean = false,
    suppressFallbackText: Boolean = false,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val atmos = FolioTheme.atmosphere
    val sizing = if (width != null) {
        Modifier.width(width).height(width * FolioTokens.coverAspect)
    } else {
        Modifier.fillMaxWidth().aspectRatio(1f / FolioTokens.coverAspect)
    }
    Box(
        modifier = modifier
            .then(sizing)
            .then(
                if (halo != null) Modifier.coverHalo(
                    // The theme owns the glow and the book only leans it. This used to
                    // pass the cover as its own theme with a zero hue range — "a glow is
                    // light the object throws" — and on device that flooded a yellow
                    // cover's plate with yellow so it read as foreign to the room around
                    // it. Same seam the rim had: he wants the palette to carry it and a
                    // bounded amount of the book on top.
                    lampColor(
                        FolioTheme.colors.primary, halo,
                        HALO_COVER_BORROW, FolioTheme.atmosphere,
                    ),
                    strength = haloStrength,
                ) else Modifier
            )
            .shadow(
                elevation = elevation * atmos.shadowScale,
                shape = shape,
                ambientColor = atmos.shadowAmbient,
                spotColor = atmos.shadowSpot,
            )
            .clip(shape),
    ) {
        BookCover(
            coverPath = coverPath,
            title = title,
            author = author,
            small = small,
            suppressFallbackText = suppressFallbackText,
        )
        // The spine: the cue that separates a book from a picture.
        Box(
            Modifier
                .fillMaxWidth(0.055f)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
                    ),
                ),
        )
        // A rim-lit seat instead of a flat 4-edge stroke: a light catch along the
        // top edge falling to a soft shade at the foot, so the plate reads as a
        // printed object lit from above (the atmosphere's light model). Dark
        // palettes emit at the rim (light top, transparent foot); light palettes
        // keep a faint foot shade so a white cover can't dissolve into the page.
        Box(
            Modifier
                .matchParentSize()
                .border(
                    0.5.dp,
                    Brush.verticalGradient(
                        listOf(
                            (if (atmos.isDark) atmos.rimLight else Color.White)
                                .copy(alpha = 0.28f),
                            // Paper occludes rather than emits, but it should occlude in
                            // the palette's own deepest outline. This stop was a literal
                            // Color.Black, which drew a black rule along the foot of a
                            // cream page — the border "going black" on light themes.
                            if (atmos.isDark) Color.Transparent
                            else atmos.rimShade.copy(alpha = atmos.rimShade.alpha * 0.55f),
                        ),
                    ),
                    shape,
                ),
        )
        // §13.7 M7 — a refractive lens lip, where the platform can show one.
        //
        // The cover-sampling gel the brief sketched (a RuntimeShader with the decoded
        // jacket as a `uniform shader`) is the speculative path; this is its sanctioned
        // fallback — a shape-aware specular rim that reads as glass over a detailed
        // jacket without live-sampling it or risking the cover decode. It is a thin
        // highlight laid along the room's own sun axis, brightest on the sun-facing
        // edge and gone on the anti-sun side (a crude fresnel), over the plate's
        // outline. Gated on the `specular` capability alone, so desktop, previews and
        // low-RAM devices (and the liquid-glass preference off) render the plate
        // byte-for-byte as it was — today's rim-lit seat above and nothing more.
        if (LocalGlassCapabilities.current.specular) {
            val daylight = LocalFolioDaylight.current
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val path = shapePath(shape)
                        val (start, end) = daylightGradient(size, daylight)
                        val lip = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.55f to atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.12f),
                            1f to atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.60f),
                            start = start,
                            end = end,
                        )
                        onDrawBehind {
                            drawPath(path, lip, style = Stroke(width = 1.5.dp.toPx()))
                        }
                    },
            )
        }
        overlay?.invoke(this)
    }
}
