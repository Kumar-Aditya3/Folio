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
    // ── the three chrome hues, authored per face ──
    //
    // The bottom nav capsule, the top masthead and the in-reader overlays. These
    // are authored rather than derived because the derivation landed on the
    // palette's warm note (a gold/olive/umber) on most faces, so the two bars and
    // the reader panels read as browns. Each face names its own three here.
    val navAccent: Long,
    val mastheadAccent: Long,
    val readerOverlayAccent: Long,
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
        navAccent = Color(navAccent),
        mastheadAccent = Color(mastheadAccent),
        readerOverlayAccent = Color(readerOverlayAccent),
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
 * | `gallery` | Gallery / Dark | the white cube, strictly monochrome — the art supplies the colour | GrainGradient |
 * | `manuscript` | Manuscript / Espresso | vellum, rubric sienna, iron-gall indigo, gold leaf | Warp |
 * | `silver` | Silver / Graphite | chrome and brushed steel; graphite is silver unlit — the metal, not the pigment | Dithering |
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
    // GALLERY — "Gallery" — the white cube, strictly monochrome: the art supplies the only
    // colour, so the room itself never does. Retuned away from warm plaster (the report:
    // "the gallery and dark theme are very green, they need to be black/white") — every
    // plane, ink and accent here is a true neutral grey, R=G=B.
    //
    // P7: the page came down from #FAFAFA and the rim from #DCDCDC. A strictly neutral face
    // still has to satisfy the two greyscale invariants the colour faces get for free: the
    // card must separate from the page by >= 2 L* (it was 1.04 — the card was invisible on
    // its own page) and the rim must clear 1.5:1 against the card (it was 1.35:1 — the card
    // had no edge at all). Both are steps within the greyscale, so the room is still neutral.
    FolioArtDirection(
        id = "gallery", light = true,
        backgroundBase = 0xFFF7F7F7, backgroundSecondary = 0xFFEFEFEF, surface = 0xFFFDFDFD, surfaceElevated = 0xFFFFFFFF,
        primaryAccent = 0xFF202020, secondaryAccent = 0xFF6E6E6E, tertiaryAccent = 0xFFA8A8A8, warmAccent = 0xFF8A8A8A,
        guillochePrimary = 0xFF3A3A3A, guillocheSecondary = 0xFF8C8C8C, guillocheHighlight = 0xFF575757,
        glowPrimary = 0xFFB8B8B8, glowSecondary = 0xFF8A8A8A,
        progress = 0xFF2B2B2B, progressTrack = 0xFFE4E4E4,
        textPrimary = 0xFF141414, textSecondary = 0xFF575757, textTertiary = 0xFF7E7E7E,
        border = 0xFFCFCFCF, borderHighlight = 0xFFEBEBEB,
        navigationSelected = 0xFF262626,
        navAccent = 0xFF444444, mastheadAccent = 0xFF7A7A7A, readerOverlayAccent = 0xFF5E5E5E,
        accentProgress = 0xFF111111, accentStreak = 0xFF525252, accentDiscovery = 0xFF2E2E2E, accentAnnotation = 0xFF737373,
    ),


    // GALLERY — "Dark" — the same monochrome room after hours: charcoal planes, a white
    // accent, no hue anywhere. The inverse of Gallery above, per the same report.
    FolioArtDirection(
        id = "gallery", light = false,
        backgroundBase = 0xFF050505, backgroundSecondary = 0xFF0B0B0B, surface = 0xFF101010, surfaceElevated = 0xFF1A1A1A,
        primaryAccent = 0xFFE8E8E8, secondaryAccent = 0xFF9E9E9E, tertiaryAccent = 0xFF6E6E6E, warmAccent = 0xFFB8B8B8,
        guillochePrimary = 0xFFC4C4C4, guillocheSecondary = 0xFF707070, guillocheHighlight = 0xFFA0A0A0,
        glowPrimary = 0xFF8C8C8C, glowSecondary = 0xFF6E6E6E,
        progress = 0xFFDCDCDC, progressTrack = 0xFF242424,
        textPrimary = 0xFFF2F2F2, textSecondary = 0xFFA8A8A8, textTertiary = 0xFF767676,
        border = 0xFF2E2E2E, borderHighlight = 0xFF181818,
        navigationSelected = 0xFFE0E0E0,
        navAccent = 0xFFCFCFCF, mastheadAccent = 0xFF8A8A8A, readerOverlayAccent = 0xFFAEAEAE,
        accentProgress = 0xFFF5F5F5, accentStreak = 0xFFCBCBCB, accentDiscovery = 0xFFA8A8A8, accentAnnotation = 0xFF838383,
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
        navAccent = 0xFF7F5BB6, mastheadAccent = 0xFF00869C, readerOverlayAccent = 0xFF007CB6,
        accentProgress = 0xFF942C02, accentStreak = 0xFF6E5000, accentDiscovery = 0xFF2E4EA3, accentAnnotation = 0xFF00663D,
    ),


    // MANUSCRIPT — "Espresso" — dark roast with crema gold — a yellow-brown, deliberately not Ember's orange
    FolioArtDirection(
        id = "manuscript", light = false,
        backgroundBase = 0xFF0E0400, backgroundSecondary = 0xFF180800, surface = 0xFF37271C, surfaceElevated = 0xFF42332A,
        primaryAccent = 0xFFF0A968, secondaryAccent = 0xFF95C0FF, tertiaryAccent = 0xFF83D6B7, warmAccent = 0xFFFAB6A5,
        guillochePrimary = 0xFF6A9FEE, guillocheSecondary = 0xFF40B690, guillocheHighlight = 0xFFEC9540,
        glowPrimary = 0xFFE18C34, glowSecondary = 0xFF79ACF8,
        progress = 0xFFE6984E, progressTrack = 0xFF321A03,
        textPrimary = 0xFFF7EEE9, textSecondary = 0xFFB4A8A2, textTertiary = 0xFF82766F,
        border = 0xFF3E3129, borderHighlight = 0xFF231913,
        navigationSelected = 0xFFF6A65D,
        navAccent = 0xFF00C5DD, mastheadAccent = 0xFF77C46F, readerOverlayAccent = 0xFFB09CFF,
        accentProgress = 0xFFFFB86E, accentStreak = 0xFFFFA78F, accentDiscovery = 0xFF8EC2FF, accentAnnotation = 0xFF67D6AF,
    ),


    // SILVER — "Silver" — real silver: chrome-white planes, brushed-steel greys, a cool
    // blue polish — a metal, not a pigment, and not the old periwinkle/mauve take that
    // read as a generic cool palette. Graphite below is its exact inverse, per the
    // report: "graphite needs to be the inverse of silver, and the silver needs to
    // actually be silver."
    FolioArtDirection(
        id = "silver", light = true,
        backgroundBase = 0xFFECEEF1, backgroundSecondary = 0xFFDCE0E6, surface = 0xFFF6F8FA, surfaceElevated = 0xFFFBFCFD,
        primaryAccent = 0xFF53606F, secondaryAccent = 0xFF59808F, tertiaryAccent = 0xFF818C9C, warmAccent = 0xFF8C8468,
        guillochePrimary = 0xFF3B4A5C, guillocheSecondary = 0xFF708090, guillocheHighlight = 0xFF55636F,
        glowPrimary = 0xFFAFC2D4, glowSecondary = 0xFF8FA6B8,
        progress = 0xFF4A5563, progressTrack = 0xFFD5DBE3,
        textPrimary = 0xFF1A212B, textSecondary = 0xFF55606E, textTertiary = 0xFF79828E,
        border = 0xFFC6CED8, borderHighlight = 0xFFE0E5EB,
        navigationSelected = 0xFF4E5A69,
        navAccent = 0xFF00884B, mastheadAccent = 0xFF426EC2, readerOverlayAccent = 0xFF008A7F,
        accentProgress = 0xFF4A5665, accentStreak = 0xFF615433, accentDiscovery = 0xFF215E6F, accentAnnotation = 0xFF55516B,
    ),


    // SILVER — "Graphite" — silver's inverse: the same steel hue axis, unlit. Blue-black
    // metal, pale-chrome accents — the metal in the dark, not a second pigment.
    FolioArtDirection(
        id = "silver", light = false,
        backgroundBase = 0xFF0A0C0F, backgroundSecondary = 0xFF10141A, surface = 0xFF292D34, surfaceElevated = 0xFF343841,
        primaryAccent = 0xFFB9C7D9, secondaryAccent = 0xFF7FA7B8, tertiaryAccent = 0xFF98A2B2, warmAccent = 0xFFC1B494,
        guillochePrimary = 0xFFA8B8CC, guillocheSecondary = 0xFF7889A0, guillocheHighlight = 0xFF9FB0C4,
        glowPrimary = 0xFF7C97B0, glowSecondary = 0xFF5E7085,
        progress = 0xFFAEBACB, progressTrack = 0xFF232B36,
        textPrimary = 0xFFEDF1F6, textSecondary = 0xFFA9B2BD, textTertiary = 0xFF757E8A,
        border = 0xFF313945, borderHighlight = 0xFF1B2027,
        navigationSelected = 0xFFB4C3D6,
        navAccent = 0xFF9BA3FF, mastheadAccent = 0xFF00C7D6, readerOverlayAccent = 0xFFFA878A,
        accentProgress = 0xFFC0CFE2, accentStreak = 0xFFD0C18F, accentDiscovery = 0xFF8CBBDF, accentAnnotation = 0xFFBCBAD8,
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
        navAccent = 0xFF007BB8, mastheadAccent = 0xFF9652A2, readerOverlayAccent = 0xFF5669C2,
        accentProgress = 0xFF005E7A, accentStreak = 0xFF804400, accentDiscovery = 0xFF006733, accentAnnotation = 0xFF544598,
    ),


    // ARCTIC — "Midnight" — polar night — navy, not black
    FolioArtDirection(
        id = "arctic", light = false,
        backgroundBase = 0xFF00070E, backgroundSecondary = 0xFF000D18, surface = 0xFF182C36, surfaceElevated = 0xFF273740,
        primaryAccent = 0xFF16CFF6, secondaryAccent = 0xFF74D5A0, tertiaryAccent = 0xFFBFB7FF, warmAccent = 0xFFF1BF7F,
        guillochePrimary = 0xFF24BA78, guillocheSecondary = 0xFF9B8EF1, guillocheHighlight = 0xFF00C2ED,
        glowPrimary = 0xFF00B8E0, glowSecondary = 0xFF44C686,
        progress = 0xFF00C2ED, progressTrack = 0xFF002431,
        textPrimary = 0xFFE9F2F7, textSecondary = 0xFFA1ADB3, textTertiary = 0xFF6F7B82,
        border = 0xFF24343C, borderHighlight = 0xFF0F1B22,
        navigationSelected = 0xFF00D1FC,
        navAccent = 0xFFDA8DDE, mastheadAccent = 0xFF8DA8FF, readerOverlayAccent = 0xFF7AC46E,
        accentProgress = 0xFF11D6FF, accentStreak = 0xFFF6AD47, accentDiscovery = 0xFF5BDA99, accentAnnotation = 0xFFBCB2FF,
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
        navAccent = 0xFFB54950, mastheadAccent = 0xFF98519F, readerOverlayAccent = 0xFF5669C2,
        accentProgress = 0xFF8C2961, accentStreak = 0xFF005993, accentDiscovery = 0xFF21660B, accentAnnotation = 0xFF893B00,
    ),


    // SAKURA — "Blossom" — night hanami — plum sky, lantern-lit petals
    FolioArtDirection(
        id = "sakura", light = false,
        backgroundBase = 0xFF0E020A, backgroundSecondary = 0xFF170612, surface = 0xFF362630, surfaceElevated = 0xFF41313C,
        primaryAccent = 0xFFF796D0, secondaryAccent = 0xFF95D088, tertiaryAccent = 0xFFF9B07E, warmAccent = 0xFF8ED2FF,
        guillochePrimary = 0xFF68B457, guillocheSecondary = 0xFFDF8540, guillocheHighlight = 0xFFF37EC6,
        glowPrimary = 0xFFE874BC, glowSecondary = 0xFF78C067,
        progress = 0xFFED83C4, progressTrack = 0xFF351429,
        textPrimary = 0xFFF6EDF3, textSecondary = 0xFFB2A7AE, textTertiary = 0xFF81757C,
        border = 0xFF3F3039, borderHighlight = 0xFF241920,
        navigationSelected = 0xFFFD91D2,
        navAccent = 0xFFCC93EE, mastheadAccent = 0xFF80ACFF, readerOverlayAccent = 0xFF00C5DC,
        accentProgress = 0xFFFFAAEB, accentStreak = 0xFF61CBFF, accentDiscovery = 0xFF8AD47A, accentAnnotation = 0xFFFFA564,
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
        navAccent = 0xFFB04970, mastheadAccent = 0xFF008A60, readerOverlayAccent = 0xFF0081AC,
        accentProgress = 0xFF784A00, accentStreak = 0xFF3E6200, accentDiscovery = 0xFF006264, accentAnnotation = 0xFF91301F,
    ),


    // HONEY — "Ember" — a dying coal — orange-red, deliberately away from Espresso's yellow-brown
    FolioArtDirection(
        id = "honey", light = false,
        backgroundBase = 0xFF0F0200, backgroundSecondary = 0xFF180600, surface = 0xFF37251A, surfaceElevated = 0xFF433128,
        primaryAccent = 0xFFFF9778, secondaryAccent = 0xFF39D6D8, tertiaryAccent = 0xFFF8B172, warmAccent = 0xFFB6D68D,
        guillochePrimary = 0xFF00B8BA, guillocheSecondary = 0xFFDE8628, guillocheHighlight = 0xFFFF7D58,
        glowPrimary = 0xFFFD734F, glowSecondary = 0xFF00C6C9,
        progress = 0xFFFF8361, progressTrack = 0xFF371107,
        textPrimary = 0xFFF7EEE9, textSecondary = 0xFFB4A8A2, textTertiary = 0xFF827670,
        border = 0xFF3E2E26, borderHighlight = 0xFF231711,
        navigationSelected = 0xFFFF9576,
        navAccent = 0xFFF586A9, mastheadAccent = 0xFF45C992, readerOverlayAccent = 0xFF2FBDF6,
        accentProgress = 0xFFFEB394, accentStreak = 0xFFA2CF62, accentDiscovery = 0xFF00D8DB, accentAnnotation = 0xFFFFA752,
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
        navAccent = 0xFF008A7F, mastheadAccent = 0xFF008A60, readerOverlayAccent = 0xFF00869C,
        accentProgress = 0xFF0F671D, accentStreak = 0xFF8B3148, accentDiscovery = 0xFF5A5900, accentAnnotation = 0xFF883C00,
    ),


    // MOSS — "Peacock" — a peacock eye is teal + sapphire + bronze + a violet sheen — its own hue set, not Moss at night
    FolioArtDirection(
        id = "moss", light = false,
        backgroundBase = 0xFF000906, backgroundSecondary = 0xFF00120C, surface = 0xFF19312B, surfaceElevated = 0xFF243C36,
        primaryAccent = 0xFF56D6A3, secondaryAccent = 0xFF56CFF2, tertiaryAccent = 0xFFFCAD84, warmAccent = 0xFFAEC7FF,
        guillochePrimary = 0xFF00B2DD, guillocheSecondary = 0xFFE2814C, guillocheHighlight = 0xFF00CC90,
        glowPrimary = 0xFF00C287, glowSecondary = 0xFF00BEE8,
        progress = 0xFF28C993, progressTrack = 0xFF002817,
        textPrimary = 0xFFE8F3F0, textSecondary = 0xFFA1AEAB, textTertiary = 0xFF6E7D79,
        border = 0xFF223833, borderHighlight = 0xFF0D1E1B,
        navigationSelected = 0xFF40D8A0,
        navAccent = 0xFF7AC46E, mastheadAccent = 0xFFBD98F8, readerOverlayAccent = 0xFF55B6FF,
        accentProgress = 0xFF4CE0A9, accentStreak = 0xFFA0BFFF, accentDiscovery = 0xFF1AD2FD, accentAnnotation = 0xFFFFA46F,
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
        navAccent = 0xFFA04E94, mastheadAccent = 0xFF00869C, readerOverlayAccent = 0xFFB54950,
        accentProgress = 0xFF6D3A8E, accentStreak = 0xFF6A5102, accentDiscovery = 0xFF8D3700, accentAnnotation = 0xFF005895,
    ),


    // DAWN — "Dusk" — twilight violet with the last ember on the horizon
    FolioArtDirection(
        id = "dawn", light = false,
        backgroundBase = 0xFF05030F, backgroundSecondary = 0xFF0B071A, surface = 0xFF282638, surfaceElevated = 0xFF343242,
        primaryAccent = 0xFFD2A3FB, secondaryAccent = 0xFFFEA195, tertiaryAccent = 0xFF98C5FF, warmAccent = 0xFFF8B0D1,
        guillochePrimary = 0xFFEB776A, guillocheSecondary = 0xFF689FF0, guillocheHighlight = 0xFFC88EF9,
        glowPrimary = 0xFFBF85EF, glowSecondary = 0xFFF58679,
        progress = 0xFFC692F2, progressTrack = 0xFF261633,
        textPrimary = 0xFFF0EFF8, textSecondary = 0xFFAAA9B5, textTertiary = 0xFF787783,
        border = 0xFF32303F, borderHighlight = 0xFF1A1824,
        navigationSelected = 0xFFD4A0FF,
        navAccent = 0xFFA2A1FF, mastheadAccent = 0xFF00C5E0, readerOverlayAccent = 0xFFE18BD5,
        accentProgress = 0xFFE4B0FD, accentStreak = 0xFFFF9DCE, accentDiscovery = 0xFFFFA192, accentAnnotation = 0xFF8EC2FF,
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
        navAccent = 0xFF008A5E, mastheadAccent = 0xFF6C62BE, readerOverlayAccent = 0xFFAE4974,
        accentProgress = 0xFF00635D, accentStreak = 0xFF005798, accentDiscovery = 0xFF913024, accentAnnotation = 0xFF714E00,
    ),


    // LAGOON — "Abyss" — the deepest page in the set — bioluminescence is the only light
    FolioArtDirection(
        id = "lagoon", light = false,
        backgroundBase = 0xFF000406, backgroundSecondary = 0xFF000A0E, surface = 0xFF15272C, surfaceElevated = 0xFF1D3337,
        primaryAccent = 0xFF00D7D4, secondaryAccent = 0xFFB1B3FF, tertiaryAccent = 0xFF8AD89B, warmAccent = 0xFFFFAEAD,
        guillochePrimary = 0xFF908FFE, guillocheSecondary = 0xFF4FB76B, guillocheHighlight = 0xFF00C8C6,
        glowPrimary = 0xFF00BEBB, glowSecondary = 0xFF9D9DFF,
        progress = 0xFF00C8C6, progressTrack = 0xFF001E1D,
        textPrimary = 0xFFE8F2F5, textSecondary = 0xFFA0AEB0, textTertiary = 0xFF6D7C7F,
        border = 0xFF1A2D31, borderHighlight = 0xFF061517,
        navigationSelected = 0xFF00D8D6,
        navAccent = 0xFF00C3E5, mastheadAccent = 0xFFC595F3, readerOverlayAccent = 0xFFF286B2,
        accentProgress = 0xFF00D9D6, accentStreak = 0xFFFF9FA0, accentDiscovery = 0xFFB3B5FF, accentAnnotation = 0xFF73D78B,
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
        navAccent = 0xFFAF4973, mastheadAccent = 0xFF0080AF, readerOverlayAccent = 0xFF008B6B,
        accentProgress = 0xFF982033, accentStreak = 0xFF006266, accentDiscovery = 0xFF744C00, accentAnnotation = 0xFF823073,
    ),


    // ROUGE — "Garnet" — wine-black with facet fire
    FolioArtDirection(
        id = "rouge", light = false,
        backgroundBase = 0xFF0D0103, backgroundSecondary = 0xFF160307, surface = 0xFF352226, surfaceElevated = 0xFF412E30,
        primaryAccent = 0xFFFF94A0, secondaryAccent = 0xFFE7B461, tertiaryAccent = 0xFFE6AAE9, warmAccent = 0xFF78DDD7,
        guillochePrimary = 0xFFD19000, guillocheSecondary = 0xFFC97FCD, guillocheHighlight = 0xFFFF7B8C,
        glowPrimary = 0xFFF87183, glowSecondary = 0xFFDC9E29,
        progress = 0xFFFC818F, progressTrack = 0xFF340F14,
        textPrimary = 0xFFF8EDEE, textSecondary = 0xFFB4A7A9, textTertiary = 0xFF837577,
        border = 0xFF3C2B2E, borderHighlight = 0xFF211416,
        navigationSelected = 0xFFFF909E,
        navAccent = 0xFFEE87BD, mastheadAccent = 0xFF00C0EE, readerOverlayAccent = 0xFF45C992,
        accentProgress = 0xFFFEABB7, accentStreak = 0xFF0AD9D1, accentDiscovery = 0xFFF0B142, accentAnnotation = 0xFFEA9EEE,
    ),


    // CYBERPUNK — "Neon Day" — the city washed out by daylight: concrete haze, cobalt glass,
    // neon signage unlit but still loud. The accents are the night face's neons pitched deep
    // enough to clear AA on paper.
    FolioArtDirection(
        id = "cyberpunk", light = true,
        backgroundBase = 0xFFEFF2F7, backgroundSecondary = 0xFFDFE6F2, surface = 0xFFF7F9FD, surfaceElevated = 0xFFFDFFFF,
        primaryAccent = 0xFF0066A8, secondaryAccent = 0xFFB3186B, tertiaryAccent = 0xFF8A5A00, warmAccent = 0xFF9A4A12,
        guillochePrimary = 0xFF0E62C3, guillocheSecondary = 0xFFB12E77, guillocheHighlight = 0xFF5A46C8,
        glowPrimary = 0xFF54C8F5, glowSecondary = 0xFFFF77B8,
        progress = 0xFF065A9E, progressTrack = 0xFFC9E7F8,
        textPrimary = 0xFF111A2E, textSecondary = 0xFF4A5872, textTertiary = 0xFF6E7C95,
        border = 0xFFC3CDE2, borderHighlight = 0xFFDEE5F2,
        navigationSelected = 0xFF0B69B4,
        navAccent = 0xFF7F5BB6, mastheadAccent = 0xFFA14E93, readerOverlayAccent = 0xFFB54950,
        accentProgress = 0xFF00559F, accentStreak = 0xFF824100, accentDiscovery = 0xFF006074, accentAnnotation = 0xFF8F245F,
    ),


    // CYBERPUNK — "Cyberpunk" — the neon city at night: asphalt blue-black, cyan signage,
    // magenta flash, electric amber. Saturation is the point where the light is.
    FolioArtDirection(
        id = "cyberpunk", light = false,
        backgroundBase = 0xFF050711, backgroundSecondary = 0xFF090D1A, surface = 0xFF272D3F, surfaceElevated = 0xFF32384C,
        primaryAccent = 0xFF3EE6FF, secondaryAccent = 0xFFFF4D9E, tertiaryAccent = 0xFFF5D90A, warmAccent = 0xFFE87A3D,
        guillochePrimary = 0xFF35C8E8, guillocheSecondary = 0xFFE84F9B, guillocheHighlight = 0xFF9FA6FF,
        glowPrimary = 0xFF2BD9F2, glowSecondary = 0xFFFF5CA8,
        progress = 0xFF49D8F5, progressTrack = 0xFF0B2E3A,
        textPrimary = 0xFFEAF4FF, textSecondary = 0xFFA7B6CE, textTertiary = 0xFF74849E,
        border = 0xFF2A3350, borderHighlight = 0xFF161C30,
        navigationSelected = 0xFF54E0FF,
        navAccent = 0xFF00C9C3, mastheadAccent = 0xFF5EC783, readerOverlayAccent = 0xFFFA878A,
        accentProgress = 0xFF4FDCFA, accentStreak = 0xFFFFA4E8, accentDiscovery = 0xFF0BDDB0, accentAnnotation = 0xFFDCBA55,
    ),


    // VICE — "Vice Day" — Miami daylight: blush-white heat, hot pink signage, a teal
    // flash. The accents are the night face's neons pitched deep enough to clear AA
    // on paper; the mechanism re-tones them against the rendered planes.
    FolioArtDirection(
        id = "vice", light = true,
        backgroundBase = 0xFFFDF6F9, backgroundSecondary = 0xFFF3E4EC, surface = 0xFFFEFAFC, surfaceElevated = 0xFFFFFFFF,
        primaryAccent = 0xFFD81B79, secondaryAccent = 0xFF008A80, tertiaryAccent = 0xFF7B1FA2, warmAccent = 0xFF9C6B00,
        guillochePrimary = 0xFFE91E63, guillocheSecondary = 0xFF009688, guillocheHighlight = 0xFF8E24AA,
        glowPrimary = 0xFFFF6FA5, glowSecondary = 0xFF4DD0E1,
        progress = 0xFFC2185B, progressTrack = 0xFFFFD9E6,
        textPrimary = 0xFF1F0A14, textSecondary = 0xFF5C4453, textTertiary = 0xFF83707C,
        border = 0xFFF2CFDF, borderHighlight = 0xFFF9E7EE,
        navigationSelected = 0xFFC2185B,
        navAccent = 0xFFA44D8E, mastheadAccent = 0xFF007FB0, readerOverlayAccent = 0xFF008849,
        accentProgress = 0xFFA00041, accentStreak = 0xFF00616D, accentDiscovery = 0xFF006457, accentAnnotation = 0xFF7327A4,
    ),


    // VICE — "Vice" — the decree face: very black, pink shades, and pink EDGES —
    // `secondary` is the role the atmosphere lifts into rimLight, so it carries a
    // lighter hot pink and every edge catch, rim and bar on the face reads pink,
    // while the teal flash survives in the glows and the streak.
    FolioArtDirection(
        id = "vice", light = false,
        backgroundBase = 0xFF060208, backgroundSecondary = 0xFF0D0512, surface = 0xFF271C2F, surfaceElevated = 0xFF31243B,
        primaryAccent = 0xFFFF4FA3, secondaryAccent = 0xFFFF7AB8, tertiaryAccent = 0xFFB388FF, warmAccent = 0xFFED9B40,
        guillochePrimary = 0xFFFF2E94, guillocheSecondary = 0xFF00E5CC, guillocheHighlight = 0xFF9C4DFF,
        glowPrimary = 0xFFFF2E94, glowSecondary = 0xFF00D9C0,
        progress = 0xFFFF2E94, progressTrack = 0xFF2A0A1E,
        textPrimary = 0xFFFDEBF4, textSecondary = 0xFFC4A8B9, textTertiary = 0xFF8F7585,
        border = 0xFF4A2438, borderHighlight = 0xFF20101C,
        navigationSelected = 0xFFFF4FA3,
        navAccent = 0xFFFA878A, mastheadAccent = 0xFF00C5DF, readerOverlayAccent = 0xFF60C781,
        accentProgress = 0xFFFF91E6, accentStreak = 0xFF02DBC2, accentDiscovery = 0xFFD2A7FF, accentAnnotation = 0xFFFCA950,
    ),


    // AURUM — "Champagne" — gilded daylight: champagne cream, deep gold-ochre,
    // emerald, plum. Old money with the lights on.
    FolioArtDirection(
        id = "aurum", light = true,
        backgroundBase = 0xFFFAF5EC, backgroundSecondary = 0xFFEFE4CE, surface = 0xFFFCF8F1, surfaceElevated = 0xFFFFFCF6,
        primaryAccent = 0xFF7A5C00, secondaryAccent = 0xFF00695C, tertiaryAccent = 0xFF7B1E4E, warmAccent = 0xFF8A4A00,
        guillochePrimary = 0xFFB8860B, guillocheSecondary = 0xFF00897B, guillocheHighlight = 0xFF8E24AA,
        glowPrimary = 0xFFFFD54F, glowSecondary = 0xFF00BFA5,
        progress = 0xFF7A5C00, progressTrack = 0xFFF0E3C8,
        textPrimary = 0xFF231A08, textSecondary = 0xFF5F5343, textTertiary = 0xFF83766B,
        border = 0xFFE4D6B8, borderHighlight = 0xFFF3EAD8,
        navigationSelected = 0xFF7A5C00,
        navAccent = 0xFFB54950, mastheadAccent = 0xFF008951, readerOverlayAccent = 0xFF6963BF,
        accentProgress = 0xFF6E5000, accentStreak = 0xFF006457, accentDiscovery = 0xFF8A2D5B, accentAnnotation = 0xFF814200,
    ),


    // AURUM — "Aurum" — black-tie: aubergine-black lacquer, burnished gold edges,
    // emerald neon, orchid. The chandelier is lit.
    FolioArtDirection(
        id = "aurum", light = false,
        backgroundBase = 0xFF0A0508, backgroundSecondary = 0xFF140A12, surface = 0xFF382A35, surfaceElevated = 0xFF443240,
        primaryAccent = 0xFFFFD180, secondaryAccent = 0xFFE6B84C, tertiaryAccent = 0xFFD8A6E8, warmAccent = 0xFFFF9E64,
        guillochePrimary = 0xFFE6B84C, guillocheSecondary = 0xFF8B6BCF, guillocheHighlight = 0xFFFFA24D,
        glowPrimary = 0xFFFFD180, glowSecondary = 0xFF8B6BCF,
        progress = 0xFFFFD180, progressTrack = 0xFF2E1F12,
        textPrimary = 0xFFFDF6EA, textSecondary = 0xFFCFC0B4, textTertiary = 0xFF94867C,
        border = 0xFF4A3320, borderHighlight = 0xFF241812,
        navigationSelected = 0xFFFFD180,
        navAccent = 0xFF48C991, mastheadAccent = 0xFFE989C8, readerOverlayAccent = 0xFF00C4E1,
        accentProgress = 0xFFEFC170, accentStreak = 0xFF54D6C9, accentDiscovery = 0xFFDAA8EA, accentAnnotation = 0xFFFFA56B,
    ),


)

/** One theme's face, by the theme pack's own id. */
internal fun folioArtDirection(id: String, light: Boolean): FolioColors =
    FOLIO_ART_DIRECTIONS.first { it.id == id && it.light == light }.toFolioColors()
