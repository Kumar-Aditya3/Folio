package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * The cosmic **semantic token layer**: the names a screen reaches for
 * (`cosmic.accent.primary`, `cosmic.glow.secondary`, `cosmic.artwork.star`) mapped onto
 * the roles the theme already derives. It invents no colour — every field is a role from
 * [FolioColors] or a term from [FolioAtmosphere]/[FolioSignature], so a new palette (or a
 * System/custom one) inherits a complete cosmic vocabulary for free and the §15 contrast
 * tests keep governing the inputs.
 *
 * Derived per theme + polarity, remembered at the root like [FolioTheme.atmosphere].
 * This is the one place call sites read cosmic colour from; [CosmicGradients] builds its
 * brushes from here, and the `ui/components/cosmic` primitives take their colours from
 * here, so no raw hex ever reaches a call site.
 */
@Immutable
data class CosmicScheme(
    /** True on the "looking into deep space" faces; false on "Cosmic Dawn" light faces. */
    val isDark: Boolean,
    val background: CosmicBackground,
    val surface: CosmicSurface,
    val accent: CosmicAccent,
    val text: CosmicText,
    val border: CosmicBorder,
    val glow: CosmicGlow,
    val artwork: CosmicArtwork,
)

/** The ground plane. `primary` is the page; `secondary` is the deepened lower field. */
@Immutable
data class CosmicBackground(val primary: Color, val secondary: Color)

/**
 * The three material fills, straight from the atmosphere so cosmic surfaces read as the
 * same panes the rest of the app draws.
 *  - [primary]  ← the floating raised fill
 *  - [secondary]← the pressed-in well
 *  - [elevated] ← the glass veil (carries its own translucency)
 */
@Immutable
data class CosmicSurface(val primary: Color, val secondary: Color, val elevated: Color)

/**
 * The accent trio the brief named:
 *  - [primary]  ← the theme's hero `primary`
 *  - [secondary]← `accentDiscovery` (the cool/violet "discovery" hue)
 *  - [tertiary] ← `accentStreak` (the warm/gold "streak" hue — gold on light faces)
 */
@Immutable
data class CosmicAccent(val primary: Color, val secondary: Color, val tertiary: Color)

/** Foreground ink, three prominences. [tertiary] is a derived dim, hue preserved. */
@Immutable
data class CosmicText(val primary: Color, val secondary: Color, val tertiary: Color)

/** [subtle] ← the atmosphere's hairline; [accent] ← the hero at low alpha. */
@Immutable
data class CosmicBorder(val subtle: Color, val accent: Color)

/** [primary] ← the celestial-body glow; [secondary] ← the warm nebula pool. */
@Immutable
data class CosmicGlow(val primary: Color, val secondary: Color)

/**
 * The artwork palette — the [FolioSignature] colours the field already paints its
 * skyscape from, re-exported under cosmic names so the foreground primitives draw stars,
 * nebulae and planets from the *same* opaque colours the base field uses (composited at
 * the primitive's own low alpha, bounded in the content band by [alphaCeiling]).
 */
@Immutable
data class CosmicArtwork(
    val skyTop: Color,
    val skyBottom: Color,
    val nebulaCool: Color,
    val nebulaWarm: Color,
    val star: Color,
    val glow: Color,
    /** The low horizon haze on light faces; null on dark ("into space" has no horizon). */
    val mist: Color?,
    val alphaCeiling: Float,
)

/**
 * Solves a palette's [CosmicScheme] from its colours only — pure and deterministic, so a
 * built-in, System or custom theme all get a cosmic vocabulary with no per-theme table.
 * Reuses [atmosphereFor] (which already derives the field, pane and signature) so the
 * cosmic tokens and the painted field can never disagree about a theme's colours.
 */
fun cosmicSchemeFor(colors: FolioColors): CosmicScheme {
    val atmos = atmosphereFor(colors)
    val sig = atmos.signature
    return CosmicScheme(
        isDark = atmos.isDark,
        background = CosmicBackground(
            primary = colors.background,
            secondary = atmos.fieldBottom,
        ),
        surface = CosmicSurface(
            primary = atmos.raisedFill,
            secondary = atmos.sunkenFill,
            elevated = atmos.veilFill,
        ),
        accent = CosmicAccent(
            primary = colors.primary,
            secondary = colors.accentDiscovery,
            tertiary = colors.accentStreak,
        ),
        text = CosmicText(
            primary = colors.onBackground,
            secondary = colors.onSurfaceVariant,
            // A derived dim: pull the secondary ink toward the page, hue kept. This is
            // tertiary (least prominent) text, so trading a little contrast for recession
            // is the intent; nothing load-bearing is drawn in it.
            tertiary = mixG(colors.onSurfaceVariant, colors.background, 0.35f),
        ),
        border = CosmicBorder(
            subtle = atmos.hairline,
            accent = colors.primary.copy(alpha = 0.40f),
        ),
        glow = CosmicGlow(
            primary = sig.glow,
            secondary = sig.nebulaWarm,
        ),
        artwork = CosmicArtwork(
            skyTop = sig.skyTop,
            skyBottom = sig.skyBottom,
            nebulaCool = sig.nebulaCool,
            nebulaWarm = sig.nebulaWarm,
            star = sig.star,
            glow = sig.glow,
            mist = sig.mist,
            alphaCeiling = sig.alphaCeiling,
        ),
    )
}

/**
 * Optional root-provided scheme. Null (the default) means "derive it" — desktop,
 * previews and tests all take that path and get the palette's own cosmic tokens for
 * free, exactly like [FolioTheme.atmosphere].
 */
val LocalCosmic = compositionLocalOf<CosmicScheme?> { null }

/** The active cosmic scheme. Provided at the theme root; derived per palette if absent. */
val FolioTheme.cosmic: CosmicScheme
    @Composable
    get() {
        val provided = LocalCosmic.current
        if (provided != null) return provided
        val colors = LocalFolioColors.current
        return remember(colors) { cosmicSchemeFor(colors) }
    }
