package com.folio.reader.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.LocalGlassCapabilities
import com.folio.reader.ui.components.shapePath
import kotlin.math.sqrt

/**
 * §13.7 / M7 — the one AGSL shader in the app.
 *
 * The film is drawn as a [ShaderBrush] behind the caller's content via
 * [drawWithCache] (draw-phase only, one pass, no recomposition), so it inherits
 * whatever clip the caller applied above it — a hero's own [FolioShapes.hero]
 * silhouette — and never paints over its text. Below API 33, with the preference
 * off, at zero intensity, or if the shader fails to compile on a given device,
 * the modifier returns unchanged and the §13.4 mesh + sheen fallback stands in.
 */
@Composable
actual fun Modifier.folioLiquidGlass(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float,
    enabled: Boolean,
    refractBackdrop: Boolean,
    focal: FolioPressFocal?,
    shape: Shape,
    platePackId: String,
    plateFieldTop: Color,
    plateFieldBottom: Color,
): Modifier {
    if (!enabled || intensity <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    val withFilm = liquidGlassLayer(accent, highlight, lightX, lightY, intensity, focal, shape)
    // `blur` is the app's existing verdict for "platform-composited and not a low-RAM
    // device", which is the budget a second full-surface shader pass needs.
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalFolioBackdrop.current
    // No room published means nothing to refract. The film is the whole effect there,
    // exactly as it was before the gel existed — desktop, previews, tests, and any
    // surface placed outside the app root.
    //
    // The gel is also now opt-in per call (`refractBackdrop`), default off. On a smooth
    // low-frequency backdrop — the hero's field is a vertical ramp plus three soft pools
    // and one lamp bloom — a lens rim has almost nothing to bend: the dispersion that is
    // meant to separate a lens from a gradient lands three samples on the same colour, so
    // the band only ever reads as a painted rectangle inset a fifth of the short side, not
    // as glass. Tuning its brightness, its Fresnel floor, its baseline and its chroma in
    // turn each fixed one face of that rectangle and surfaced the next, which is the loop
    // this flag ends. It stays available for a surface that genuinely has detail behind it.
    if (!refractBackdrop || !caps.blur || backdrop == null) return withFilm
    val len = sqrt(lightX * lightX + lightY * lightY)
    return withFilm.backdropGel(
        backdrop = backdrop,
        shape = shape,
        highlight = highlight,
        accent = accent,
        lx = if (len < 1e-4f) 0f else lightX / len,
        ly = if (len < 1e-4f) -1f else lightY / len,
        platePackId = platePackId,
        plateFieldTop = plateFieldTop,
        plateFieldBottom = plateFieldBottom,
        intensity = intensity.coerceIn(0f, 1f),
        focal = focal,
    )
}

/**
 * The refractive layer: the **room**, bent.
 *
 * A render effect can only ever see the subtree it is applied to, which is why the
 * previous attempt at this — [android.graphics.RenderEffect]'s
 * `createRuntimeShaderEffect(shader, "content")` on a `graphicsLayer` — could bend a
 * hero's own mesh and type and nothing else. Refraction of what is *behind* a
 * surface has to reproduce that thing, so this pass is a [ShaderBrush] drawn behind
 * the content and evaluates [LIQUID_GEL_AGSL] against the field's own closed form,
 * handed over by [FolioBackdrop]. The same helpers
 * [com.folio.reader.ui.components.folioField] draws with —
 * [fieldColors], [poolAlphaAt], [poolCenterAt] — feed the uniforms, so the pane
 * cannot disagree with the page it sits on.
 *
 * The card's position in the room is measured, not passed: [onGloballyPositioned]
 * writes a state read in the draw phase, so scrolling a pane across its backdrop
 * costs a redraw of that pane alone.
 *
 * **The §13.9 collapse is handled, and this used to claim the opposite.** The comment
 * here said layout position "does not follow" the `graphicsLayer` scale + 12dp
 * translation on the hero node, so the refraction rode up during the collapse. That is
 * not true, and it is worth recording *why* it is not, because the claim was plausible
 * and survived a long time. [androidx.compose.ui.layout.positionInRoot] is not a layout
 * read at all — it is `localToRoot(Offset.Zero)`, and `localToRoot` walks the coordinator
 * chain calling `toParentPosition`, which is `layer?.mapOffset(position, inverse = false)`
 * followed by the layout position. It *is* the composed transform: every `graphicsLayer`
 * between this node and the root, scale and translation included. So the gel has always
 * tracked the collapse, and the correction is to the comment rather than to the code.
 *
 * The one thing that genuinely is not in the model is what the field paints *over* itself
 * — §17's additive bloom film, at well under 1%, which is a low-frequency wash with no
 * figure. A lens rim has nothing to bend in it, which is a different statement from the
 * film being bent wrongly, and it is the same reason the film is still not refracted.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.backdropGel(
    backdrop: FolioBackdrop,
    shape: Shape,
    highlight: Color,
    accent: Color,
    lx: Float,
    ly: Float,
    platePackId: String,
    plateFieldTop: Color,
    plateFieldBottom: Color,
    intensity: Float,
    focal: FolioPressFocal?,
): Modifier {
    // `remember`ed for the same reason the film's is (see [liquidGlassLayer]): the
    // `RuntimeShader` constructor compiles the SkSL, and the gel's program is the largest
    // in the app — it carries the whole guilloche plate model. Unremembered it was being
    // re-compiled on every recomposition of the hero it is attached to, which includes
    // every frame of the §13.9 collapse that recomposes the card.
    val shader = remember { runCatching { RuntimeShader(LIQUID_GEL_AGSL) }.getOrNull() }
    if (shader == null) {
        // A rejected AGSL switches the gel off silently, and the rim band simply
        // vanishing looks *exactly* like a successful retune of it. Say so where a
        // device log can find it, so "better" can never be mistaken for "dead".
        androidx.compose.runtime.LaunchedEffect(Unit) {
            android.util.Log.w("folio-gel", "RuntimeShader rejected the gel AGSL — gel DISABLED")
        }
        return this
    }
    shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
    // The surface's own colour, for the rim catch — a lens edge carries the glass's
    // colour, not a white line. Read in main(), so SkSL keeps it in the uniform list.
    shader.setFloatUniform("accent", accent.red, accent.green, accent.blue)
    // Declared *and* used in the AGSL, or SkSL strips it from the active uniform list
    // and this setter throws at draw time rather than compile time.
    shader.setFloatUniform("highlightAlpha", highlight.alpha)
    shader.setFloatUniform("light", lx, ly)
    shader.setFloatUniform("intensity", intensity)
    // The pane's own chroma envelope. The refracted rim reproduces the room's accent
    // pools at the pool's *own* saturation, so a teal `accentProgress` pool behind the
    // edge painted a frame several times more chromatic than the pane it borders —
    // "the colour is outside the inner rectangle". The pane's fill was already
    // neutralised at palette time (FolioAtmosphere.raisedFill), and `tameCover` reins a
    // jacket to this exact ceiling; the rim was the one surface that borrowed the
    // room's colour without it. Same `chromaSpread` and envelope so there is one
    // definition of "too saturated", not a second guessed in the shader. Read
    // genuinely in `main`, so SkSL cannot strip it the way build 128's `highlightAlpha`
    // was stripped.
    shader.setFloatUniform(
        "rimChromaCeiling",
        chromaSpread(backdrop.atmos.raisedFill) * COVER_CHROMA_ENVELOPE,
    )
    // ── the plate, so the pane bends a room that has its own engraving in it ──
    //
    // The plate's uniform block is set by the *plate's own* shared setter, so the gel cannot
    // be wired differently from the ground plane it is refracting: there is one spelling of
    // `crossFreq1` and one place a new rule constant has to be added. The clock is
    // deliberately not in that block — the gel's `phase` is its rim travel on the glass
    // period, and the plate's is its weave on the guilloche period — so it is set here.
    val plateSpec = remember { guillocheSpecFor(platePackId) }
    val plateActive = LocalFolioGuillocheActive.current
    val platePhases = rememberSlowPhases(plateSpec.periodsMs)
    val plateMotion = rememberMotionEnabled()
    // Derived the same way the ground plane derives them — `guillocheTiersFor(FolioTheme.colors,
    // isDark)`. `FolioTheme.colors` is a composable read, hence outside the draw cache. All
    // three, because the plate is a multi-hue field now and a pane that bent only the first
    // tier would refract an engraving that is not the one behind it.
    val plateTiers = guillocheTiersFor(FolioTheme.colors, backdrop.atmos.isDark)
    val plateAccent = plateTiers[0]
    val plateAccentB = plateTiers[1]
    val plateAccentC = plateTiers[2]
    // The block is set unconditionally, and *only* here — one call, no mirroring loop. The
    // "no plate" case is handled by `plateStrength` in the draw block rather than by zeroing
    // twenty-odd uniforms, because a hand-written zeroing loop is exactly the second copy
    // this shared setter exists to make impossible: it would silently stop covering a uniform
    // the moment a fourth wave or a new rule constant were added.
    //
    // **This sets the shared block only, and that is the whole of its contract.** `plateAt`
    // reads the numeric block as globals (`faceWeight`, `isDark`, the five colour rules, the
    // shimmer depth) and the waves/fan likewise, and the gel has no competing meaning for any
    // of those — so sharing them is exactly the point. The three *colours* are deliberately
    // outside it: they are `plateAt` parameters, and the gel spells them
    // `plateAccent`/`plateFieldTop`/`plateFieldBottom` because it already owns
    // `fieldTop`/`fieldBottom` for its own film and declares no `accent` whatsoever. A setter
    // that wrote bare colour names here would overwrite this pane's own ramp and then throw
    // for the missing `accent`. They are set in the draw block; see the note there.
    shader.applyGuillocheUniforms(spec = plateSpec, isDark = backdrop.atmos.isDark)
    // The plate's aspect is the *field's*, not this pane's — the engraving is specified in
    // field fractions, and the pane's box is a different shape. A pane's own ratio here would
    // shear the engraving it bends away from the one the page painted, which is the exact
    // failure sharing `plateAt` exists to prevent. Set in the draw block, where the room's
    // size is known, exactly as the ground plane reads it for its own `aspect` uniform.
    // The quiet mask is shared with the plate for the reason its own doc gives: a region
    // that silences the engraving has to silence the refraction too, or the pane pokes a
    // ribbon back into the reader's clean page. Set unconditionally — an empty list is
    // `quietCount = 0`, which the shader's mask reads as "no regions".
    shader.applyQuietUniforms(LocalFolioGuillocheQuiet.current)
    val brush = ShaderBrush(shader)
    // Layout position in root pixels. Draw-phase state: a scroll invalidates this
    // pane's draw, never the tree under it.
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { coordinates -> origin = coordinates.positionInRoot() }
        .then(if (focal != null) Modifier.folioPressFocalOrigin(focal) else Modifier)
        .drawWithCache {
            val path = shapePath(shape)
            // The rim band has to follow *this* silhouette. Approximating it with one
            // radius guessed from the box gave a hero whose real corners are
            // 0/26/26/0 a rounded-rect band that ended well inside the card, and the
            // edge of that band is the dark rectangle this used to draw. The outline
            // already knows all four corners, and it allocates, so read it here in the
            // cache rather than per frame.
            val radii = when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> with(outline.roundRect) {
                    floatArrayOf(
                        topLeftCornerRadius.x, topRightCornerRadius.x,
                        bottomRightCornerRadius.x, bottomLeftCornerRadius.x,
                    )
                }
                else -> FloatArray(4) { minOf(size.width, size.height) * 0.12f }
            }
            onDrawBehind {
                val field = backdrop.fieldSize.value
                val fw = field.width.toFloat()
                val fh = field.height.toFloat()
                // Unmeasured room: there is no coordinate space to bend, so draw the
                // film alone rather than guess at a geometry.
                if (fw <= 0f || fh <= 0f) return@onDrawBehind
                val colors = backdrop.colors()
                val (driftX, driftY) = backdrop.drift()
                val drift = fieldDrift(backdrop.shift)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("fieldSize", fw, fh)
                shader.setFloatUniform("origin", origin.x, origin.y)
                shader.setFloatUniform("fieldTop", colors.top.red, colors.top.green, colors.top.blue)
                shader.setFloatUniform(
                    "fieldBottom",
                    colors.bottom.red, colors.bottom.green, colors.bottom.blue,
                )
                // The field's own gradient origin — see `fieldT` in FolioFieldModel.kt.
                shader.setFloatUniform("fieldSource", colors.sourceX, colors.sourceY)
                // Three pools, as three fixed uniform pairs rather than an array:
                // SkSL will not index a uniform array dynamically, and three is the
                // atmosphere's own ceiling on how many pools a field may hold.
                DAYLIGHT_POOLS.forEachIndexed { i, pool ->
                    val color = colors.poolColors.getOrNull(pool.index)
                    if (color == null) {
                        shader.setFloatUniform("poolColor$i", 0f, 0f, 0f)
                        shader.setFloatUniform("poolGeom$i", 0f, 0f, 0f, 0f)
                        return@forEachIndexed
                    }
                    val alpha = poolAlphaAt(colors.poolAlpha, pool.sideX, backdrop.shift)
                    val (cx, cy) = poolCenterAt(pool, drift, driftX, driftY)
                    shader.setFloatUniform("poolColor$i", color.red, color.green, color.blue)
                    // Centre and radius in the room's own pixels, exactly as the
                    // field places them.
                    shader.setFloatUniform("poolGeom$i", cx * fw, cy * fh, pool.fr * fw, alpha)
                }
                // The surface's own four corners, so the rim band lands on the
                // silhouette rather than inside it.
                shader.setFloatUniform(
                    "radii", radii[0], radii[1], radii[2], radii[3]
                )
                // Keep the rim a thin edge on any size of surface: a small card's natural
                // 20%-of-short-side band is already near this, but a full-width settings
                // panel's would be a wide inset frame reading as a box. 22dp holds the
                // "liquid rim" read without becoming an internal rectangle.
                shader.setFloatUniform("bandMax", 22.dp.toPx())
                // The plate's clock and strength. `plateStrength` is the gel's copy of the
                // plate's own `enabled`, and at 0 the whole plate branch in `fieldAt` is
                // skipped — so a pane with no engraving behind it is the pre-plate shader.
                shader.setFloatUniform("plateStrength", if (plateActive) 1f else 0f)
                // The plate's *appearance* uniforms, under the gel's `plate`-prefixed names.
                //
                // These three are the reason the prefix exists: the gel already owns `accent`,
                // `fieldTop` and `fieldBottom` for its own film, with different meanings, so the
                // plate's copies have to be spelled apart. `applyGuillocheUniforms` writes the
                // *plate's* bare names and is therefore not the right setter for them — setting
                // `accent` here would overwrite the pane's material colour, and the three
                // `plate*` uniforms the gel declares would silently stay at (0,0,0), cutting the
                // refracted engraving out of a black ramp instead of the theme's gradient.
                //
                // Colours only: the numeric plate block (waves, fan, colour rules, quiet margin)
                // is genuinely shared and does go through `applyGuillocheUniforms`.
                shader.setFloatUniform("plateAccent", plateAccent.red, plateAccent.green, plateAccent.blue)
                shader.setFloatUniform("plateAccentB", plateAccentB.red, plateAccentB.green, plateAccentB.blue)
                shader.setFloatUniform("plateAccentC", plateAccentC.red, plateAccentC.green, plateAccentC.blue)
                shader.setFloatUniform("plateFieldTop", plateFieldTop.red, plateFieldTop.green, plateFieldTop.blue)
                shader.setFloatUniform("plateFieldBottom", plateFieldBottom.red, plateFieldBottom.green, plateFieldBottom.blue)
                // The refraction-only amplification. Fed here rather than written into the AGSL
                // so the number that decides whether the refraction reads is next to the
                // measurement that justified it — see `GLASS_PLATE_REFRACT_GAIN`.
                shader.setFloatUniform("plateGain", GLASS_PLATE_REFRACT_GAIN)
                // The dark-face lift. `plateDark` is the *same* polarity the plate itself was
                // built with, so the gel cannot take a different branch than the engraving it is
                // bending; `plateIntensityRef`/`plateFaceWeightRef` are the two factors `plateAt`
                // bakes into its alpha, so the gel can recover the plate's coverage from it
                // without `plateAt` growing a second signature. `plateLift` is the refraction's
                // own share toward the accent — see `GLASS_PLATE_REFRACT_LIFT`.
                shader.setFloatUniform(
                    "plateLift",
                    if (backdrop.atmos.isDark) GLASS_PLATE_REFRACT_LIFT else 0f,
                )
                shader.setFloatUniform("plateDark", if (backdrop.atmos.isDark) 1f else 0f)
                shader.setFloatUniform("plateIntensityRef", GUILLOCHE_ALPHA_MAX)
                shader.setFloatUniform(
                    "plateFaceWeightRef",
                    guillocheWeightFor(backdrop.atmos.isDark),
                )
                shader.setFloatUniform(
                    "platePhase",
                    if (plateMotion) platePhases.value.getOrElse(0) { 0f } else 0f,
                )
                shader.setFloatUniform(
                    "plateBreath",
                    if (plateMotion) platePhases.value.getOrElse(1) { 0f } else 0f,
                )
                // The plate's own aspect correction, from the *field's* box — the engraving
                // is specified in field fractions and this pane is a different shape, so a
                // pane-ratio here would shear the plate the gel bends away from the plate the
                // page painted. Guarded, because an unmeasured room would divide by zero.
                shader.setFloatUniform(
                    "plateAspect",
                    if (fh > 0f) fw / fh else 1f,
                )
                // The lamp, at rest. Bending a light is the whole reason the gel has
                // anything worth bending: a bare gradient looks the same displaced.
                val lamp = backdrop.lamp?.let { holder ->
                    lampOf(
                        atmos = backdrop.atmos,
                        authored = holder.authored,
                        themeColor = holder.themeColor,
                        cover = holder.light.value,
                        at = holder.at.value,
                        field = field,
                        phase = 0f,
                    )
                }
                if (lamp == null) {
                    shader.setFloatUniform("lampColor", 0f, 0f, 0f)
                    shader.setFloatUniform("lampGeom", 0f, 0f, 0f, 0f)
                } else {
                    shader.setFloatUniform(
                        "lampColor", lamp.color.red, lamp.color.green, lamp.color.blue
                    )
                    shader.setFloatUniform(
                        "lampGeom",
                        lamp.center.x, lamp.center.y, lamp.radius, lamp.alpha,
                    )
                }
                val at = focal?.pixelPoint()
                if (at == null) {
                    shader.setFloatUniform("focal", size.width * 0.5f, size.height * 0.5f)
                    shader.setFloatUniform("focalStrength", 0f)
                } else {
                    shader.setFloatUniform("focal", at.x, at.y)
                    shader.setFloatUniform("focalStrength", focal.strength.value.coerceIn(0f, 1f))
                }
                clipPath(path) { drawRect(brush) }
            }
        }
}

/**
 * AGSL source. A fixed virtual light (uniform `light`) with one slow clock:
 *
 *  - **rim** — the surface's **own silhouette**, as a rounded-rect signed distance field,
 *    carrying a thin bright catch along the edge. This is the term the film spent a long
 *    time without: the code below still records why the old one was deleted (a chebyshev
 *    metric on `uv` lit the outer band of the *bounding box*, so every non-rectangular
 *    surface got a bright inset rectangle). The fix is not to remove the edge but to give
 *    it the right geometry — an SDF with the surface's own four corner radii, which is
 *    what the gel already computes. A lens with no lit edge is not glass.
 *  - **glint** — a small, hard, four-point star on the sun-facing side. The reference's
 *    sparkle is tiny and sharp, not a soft bloom: "less area, more definition".
 *  - **rays** — two faint diagonal sheen streaks aligned to the light, giving the face
 *    *grain* rather than an isotropic shimmer.
 *  - **body** — a low-amplitude caustic so the fill reads as thick material.
 *  - **focal** — the glint, moved under the finger while a press is held.
 *  - **travel** (Stage R3, "the animated edge") — `phase` walks the rim's brightest
 *    point slowly around the silhouette and lets the glint drift a hair. This is a
 *    *redistribution*, not an addition: `rimTravel` is normalised to mean 1 and each of
 *    `glintTravel`/`rayTravel` is the same function of `phase` remapped to `[-1, 1]`
 *    (mean 0), so the time term can only ever **move** the catches along the edge and
 *    across the face. It can never enlarge them, which is what keeps the A5b amplitude
 *    bound true at every instant rather than only on average. `phase` is folded into
 *    `[0, 2π)` on the Kotlin side and parked at a still-but-complete value under
 *    reduce-motion, so the film has no animated path when the user has asked for none.
 *
 * Output is premultiplied (Skia convention). Every term is bounded so text drawn over the
 * hero keeps its contrast; the legibility guard holds the veto.
 * Colours arrive as uniforms from the caller's accent roles — so the glass is the theme's
 * own colour rather than a fixed white.
 */

/**
 * The rim kernel, shared by the film and the gel so the two cannot disagree about where an
 * edge is or how fast it decays.
 *
 * **This is the fix for "the glass has a visible square inside it".** Both shaders used to
 * weight their edge terms with a clamped linear ramp:
 *
 * ```
 * rim = clamp(1.0 + d / band, 0.0, 1.0) * step(d, 0.0)
 * ```
 *
 * and that ramp *draws a sharp-cornered rectangle*, by construction, for two compounding
 * reasons:
 *
 *  1. **Geometry.** The ramp reaches zero at exactly `d = -band`, and the iso-contour of a
 *     rounded-rect SDF at `-band` has corner radius `max(r - band, 0)`. The gel's band caps
 *     at `bandMax` = 22dp and the film's is 16% of the shorter side, while
 *     [FolioShapes.card] is 18dp and [FolioShapes.inset] is 12dp. On every settings panel
 *     and every theme card the band is therefore *wider than the corner it sits in*, so
 *     that contour is a **radius-zero rectangle** — a box with square corners, inside a
 *     rounded card.
 *  2. **Continuity.** Across that same contour the ramp's slope jumps from 0 to `1/band`.
 *     A gradient discontinuity is a Mach band: the eye resolves it as a drawn line even
 *     where no line was drawn. In the gel this was worse than a stray highlight, because
 *     the gel's alpha is `rim * GEL_COVERAGE` — the crease was in the pane's own *opacity*,
 *     the most visible channel there is.
 *
 * An exponential fixes both at once: it has no finite support, so there is no inner contour
 * to see at any radius or any surface size, and it is C-infinity everywhere instead of C0
 * on one ring. No amount of retuning the old constants could have done this — a ramp has an
 * end, and an end is an edge.
 *
 * Also here: [lumaAdapt], the adaptive-luminance term. One fixed specular weight cannot
 * serve an *animated* backdrop — whatever constant reads well over a bright pool blows out
 * over it and vanishes over a dark trough, which is why the same material kept looking
 * excellent in one region of the field and muddy in another. That is a structural problem,
 * not a tuning problem, and the cure is to drive the catch from the luminance it is actually
 * standing on.
 */
private const val GLASS_RIM_FUNCTIONS = """
// 1 on the silhouette, decaying smoothly inward, with no inner contour at any radius.
// `min(d, 0.0)` bounds the result at 1 on the boundary. The 0.5 scale makes the integral
// equal the clamped ramp this replaces (both come to band/2), so it is a drop-in with the
// same rim budget and no edge to see.
float rimFalloff(float d, float band) {
    return exp(min(d, 0.0) / max(band * 0.5, 1e-4));
}

// The silhouette gate, one pixel wide. `step(d, 0.0)` was a hard outer ring that the
// antialiased clipPath around the draw did not agree with, so the outermost pixel of every
// pane carried a sliver of aliasing on top of everything else.
float rimGate(float d) {
    return smoothstep(0.5, -0.5, d);
}

// Adaptive luminance: how much of its specular budget this fragment may spend, given the
// brightness of what it is standing on. Rec.709 luma, then a gentle inverse — a little more
// headroom on a dark backdrop, a little less on a bright one. Deliberately bounded to
// [0.75, 1.25] so this MODULATES the existing budget and can never become a second, larger
// rim. The window (0.08..0.62) is the range the silk field's own stops actually span across
// the ten packs.
float lumaAdapt(float3 backdrop) {
    float luma = dot(backdrop, float3(0.2126, 0.7152, 0.0722));
    return mix(1.25, 0.75, smoothstep(0.08, 0.62, luma));
}
"""

private const val LIQUID_GLASS_AGSL = """
uniform float2 size;
uniform float3 accent;
uniform float3 highlight;
uniform float highlightAlpha;
uniform float2 light;
uniform float intensity;
uniform float2 focal;
uniform float focalStrength;
// The surface's own four corners: x = topStart, y = topEnd, z = bottomEnd, w = bottomStart.
// A hero bleeds square on one edge and rounds on the other, so one radius cannot describe
// it — the same reason the gel takes these instead of a single figure.
uniform float4 radii;
// The slow clock, in radians and already folded into [0, 2π). Read genuinely in main(),
// so SkSL cannot strip it. One tick per 100 ms (SLOW_MOTION_TICK_MS) means a rim lap of
// ~110 s is ~1100 uniformly-spaced samples around the silhouette: smooth by eye, and
// exactly zero work between ticks because nothing recomposes to produce it.
uniform float phase;
""" + GLASS_RIM_FUNCTIONS + """

// The rim band's width as a fraction of the shorter side. Wide and shallow rather than
// narrow and strong: a narrow band at high coverage draws an *edge*, a wide one at low
// coverage reads as *thickness*. Same reasoning as the gel's own GEL_COVERAGE.
const float RIM_BAND = 0.16;
// The glint's own radius. Small on purpose — this is the reference's sparkle, not a bloom.
const float GLINT_RADIUS = 0.11;
// The travelling highlight: its concentration, and the reciprocal of its mean over a lap.
// The kappa mirrors GLASS_TRAVEL_KAPPA in GuillocheModel.kt and the mean its
// GLASS_TRAVEL_KAPPA_MEAN, so the model the test pins and the shader that draws cannot drift
// apart. Constant-folded by SkSL, so this costs nothing at run time.
const float GLASS_TRAVEL_KAPPA = 3.2;
const float GLASS_TRAVEL_KAPPA_MEAN = 0.23427;

// A raised cosine on the slow clock, remapped to [0, 1]. Mean is exactly 0.5 over a lap,
// and it never leaves the interval, so whatever it multiplies cannot leave its own range.
float travel01(float p) {
    return 0.5 - 0.5 * cos(p);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;

    // CAVEAT: highlightAlpha MUST be referenced before any dead-code elimination passes.
    // This line ensures the uniform isn't stripped by SkSL's static analysis.
    float _alphaCheck = highlightAlpha;
    // Same trap, same reason: `phase` is only ever used to *modulate* other terms, and a
    // shader whose only time term was optimised away would silently freeze the edge under
    // reduce-motion and make "parked" indistinguishable from "broken".
    float p = phase + _alphaCheck * 0.0;

    // ── the silhouette, as a rounded-rect SDF ──
    float2 c = size * 0.5;
    float2 v = fragCoord - c;
    float2 n = normalize(v + float2(0.0001, 0.0001));
    // The corner this fragment belongs to. Screen y grows downward, so y < 0 is the top
    // pair — which is why topStart is x and bottomStart is w.
    float r = (v.x < 0.0)
        ? ((v.y < 0.0) ? radii.x : radii.w)
        : ((v.y < 0.0) ? radii.y : radii.z);
    r = min(r, min(c.x, c.y));
    float2 q = abs(v) - (c - r);
    float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;

    // 1 at the edge, decaying smoothly inward, 0 outside the shape. Outside is gated so a
    // non-rectangular silhouette can never light its bounding box (the old bug).
    //
    // The kernel is `rimFalloff`, not a clamped ramp — see GLASS_RIM_FUNCTIONS for why the
    // ramp drew a square. The 1.45 is the energy compensation for the `pow(rim, 2.6)` on
    // the next line: `rimFalloff` is integral-matched to the ramp for a *linear* consumer
    // (both band/2), but raising it to 2.6 shortens the effective decay to band*0.5/2.6 =
    // 0.192*band, against the old pow(ramp, 2.6)'s band/3.6 = 0.278*band. Widening the
    // band by 0.278/0.192 = 1.45 lands the term on the same total brightness it had
    // before, so this is a geometry fix and not a restyling: same edge, no square.
    float band = min(size.x, size.y) * RIM_BAND * 1.45;
    float rim = rimFalloff(d, band) * rimGate(d);
    // Sharpen: the catch should be a *line of light on an edge*, not a soft gradient. The
    // exponent pulls the weight toward the outline itself.
    float rimLine = pow(rim, 2.6);
    // The animated edge: one bright region travelling the silhouette, counter-clockwise.
    // exp() of an angle difference is the von Mises kernel — a narrow, wrap-free arc — so
    // there is no seam at the top of the lap the way a raw compare against `atan` would
    // leave. The kernel peaks at 1 but *averages* only ~0.234, so it is divided by its own
    // mean; that normalisation is the whole contract, because without it the rim would be
    // 4.3x brighter on average and the term would be an addition dressed as a rotation. With
    // it, mean over a lap is exactly 1 and the rim budget is redistributed, never enlarged.
    // One lap per GLASS_TRAVEL_PERIOD_MS (142 s), chosen so its ratio to the plate's 53 s /
    // 79 s and the film's 42 s / lamp's 61 s clears every small-integer ratio — no beat.
    float th = atan(v.y, v.x);
    float rimTravel = exp(GLASS_TRAVEL_KAPPA * (cos(th - p) - 1.0)) / GLASS_TRAVEL_KAPPA_MEAN;

    // ── the glint ──
    // A small hard star on the sun-facing side. `spot0` sits where every other material's
    // catch already is, so the glass agrees with the rim light.
    float2 spot0 = float2(0.5, 0.5) - light * 0.30;
    // The drift: a short arc swung at right angles to the light. Perpendicular on
    // purpose — a spot that slid *along* the light would read as the sun moving, which
    // would contradict every other material in the app that shares this light vector.
    float2 ld = normalize(light + float2(0.0001, -0.0001));
    float2 lp = float2(-ld.y, ld.x);
    // ±0.035 of the pane, i.e. about a third of the glint's own radius: enough to catch
    // the eye on a still screen, not enough to look like a different glint.
    float2 spot = spot0 + lp * (cos(p) * 0.035);
    float2 gv = uv - spot;
    // A four-point star: a tight radial core plus two crossed needles. The needles are what
    // read as "sparkle" rather than "dot".
    float core = smoothstep(GLINT_RADIUS, 0.0, length(gv));
    float needleX = smoothstep(0.006, 0.0, abs(gv.y)) * smoothstep(GLINT_RADIUS * 3.2, 0.0, abs(gv.x));
    float needleY = smoothstep(0.006, 0.0, abs(gv.x)) * smoothstep(GLINT_RADIUS * 2.2, 0.0, abs(gv.y));
    float glint = core * core * 0.75 + (needleX + needleY) * 0.30;

    // ── the rays ──
    // Two long faint streaks along the light direction, giving the face grain. Deliberately
    // low frequency so they read as sheen, never as more pattern. The sheen itself drifts a
    // few percent across the face: a completely still sheen on an otherwise alive surface
    // reads as a printed gradient, which is the state the reference moves away from. A
    // phase offset in the sine's argument *slides the fringes*; it does not raise their
    // amplitude, so the peak brightness of this term is unchanged.
    float across = dot(uv, lp);
    float rayTravel = cos(p);
    float rays = 0.5 + 0.5 * sin(across * 7.0 + dot(uv, ld) * 2.0 + rayTravel * 0.22);
    rays = rays * rays * 0.5;

    // ── the body ──
    float body = 0.5
        + 0.5 * sin(uv.x * 6.2831853 + uv.y * 3.5)
        * cos(uv.y * 4.7123889 - uv.x * 2.3);
    body = body * body;

    // Direction from centre, so the catches sit on the sun-facing side.
    float facing = clamp(dot(n, -light), 0.0, 1.0);

    // Press focal: the glint follows the finger. `focalStrength` is the press spring, so at
    // rest this term is exactly zero and the film is the one that shipped before the finger
    // existed.
    float focus = smoothstep(GLINT_RADIUS * 2.4, 0.0, distance(uv, focal)) * focalStrength;

    // Compose the contributions into a premultiplied colour.
    // Weighting: the rim and the glint are *definition* (small area, high value); the body
    // and rays are *material* (large area, very low value). This split is what keeps the
    // film restrained while still reading as glass — the reference's own balance.
    float bodyW = body * 0.045;
    float rayW = rays * 0.030 * facing;
    // Crisper and brighter than before (0.20 -> 0.34): the reference's single loudest
    // signal is a clean line of light tracing the top and sides, and at 0.20 ours read
    // as a faint smudge rather than an edge. The `rimLine` exponent still keeps it a
    // line, not a band — this spends the budget on *value*, not area.
    float rimW = rimLine * 0.34 * (0.55 + 0.45 * facing) * rimTravel;
    // The glint is the one *point* term, so travelling it by its own radius is the
    // difference between a sparkle that lives and a sparkle that is a decal. `travel01` is
    // a life in [0, 1] rather than a signed swing: an eyelid that closes to 0.35 loses
    // nothing (a star disappearing and returning *is* the effect) but never brightens
    // beyond 1, which is the whole point of the normalisation above.
    float glintTravel = 0.35 + 0.65 * travel01(p + 1.1);
    float glintW = glint * 0.42 * facing * glintTravel;
    float focusW = focus * 0.26;
    // The reference's edge is the glass's *own colour*, not a white hairline — a faint
    // white border was the whole "cheap glass" tell. The rim therefore leans toward the
    // surface accent (the theme's primary/tertiary at the call sites), while the point
    // catches — the star glint and the press focal — stay the light's own near-white,
    // because a specular sparkle is white in every real material. `rimChromaCeiling` on
    // the gel still bounds how loud the edge may get; this only changes its *hue*.
    // The reference's edge is a bright, near-white specular line with only a *breath* of
    // the surface's hue — liquid glass catches the room's light, it is not a coloured
    // frame. An earlier cut leaned 0.62 toward the accent and read as a neon border; this
    // keeps it mostly the light's own white and lets the travelling theme-rim carry the
    // colour instead.
    float3 rimCol = mix(highlight, accent, 0.22);
    float rimWa = rimW * highlightAlpha;
    float ptWa = (glintW + focusW) * highlightAlpha;
    // Force use of _alphaCheck to prevent SkSL stripping - multiply result by it once.
    rimWa = rimWa * (_alphaCheck / max(0.001, _alphaCheck));

    float3 rgb = accent * (bodyW + rayW) + rimCol * rimWa + highlight * ptWa;
    float a = (bodyW + rayW + rimWa + ptWa) * intensity;
    rgb = rgb * intensity;
    return half4(half3(rgb), half(a));
}
"""

/**
 * The gel: the **room**, bent.
 *
 * [fieldAt] is the page's own closed form — the vertical ramp and the three drifting
 * pools, in the same order and by the same arithmetic `folioField` draws with, so a
 * pane shows the room rather than a likeness of it. `mix` per channel is what
 * [fieldColorAt] does, and a radial gradient that fades a colour to transparent
 * composites identically to a linear alpha falloff of that colour, which is why the
 * pool term is one `mix` and not a two-stop ramp.
 *
 * - **lens** — a rounded-rect signed distance field gives the outward normal and the
 *   rim weight; sample points move toward the centre near the edge, so the outline
 *   rolls over like the lip of a thick pane instead of stopping.
 * - **dispersion** — R, G and B are pulled at three different depths, which is the
 *   single cue that separates a lens from a gradient.
 * - **Fresnel** — the rim brightens where the edge faces away from the viewer and
 *   toward the room's light, on the same `light` vector the film uses.
 * - **gel highlight** — the press focal, now riding a refractive surface.
 *
 * Alpha is the rim weight, so the gel paints only its own border: at the centre of a
 * pane the lens term is 0, `fieldAt` returns the backdrop exactly as the page already
 * drew it there, and repainting it would be wasted work hiding the surface's own
 * fill. Fading to nothing a fifth of the short side inside is what keeps a hero a
 * hero; widening the band is one constant away if the pane ever deserves more of it.
 */
private const val LIQUID_GEL_AGSL = """
uniform float2 size;
uniform float2 fieldSize;
uniform float2 fieldSource;
uniform float2 origin;
uniform float3 fieldTop;
uniform float3 fieldBottom;
uniform float3 poolColor0;
uniform float4 poolGeom0;
uniform float3 poolColor1;
uniform float4 poolGeom1;
uniform float3 poolColor2;
uniform float4 poolGeom2;
// The lamp: colour, then centre.xy / radius / alpha.
uniform float3 lampColor;
uniform float4 lampGeom;
uniform float3 highlight;
uniform float3 accent;
uniform float highlightAlpha;
uniform float2 light;
// The most chroma the refracted rim may carry, as a max-minus-min channel spread.
// Set from the pane's own neutralised fill, so the band cannot out-saturate the
// surface it edges. Used in main(); a bare declaration would be stripped by SkSL.
uniform float rimChromaCeiling;
// The surface's own four corners: x = topStart, y = topEnd, z = bottomEnd,
// w = bottomStart. A hero bleeds square on one edge and rounds on the other, so
// one radius cannot describe it and the band ended up inside the card.
uniform float4 radii;
uniform float intensity;
uniform float2 focal;
uniform float focalStrength;
// The rim band's hard ceiling in pixels. The band is a fraction of the short side,
// which is right for a small card but on a large overlay panel (a settings group, a
// sheet) that fraction becomes a wide inset frame that reads as a painted rectangle
// inside the glass — the "internal square". Capping it keeps the gel a thin rim on
// every surface, so a pane and a panel wear the same edge thickness.
uniform float bandMax;
// ── the plate ─────────────────────────────────────────────────────────────
// The engraving on the ground plane, so this pane bends a room that has its own plate in
// it. Without these the gel evaluated `fieldAt` — a bare ramp, three pools and a lamp — and
// refracted a *likeness* of the room: a rim seen over the plate's ribbons had no ribbons to
// bend, so the refraction contributed nothing exactly where the surface is most visible.
//
// `plateStrength` is 0 wherever there is no plate behind this pane (a reading route, a
// desktop preview, the preference off), and at 0 the plate term below is an identity: the
// gel is then bit-for-bit the shader that shipped before the plate existed.
uniform float plateStrength;
uniform float3 plateAccent;
// The plate's other two authored hues, so the pane refracts the *multi-hue* engraving the
// page draws rather than a monochrome likeness of it. Prefixed for the same reason as
// `plateAccent`: the gel owns `accent` for its own material.
uniform float3 plateAccentB;
uniform float3 plateAccentC;
// ── the plate refraction gain ─────────────────────────────────────────────
// `plateAt` returns the plate's *authored* alpha — the coverage of a background texture, drawn
// at `GUILLOCHE_ALPHA_MAX = 0.48`. Compositing that faithfully into the field model is correct
// but, measured, invisible: the refracted plate adds 0.00–0.08 of one 8-bit step on a dark face
// at 11:1 ink contrast, and the gel's own rim catch is what the eye actually reads. A lens that
// bends a hairline produces a *displaced* hairline, not a fainter one, so the honest model is
// the plate's shape at a strength a pane can register — this is that strength, applied only to
// the refraction (never to a gain the page itself is drawn at, so the plate on screen is
// unchanged).
//
// It lives in Kotlin as `GLASS_PLATE_REFRACT_GAIN` and is fed as a uniform rather than written
// here, so the one number that decides whether the refraction is visible is next to the
// measurement that justified it, and R4 can retune it without touching AGSL. A gain of exactly
// 1.0 restores the faithful composite above; 0.0 disables the plate term entirely (same as
// `plateStrength`). Note the gain is **not** the whole story on a dark face — see `plateLift`.
uniform float plateGain;
// ── the dark-face lift ────────────────────────────────────────────────────
// On a dark theme the plate is a *deepen* of the field, and a near-black line over a near-black
// room is invisible at any gain (`Probe18`: 0.80 of one 8-bit step even at 12x). The gel therefore
// refracts a *lifted* line on dark faces — see the long note in `fieldAt`. `plateLift` is the
// refraction's own share toward the accent; `plateDark` is the face polarity the plate was built
// with (so the gel and the plate cannot disagree about which branch to take); the other two exist
// so the gel can recover the plate's coverage from its own alpha without growing a second
// signature on `plateAt`.
uniform float plateLift;
uniform float plateDark;
uniform float plateIntensityRef;
uniform float plateFaceWeightRef;
// The plate's own aspect correction. It must be the *field's*, not this pane's: the plate is
// specified in field fractions and the pane's box is a different shape, so reusing the pane's
// ratio would shear the engraving the pane bends away from the one the page painted — the
// exact failure the shared `plateAt` exists to prevent.
uniform float plateAspect;
// `plateFieldTop`/`plateFieldBottom` are separate from `fieldTop`/`fieldBottom` on purpose.
// The first pair is the ramp the *page* paints; the second is the ramp the plate is *cut
// out of*, which is the same ramp only while the pane sits directly on the field. Keeping
// them apart is what lets a card tinted by the ambient crossfade carry its own wash without
// silently recolouring the engraving behind it.
uniform float3 plateFieldTop;
uniform float3 plateFieldBottom;
uniform float platePhase;
uniform float plateBreath;
""" + GUILLOCHE_UNIFORMS + GUILLOCHE_FUNCTIONS + GLASS_RIM_FUNCTIONS + """

// 0.26: the rim is what makes the hero read as an outlined box, so the gradient has
// to be long and shallow rather than short and strong. A narrow band at high
// coverage draws an edge; a wide one at low coverage reads as thickness.
const float GEL_COVERAGE = 0.26;

float3 addPool(float3 col, float4 geom, float2 p, float3 over) {
    // geom is centre.xy, radius, alpha — all in the room's own pixels.
    if (geom.w <= 0.0 || geom.z <= 0.0) return over;
    float d = distance(p, geom.xy) / geom.z;
    if (d >= 1.0) return over;
    return mix(over, col, geom.w * (1.0 - d));
}

float lampFalloff(float d) {
    // Mirrors FolioLamp.lampFalloff: flat leaving the source, flat arriving, so the
    // glow has no limb to bend and no edge to read as an object.
    if (d >= 1.0) return 0.0;
    if (d <= 0.0) return 1.0;
    float s = 1.0 - d;
    return s * s * (3.0 - 2.0 * s);
}

// The room, in the room's own pixels.
//
// This used to bend the pane's own material, and that was right while the hero was
// 86% opaque: an opaque card has no business showing the wallpaper at its edge, so
// the rim band was pure import and read as a frame. The pane is now genuinely
// translucent, which is the only condition under which refracting the room is a
// true statement about the surface — the two settings are a package deal, and
// changing one without the other reproduces the brown band this replaced.
//
// **The plate is part of the room.** This function used to stop at the lamp: a ramp, three
// pools, a bloom — everything the *field* paints and nothing the *plate* engraves over it.
// That was defensible while the plate was absent or near-invisible, and it is wrong now: a
// rim that crosses a bright ribbon and returns the un-engraved field is a rim that has
// silently replaced the detail with a likeness of it. The plate term carries the same
// engraving the ground plane paints, evaluated by the plate's *own* shared functions, so
// the two cannot disagree about where a ribbon is — see GUILLOCHE_FUNCTIONS in
// GuillocheShader.android.kt, which is where that arithmetic lives.
float3 fieldAt(float2 p) {
    // The field's own axis — see `fieldT` in FolioFieldModel.kt. `p.y / fieldSize.y` was the
    // vertical ramp; the page is a 2-D gradient along the theme's light, and a pane that
    // reproduces only the vertical one disagrees with the room it is sitting in. `p` and
    // `fieldSize` are both in pixels, so the projection is direct.
    // Centred on the field and scaled by its own projection range, so t = 0 and t = 1 land on
    // the two extreme corners and nothing clamps flat — anchoring t at the source instead puts
    // 22-30% of the page outside [0, 1]. See `fieldT` in FolioFieldModel.kt.
    float2 fD = (float2(1.0) - 2.0 * fieldSource) * fieldSize;
    float fSpan = abs(fD.x) * fieldSize.x + abs(fD.y) * fieldSize.y;
    float t = 0.5 + dot(p - 0.5 * fieldSize, fD) / max(fSpan, 1e-6);
    float3 c = mix(fieldTop, fieldBottom, clamp(t, 0.0, 1.0));
    c = addPool(poolColor0, poolGeom0, p, c);
    c = addPool(poolColor1, poolGeom1, p, c);
    c = addPool(poolColor2, poolGeom2, p, c);
    // The lamp, as the Canvas layer draws it: one bloom and nothing else, so the pane
    // bends what the page actually shows.
    if (lampGeom.w > 0.0 && lampGeom.z > 0.0) {
        float d = distance(p, lampGeom.xy) / lampGeom.z;
        c = mix(c, lampColor, lampGeom.w * lampFalloff(d));
    }
    // The plate. `p` is in the room's own pixels and the plate is specified in the field's
    // normalized space, so the tap has to be divided back down — the same round trip the
    // pool uniforms make in the other direction (`poolCenterAt(...) * fieldSize`).
    //
    // `plateStrength` replicates the plate's own gate exactly, INCLUDING the quiet mask: the
    // plate multiplies its coverage by `quietWeight` before it composites, and a pane that
    // kept refracting the engraving inside a quiet region would poke a ribbon back into the
    // reader's clean page. One mask, read from the same uniforms, for one silence.
    //
    // The composite mirrors the plate's own: the plate is drawn *over* the field as a line at
    // low alpha, so its premultiplied `rgb * a` is what the page shows and what this must
    // reproduce. `plate.a` is already `coverage * intensity * faceWeight`, so it needs no
    // extra scaling — a rim bend is not a second application of the plate's strength.
    if (plateStrength > 0.0 && plateGain > 0.0) {
        float2 uv = clamp(p / fieldSize, 0.0, 1.0);
        float q = quietWeight(uv, quietCount, quietMargin);
        if (q > 0.0) {
            // `plateIntensityRef` — never this shader's own `intensity`, which is the pane's
            // film strength. `plateAt` asks its dark-face headroom question at the plate's
            // working strength, so handing it the wrong material's opacity would make the gel
            // take a different colour branch than the engraving it is bending.
            float4 plate = plateAt(uv, platePhase, plateBreath, plateAspect, plateIntensityRef,
                                   plateFieldTop, plateFieldBottom, fieldSource,
                                   plateAccent, plateAccentB, plateAccentC);
            // ── the dark-face lift (the R4 finding) ──────────────────────────────
            // On a dark theme the plate is authored as a *deepen* of the field, and the field
            // bottoms out near black — so a near-black line over a near-black room is invisible
            // **at any gain**. `Probe18` swept 1×..12× and the best dark-face ribbon reached
            // 0.80 of one 8-bit step: the failure is structural, not a matter of amplitude.
            //
            // The plate's own `plateAt` already knows this and has a lift branch, but it asks a
            // *local* headroom question (`can this fragment's cut register?`) and so fires only
            // on the darkest pool corners. A lens, however, faces the question everywhere: it
            // is bending the engraving into a rim line that must read against the room. So the
            // gel resolves it globally — on a dark face it refracts the plate LIFTED TOWARD THE
            // ACCENT (an engraving lit by the theme's own hue) instead of the deepened one.
            //
            // This is deliberately the gel only. The page still draws the deepened plate, so
            // the ground plane is unchanged and the "less flashy, its own colour" brief holds;
            // what changes is that the pane's bend of it becomes a lit line rather than a
            // shadow that has nowhere to go. On light faces the plate already clears the floor
            // (1.66..4.60 steps at gain 1.0 — paper needs no lift), so this branch is dark-only
            // and paper is bit-for-bit what it was.
            // `plate.rgb` is the plate's LINE COLOUR, not premultiplied: `plateAt` returns
            // `float4(line, coverage)` and the plate's own `main` premultiplies it as
            // `min(plate.rgb, 1.0) * a`. So it is taken directly — dividing by `plate.a` here
            // would over-brighten the line by the inverse of its own coverage.
            float3 line = min(plate.rgb, float3(1.0));
            if (plateDark > 0.5) {
                // `cover` here is the plate's own coverage before the intensity/faceWeight
                // scaling — the same quantity `plateAt` mixes with. Recovered rather than
                // passed, so this file does not have to grow a second plate signature.
                float cover = clamp(plate.a / max(plateIntensityRef * plateFaceWeightRef, 1e-5),
                                    0.0, 1.0);
                // `plateLift` is the refraction's own lift share: how far toward the accent the
                // bent engraving is pushed. Fed from Kotlin beside the gain.
                line = mix3(line, plateAccent, cover * plateLift);
            }
            // `plateGain` is the refraction-only amplification explained at the uniform block,
            // folded into the coverage `plateAt` already produced. `mix` is then the correct
            // composite for an un-premultiplied line colour and a coverage: it is the same
            // arithmetic the plate's own `main` does (`line * a` over the backdrop), so the pane
            // bends exactly the engraving the page drew, at the strength the gain asks for.
            float a = clamp(plate.a * q * plateStrength * plateGain, 0.0, 1.0);
            c = mix(c, line, a);
        }
    }
    return c;
}

half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 v = xy - c;
    float2 n = normalize(v + float2(0.0001, 0.0001));
    // The corner this fragment belongs to, so an asymmetric silhouette gets an
    // asymmetric band. Screen y grows downward, so y < 0 is the top pair.
    float r = (v.x < 0.0)
        ? ((v.y < 0.0) ? radii.x : radii.w)
        : ((v.y < 0.0) ? radii.y : radii.z);
    r = min(r, min(c.x, c.y));
    float2 q = abs(v) - (c - r);
    float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;

    // Rim weight: 1 at the edge, decaying smoothly inward, nothing outside the shape. It is
    // both the lens depth and the opacity, so the pane only paints where it actually bends
    // — which is exactly why the kernel has to be smooth. `bandMax` (22dp) is wider than
    // FolioShapes.card (18dp), so the old clamped ramp's zero-contour was a radius-zero
    // rectangle and that rectangle was being drawn into the pane's ALPHA. See
    // GLASS_RIM_FUNCTIONS. The band itself is unchanged; only its falloff is.
    float band = min(min(size.x, size.y) * 0.20, bandMax);
    float rim = rimFalloff(d, band) * rimGate(d);
    float lens = 15.0 * rim;

    // The card's own pixels are not the room's: the taps have to land where the page
    // is, which is the fragment plus however far the pane sits into the field.
    float2 p = xy + origin;
    float3 rr = fieldAt(p - n * (lens * 1.12));
    float3 gg = fieldAt(p - n * lens);
    float3 bb = fieldAt(p - n * (lens * 0.88));

    // The dispersed refraction of the room. R, G and B are pulled at three depths, so
    // a saturated room pool sitting behind the rim arrives here at the pool's *own*
    // chroma — a frame louder than the pane it edges, the colour that read as "outside
    // the inner rectangle". Rein it to the pane's envelope exactly as tameCover reins a
    // jacket: pull toward the colour's own grey until its spread is within the ceiling,
    // hue direction and (mean) lightness untouched, only intensity moving. min(1.0, …)
    // so a quiet refraction is only ever pulled under the ceiling, never lifted toward
    // it, and the >0 guard keeps a neutral sample off the divide. The dispersion that
    // separates a lens from a gradient survives — the three depths still disagree, they
    // are merely no longer allowed to disagree in a saturated colour.
    float3 room = float3(rr.r, gg.g, bb.b);
    float spread = max(room.r, max(room.g, room.b)) - min(room.r, min(room.g, room.b));
    float grey = (room.r + room.g + room.b) / 3.0;
    // The chroma gate: pull the bent room toward its own grey until its spread is within the
    // pane's envelope, hue direction and (mean) lightness untouched, only intensity moving.
    // min(1.0, …) so a quiet refraction is only ever pulled under the ceiling, never lifted
    // toward it, and the >0 guard keeps a neutral sample off the divide.
    //
    // The gate is deliberately left over the plate as well, and that is the *right* call
    // even though the plate is mostly neutral: the plate rides `fieldAt`, so by the time it
    // reaches here it is part of `room` and cannot be separated again without a fourth,
    // unengraved tap. Gating it costs only chroma the plate barely has — its colour is a
    // deepen of the field on a dark face and a luminance-pinned tint on paper — while the
    // term the gate actually exists for (a saturated room pool arriving at its own chroma,
    // "the colour is outside the inner rectangle") is suppressed exactly as before. Adding a
    // bypass for the plate would be re-opening the A3-colour bug to fix a difference no eye
    // can find.
    float k = (spread > 1e-5) ? min(1.0, rimChromaCeiling / spread) : 1.0;
    room = mix(float3(grey, grey, grey), room, k);

    // ── adaptive luminance ──────────────────────────────────────────────────────────
    // The pane stands on an *animated* field, so no single specular weight can serve it:
    // the constant that reads over a bright pool blows out over it, and the one that
    // survives a dark trough disappears into it. That is the "same glass is excellent over
    // one region of the background and muddy over another" failure, and it is structural —
    // the backdrop moves, so there is no constant to find.
    //
    // `room` is the backdrop this fragment just sampled, so the answer is already in hand:
    // drive the catch from the luminance it is actually over. Bounded to [0.75, 1.25] by
    // `lumaAdapt`, so this redistributes the existing budget across the field rather than
    // enlarging it — the same contract `rimTravel` holds for the film's travelling edge.
    float adapt = lumaAdapt(room);

    float facing = clamp(dot(n, -light), 0.0, 1.0);
    // Raised floor (0.55 vs 0.35) so the gel never dims below a baseline, preventing
    // dark field taps from producing edge dimming when the sun-side faces away.
    float fres = pow(rim, 1.6) * (0.55 + 0.45 * facing) * 0.34 * adapt;
    float foc = smoothstep(band * 1.8, 0.0, distance(xy, focal)) * focalStrength * 0.30;
    // Band baseline: a rim-weighted highlight that does not go through `facing`, so
    // the rim cannot darken when the field taps land below the card face. Tuned
    // alongside the Fresnel-floor raise (0.35 -> 0.55): the floor lifts the
    // anti-sun side, the baseline lifts everywhere along the band so a chromatic
    // cover on a dark pane can no longer paint a darker rim than its centre.
    float bandBase = rim * 0.12 * adapt;

    // The same dropped-alpha repair the film got: the Fresnel and focal catches are
    // laid over the bent room, and on a dark face `highlight` is a 55% token being
    // spent as if it were solid. The band baseline shares the highlightAlpha chain
    // so a refactor of the token's alpha carries through. The refracted plate needs no
    // term of its own here: it is already inside `room`, which is the whole point of
    // putting it in `fieldAt`.
    // The same dropped-alpha repair the film got: the Fresnel and focal catches are
    // laid over the bent room, and on a dark face `highlight` is a 55% token being
    // spent as if it were solid. The band baseline shares the highlightAlpha chain
    // so a refactor of the token's alpha carries through. The refracted plate needs no
    // term of its own here: it is already inside `room`, which is the whole point of
    // putting it in `fieldAt`.
    //
    // The catch leans to the surface accent for the same reason the film's rim does —
    // a lens edge carries the glass's own colour, not a white line — while the focal
    // stays near-white as a specular point. `rimChromaCeiling` above still bounds how
    // chromatic the bent room (and so this edge over it) is allowed to be.
    float3 rimCol = mix(highlight, accent, 0.22);
    float3 rgb = room +
        rimCol * ((fres + bandBase) * highlightAlpha * intensity) +
        highlight * (foc * highlightAlpha * intensity);
    return half4(half3(rgb), half(rim * GEL_COVERAGE));
}
"""

/** One-shot so a dead film logs once per process instead of once per recomposition. */
private var filmDisabledWarned = false

/**
 * Where the animated edge parks under reduce-motion: exactly 2π, the same figure the clock
 * reaches at the end of a lap. Spelled as a literal rather than borrowed from the ambient
 * film's `TWO_PI` so this file owns its own still frame — and so the parked value is
 * obviously "one lap complete" to a reader, not "a number that happened to be available".
 */
private const val GLASS_TRAVEL_PARKED_RAD = 6.2831855f

/**
 * How hard the gel amplifies the engraving it refracts, relative to the plate's authored alpha.
 *
 * The plate is a *background texture* drawn at `GUILLOCHE_ALPHA_MAX = 0.48`, so compositing it
 * into the field model at its own strength is correct and, measured with `Probe16`/`Probe17`,
 * invisible: the refracted contribution lands at **0.00–0.08 of one 8-bit step** on a dark face
 * at 11:1 ink contrast, and the gel's own rim catch is what the eye reads. A lens that bends a
 * hairline returns a *displaced* hairline, not a fainter one — so the plate's **shape** is the
 * thing worth carrying, at a strength a pane can actually register.
 *
 * This is that strength, and it is deliberately *only* the refraction: the page draws its plate
 * at its own alpha regardless, so nothing below this file changes. `1.0f` restores the faithful
 * composite (and the invisibility); `GLASS_PLATE_REFRACT_OFF` disables the term.
 *
 * `1.5f` — the smallest round value that lifts a full-coverage dark-face ribbon past one 8-bit
 * step (0.08 × 1.5 = 0.12, still short; the *band* is what crosses, because a rim tap rides the
 * gradient where the plate is locally denser) while leaving a sub-1% tap sub-1%. It is chosen to
 * be *visible where the engraving is dense and absent where it is faint*, which is the whole
 * "flaunted evenly but not extravagant" brief. Retune here, not in AGSL.
 */
private const val GLASS_PLATE_REFRACT_GAIN = 1.5f

/**
 * How far the gel pushes a *dark-face* engraving toward the accent when it refracts it.
 *
 * On a dark theme the plate is authored as a *deepen* of the field, and the field bottoms out
 * near black — so the refracted engraving is a near-black line over a near-black room and is
 * invisible **at any gain**. `Probe18` swept `GLASS_PLATE_REFRACT_GAIN` from 1× to 12× and the
 * best dark-face ribbon reached 0.80 of one 8-bit step: the failure is structural, not amplitude.
 *
 * A lens faces the headroom question *everywhere* on the rim (it is bending the engraving into a
 * line that has to read against the room), whereas `plateAt`'s existing lift branch asks only a
 * *local* question and so fires on the darkest pool corners alone. This constant resolves it
 * globally for the refraction: on a dark face the bent line is mixed toward the theme's accent,
 * turning a shadow with nowhere to go into an engraving lit by the theme's own hue.
 *
 * `0.70f` — measured, not reasoned (`Probe19`). The sweep shows a clean valley: sparse corners stay
 * at 0.00–0.03 of an 8-bit step all the way to `0.90`, so the lift cannot introduce speckle, while
 * the dense ribbons cross the visible floor at `0.70` (`DARK` 75% ribbon: 2.52 steps, 90%: 4.06;
 * `BLOSSOM`: 2.10 / 3.34). That is the "flaunted evenly but not extravagant" brief as a number:
 * visible where the engraving is dense, silent where it is sparse.
 *
 * **Not `0.55`, which was the first guess, and the reason is worth keeping.** `0.55` is the paper
 * branch's `lightTint`, so matching it looked like "the same vibrance for each theme light and
 * dark" achieved by construction. It is not: at `0.55` dark reaches only 1.84 steps against paper's
 * 6.87, and **even at 0.90 dark reaches only ~52% of paper's signal** — the gap is structural, not
 * a tuning miss. Paper's line is a *colour* line on a light field, luminance-pinned, so a small
 * shift reads strongly; dark's lift is a small *absolute* rise that the chroma gate partly
 * re-neutralises. "Same vibrance" therefore cannot mean "same number" here — copying the number
 * would have shipped a dark theme at 27% of the intended signal while looking justified. The
 * honest target is each polarity visible *in its own right*, and `0.70` is where dark gets there.
 */
private const val GLASS_PLATE_REFRACT_LIFT = 0.70f

/**
 * Builds the shader once per (size, colour, light) identity inside the draw
 * cache, wraps it in a [ShaderBrush] and paints it behind the content. Guarded:
 * a device that rejects the AGSL (a vendor SkSL quirk) draws nothing rather than
 * crashing, which is the same visual outcome as the pre-shader fallback.
 *
 * The animated edge rides the app's shared slow clock ([rememberSlowPhases] over
 * [GLASS_TRAVEL_PERIOD_MS]), read in the draw phase only — exactly as [ambientShaderLayer]
 * does it. Nothing here resolves the phase at the call site, so no hero recomposes ten
 * times a second to move one float; only the pane redraws.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.liquidGlassLayer(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float,
    focal: FolioPressFocal?,
    shape: Shape,
): Modifier {
    // `remember`ed, and that is a frame-time fix rather than tidiness. `RuntimeShader(...)`
    // is not a handle to a cached program: the constructor hands the SkSL string to Skia,
    // which parses and compiles it. Unremembered, every blurred veil, every bar and every
    // hero re-compiled this shader on **every recomposition** — and the film is attached to
    // exactly the surfaces that recompose most (anything whose fill alpha, accent, ambient
    // tint or presence moves). The brush is remembered with it, since a `ShaderBrush` is
    // keyed by identity in `drawWithCache`'s own caching.
    val shader = remember { runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull() }
    if (shader == null) {
        // Same trap the gel logs against: a rejected AGSL switches the film off
        // silently, and a sheen that has stopped drawing is indistinguishable by eye
        // from one that has been successfully quietened. Logged through the process flag
        // rather than a `LaunchedEffect`: this runs inside the draw-cache builder on the
        // first frame, and a one-shot warning does not need a composition to hang off.
        if (!filmDisabledWarned) {
            filmDisabledWarned = true
            android.util.Log.w("folio-gel", "RuntimeShader rejected the film AGSL — film DISABLED")
        }
        return this
    }
    // The animated edge. Reduce-motion parks at GLASS_TRAVEL_PARKED_RAD — the *same* figure
    // the clock reaches at the end of a lap — so the film stays complete, just still. That
    // is the contract the guilloche and the ambient film follow too, and the reason a parked
    // phase is not 0: 0 is a particular point in the lap, not the absence of one.
    val motion = rememberMotionEnabled()
    val phases = rememberSlowPhases(GLASS_TRAVEL_PERIOD_MS)
    // Normalise the light vector defensively; a zero vector would divide by zero
    // in the shader's normalize(). Neutral daylight is straight-down (0, -1).
    val len = sqrt(lightX * lightX + lightY * lightY)
    val lx = if (len < 1e-4f) 0f else lightX / len
    val ly = if (len < 1e-4f) -1f else lightY / len
    shader.setFloatUniform("accent", accent.red, accent.green, accent.blue)
    shader.setFloatUniform("highlight", highlight.red, highlight.green, highlight.blue)
    // The token's alpha is part of the token. Handing over only the channels is what
    // let the film spend a 55% light-catch at full strength on every dark palette.
    shader.setFloatUniform("highlightAlpha", highlight.alpha)
    shader.setFloatUniform("light", lx, ly)
    shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
    val brush = ShaderBrush(shader)
    val base = if (focal != null) this.folioPressFocalOrigin(focal) else this
    return base.drawWithCache {
        shader.setFloatUniform("size", size.width, size.height)
        // The surface's own silhouette, read from the Outline so all four corners are
        // right — a hero bleeds square on one edge and rounds on the other, and one radius
        // guessed from the box is exactly how the old edge term painted a rectangle inset
        // inside the card. Allocated here in the cache, never per frame.
        val radii = when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> with(outline.roundRect) {
                floatArrayOf(
                    topLeftCornerRadius.x, topRightCornerRadius.x,
                    bottomRightCornerRadius.x, bottomLeftCornerRadius.x,
                )
            }
            else -> FloatArray(4) { minOf(size.width, size.height) * 0.12f }
        }
        shader.setFloatUniform("radii", radii[0], radii[1], radii[2], radii[3])
        onDrawBehind {
            // The animated edge: the rim's brightest point travels the silhouette and the
            // glint drifts across the face, both on the shared slow clock. Read here, not
            // cached — folding the phase into the cache's identity would rebuild the brush
            // and re-upload every colour uniform ten times a second to move one float.
            // Reduce-motion parks at 2π: the same complete figure, still.
            shader.setFloatUniform(
                "phase",
                if (motion) phases.value.firstOrNull() ?: 0f else GLASS_TRAVEL_PARKED_RAD,
            )
            // Draw-phase only. Reading the press here rather than capturing it in the
            // cache block keeps it out of the cache's identity, which would otherwise
            // rebuild the brush and re-set all four colour uniforms on every frame of
            // the settle spring. At rest the strength is 0, so this is exactly the
            // film that shipped before the finger existed.
            val uv = focal?.normalized(Offset(size.width, size.height))
            if (uv == null) {
                shader.setFloatUniform("focal", 0.5f, 0.5f)
                shader.setFloatUniform("focalStrength", 0f)
            } else {
                shader.setFloatUniform("focal", uv.x, uv.y)
                shader.setFloatUniform("focalStrength", focal.strength.value.coerceIn(0f, 1f))
            }
            drawRect(brush)
        }
    }
}
