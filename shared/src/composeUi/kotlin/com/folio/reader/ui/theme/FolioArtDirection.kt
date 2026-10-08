package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * §18 — the art-direction layer: **one theme, two faces, each individually painted.**
 *
 * The problem this replaces is architectural, not cosmetic. Every previous palette was
 * seven seeds pushed through one `lightScheme`/`darkScheme` derivation, so a theme's
 * background, glass, glow, progress and navigation all landed on *one hue at several
 * lightnesses* — which is why nine "different" themes read as the same app wearing
 * different sunglasses. A [FolioColors] is a role set, and a role set can be derived;
 * a **palette** is an artistic decision, and a decision cannot be derived from itself.
 *
 * So this type authors the decisions and derives only the relationships. It carries the
 * roles a designer actually chooses — the two page planes, the two surface planes, the
 * four accents, the four guilloche hues, the two glows, progress, three text tiers, two
 * edges, the navigation accent — and [toFolioColors] expands them into the full
 * Material-shaped role set, deriving the containers, the `on*` inks and the inverse
 * family, which are relationships rather than choices.
 *
 * **Light and dark are separately art-directed, never inverted.** The dark face is not
 * the light face darkened: it is the same *world* at night, which is why its accents are
 * the luminous versions of the light face's and its page is a coloured near-black rather
 * than black. Every hex below was chosen for its own face.
 *
 * **The colours are the prompt's; the accents are fitted.** The four §12.3 semantic
 * accents (`accentProgress`/`Streak`/`Discovery`/`Annotation`) are the one place art
 * direction meets a hard floor: they are read as *foreground text* on `surface`, so each
 * is the palette's own accent hue pushed only as far toward the page's ink as WCAG AA
 * requires, and held pairwise ≥ 10 ΔE apart so the progress ring, the streak and the
 * discovery tint stay tellable apart. `ThemeSchemeTest` pins all of it.
 */
internal data class FolioArtDirection(
    val id: String,
    val light: Boolean,
    // ── planes ──
    val backgroundBase: Long,
    val backgroundSecondary: Long,
    val surface: Long,
    val surfaceElevated: Long,
    // ── the four authored hues ──
    val primaryAccent: Long,
    val secondaryAccent: Long,
    val tertiaryAccent: Long,
    val warmAccent: Long,
    // ── the guilloche's own three ──
    val guillochePrimary: Long,
    val guillocheSecondary: Long,
    val guillocheHighlight: Long,
    // ── the two glows ──
    val glowPrimary: Long,
    val glowSecondary: Long,
    // ── progress ──
    // A 3dp bar is a *graphical object*, so its floor is WCAG 1.4.11's 3:1 — measured
    // between the fill and the **track**, not between the fill and the page, and not the
    // 4.5:1 body-text floor that `accentProgress` answers to. The two roles are therefore
    // not one hue at two alphas: `accentProgress` is the AA-fitted *text* form of the
    // progress hue, this pair is the drawn form.
    //
    // On a DARK face the fill is the luminous version and the track is a deep tint of it,
    // so separation is easy. On a LIGHT face nothing glows — light can only add darkness —
    // so the fill must be the *committed* mid-tone, dark enough to clear 3:1 against a
    // track that is deliberately a near-invisible pale tint (1.2–1.4:1 against the page, by
    // design: the bar is an inlay, not a widget). Authoring the light fills at their
    // "luminous" lightness instead put six of nine light faces at 2.2–2.8:1 and the bar
    // read as a soft gradient. Darkening the track is not the fix: from a fill at L 0.25,
    // reaching 3:1 needs a track at L ≤ 0.05 — a near-black line on white, louder than the
    // fill, which inverts the hierarchy. So the light fills below are authored to clear
    // 3.3:1 against their own track (margin for authoring drift), and
    // `DesignSystemTest.progressFillSeparatesFromItsTrack` pins the 3:1 floor.
    val progress: Long,
    val progressTrack: Long,
    // ── text ──
    val textPrimary: Long,
    val textSecondary: Long,
    val textTertiary: Long,
    // ── edges ──
    val border: Long,
    val borderHighlight: Long,
    // ── navigation ──
    val navigationSelected: Long,
    // ── the four semantic accents, fitted to AA on `surface` ──
    val accentProgress: Long,
    val accentStreak: Long,
    val accentDiscovery: Long,
    val accentAnnotation: Long,
)

/** Straight-line sRGB mix, the same arithmetic `lightScheme` uses. */
private fun mixA(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** Black or white, whichever the fill can carry. */
private fun onFillA(c: Color): Color =
    if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.5f) Color(0xFF14110C) else Color.White

/**
 * Expands an art direction into the full role set.
 *
 * The authored roles pass through **byte-for-byte** — the page, the card, the inks, the
 * accents, the guilloche hues are exactly what the designer chose. Only the relationships
 * are computed: the `*Container`/`on*Container` pairs, the inverse family and the status
 * ground, all from the authored roles and the face's polarity.
 */
internal fun FolioArtDirection.toFolioColors(): FolioColors {
    val page = Color(backgroundBase)
    val page2 = Color(backgroundSecondary)
    val card = Color(surface)
    val cardUp = Color(surfaceElevated)
    val ink = Color(textPrimary)
    val ink2 = Color(textSecondary)
    val ink3 = Color(textTertiary)
    val p = Color(primaryAccent)
    val s = Color(secondaryAccent)
    val t = Color(tertiaryAccent)

    return FolioColors(
        primary = p, onPrimary = onFillA(p),
        primaryContainer = mixA(p, page, if (light) 0.72f else 0.52f),
        onPrimaryContainer = mixA(p, if (light) ink else Color.White, 0.62f),
        secondary = s, onSecondary = onFillA(s),
        secondaryContainer = mixA(s, page, if (light) 0.74f else 0.54f),
        onSecondaryContainer = mixA(s, if (light) ink else Color.White, 0.62f),
        tertiary = t, onTertiary = onFillA(t),
        tertiaryContainer = mixA(t, page, if (light) 0.72f else 0.52f),
        onTertiaryContainer = mixA(t, if (light) ink else Color.White, 0.62f),
        error = if (light) Color(0xFFC5221F) else Color(0xFFF2B8B5),
        onError = if (light) Color.White else Color(0xFF601410),
        errorContainer = if (light) Color(0xFFF9DEDC) else Color(0xFF8C1D18),
        onErrorContainer = if (light) Color(0xFF5F1412) else Color(0xFFF9DEDC),

        background = page, onBackground = ink,
        surface = card, onSurface = ink,
        surfaceVariant = page2,
        onSurfaceVariant = ink2,
        surfaceContainerHighest = cardUp,
        outline = Color(border),
        outlineVariant = Color(borderHighlight),
        shadow = Color.Black, scrim = Color.Black,
        inverseSurface = ink, inverseOnSurface = page,
        inversePrimary = mixA(p, if (light) Color.White else Color.Black, if (light) 0.30f else 0.28f),
        statusBar = if (light) ink else page,

        accentProgress = Color(accentProgress),
        accentStreak = Color(accentStreak),
        accentDiscovery = Color(accentDiscovery),
        accentAnnotation = Color(accentAnnotation),

        // ── §18 the roles that stop every theme collapsing onto one hue ──
        backgroundSecondary = page2,
        surfaceElevated = cardUp,
        warmAccent = Color(warmAccent),
        guillochePrimary = Color(guillochePrimary),
        guillocheSecondary = Color(guillocheSecondary),
        guillocheHighlight = Color(guillocheHighlight),
        // The whisper tier: the highlight hue laid most of the way back into the
        // page, so the faintest filaments tint rather than brighten.
        guillocheSubtle = mixA(Color(guillocheHighlight), page, if (light) 0.52f else 0.44f),
        glowPrimary = Color(glowPrimary),
        glowSecondary = Color(glowSecondary),
        progress = Color(progress),
        progressTrack = Color(progressTrack),
        textPrimary = ink,
        textSecondary = ink2,
        textTertiary = ink3,
        // The chrome plane must NOT track the (now darker) page. On a light face it stays the
        // authored mix of card/page2. On a DARK face it is deliberately LIFTED above the room:
        // the elevated surface pulled a further ~16% toward white, so the top bar and bottom nav
        // read as their own plane that sits proud of the darkened background instead of melting
        // into it. This is the fix for "top bar and bottom nav shouldn't get dark along with it".
        navigationSurface = if (light) mixA(card, page2, 0.45f) else mixA(cardUp, Color.White, 0.16f),
        navigationSelected = Color(navigationSelected),
        border = Color(border),
        borderHighlight = Color(borderHighlight),
    )
}
/**
 * The ten themes, each with its two separately painted faces.
 *
 * **The table is keyed by the theme pack's own id.** It used to be keyed by a parallel set of
 * "art-direction family" ids (`ocean`, `nebula`, `forest`, ...) which no other registry used,
 * and that second id space was not merely redundant — it silently broke the per-theme
 * background animation, because `LocalFolioPackId` carries a *pack* id and the field lookup
 * keyed on a *family* id, so nine of the ten packs fell through to the same default field.
 * One id space, one lookup, no drift.
 *
 * | theme | light / dark | identity | field |
 * |---|---|---|---|
 * | `gallery` | Gallery / Dark | white-cube plaster, cobalt placard, the red sold-dot | GrainGradient |
 * | `manuscript` | Manuscript / Espresso | vellum, rubric sienna, iron-gall indigo, gold leaf | Warp |
 * | `silver` | Silver / Graphite | gelatin-silver print; cool platinum, a magenta flash | Dithering |
 * | `arctic` | Arctic / Midnight | glacier blue, snow-shadow, aurora green, low-sun gold | Swirl |
 * | `sakura` | Sakura / Blossom | petal rose, young leaf, branch umber, spring sky | DotOrbit |
 * | `honey` | Honey / Ember | amber gold, hive-shade teal, propolis, clover | Metaballs |
 * | `moss` | Moss / Peacock | forest green, lichen, bark; at night teal + sapphire + bronze | Voronoi |
 * | `dawn` | Dawn / Dusk | lilac sky, apricot horizon, morning blue | GodRays |
 * | `lagoon` | Lagoon / Abyss | turquoise shallows, coral, sand; at depth bioluminescence | MeshGradient |
 * | `rouge` | Rouge / Garnet | theatrical crimson, gilt, velvet plum | SmokeRing |
 *
 * Every face was authored in OKLCH and verified with `coloraide`
 * (`outputs/palette/folio_theme_rebuild.py`, report in `outputs/palette/folio_theme_audit.md`):
 * the three ink tiers clear 7:1 / 4.5:1 / 3:1 on the *darkest* plane they ever land on, the
 * progress fill clears 3:1 against its own track, the four semantic accents clear AA on
 * `surface` and are held pairwise >= 10 dE2000 apart, and the ten themes' primaries are held
 * >= 10 dE2000 apart from each other on both faces so no two themes read as the same app in
 * different sunglasses.
 *
 * The ten primaries are spread deliberately around the hue circle rather than clustered:
 * light faces sit at 18 42 80 145 188 218 248 276 310 350 degrees, and the dark faces are
 * re-pitched per their *own* name (Ember is an orange coal, not Honey darkened; Peacock is
 * teal + sapphire + bronze, not Moss at night).
 */
internal val FOLIO_ART_DIRECTIONS: List<FolioArtDirection> = listOf(
    // GALLERY — "Gallery" — warm plaster white; the art supplies the colour
    FolioArtDirection(
        id = "gallery", light = true,
        backgroundBase = 0xFFF7F5F1, backgroundSecondary = 0xFFEDE4DF, surface = 0xFFFCFBF9, surfaceElevated = 0xFFFFFFFF,
        primaryAccent = 0xFF0077D2, secondaryAccent = 0xFFBD5348, tertiaryAccent = 0xFFA27700, warmAccent = 0xFF4A9A5F,
        guillochePrimary = 0xFFAF3D34, guillocheSecondary = 0xFF8A6100, guillocheHighlight = 0xFF0076D0,
        glowPrimary = 0xFF68BEFF, glowSecondary = 0xFFFFA194,
        progress = 0xFF0065B9, progressTrack = 0xFFC2E2FF,
        textPrimary = 0xFF2B2112, textSecondary = 0xFF6B6052, textTertiary = 0xFF8C8276,
        border = 0xFFD3CBC0, borderHighlight = 0xFFE8E2DA,
        navigationSelected = 0xFF006BC4,
        accentProgress = 0xFF006BC4, accentStreak = 0xFF008032, accentDiscovery = 0xFFB3372F, accentAnnotation = 0xFF8A6100,
    ),
    // GALLERY — "Dark" — the gallery after hours — cool charcoal, picture-lights off
    FolioArtDirection(
        id = "gallery", light = false,
        backgroundBase = 0xFF05080D, backgroundSecondary = 0xFF090E15, surface = 0xFF0D1117, surfaceElevated = 0xFF171B21,
        primaryAccent = 0xFF76C0FF, secondaryAccent = 0xFFFEA195, tertiaryAccent = 0xFFE2BD71, warmAccent = 0xFF9DDAA9,
        guillochePrimary = 0xFFEB776A, guillocheSecondary = 0xFFC49724, guillocheHighlight = 0xFF52B1FF,
        glowPrimary = 0xFF48A8FF, glowSecondary = 0xFFF58679,
        progress = 0xFF5EB2FF, progressTrack = 0xFF09243D,
        textPrimary = 0xFFEBF1F8, textSecondary = 0xFFA4ACB5, textTertiary = 0xFF727A84,
        border = 0xFF33373C, borderHighlight = 0xFF1B1E22,
        navigationSelected = 0xFF74C0FF,
        accentProgress = 0xFF7BC3FF, accentStreak = 0xFF7AD68F, accentDiscovery = 0xFFFF998B, accentAnnotation = 0xFFE4B750,
    ),
    // MANUSCRIPT — "Manuscript" — vellum cream
    FolioArtDirection(
        id = "manuscript", light = true,
        backgroundBase = 0xFFFCEED7, backgroundSecondary = 0xFFF8DDB1, surface = 0xFFFDF5E8, surfaceElevated = 0xFFFEF9F3,
        primaryAccent = 0xFFBA4F1F, secondaryAccent = 0xFF5576C1, tertiaryAccent = 0xFF24946E, warmAccent = 0xFF9D8431,
        guillochePrimary = 0xFF4364B4, guillocheSecondary = 0xFF007E57, guillocheHighlight = 0xFFB84E1D,
        glowPrimary = 0xFFFC9B75, glowSecondary = 0xFF9FC0FF,
        progress = 0xFFA3400F, progressTrack = 0xFFFCD4C5,
        textPrimary = 0xFF2A2112, textSecondary = 0xFF6A6051, textTertiary = 0xFF887F72,
        border = 0xFFD2CBBF, borderHighlight = 0xFFE7E2DA,
        navigationSelected = 0xFFAC430E,
        accentProgress = 0xFFAC430E, accentStreak = 0xFF836500, accentDiscovery = 0xFF4063BA, accentAnnotation = 0xFF008055,
    ),
    // MANUSCRIPT — "Espresso" — dark roast with crema gold — a yellow-brown, deliberately not Ember's orange
    FolioArtDirection(
        id = "manuscript", light = false,
        backgroundBase = 0xFF0E0400, backgroundSecondary = 0xFF180800, surface = 0xFF190B03, surfaceElevated = 0xFF23160E,
        primaryAccent = 0xFFF0A968, secondaryAccent = 0xFF95C0FF, tertiaryAccent = 0xFF83D6B7, warmAccent = 0xFFFAB6A5,
        guillochePrimary = 0xFF6A9FEE, guillocheSecondary = 0xFF40B690, guillocheHighlight = 0xFFEC9540,
        glowPrimary = 0xFFE18C34, glowSecondary = 0xFF79ACF8,
        progress = 0xFFE6984E, progressTrack = 0xFF321A03,
        textPrimary = 0xFFF7EEE9, textSecondary = 0xFFB4A8A2, textTertiary = 0xFF82766F,
        border = 0xFF3E3129, borderHighlight = 0xFF231913,
        navigationSelected = 0xFFF6A65D,
        accentProgress = 0xFFF9AA60, accentStreak = 0xFFFFA189, accentDiscovery = 0xFF8BBFFF, accentAnnotation = 0xFF67D6AF,
    ),
    // SILVER — "Silver" — cool platinum; restrained chroma is the point — a metal, not a pigment
    FolioArtDirection(
        id = "silver", light = true,
        backgroundBase = 0xFFF1F5F9, backgroundSecondary = 0xFFDEE7ED, surface = 0xFFF9FBFD, surfaceElevated = 0xFFFDFEFF,
        primaryAccent = 0xFF656FAC, secondaryAccent = 0xFF398596, tertiaryAccent = 0xFF986F93, warmAccent = 0xFF978457,
        guillochePrimary = 0xFF1C7587, guillocheSecondary = 0xFF82597E, guillocheHighlight = 0xFF646EAA,
        glowPrimary = 0xFFA9B4EB, glowSecondary = 0xFF8DCDDD,
        progress = 0xFF555E96, progressTrack = 0xFFD8DDF3,
        textPrimary = 0xFF192431, textSecondary = 0xFF586371, textTertiary = 0xFF7B8591,
        border = 0xFFC4CDD8, borderHighlight = 0xFFDEE3EB,
        navigationSelected = 0xFF5A639F,
        accentProgress = 0xFF5A639F, accentStreak = 0xFF7E6625, accentDiscovery = 0xFF077689, accentAnnotation = 0xFF855680,
    ),
    // SILVER — "Graphite" — pencil graphite — blue-black with the silver flash
    FolioArtDirection(
        id = "silver", light = false,
        backgroundBase = 0xFF07090E, backgroundSecondary = 0xFF0B1017, surface = 0xFF0E1318, surfaceElevated = 0xFF191D22,
        primaryAccent = 0xFFADB5F3, secondaryAccent = 0xFF82CBDD, tertiaryAccent = 0xFFDDB3D9, warmAccent = 0xFFDAC79B,
        guillochePrimary = 0xFF49AEC4, guillocheSecondary = 0xFFBF8AB9, guillocheHighlight = 0xFF9CA6F0,
        glowPrimary = 0xFF939CE6, glowSecondary = 0xFF5DBACF,
        progress = 0xFF9EA6EA, progressTrack = 0xFF212338,
        textPrimary = 0xFFEBF1F8, textSecondary = 0xFFA5ABB5, textTertiary = 0xFF727984,
        border = 0xFF36393F, borderHighlight = 0xFF1E2125,
        navigationSelected = 0xFFABB5F9,
        accentProgress = 0xFFAFB8FD, accentStreak = 0xFFD6BB79, accentDiscovery = 0xFF71CDE3, accentAnnotation = 0xFFDFA9D9,
    ),
    // ARCTIC — "Arctic" — pale ice; snow's shadow is blue
    FolioArtDirection(
        id = "arctic", light = true,
        backgroundBase = 0xFFE1F7FC, backgroundSecondary = 0xFFC3EEE7, surface = 0xFFEFFBFE, surfaceElevated = 0xFFF7FEFF,
        primaryAccent = 0xFF0084A3, secondaryAccent = 0xFF288E5D, tertiaryAccent = 0xFF7C73BC, warmAccent = 0xFFAC7B3A,
        guillochePrimary = 0xFF007E4B, guillocheSecondary = 0xFF675CA7, guillocheHighlight = 0xFF0082A1,
        glowPrimary = 0xFF41CAEC, glowSecondary = 0xFF84D6A7,
        progress = 0xFF00718D, progressTrack = 0xFFBBE6F3,
        textPrimary = 0xFF10272B, textSecondary = 0xFF51666A, textTertiary = 0xFF74878B,
        border = 0xFFC0D0D3, borderHighlight = 0xFFDAE5E7,
        navigationSelected = 0xFF007794,
        accentProgress = 0xFF007794, accentStreak = 0xFF965900, accentDiscovery = 0xFF007F49, accentAnnotation = 0xFF675AAF,
    ),
    // ARCTIC — "Midnight" — polar night — navy, not black
    FolioArtDirection(
        id = "arctic", light = false,
        backgroundBase = 0xFF00070E, backgroundSecondary = 0xFF000D18, surface = 0xFF001018, surfaceElevated = 0xFF0A1A22,
        primaryAccent = 0xFF16CFF6, secondaryAccent = 0xFF74D5A0, tertiaryAccent = 0xFFBFB7FF, warmAccent = 0xFFF1BF7F,
        guillochePrimary = 0xFF24BA78, guillocheSecondary = 0xFF9B8EF1, guillocheHighlight = 0xFF00C2ED,
        glowPrimary = 0xFF00B8E0, glowSecondary = 0xFF44C686,
        progress = 0xFF00C2ED, progressTrack = 0xFF002431,
        textPrimary = 0xFFE9F2F7, textSecondary = 0xFFA1ADB3, textTertiary = 0xFF6F7B82,
        border = 0xFF24343C, borderHighlight = 0xFF0F1B22,
        navigationSelected = 0xFF00D1FC,
        accentProgress = 0xFF00D4FF, accentStreak = 0xFFF6AD47, accentDiscovery = 0xFF5BDA99, accentAnnotation = 0xFFBAB0FF,
    ),
    // SAKURA — "Sakura" — blush paper
    FolioArtDirection(
        id = "sakura", light = true,
        backgroundBase = 0xFFFFEEF5, backgroundSecondary = 0xFFFFDAE7, surface = 0xFFFFF7FB, surfaceElevated = 0xFFFFFDFF,
        primaryAccent = 0xFFB04B80, secondaryAccent = 0xFF508A43, tertiaryAccent = 0xFFB06B39, warmAccent = 0xFF468EBD,
        guillochePrimary = 0xFF3C7A2D, guillocheSecondary = 0xFF9B5419, guillocheHighlight = 0xFFAF497F,
        glowPrimary = 0xFFF296C2, glowSecondary = 0xFF9DD291,
        progress = 0xFF9A3C6E, progressTrack = 0xFFF8D2E2,
        textPrimary = 0xFF2F1D23, textSecondary = 0xFF6F5C62, textTertiary = 0xFF8F7E84,
        border = 0xFFD6C8CD, borderHighlight = 0xFFEAE0E3,
        navigationSelected = 0xFFA33F75,
        accentProgress = 0xFFA33F75, accentStreak = 0xFF0070AD, accentDiscovery = 0xFF377B26, accentAnnotation = 0xFFA05000,
    ),
    // SAKURA — "Blossom" — night hanami — plum sky, lantern-lit petals
    FolioArtDirection(
        id = "sakura", light = false,
        backgroundBase = 0xFF0E020A, backgroundSecondary = 0xFF170612, surface = 0xFF180A14, surfaceElevated = 0xFF22141E,
        primaryAccent = 0xFFF796D0, secondaryAccent = 0xFF95D088, tertiaryAccent = 0xFFF9B07E, warmAccent = 0xFF8ED2FF,
        guillochePrimary = 0xFF68B457, guillocheSecondary = 0xFFDF8540, guillocheHighlight = 0xFFF37EC6,
        glowPrimary = 0xFFE874BC, glowSecondary = 0xFF78C067,
        progress = 0xFFED83C4, progressTrack = 0xFF351429,
        textPrimary = 0xFFF6EDF3, textSecondary = 0xFFB2A7AE, textTertiary = 0xFF81757C,
        border = 0xFF3F3039, borderHighlight = 0xFF241920,
        navigationSelected = 0xFFFD91D2,
        accentProgress = 0xFFFF94D5, accentStreak = 0xFF5FC9FF, accentDiscovery = 0xFF8AD47A, accentAnnotation = 0xFFFFA564,
    ),
    // HONEY — "Honey" — honeycomb cream — the warmest page in the set
    FolioArtDirection(
        id = "honey", light = true,
        backgroundBase = 0xFFFFF1CF, backgroundSecondary = 0xFFC2F2BD, surface = 0xFFFFF7E5, surfaceElevated = 0xFFFFFBF2,
        primaryAccent = 0xFF9D6900, secondaryAccent = 0xFF008D8F, tertiaryAccent = 0xFFB96352, warmAccent = 0xFF74924A,
        guillochePrimary = 0xFF007B7D, guillocheSecondary = 0xFFA24B3B, guillocheHighlight = 0xFF9B6800,
        glowPrimary = 0xFFE2AE4E, glowSecondary = 0xFF5DD7D8,
        progress = 0xFF875900, progressTrack = 0xFFF0DBBA,
        textPrimary = 0xFF292211, textSecondary = 0xFF686151, textTertiary = 0xFF898375,
        border = 0xFFD1CCBF, borderHighlight = 0xFFE6E3DA,
        navigationSelected = 0xFF8E5E00,
        accentProgress = 0xFF8E5E00, accentStreak = 0xFF517700, accentDiscovery = 0xFF007B7D, accentAnnotation = 0xFFA84533,
    ),
    // HONEY — "Ember" — a dying coal — orange-red, deliberately away from Espresso's yellow-brown
    FolioArtDirection(
        id = "honey", light = false,
        backgroundBase = 0xFF0F0200, backgroundSecondary = 0xFF180600, surface = 0xFF190902, surfaceElevated = 0xFF24140C,
        primaryAccent = 0xFFFF9778, secondaryAccent = 0xFF39D6D8, tertiaryAccent = 0xFFF8B172, warmAccent = 0xFFB6D68D,
        guillochePrimary = 0xFF00B8BA, guillocheSecondary = 0xFFDE8628, guillocheHighlight = 0xFFFF7D58,
        glowPrimary = 0xFFFD734F, glowSecondary = 0xFF00C6C9,
        progress = 0xFFFF8361, progressTrack = 0xFF371107,
        textPrimary = 0xFFF7EEE9, textSecondary = 0xFFB4A8A2, textTertiary = 0xFF827670,
        border = 0xFF3E2E26, borderHighlight = 0xFF231711,
        navigationSelected = 0xFFFF9576,
        accentProgress = 0xFFFF9B7D, accentStreak = 0xFFA2CF62, accentDiscovery = 0xFF00DBDE, accentAnnotation = 0xFFFFA752,
    ),
    // MOSS — "Moss" — pale moss
    FolioArtDirection(
        id = "moss", light = true,
        backgroundBase = 0xFFEBF7E3, backgroundSecondary = 0xFFDEEAC0, surface = 0xFFF4FBF0, surfaceElevated = 0xFFFAFEF8,
        primaryAccent = 0xFF37883E, secondaryAccent = 0xFF7D7E23, tertiaryAccent = 0xFFAF6B44, warmAccent = 0xFFB76D7B,
        guillochePrimary = 0xFF6E6E00, guillocheSecondary = 0xFF995429, guillocheHighlight = 0xFF36873D,
        glowPrimary = 0xFF87CB89, glowSecondary = 0xFFC4C77C,
        progress = 0xFF297530, progressTrack = 0xFFCDE6CC,
        textPrimary = 0xFF1D2617, textSecondary = 0xFF5C6556, textTertiary = 0xFF7E8678,
        border = 0xFFC8CFC3, borderHighlight = 0xFFE0E5DD,
        navigationSelected = 0xFF2A7C32,
        accentProgress = 0xFF2A7C32, accentStreak = 0xFFA2465B, accentDiscovery = 0xFF6E6E00, accentAnnotation = 0xFF9F501C,
    ),
    // MOSS — "Peacock" — a peacock eye is teal + sapphire + bronze + a violet sheen — its own hue set, not Moss at night
    FolioArtDirection(
        id = "moss", light = false,
        backgroundBase = 0xFF000906, backgroundSecondary = 0xFF00120C, surface = 0xFF00140F, surfaceElevated = 0xFF061E19,
        primaryAccent = 0xFF56D6A3, secondaryAccent = 0xFF56CFF2, tertiaryAccent = 0xFFFCAD84, warmAccent = 0xFFAEC7FF,
        guillochePrimary = 0xFF00B2DD, guillocheSecondary = 0xFFE2814C, guillocheHighlight = 0xFF00CC90,
        glowPrimary = 0xFF00C287, glowSecondary = 0xFF00BEE8,
        progress = 0xFF28C993, progressTrack = 0xFF002817,
        textPrimary = 0xFFE8F3F0, textSecondary = 0xFFA1AEAB, textTertiary = 0xFF6E7D79,
        border = 0xFF223833, borderHighlight = 0xFF0D1E1B,
        navigationSelected = 0xFF40D8A0,
        accentProgress = 0xFF44DBA4, accentStreak = 0xFF9CBBFF, accentDiscovery = 0xFF1AD2FD, accentAnnotation = 0xFFFFA26E,
    ),
    // DAWN — "Dawn" — first light — lilac over apricot
    FolioArtDirection(
        id = "dawn", light = true,
        backgroundBase = 0xFFFFEDFE, backgroundSecondary = 0xFFFCD8F7, surface = 0xFFFFF6FF, surfaceElevated = 0xFFFFFCFF,
        primaryAccent = 0xFF8D5AB1, secondaryAccent = 0xFFAF6237, tertiaryAccent = 0xFF4285BA, warmAccent = 0xFF99853A,
        guillochePrimary = 0xFFA04F1E, guillocheSecondary = 0xFF246EA5, guillocheHighlight = 0xFF8C58AF,
        glowPrimary = 0xFFCFA2F0, glowSecondary = 0xFFF7AD87,
        progress = 0xFF7A4A9B, progressTrack = 0xFFE7D6F6,
        textPrimary = 0xFF2B1E2A, textSecondary = 0xFF6B5D69, textTertiary = 0xFF8A7E89,
        border = 0xFFD3C8D2, borderHighlight = 0xFFE8E0E7,
        navigationSelected = 0xFF814EA4,
        accentProgress = 0xFF814EA4, accentStreak = 0xFF806600, accentDiscovery = 0xFFA44C12, accentAnnotation = 0xFF0E6EAC,
    ),
    // DAWN — "Dusk" — twilight violet with the last ember on the horizon
    FolioArtDirection(
        id = "dawn", light = false,
        backgroundBase = 0xFF05030F, backgroundSecondary = 0xFF0B071A, surface = 0xFF0D0A1A, surfaceElevated = 0xFF171523,
        primaryAccent = 0xFFD2A3FB, secondaryAccent = 0xFFFEA195, tertiaryAccent = 0xFF98C5FF, warmAccent = 0xFFF8B0D1,
        guillochePrimary = 0xFFEB776A, guillocheSecondary = 0xFF689FF0, guillocheHighlight = 0xFFC88EF9,
        glowPrimary = 0xFFBF85EF, glowSecondary = 0xFFF58679,
        progress = 0xFFC692F2, progressTrack = 0xFF261633,
        textPrimary = 0xFFF0EFF8, textSecondary = 0xFFAAA9B5, textTertiary = 0xFF787783,
        border = 0xFF32303F, borderHighlight = 0xFF1A1824,
        navigationSelected = 0xFFD4A0FF,
        accentProgress = 0xFFD8A3FF, accentStreak = 0xFFFE99CA, accentDiscovery = 0xFFFF998B, accentAnnotation = 0xFF8BBFFF,
    ),
    // LAGOON — "Lagoon" — sunlit shallows
    FolioArtDirection(
        id = "lagoon", light = true,
        backgroundBase = 0xFFDAFBFB, backgroundSecondary = 0xFFE4EAB8, surface = 0xFFECFDFD, surfaceElevated = 0xFFF6FFFF,
        primaryAccent = 0xFF008A81, secondaryAccent = 0xFFB25C4F, tertiaryAccent = 0xFF997A26, warmAccent = 0xFF568ABF,
        guillochePrimary = 0xFFA4493C, guillocheSecondary = 0xFF846400, guillocheHighlight = 0xFF008880,
        glowPrimary = 0xFF42D0C5, glowSecondary = 0xFFFBA89A,
        progress = 0xFF00766E, progressTrack = 0xFFBCE8E4,
        textPrimary = 0xFF0F2727, textSecondary = 0xFF516767, textTertiary = 0xFF748787,
        border = 0xFFBFD0D0, borderHighlight = 0xFFDAE6E5,
        navigationSelected = 0xFF007C75,
        accentProgress = 0xFF007C75, accentStreak = 0xFF206CAF, accentDiscovery = 0xFFA84538, accentAnnotation = 0xFF876300,
    ),
    // LAGOON — "Abyss" — the deepest page in the set — bioluminescence is the only light
    FolioArtDirection(
        id = "lagoon", light = false,
        backgroundBase = 0xFF000406, backgroundSecondary = 0xFF000A0E, surface = 0xFF000B10, surfaceElevated = 0xFF021619,
        primaryAccent = 0xFF00D7D4, secondaryAccent = 0xFFB1B3FF, tertiaryAccent = 0xFF8AD89B, warmAccent = 0xFFFFAEAD,
        guillochePrimary = 0xFF908FFE, guillocheSecondary = 0xFF4FB76B, guillocheHighlight = 0xFF00C8C6,
        glowPrimary = 0xFF00BEBB, glowSecondary = 0xFF9D9DFF,
        progress = 0xFF00C8C6, progressTrack = 0xFF001E1D,
        textPrimary = 0xFFE8F2F5, textSecondary = 0xFFA0AEB0, textTertiary = 0xFF6D7C7F,
        border = 0xFF1A2D31, borderHighlight = 0xFF061517,
        navigationSelected = 0xFF00D8D6,
        accentProgress = 0xFF00DCD9, accentStreak = 0xFFFF9798, accentDiscovery = 0xFFB1B3FF, accentAnnotation = 0xFF73D78B,
    ),
    // ROUGE — "Rouge" — playbill cream with a crimson press
    FolioArtDirection(
        id = "rouge", light = true,
        backgroundBase = 0xFFFFEEEE, backgroundSecondary = 0xFFFDDECC, surface = 0xFFFFF7F6, surfaceElevated = 0xFFFFFCFC,
        primaryAccent = 0xFFBE4551, secondaryAccent = 0xFF9B7100, tertiaryAccent = 0xFFA9629A, warmAccent = 0xFF009A9E,
        guillochePrimary = 0xFF8A6100, guillocheSecondary = 0xFF934A85, guillocheHighlight = 0xFFBC4450,
        glowPrimary = 0xFFFF9397, glowSecondary = 0xFFE2BB68,
        progress = 0xFFA63642, progressTrack = 0xFFFED1D1,
        textPrimary = 0xFF301D1D, textSecondary = 0xFF705C5C, textTertiary = 0xFF907E7E,
        border = 0xFFD8C8C7, borderHighlight = 0xFFEBE0E0,
        navigationSelected = 0xFFB03846,
        accentProgress = 0xFFB03846, accentStreak = 0xFF007B7F, accentDiscovery = 0xFF8A6100, accentAnnotation = 0xFF984588,
    ),
    // ROUGE — "Garnet" — wine-black with facet fire
    FolioArtDirection(
        id = "rouge", light = false,
        backgroundBase = 0xFF0D0103, backgroundSecondary = 0xFF160307, surface = 0xFF17070A, surfaceElevated = 0xFF221113,
        primaryAccent = 0xFFFF94A0, secondaryAccent = 0xFFE7B461, tertiaryAccent = 0xFFE6AAE9, warmAccent = 0xFF78DDD7,
        guillochePrimary = 0xFFD19000, guillocheSecondary = 0xFFC97FCD, guillocheHighlight = 0xFFFF7B8C,
        glowPrimary = 0xFFF87183, glowSecondary = 0xFFDC9E29,
        progress = 0xFFFC818F, progressTrack = 0xFF340F14,
        textPrimary = 0xFFF8EDEE, textSecondary = 0xFFB4A7A9, textTertiary = 0xFF837577,
        border = 0xFF3C2B2E, borderHighlight = 0xFF211416,
        navigationSelected = 0xFFFF909E,
        accentProgress = 0xFFFF96A2, accentStreak = 0xFF02D9D1, accentDiscovery = 0xFFF0B142, accentAnnotation = 0xFFE99EED,
    ),
)

/** One theme's face, by the theme pack's own id. */
internal fun folioArtDirection(id: String, light: Boolean): FolioColors =
    FOLIO_ART_DIRECTIONS.first { it.id == id && it.light == light }.toFolioColors()
