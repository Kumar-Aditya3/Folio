package com.folio.reader.ui.theme

/**
 * The family of living-background animation a theme paints as its ground-plane field.
 *
 * Each of the ten themes is mapped to a different [Mirage](https://github.com/AndroidPoet/mirage)
 * AGSL shader whose motion suits that theme's *concept*, chosen in
 * `outputs/palette/REBUILD_NOTES.md` and justified in `outputs/palette/folio_theme_audit.md`.
 * This is pure data with no Compose or platform dependency, so the mapping is unit-testable and
 * is the single source of truth the Android shader dispatch ([FolioThemeField]) switches on.
 *
 * ## The id-space bug this enum used to carry
 *
 * The lookup previously keyed on a parallel set of "art-direction family" ids
 * (`ocean`, `nebula`, `forest`, ...) that **no other registry used**. The live value flowing
 * through `LocalFolioPackId` is a *theme pack* id (`gallery`, `manuscript`, ...), so nine of the
 * ten lookups missed and every theme but one fell through to the same default field. The enum is
 * now keyed on the pack id that is actually provided, so the chain
 * `MainActivity -> LocalFolioPackId -> folioFieldKindFor -> FolioThemeField` resolves per theme.
 */
enum class FolioFieldKind {
    /** Gallery/Dark — a vast quiet wall whose light drifts under canvas grain. */
    GrainGradient,
    /** Manuscript/Espresso — ink bleeding through laid paper, ruled lines pulled out of true. */
    Warp,
    /** Silver/Graphite — the silver-halide grain of a darkroom print. */
    Dithering,
    /** Arctic/Midnight — aurora curtains twisting over ice strata. */
    Swirl,
    /** Sakura/Blossom — petals orbiting on the wind. */
    DotOrbit,
    /** Honey/Ember — viscous drops coalescing. */
    Metaballs,
    /** Quiet colour facets with fine seams and gentle drift. */
    Voronoi,
    /** Dawn/Dusk — volumetric sun-shafts from a low, off-screen source. */
    GodRays,
    /** Lagoon/Abyss — turquoise, sand and coral drifting through moving water. */
    MeshGradient,
    /** Rouge/Garnet — a turbulent ring of smoke in a single red spotlight. */
    SmokeRing,

    // --- Additional Mirage families -------------------------------------------------------------
    // Not assigned as any theme's *default* (that mapping stays the ten above), but every one of
    // these is selectable per theme through the Settings animation picker, so the user can give any
    // theme any Mirage animation. Each is fed that theme's own palette in the Android dispatch.

    /** Vertical panels of colour revolving past one another, like gallery light through blinds. */
    ColorPanels,
    /** A breathing grid of dots — quiet, technical, editorial. */
    DotGrid,
    /** Organic neural filaments branching and dissolving. */
    NeuroNoise,
    /** Classic Perlin cloud drift — soft, slow, atmospheric. */
    PerlinNoise,
    /** Banded simplex noise stepping between palette colours. */
    SimplexNoise,
    /** A hypnotic rotating spiral of fine strokes. */
    Spiral,
    /** Horizontal wave bands rolling across the field. */
    Waves,
    /** Caustic water surface with drifting highlights. */
    Water,
    /** Chromatic liquid-metal sheen with refraction. */
    LiquidMetal,
    /** Thermal contour bloom — glowing isotherms. */
    Heatmap,
    /** Faceted gemstone smoke with inner glow. */
    GemSmoke,
    /** Printed halftone dot screen — risograph character. */
    HalftoneDots,
    /** A soft pulsing light border, bloom breathing around the edges. */
    PulsingBorder,
    /** A still, poster-like mesh gradient (no motion). */
    StaticMeshGradient,
    /** A still radial gradient wash (no motion). */
    StaticRadialGradient,
}

/**
 * The ten animation families that serve as the themes' designed defaults — the only ones ever
 * returned by [folioFieldKindFor]. The remaining [FolioFieldKind] entries are opt-in via the
 * per-theme override picker. Ordered to read naturally in the Settings list.
 */
val FOLIO_DEFAULT_FIELD_KINDS: List<FolioFieldKind> = listOf(
    FolioFieldKind.GrainGradient,
    FolioFieldKind.Warp,
    FolioFieldKind.Dithering,
    FolioFieldKind.Swirl,
    FolioFieldKind.DotOrbit,
    FolioFieldKind.Metaballs,
    FolioFieldKind.Voronoi,
    FolioFieldKind.GodRays,
    FolioFieldKind.MeshGradient,
    FolioFieldKind.SmokeRing,
)

/** A short, human-facing label for each animation family, shown in the Settings picker. */
fun FolioFieldKind.displayName(): String = when (this) {
    FolioFieldKind.GrainGradient -> "Grain Gradient"
    FolioFieldKind.Warp -> "Warp"
    FolioFieldKind.Dithering -> "Dither"
    FolioFieldKind.Swirl -> "Aurora Swirl"
    FolioFieldKind.DotOrbit -> "Dot Orbit"
    FolioFieldKind.Metaballs -> "Metaballs"
    FolioFieldKind.Voronoi -> "Voronoi"
    FolioFieldKind.GodRays -> "God Rays"
    FolioFieldKind.MeshGradient -> "Mesh Gradient"
    FolioFieldKind.SmokeRing -> "Smoke Ring"
    FolioFieldKind.ColorPanels -> "Colour Panels"
    FolioFieldKind.DotGrid -> "Dot Grid"
    FolioFieldKind.NeuroNoise -> "Neuro Noise"
    FolioFieldKind.PerlinNoise -> "Perlin Cloud"
    FolioFieldKind.SimplexNoise -> "Simplex Bands"
    FolioFieldKind.Spiral -> "Spiral"
    FolioFieldKind.Waves -> "Waves"
    FolioFieldKind.Water -> "Water"
    FolioFieldKind.LiquidMetal -> "Liquid Metal"
    FolioFieldKind.Heatmap -> "Heatmap"
    FolioFieldKind.GemSmoke -> "Gem Smoke"
    FolioFieldKind.HalftoneDots -> "Halftone"
    FolioFieldKind.PulsingBorder -> "Pulsing Border"
    FolioFieldKind.StaticMeshGradient -> "Static Mesh"
    FolioFieldKind.StaticRadialGradient -> "Static Radial"
}

/** A one-line description of each family's motion/character for the detailed picker rows. */
fun FolioFieldKind.describe(): String = when (this) {
    FolioFieldKind.GrainGradient -> "Soft light drifting under canvas grain"
    FolioFieldKind.Warp -> "Silk folds bending through flowing colour"
    FolioFieldKind.Dithering -> "Darkroom silver-halide dither"
    FolioFieldKind.Swirl -> "Aurora curtains over ice strata"
    FolioFieldKind.DotOrbit -> "Petals orbiting on the wind"
    FolioFieldKind.Metaballs -> "Glazed droplets merging and separating"
    FolioFieldKind.Voronoi -> "Drifting stained-glass facets with fine seams"
    FolioFieldKind.GodRays -> "Volumetric sun-shafts, low source"
    FolioFieldKind.MeshGradient -> "Rich colour pools weaving through one another"
    FolioFieldKind.SmokeRing -> "A lit smoke torus curling and breathing"
    FolioFieldKind.ColorPanels -> "Crisp matte colour panes gliding across the screen"
    FolioFieldKind.DotGrid -> "A quiet breathing dot grid"
    FolioFieldKind.NeuroNoise -> "Neural filaments branching"
    FolioFieldKind.PerlinNoise -> "Soft billowing clouds carried by a slow wind"
    FolioFieldKind.SimplexNoise -> "Banded simplex noise steps"
    FolioFieldKind.Spiral -> "Pearlescent ribbons winding into a quiet vortex"
    FolioFieldKind.Waves -> "Layered wave ribbons with travelling crests"
    FolioFieldKind.Water -> "Refracted colour under crossing caustic light"
    FolioFieldKind.LiquidMetal -> "Deforming mercury with polished light reflections"
    FolioFieldKind.Heatmap -> "Moving thermal pools with luminous contour lines"
    FolioFieldKind.GemSmoke -> "Faceted gemstone smoke"
    FolioFieldKind.HalftoneDots -> "Printed halftone dot screen"
    FolioFieldKind.PulsingBorder -> "Bloom breathing at the edges"
    FolioFieldKind.StaticMeshGradient -> "A still mesh-gradient poster"
    FolioFieldKind.StaticRadialGradient -> "A still radial wash"
}

/**
 * The one animation family every theme wears. The background motion is now a single
 * unified field across every pack — switching themes re-colours the field (the colours
 * fed to it still come from each theme's atmosphere) but never changes the *style* of the
 * animation. [FolioFieldKind.DotOrbit] remains the default; alternatives do not change its
 * geometry, palette or motion.
 *
 * A user can still pick a specific family per theme through the appearance picker
 * ([folioFieldChoiceFor] + `ReaderSettings.fieldOverrides`); this function is only the
 * default, which is deliberately uniform so the theme choice no longer drags the motion with it.
 */
val FOLIO_FIELD_UNIFIED: FolioFieldKind = FolioFieldKind.DotOrbit

/**
 * Maps a **theme pack id** to its animation family. Unified: every theme returns the same
 * [FOLIO_FIELD_UNIFIED] family so the background animation style is constant across themes.
 */
@Suppress("UNUSED_PARAMETER")
fun folioFieldKindFor(packId: String): FolioFieldKind = FOLIO_FIELD_UNIFIED

/**
 * The resolved background choice for a theme: either a specific animation family, or [Plain]
 * (static ramp, no Mirage shader). This is what the user-override layer yields.
 */
sealed interface FolioFieldChoice {
    data class Animated(val kind: FolioFieldKind) : FolioFieldChoice
    data object Plain : FolioFieldChoice
}

/** The sentinel stored in `ReaderSettings.fieldOverrides` to mean "no animation". */
const val FOLIO_FIELD_PLAIN = "PLAIN"

/**
 * The reserved key under which the user's **global** background-animation choice is stored in
 * `ReaderSettings.fieldOverrides`. The selector is global by default: one choice applies across
 * every theme, so switching themes re-colours the field but never changes the animation style.
 *
 * It is not a valid `ThemePack.id` (the leading underscores guarantee no collision), so it rides
 * the same `Map<String, String>` serialization/sync path with no migration. A reader who wants one
 * theme to differ writes that theme's own pack id as the key instead, and [folioFieldChoiceFor]
 * resolves per-theme-first — so the per-pack entries older builds wrote are honoured again rather
 * than ignored.
 */
const val FOLIO_FIELD_GLOBAL_KEY = "__global_field__"

/**
 * Resolves the effective [FolioFieldChoice] from the user's [overrides] map.
 *
 * The scope is **per theme first, then global**, so a reader who wants Gallery still and Arctic
 * swirling can have it while every other theme keeps the one global choice:
 *
 * - a pack key for this `packId` -> that theme's own choice;
 * - else the [FOLIO_FIELD_GLOBAL_KEY] -> the one global choice;
 * - [FOLIO_FIELD_PLAIN] -> [FolioFieldChoice.Plain];
 * - a valid [FolioFieldKind] name -> that family;
 * - anything else (stale / malformed token) -> safe fall-through to the designed default, so a bad
 *   persisted value can never blank the background.
 *
 * Reading the pack key first is what makes the per-theme entries older builds wrote start working
 * again instead of being ignored — the picker has always accepted a `packId`, so this restores the
 * scope its UI already implies.
 */
fun folioFieldChoiceFor(
    packId: String,
    overrides: Map<String, String>,
): FolioFieldChoice {
    val token = overrides[packId] ?: overrides[FOLIO_FIELD_GLOBAL_KEY]
        ?: return FolioFieldChoice.Animated(folioFieldKindFor(packId))
    if (token == FOLIO_FIELD_PLAIN) return FolioFieldChoice.Plain
    val kind = FolioFieldKind.entries.firstOrNull { it.name == token }
    return if (kind != null) FolioFieldChoice.Animated(kind)
    else FolioFieldChoice.Animated(folioFieldKindFor(packId))
}
