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
 * The seven iconic packs, each with its two separately painted faces.
 *
 * **The table is keyed by the theme pack's own id.** It used to be keyed by a parallel set of
 * "art-direction family" ids (`ocean`, `nebula`, `forest`, ...) which no other registry used,
 * and that second id space was not merely redundant — it silently broke the per-theme
 * background animation, because `LocalFolioPackId` carries a *pack* id and the field lookup
 * keyed on a *family* id, so packs fell through to the same default field. One id space, one
 * lookup, no drift.
 *
 * | pack | light / dark | identity |
 * |---|---|---|
 * | `classic` | Classic / Charcoal | the white cube, strictly monochrome — the art supplies the colour |
 * | `paper` | Paper / Ember | vellum, rubric sienna, iron-gall indigo, gold leaf; at night a dying coal |
 * | `forest` | Forest / Midnight | sage ground, conifer ink; at night teal-black with mint frost |
 * | `ocean` | Ocean / Abyss | pale aqua over navy ink; at depth, bioluminescence only |
 * | `editorial` | Editorial / Crimson | bone white, warm brown-black ink; at night burgundy and rose-gold |
 * | `blush` | Blush / Vaporwave | baby pink with rose ink; at night hot magenta and electric lavender |
 * | `acid` | Acid Rave / Synthwave | lime chartreuse with acid green; at night black and multi-neon |
 *
 * Every face is authored, not derived, and every face behind an `AppPalette` entry is swept by
 * `DesignSystemTest` / `FolioFieldModelTest`: the three ink tiers clear 7:1 / 3.5:1 on their own
 * `surface`, the progress fill clears 3:1 against its *track* (WCAG 1.4.11 — the bar is a
 * graphical object, so the neighbour the eye resolves is the track, not the page), the four
 * semantic accents clear AA on `surface` and are held pairwise distinct, and the rim clears
 * 1.5:1 against the card. The five faces added when the packs were consolidated from twelve to
 * seven were fitted with those floors rather than eyeballed.
 *
 * The light and dark faces of a pack are separately art-directed rather than inverted: a pack's
 * dark face is the same *world* at night, which is why its accents are the luminous versions of
 * the light face's and its page is a coloured near-black rather than black.
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
        border = 0xFFCECECE, borderHighlight = 0xFFEBEBEB,
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
        border = 0xFF353535, borderHighlight = 0xFF181818,
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
        border = 0xFFCEC7BB, borderHighlight = 0xFFE7E2DA,
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
        border = 0xFF51453E, borderHighlight = 0xFF231913,
        navigationSelected = 0xFFF6A65D,
        navAccent = 0xFF00C5DD, mastheadAccent = 0xFF77C46F, readerOverlayAccent = 0xFFB09CFF,
        accentProgress = 0xFFFFB86E, accentStreak = 0xFFFFA78F, accentDiscovery = 0xFF8EC2FF, accentAnnotation = 0xFF67D6AF,
    ),


    // FOREST — "Forest" — sage ground and conifer ink. The calm, refreshing pack:
    // nature at its most restful, green without being earthy.
    FolioArtDirection(
        id = "forest", light = true,
        backgroundBase = 0xFFE7F3E8, backgroundSecondary = 0xFFD1E6D7, surface = 0xFFF3F9F4, surfaceElevated = 0xFFF9FDFA,
        primaryAccent = 0xFF1F6B33, secondaryAccent = 0xFF0F6070, tertiaryAccent = 0xFF6E5314, warmAccent = 0xFF4F5E86,
        guillochePrimary = 0xFF2E7A45, guillocheSecondary = 0xFF0E6A66, guillocheHighlight = 0xFF4A8F58,
        glowPrimary = 0xFF6DC182, glowSecondary = 0xFF66B6C4,
        progress = 0xFF1B6730, progressTrack = 0xFFC8E5CF,
        textPrimary = 0xFF0F1F10, textSecondary = 0xFF4E5C4E, textTertiary = 0xFF727E70,
        border = 0xFFBBCABE, borderHighlight = 0xFFDCE7DF,
        navigationSelected = 0xFF1E6B36,
        navAccent = 0xFF00796B, mastheadAccent = 0xFF0F6FA8, readerOverlayAccent = 0xFF8A4E78,
        accentProgress = 0xFF0C5A28, accentStreak = 0xFF00615E, accentDiscovery = 0xFF00517A, accentAnnotation = 0xFF7A4A10,
    ),

    // FOREST — "Midnight" — teal-black with mint frost. The same wood after dark:
    // cool, deep, still.
    FolioArtDirection(
        id = "forest", light = false,
        backgroundBase = 0xFF020E0D, backgroundSecondary = 0xFF061A17, surface = 0xFF1A2E2A, surfaceElevated = 0xFF243A35,
        primaryAccent = 0xFF4FDC9A, secondaryAccent = 0xFF5FD0F0, tertiaryAccent = 0xFFF0B183, warmAccent = 0xFFB0A6FF,
        guillochePrimary = 0xFF2FC98C, guillocheSecondary = 0xFF3FB8E0, guillocheHighlight = 0xFF9BE87A,
        glowPrimary = 0xFF2FCB8C, glowSecondary = 0xFF39BEE4,
        progress = 0xFF3ED49A, progressTrack = 0xFF042A21,
        textPrimary = 0xFFE6F7F0, textSecondary = 0xFF9FB2AC, textTertiary = 0xFF6E817B,
        border = 0xFF394E48, borderHighlight = 0xFF0B1E1A,
        navigationSelected = 0xFF4FE0A0,
        navAccent = 0xFF7AD08A, mastheadAccent = 0xFF9E9EFF, readerOverlayAccent = 0xFF55B6FF,
        accentProgress = 0xFF5AE4A8, accentStreak = 0xFFB8AEFF, accentDiscovery = 0xFF3ED2F0, accentAnnotation = 0xFFF0A878,
    ),

    // OCEAN — "Ocean" — pale aqua shallows over navy ink. Calm and intellectual:
    // water you can see the bottom of.
    FolioArtDirection(
        id = "ocean", light = true,
        backgroundBase = 0xFFE4F2F8, backgroundSecondary = 0xFFC9E2EF, surface = 0xFFF2FAFD, surfaceElevated = 0xFFF9FDFF,
        primaryAccent = 0xFF0F5A96, secondaryAccent = 0xFF0B6E86, tertiaryAccent = 0xFF7A5A18, warmAccent = 0xFF6A4E96,
        guillochePrimary = 0xFF1668A8, guillocheSecondary = 0xFF0D7A8C, guillocheHighlight = 0xFF2E86C8,
        glowPrimary = 0xFF6FBEE8, glowSecondary = 0xFF63C8D8,
        progress = 0xFF0B5288, progressTrack = 0xFFC4E2F2,
        textPrimary = 0xFF0A1D33, textSecondary = 0xFF4C5F73, textTertiary = 0xFF6F8092,
        border = 0xFFB7CAD8, borderHighlight = 0xFFDCE9F2,
        navigationSelected = 0xFF0D5C94,
        navAccent = 0xFF00798C, mastheadAccent = 0xFF2E5CC4, readerOverlayAccent = 0xFF8A4A88,
        accentProgress = 0xFF0A4A7A, accentStreak = 0xFF6B4A00, accentDiscovery = 0xFF00636E, accentAnnotation = 0xFF5A3F94,
    ),

    // OCEAN — "Abyss" — midnight blue with bioluminescent cyan. The deep, where
    // the only light is what lives there.
    FolioArtDirection(
        id = "ocean", light = false,
        backgroundBase = 0xFF01060F, backgroundSecondary = 0xFF04101F, surface = 0xFF152538, surfaceElevated = 0xFF1F3145,
        primaryAccent = 0xFF3FC9F5, secondaryAccent = 0xFF7FB0FF, tertiaryAccent = 0xFFFFC46B, warmAccent = 0xFFB79CFF,
        guillochePrimary = 0xFF2FB8E8, guillocheSecondary = 0xFF5F92F0, guillocheHighlight = 0xFF48D8D0,
        glowPrimary = 0xFF2ABCEA, glowSecondary = 0xFF5E8FF0,
        progress = 0xFF3FC4F2, progressTrack = 0xFF03202F,
        textPrimary = 0xFFE4F1FA, textSecondary = 0xFF9BADC2, textTertiary = 0xFF6C7E94,
        border = 0xFF364559, borderHighlight = 0xFF0A1725,
        navigationSelected = 0xFF45CDFA,
        navAccent = 0xFF00C0C8, mastheadAccent = 0xFF9B8CF0, readerOverlayAccent = 0xFF66C98A,
        accentProgress = 0xFF4FD2FF, accentStreak = 0xFFB8A2FF, accentDiscovery = 0xFF6FE0C8, accentAnnotation = 0xFFFFC98A,
    ),

    // EDITORIAL — "Editorial" — bone white with warm brown-black ink. A literary
    // magazine: sophisticated, expressive, printed.
    FolioArtDirection(
        id = "editorial", light = true,
        backgroundBase = 0xFFF7F2EA, backgroundSecondary = 0xFFE9DFD0, surface = 0xFFFCF9F4, surfaceElevated = 0xFFFFFDFA,
        primaryAccent = 0xFF8A3A1E, secondaryAccent = 0xFF1F5F6B, tertiaryAccent = 0xFF6E5210, warmAccent = 0xFF5A4A78,
        guillochePrimary = 0xFF9C4222, guillocheSecondary = 0xFF20626E, guillocheHighlight = 0xFFB0562E,
        glowPrimary = 0xFFD98A6A, glowSecondary = 0xFF7FAEB8,
        progress = 0xFF84351A, progressTrack = 0xFFEEDCCB,
        textPrimary = 0xFF211812, textSecondary = 0xFF5E5048, textTertiary = 0xFF7F7269,
        border = 0xFFD3C6B6, borderHighlight = 0xFFE9DFD2,
        navigationSelected = 0xFF8A3A1E,
        navAccent = 0xFF7A2F4A, mastheadAccent = 0xFF1E5F7A, readerOverlayAccent = 0xFF4A5A9A,
        accentProgress = 0xFF7A2E14, accentStreak = 0xFF17505C, accentDiscovery = 0xFF63490E, accentAnnotation = 0xFF4E3A6C,
    ),

    // EDITORIAL — "Crimson" — deep burgundy with rose-gold. The bold statement:
    // the same press, after hours.
    FolioArtDirection(
        id = "editorial", light = false,
        backgroundBase = 0xFF150306, backgroundSecondary = 0xFF25070C, surface = 0xFF3A1D20, surfaceElevated = 0xFF472A2B,
        primaryAccent = 0xFFFF8A6B, secondaryAccent = 0xFFF2C06A, tertiaryAccent = 0xFFE09BE8, warmAccent = 0xFF6FD8CF,
        guillochePrimary = 0xFFF2734F, guillocheSecondary = 0xFFD9A33F, guillocheHighlight = 0xFFFF9E7A,
        glowPrimary = 0xFFF2653F, glowSecondary = 0xFFD9A33F,
        progress = 0xFFFF7A58, progressTrack = 0xFF340D0C,
        textPrimary = 0xFFFBE9E2, textSecondary = 0xFFC0A69F, textTertiary = 0xFF8B736C,
        border = 0xFF57403E, borderHighlight = 0xFF241210,
        navigationSelected = 0xFFFF8A6B,
        navAccent = 0xFFEE7FA8, mastheadAccent = 0xFF00C2D8, readerOverlayAccent = 0xFF4FC98A,
        accentProgress = 0xFFFF9C82, accentStreak = 0xFF6FD8CF, accentDiscovery = 0xFFF0B45C, accentAnnotation = 0xFFE5A6EE,
    ),

    // BLUSH — "Blush" — baby pink ground with rose ink. Y2K nostalgia:
    // dreamy, soft, a little unreal.
    FolioArtDirection(
        id = "blush", light = true,
        backgroundBase = 0xFFFFECF3, backgroundSecondary = 0xFFF7D6E4, surface = 0xFFFFF6FA, surfaceElevated = 0xFFFFFCFE,
        primaryAccent = 0xFFB03A66, secondaryAccent = 0xFF7A4A9A, tertiaryAccent = 0xFF1E6E78, warmAccent = 0xFF8A5A18,
        guillochePrimary = 0xFFC44569, guillocheSecondary = 0xFF8A56A8, guillocheHighlight = 0xFFD9527A,
        glowPrimary = 0xFFF080AC, glowSecondary = 0xFFB08ED0,
        progress = 0xFFAA2F5C, progressTrack = 0xFFFBD3E2,
        textPrimary = 0xFF2E1520, textSecondary = 0xFF6B4C58, textTertiary = 0xFF8C6E79,
        border = 0xFFDDBFCC, borderHighlight = 0xFFF2E0E8,
        navigationSelected = 0xFFB03A66,
        navAccent = 0xFF8A3F8C, mastheadAccent = 0xFF1E6E9A, readerOverlayAccent = 0xFF2E7A6A,
        accentProgress = 0xFF9C2455, accentStreak = 0xFF1A6069, accentDiscovery = 0xFF6A3A94, accentAnnotation = 0xFF7A5210,
    ),

    // BLUSH — "Vaporwave" — hot magenta with electric lavender. Cyber-love: the
    // sunset rendered in neon.
    FolioArtDirection(
        id = "blush", light = false,
        backgroundBase = 0xFF1B0A1E, backgroundSecondary = 0xFF2A0F2E, surface = 0xFF3D2244, surfaceElevated = 0xFF4A2C52,
        primaryAccent = 0xFFFF7AC8, secondaryAccent = 0xFF5FE0E8, tertiaryAccent = 0xFFC89BFF, warmAccent = 0xFFFFC46B,
        guillochePrimary = 0xFFF05AB8, guillocheSecondary = 0xFF44D0DE, guillocheHighlight = 0xFFFF8AD8,
        glowPrimary = 0xFFF04FB0, glowSecondary = 0xFF3FCBDC,
        progress = 0xFFFF6BC0, progressTrack = 0xFF3A0C34,
        textPrimary = 0xFFFAE9F7, textSecondary = 0xFFC4A6C2, textTertiary = 0xFF8F758D,
        border = 0xFF5D4262, borderHighlight = 0xFF241028,
        navigationSelected = 0xFFFF80CC,
        navAccent = 0xFFA88AFF, mastheadAccent = 0xFF4FD8E8, readerOverlayAccent = 0xFF6FD88A,
        accentProgress = 0xFFFF9BD8, accentStreak = 0xFFFFC98A, accentDiscovery = 0xFF8FE8F0, accentAnnotation = 0xFFC9A8FF,
    ),

    // ACID — "Acid Rave" — lime chartreuse with acid-green accents. High-voltage:
    // maximum visual impact on paper.
    FolioArtDirection(
        id = "acid", light = true,
        backgroundBase = 0xFFEEFADF, backgroundSecondary = 0xFFDCF2C4, surface = 0xFFF8FDF0, surfaceElevated = 0xFFFCFEF8,
        primaryAccent = 0xFF4A7A0A, secondaryAccent = 0xFF7A2A8A, tertiaryAccent = 0xFF0A6E78, warmAccent = 0xFFA84A0A,
        guillochePrimary = 0xFF5C9010, guillocheSecondary = 0xFF8A34A0, guillocheHighlight = 0xFF6FA81A,
        glowPrimary = 0xFFA8D060, glowSecondary = 0xFFC08AD8,
        progress = 0xFF42700A, progressTrack = 0xFFDCEFC0,
        textPrimary = 0xFF141F06, textSecondary = 0xFF4C5A3A, textTertiary = 0xFF6E7A5E,
        border = 0xFFBECFA7, borderHighlight = 0xFFE2EFD2,
        navigationSelected = 0xFF4A7A0A,
        navAccent = 0xFF8A2FA0, mastheadAccent = 0xFF0A6EA8, readerOverlayAccent = 0xFFA84A0A,
        accentProgress = 0xFF3E6A08, accentStreak = 0xFF0A6E78, accentDiscovery = 0xFF7A2A8A, accentAnnotation = 0xFF9A4408,
    ),

    // ACID — "Synthwave" — a pure-black canvas with multi-neon ink. Rave culture:
    // the loudest room in the set.
    FolioArtDirection(
        id = "acid", light = false,
        backgroundBase = 0xFF0A0810, backgroundSecondary = 0xFF140E1E, surface = 0xFF2A2038, surfaceElevated = 0xFF362B46,
        primaryAccent = 0xFFFF5FD0, secondaryAccent = 0xFF3FE0F0, tertiaryAccent = 0xFFB08AFF, warmAccent = 0xFFFFC44F,
        guillochePrimary = 0xFFFF4FC8, guillocheSecondary = 0xFF2FD8EC, guillocheHighlight = 0xFFFF7ADC,
        glowPrimary = 0xFFF04CC0, glowSecondary = 0xFF2FD0E8,
        progress = 0xFFFF5FD0, progressTrack = 0xFF320A2A,
        textPrimary = 0xFFFBEAF8, textSecondary = 0xFFC0AAC8, textTertiary = 0xFF8A7A96,
        border = 0xFF4A405A, borderHighlight = 0xFF18111F,
        navigationSelected = 0xFFFF6FD8,
        navAccent = 0xFF9B8AFF, mastheadAccent = 0xFF3FE0F0, readerOverlayAccent = 0xFF5FD88A,
        accentProgress = 0xFFFF8AE0, accentStreak = 0xFFFFC98A, accentDiscovery = 0xFF6FE8F5, accentAnnotation = 0xFFBFA6FF,
    ),
)

/** One theme's face, by the theme pack's own id. */
internal fun folioArtDirection(id: String, light: Boolean): FolioColors =
    FOLIO_ART_DIRECTIONS.first { it.id == id && it.light == light }.toFolioColors()
