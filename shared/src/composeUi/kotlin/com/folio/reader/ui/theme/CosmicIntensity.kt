package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * How loud the cosmic field is on a given screen.
 *
 * One field sits under the whole app ([com.folio.reader.ui.components.folioField]); the
 * intensity is the single dial a screen turns to say how much of that field it wants
 * behind it. The payload is per-level scalars solved once here, so the field renderers
 * (`drawSkyscape` + the AGSL `folioSkyShader`) and the foreground primitives all read
 * the *same* numbers rather than each inventing its own idea of "quiet".
 *
 *  - [Quiet]       — settings, dialogs, forms: a faint gradient, a near-invisible
 *                    orbital line, a minimal dusting of stars, no planet, no haze.
 *  - [Atmospheric] — the default, and the look the app already shipped: the full
 *                    skyscape at its proven, contrast-guarded ceiling.
 *  - [Expressive]  — Home / Stats / rich empty states: the content band is held at
 *                    the *same* legible ceiling as [Atmospheric] (so the contrast
 *                    guards never move), while the margins run bolder and screens
 *                    compose foreground celestial art (see `ui/components/cosmic`).
 *
 * **Why the content band never gets brighter than [Atmospheric].** The whole
 * legibility story — `FolioFieldModelTest` compositing the motif at
 * [FOLIO_SIGNATURE_ALPHA_MAX] and asserting ink clears 7:1 — is calibrated against the
 * current field. [baseAtmosphereAlpha] is therefore capped at 1f: a level may make the
 * field *fainter* (Quiet) or push *edge* vividness ([edgeStrength]) past 1, but it may
 * never raise the content-band ceiling the guard measures. Expressiveness is delivered
 * at the margins and by foreground primitives, not by brightening the ground under text.
 */
enum class CosmicIntensity(
    /**
     * Content-band ceiling multiplier, 0..1. Scales every skyscape term in the middle
     * of the screen where cardless body text lives. Capped at 1f on purpose — see the
     * class doc — so the strongest any level can be under text is exactly the field the
     * contrast guards already prove legible.
     */
    val baseAtmosphereAlpha: Float,
    /**
     * Edge vividness multiplier applied only to the portion of each layer that lives at
     * the top/bottom margins (where no cardless body text sits). May exceed 1f: this is
     * how [Expressive] reads bolder without touching the content-band ceiling.
     */
    val edgeStrength: Float,
    /** Fraction of the built star field actually drawn. Thins the sky on [Quiet]. */
    val starDensity: Float,
    /** Multiplier on the faint orbital-arc alpha. */
    val orbitLineAlpha: Float,
    /** Whether the far planet / celestial body draws at all. */
    val drawsPlanet: Boolean,
    /** Whether the light-face horizon haze / mist draws at all. */
    val drawsHaze: Boolean,
) {
    Quiet(
        baseAtmosphereAlpha = 0.5f,
        edgeStrength = 0.6f,
        starDensity = 0.4f,
        orbitLineAlpha = 0.3f,
        drawsPlanet = false,
        drawsHaze = false,
    ),
    Atmospheric(
        baseAtmosphereAlpha = 1f,
        edgeStrength = 1f,
        starDensity = 1f,
        orbitLineAlpha = 1f,
        drawsPlanet = true,
        drawsHaze = true,
    ),
    Expressive(
        baseAtmosphereAlpha = 1f,
        edgeStrength = 1.3f,
        starDensity = 1f,
        orbitLineAlpha = 1.2f,
        drawsPlanet = true,
        drawsHaze = true,
    ),
}

/**
 * The intensity a screen asked for, hoisted to the room.
 *
 * The field is an *ancestor* of every screen, so a screen cannot provide the intensity
 * downward to the ground it stands on — it has to travel *upward*. This is the same
 * seam [FolioAmbientTint] uses for the room's colour: a screen writes with
 * [CosmicIntensitySource], the root reads [level] in `folioField`'s composition and
 * rebuilds its wash once. Last-writer-wins, which is what makes the field change as a
 * tab does; the default is [CosmicIntensity.Atmospheric].
 */
class CosmicIntensityState internal constructor(
    private val requested: MutableState<CosmicIntensity?>,
) {
    /** The level the field is rendering now, or the default if no screen has written. */
    val level: CosmicIntensity get() = requested.value ?: CosmicIntensity.Atmospheric

    /** The raw request, for a reader that wants to tell "unset" from "Atmospheric". */
    val requestedLevel: CosmicIntensity? get() = requested.value

    /** Ask the field to render at [level]. */
    fun to(level: CosmicIntensity) {
        requested.value = level
    }
}

/**
 * Provided at the app root (above `folioField`). An un-provided tree — desktop, a
 * preview, a unit test — leaves every [CosmicIntensitySource] a no-op and the field at
 * [CosmicIntensity.Atmospheric], which is the look the app already shipped.
 */
val LocalCosmicIntensityController = compositionLocalOf<CosmicIntensityState?> { null }

/**
 * The fallback intensity for a tree with no hoisted controller — desktop, a preview, a
 * unit test — and an explicit downward default. The field reads the controller, not
 * this, so in the running app the controller's level wins; this governs
 * [rememberCosmicIntensity] only where no controller is provided.
 */
val LocalCosmicIntensity = compositionLocalOf { CosmicIntensity.Atmospheric }

@Composable
fun rememberCosmicIntensityState(): CosmicIntensityState {
    val requested = remember { mutableStateOf<CosmicIntensity?>(null) }
    return remember(requested) { CosmicIntensityState(requested) }
}

/**
 * Declares that this screen wants the field rendered at [level]. Call it from a screen
 * body; the last writer wins. Mirrors [FolioAmbientSource]: it does not clear on
 * dispose, so a screen leaving does not drop the field back to the default for the
 * length of a transition it is mid-way through (which would read as a flicker).
 */
@Composable
fun CosmicIntensitySource(level: CosmicIntensity) {
    val controller = LocalCosmicIntensityController.current ?: return
    LaunchedEffect(level) { controller.to(level) }
}

/**
 * The level the current subtree should render at. The hoisted controller is the source
 * of truth (so a primitive composed on a screen that called `CosmicIntensitySource`
 * sees that screen's level); [LocalCosmicIntensity] is the fallback / explicit
 * override, and the default is [CosmicIntensity.Atmospheric].
 */
@Composable
fun rememberCosmicIntensity(): CosmicIntensity =
    LocalCosmicIntensityController.current?.level ?: LocalCosmicIntensity.current

/** The field's intensity, exposed on the theme façade like [FolioTheme.atmosphere]. */
val FolioTheme.cosmicIntensity: CosmicIntensity
    @Composable
    get() = rememberCosmicIntensity()

/**
 * The intensity resolved into the exact scalars the skyscape renderers consume, so the
 * Canvas (`drawSkyscape`) and AGSL (`folioSkyShader`) sides read one solved value and
 * cannot drift (Rule 3). [contentScale] is clamped to 1f here, not trusted from the
 * enum, so the content-band ceiling is bounded no matter what a future level declares.
 */
@androidx.compose.runtime.Immutable
class CosmicSkyIntensity(
    val contentScale: Float,
    val edgeStrength: Float,
    val starDensity: Float,
    val orbitLineAlpha: Float,
    val drawsPlanet: Boolean,
    val drawsHaze: Boolean,
) {
    companion object {
        /** The identity used by an un-provided tree and by `drawSkyscape`'s default. */
        val Atmospheric = CosmicIntensity.Atmospheric.sky()
    }
}

/** Solve a level into renderer scalars; [contentScale] is capped at the proven ceiling. */
fun CosmicIntensity.sky(): CosmicSkyIntensity = CosmicSkyIntensity(
    contentScale = baseAtmosphereAlpha.coerceIn(0f, 1f),
    edgeStrength = edgeStrength.coerceAtLeast(0f),
    starDensity = starDensity.coerceIn(0f, 1f),
    orbitLineAlpha = orbitLineAlpha.coerceAtLeast(0f),
    drawsPlanet = drawsPlanet,
    drawsHaze = drawsHaze,
)

/**
 * The field's intensity scalars, read for the draw pass. A `State` read in composition
 * subscribes `folioField` to intensity changes so a tab switch rebuilds the wash once —
 * the same one-rebuild cost model as the ambient tint.
 */
@Composable
fun rememberCosmicSkyIntensity(): State<CosmicSkyIntensity> {
    val level = rememberCosmicIntensity()
    return remember(level) { mutableStateOf(level.sky()) }
}
