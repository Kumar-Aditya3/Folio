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
 * is the palette's own accent hue fitted offline against its planes. Contrast and
 * separation are checked by `ThemeSchemeTest`; monochrome faces retain neutral hues.
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
        // Categorical charts use this face's authored inks, never the legacy global traffic lights.
        chartSeries = folioChartPalette(id, light),

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
/** Nine material families, each with two independently authored faces.
 * Interaction, time, incoming content and saved meaning retain their roles across surfaces.
 * Classic is achromatic; Vaporwave is opalescent rose/aqua; Synthwave is graphite/lime/blue.
 * Crimson balances garnet with verdigris, parchment and cool violet.
 */
internal val FOLIO_ART_DIRECTIONS: List<FolioArtDirection> = listOf(
    FolioArtDirection(
        id = "gallery", light = true,
        backgroundBase = 0xFFF5F5F5, backgroundSecondary = 0xFFE9E9E9, surface = 0xFFFDFDFD,
        surfaceElevated = 0xFFFFFFFF, primaryAccent = 0xFF252525, secondaryAccent = 0xFF505050,
        tertiaryAccent = 0xFF393939, warmAccent = 0xFF6E6E6E, guillochePrimary = 0xFF252525,
        guillocheSecondary = 0xFF626262, guillocheHighlight = 0xFF3D3D3D, glowPrimary = 0xFF252525,
        glowSecondary = 0xFF626262, progress = 0xFF505050, progressTrack = 0xFFECECEC,
        textPrimary = 0xFF171717, textSecondary = 0xFF555555, textTertiary = 0xFF737373,
        border = 0xFFC7C7C7, borderHighlight = 0xFFF2F2F2, navigationSelected = 0xFF252525,
        navAccent = 0xFF252525, mastheadAccent = 0xFF252525, readerOverlayAccent = 0xFF252525,
        accentProgress = 0xFF505050, accentStreak = 0xFF6E6E6E, accentDiscovery = 0xFF626262,
        accentAnnotation = 0xFF3D3D3D,
    ),
    FolioArtDirection(
        id = "gallery", light = false,
        backgroundBase = 0xFF080808, backgroundSecondary = 0xFF111111, surface = 0xFF1C1C1C,
        surfaceElevated = 0xFF292929, primaryAccent = 0xFFE4E4E4, secondaryAccent = 0xFFC8C8C8,
        tertiaryAccent = 0xFFDADADA, warmAccent = 0xFF979797, guillochePrimary = 0xFFE4E4E4,
        guillocheSecondary = 0xFFAFAFAF, guillocheHighlight = 0xFFD8D8D8, glowPrimary = 0xFFE4E4E4,
        glowSecondary = 0xFFAFAFAF, progress = 0xFFC8C8C8, progressTrack = 0xFF323232,
        textPrimary = 0xFFF1F1F1, textSecondary = 0xFFBEBEBE, textTertiary = 0xFF969696,
        border = 0xFF505050, borderHighlight = 0xFF272727, navigationSelected = 0xFFE4E4E4,
        navAccent = 0xFFE4E4E4, mastheadAccent = 0xFFE4E4E4, readerOverlayAccent = 0xFFE4E4E4,
        accentProgress = 0xFFC8C8C8, accentStreak = 0xFF979797, accentDiscovery = 0xFFAFAFAF,
        accentAnnotation = 0xFFD8D8D8,
    ),
    FolioArtDirection(
        id = "manuscript", light = true,
        backgroundBase = 0xFFF3EFE5, backgroundSecondary = 0xFFE8E0D1, surface = 0xFFFCF8ED,
        surfaceElevated = 0xFFFFFCF5, primaryAccent = 0xFF87542E, secondaryAccent = 0xFF775C13,
        tertiaryAccent = 0xFF6B4F80, warmAccent = 0xFF8F4235, guillochePrimary = 0xFF87542E,
        guillocheSecondary = 0xFF355D79, guillocheHighlight = 0xFF72548B, glowPrimary = 0xFF87542E,
        glowSecondary = 0xFF355D79, progress = 0xFF775C13, progressTrack = 0xFFEFE8D7,
        textPrimary = 0xFF27251F, textSecondary = 0xFF5A574E, textTertiary = 0xFF767166,
        border = 0xFFC9BBA4, borderHighlight = 0xFFF1EDE3, navigationSelected = 0xFF87542E,
        navAccent = 0xFF87542E, mastheadAccent = 0xFF87542E, readerOverlayAccent = 0xFF87542E,
        accentProgress = 0xFF775C13, accentStreak = 0xFF8F4235, accentDiscovery = 0xFF355D79,
        accentAnnotation = 0xFF72548B,
    ),
    FolioArtDirection(
        id = "manuscript", light = false,
        backgroundBase = 0xFF151410, backgroundSecondary = 0xFF211D16, surface = 0xFF2C2920,
        surfaceElevated = 0xFF393328, primaryAccent = 0xFFE4B17C, secondaryAccent = 0xFFD5C27D,
        tertiaryAccent = 0xFFC8B4DC, warmAccent = 0xFFE89C88, guillochePrimary = 0xFFE4B17C,
        guillocheSecondary = 0xFF9BBFD7, guillocheHighlight = 0xFFC4AEDC, glowPrimary = 0xFFE4B17C,
        glowSecondary = 0xFF9BBFD7, progress = 0xFFD5C27D, progressTrack = 0xFF423D2C,
        textPrimary = 0xFFF1EADC, textSecondary = 0xFFC1B6A0, textTertiary = 0xFFA29882,
        border = 0xFF645B48, borderHighlight = 0xFF363329, navigationSelected = 0xFFE4B17C,
        navAccent = 0xFFE4B17C, mastheadAccent = 0xFFE4B17C, readerOverlayAccent = 0xFFE4B17C,
        accentProgress = 0xFFD5C27D, accentStreak = 0xFFE89C88, accentDiscovery = 0xFF9BBFD7,
        accentAnnotation = 0xFFC4AEDC,
    ),
    FolioArtDirection(
        id = "forest", light = true,
        backgroundBase = 0xFFEAF0E9, backgroundSecondary = 0xFFDAE5DF, surface = 0xFFF4F9F1,
        surfaceElevated = 0xFFFCFFF9, primaryAccent = 0xFF286453, secondaryAccent = 0xFF765B1C,
        tertiaryAccent = 0xFF695383, warmAccent = 0xFF8C483D, guillochePrimary = 0xFF286453,
        guillocheSecondary = 0xFF3F657F, guillocheHighlight = 0xFF72578D, glowPrimary = 0xFF286453,
        glowSecondary = 0xFF3F657F, progress = 0xFF765B1C, progressTrack = 0xFFE7E9DC,
        textPrimary = 0xFF172B28, textSecondary = 0xFF45625B, textTertiary = 0xFF657C72,
        border = 0xFFADBCB1, borderHighlight = 0xFFE9EFE7, navigationSelected = 0xFF286453,
        navAccent = 0xFF286453, mastheadAccent = 0xFF286453, readerOverlayAccent = 0xFF286453,
        accentProgress = 0xFF765B1C, accentStreak = 0xFF8C483D, accentDiscovery = 0xFF3F657F,
        accentAnnotation = 0xFF72578D,
    ),
    FolioArtDirection(
        id = "forest", light = false,
        backgroundBase = 0xFF0B1718, backgroundSecondary = 0xFF102126, surface = 0xFF1C3030,
        surfaceElevated = 0xFF294140, primaryAccent = 0xFFA0D9B6, secondaryAccent = 0xFFD9C489,
        tertiaryAccent = 0xFFCCBBE7, warmAccent = 0xFFE9A694, guillochePrimary = 0xFFA0D9B6,
        guillocheSecondary = 0xFF9BC6DB, guillocheHighlight = 0xFFCAB5E7, glowPrimary = 0xFFA0D9B6,
        glowSecondary = 0xFF9BC6DB, progress = 0xFFD9C489, progressTrack = 0xFF35433C,
        textPrimary = 0xFFE2F1E5, textSecondary = 0xFFA3C1B5, textTertiary = 0xFF819D94,
        border = 0xFF4A6563, borderHighlight = 0xFF263A39, navigationSelected = 0xFFA0D9B6,
        navAccent = 0xFFA0D9B6, mastheadAccent = 0xFFA0D9B6, readerOverlayAccent = 0xFFA0D9B6,
        accentProgress = 0xFFD9C489, accentStreak = 0xFFE9A694, accentDiscovery = 0xFF9BC6DB,
        accentAnnotation = 0xFFCAB5E7,
    ),
    FolioArtDirection(
        id = "ocean", light = true,
        backgroundBase = 0xFFEAF1F6, backgroundSecondary = 0xFFDCE6F0, surface = 0xFFF6FAFC,
        surfaceElevated = 0xFFFFFFFF, primaryAccent = 0xFF285F9A, secondaryAccent = 0xFF825C24,
        tertiaryAccent = 0xFF6C5184, warmAccent = 0xFF945242, guillochePrimary = 0xFF285F9A,
        guillocheSecondary = 0xFF266A68, guillocheHighlight = 0xFF76558B, glowPrimary = 0xFF285F9A,
        glowSecondary = 0xFF266A68, progress = 0xFF825C24, progressTrack = 0xFFEAEAE6,
        textPrimary = 0xFF142C46, textSecondary = 0xFF49627C, textTertiary = 0xFF697C92,
        border = 0xFFAFBFCE, borderHighlight = 0xFFEBF0F3, navigationSelected = 0xFF285F9A,
        navAccent = 0xFF285F9A, mastheadAccent = 0xFF285F9A, readerOverlayAccent = 0xFF285F9A,
        accentProgress = 0xFF825C24, accentStreak = 0xFF945242, accentDiscovery = 0xFF266A68,
        accentAnnotation = 0xFF76558B,
    ),
    FolioArtDirection(
        id = "ocean", light = false,
        backgroundBase = 0xFF080E1D, backgroundSecondary = 0xFF111A2D, surface = 0xFF1B2940,
        surfaceElevated = 0xFF2A3B55, primaryAccent = 0xFF90BFF3, secondaryAccent = 0xFFE0BC7B,
        tertiaryAccent = 0xFFCCB7EE, warmAccent = 0xFFEDA29A, guillochePrimary = 0xFF90BFF3,
        guillocheSecondary = 0xFF8FD2C6, guillocheHighlight = 0xFFC9B1ED, glowPrimary = 0xFF90BFF3,
        glowSecondary = 0xFF8FD2C6, progress = 0xFFE0BC7B, progressTrack = 0xFF353C48,
        textPrimary = 0xFFE6F0FA, textSecondary = 0xFFA3B8D1, textTertiary = 0xFF879BB7,
        border = 0xFF465C7B, borderHighlight = 0xFF253349, navigationSelected = 0xFF90BFF3,
        navAccent = 0xFF90BFF3, mastheadAccent = 0xFF90BFF3, readerOverlayAccent = 0xFF90BFF3,
        accentProgress = 0xFFE0BC7B, accentStreak = 0xFFEDA29A, accentDiscovery = 0xFF8FD2C6,
        accentAnnotation = 0xFFC9B1ED,
    ),
    FolioArtDirection(
        id = "editorial", light = true,
        backgroundBase = 0xFFEDE4D0, backgroundSecondary = 0xFFE0E6DC, surface = 0xFFFFFAEB,
        surfaceElevated = 0xFFFFFCF7, primaryAccent = 0xFF963C50, secondaryAccent = 0xFF725C20,
        tertiaryAccent = 0xFF62558A, warmAccent = 0xFF9E4C2E, guillochePrimary = 0xFF963C50,
        guillocheSecondary = 0xFF326B66, guillocheHighlight = 0xFF685A95, glowPrimary = 0xFF963C50,
        glowSecondary = 0xFF326B66, progress = 0xFF725C20, progressTrack = 0xFFF1EAD7,
        textPrimary = 0xFF2A2428, textSecondary = 0xFF66535B, textTertiary = 0xFF7F6F73,
        border = 0xFFC6B7B3, borderHighlight = 0xFFF4EFE1, navigationSelected = 0xFF963C50,
        navAccent = 0xFF963C50, mastheadAccent = 0xFF963C50, readerOverlayAccent = 0xFF963C50,
        accentProgress = 0xFF725C20, accentStreak = 0xFF9E4C2E, accentDiscovery = 0xFF326B66,
        accentAnnotation = 0xFF685A95,
    ),
    FolioArtDirection(
        id = "editorial", light = false,
        backgroundBase = 0xFF18151C, backgroundSecondary = 0xFF1E2A2A, surface = 0xFF30252F,
        surfaceElevated = 0xFF3C3541, primaryAccent = 0xFFED9AA9, secondaryAccent = 0xFFD5C385,
        tertiaryAccent = 0xFFC3BFEA, warmAccent = 0xFFE8AC82, guillochePrimary = 0xFFED9AA9,
        guillocheSecondary = 0xFF9ACCC2, guillocheHighlight = 0xFFBEBAEB, glowPrimary = 0xFFED9AA9,
        glowSecondary = 0xFF9ACCC2, progress = 0xFFD5C385, progressTrack = 0xFF453A3A,
        textPrimary = 0xFFF2E8DD, textSecondary = 0xFFC5B5B5, textTertiary = 0xFFA3949F,
        border = 0xFF695361, borderHighlight = 0xFF3A2F38, navigationSelected = 0xFFED9AA9,
        navAccent = 0xFFED9AA9, mastheadAccent = 0xFFED9AA9, readerOverlayAccent = 0xFFED9AA9,
        accentProgress = 0xFFD5C385, accentStreak = 0xFFE8AC82, accentDiscovery = 0xFF9ACCC2,
        accentAnnotation = 0xFFBEBAEB,
    ),
    FolioArtDirection(
        id = "blush", light = true,
        backgroundBase = 0xFFF3EAF1, backgroundSecondary = 0xFFE5E8F1, surface = 0xFFFCF6FA,
        surfaceElevated = 0xFFFFFFFF, primaryAccent = 0xFF9A3C70, secondaryAccent = 0xFF7D5B20,
        tertiaryAccent = 0xFF624E8E, warmAccent = 0xFF9D4E3F, guillochePrimary = 0xFF9A3C70,
        guillocheSecondary = 0xFF2C6973, guillocheHighlight = 0xFF675297, glowPrimary = 0xFF9A3C70,
        glowSecondary = 0xFF2C6973, progress = 0xFF7D5B20, progressTrack = 0xFFEFE6E4,
        textPrimary = 0xFF35263B, textSecondary = 0xFF6B536E, textTertiary = 0xFF826D88,
        border = 0xFFC8B6CB, borderHighlight = 0xFFF2ECF0, navigationSelected = 0xFF9A3C70,
        navAccent = 0xFF9A3C70, mastheadAccent = 0xFF9A3C70, readerOverlayAccent = 0xFF9A3C70,
        accentProgress = 0xFF7D5B20, accentStreak = 0xFF9D4E3F, accentDiscovery = 0xFF2C6973,
        accentAnnotation = 0xFF675297,
    ),
    FolioArtDirection(
        id = "blush", light = false,
        backgroundBase = 0xFF21182A, backgroundSecondary = 0xFF202D38, surface = 0xFF352A43,
        surfaceElevated = 0xFF463653, primaryAccent = 0xFFE9A3CF, secondaryAccent = 0xFFE5C887,
        tertiaryAccent = 0xFFCCBCF0, warmAccent = 0xFFF0AF9B, guillochePrimary = 0xFFE9A3CF,
        guillocheSecondary = 0xFF9ED3D5, guillocheHighlight = 0xFFC7B7F0, glowPrimary = 0xFFE9A3CF,
        glowSecondary = 0xFF9ED3D5, progress = 0xFFE5C887, progressTrack = 0xFF4C3F4C,
        textPrimary = 0xFFF6EAF3, textSecondary = 0xFFC6B2CF, textTertiary = 0xFFA58EAF,
        border = 0xFF685374, borderHighlight = 0xFF3F344C, navigationSelected = 0xFFE9A3CF,
        navAccent = 0xFFE9A3CF, mastheadAccent = 0xFFE9A3CF, readerOverlayAccent = 0xFFE9A3CF,
        accentProgress = 0xFFE5C887, accentStreak = 0xFFF0AF9B, accentDiscovery = 0xFF9ED3D5,
        accentAnnotation = 0xFFC7B7F0,
    ),
    FolioArtDirection(
        id = "acid", light = true,
        backgroundBase = 0xFFEEF1DB, backgroundSecondary = 0xFFDFE9DF, surface = 0xFFF9FBEF,
        surfaceElevated = 0xFFFEFFF8, primaryAccent = 0xFF53691A, secondaryAccent = 0xFF845C17,
        tertiaryAccent = 0xFF6F4F89, warmAccent = 0xFFA34B2B, guillochePrimary = 0xFF53691A,
        guillocheSecondary = 0xFF285F92, guillocheHighlight = 0xFF785395, glowPrimary = 0xFF53691A,
        glowSecondary = 0xFF285F92, progress = 0xFF845C17, progressTrack = 0xFFEDEBD9,
        textPrimary = 0xFF202919, textSecondary = 0xFF56634B, textTertiary = 0xFF74816B,
        border = 0xFFB7C2A3, borderHighlight = 0xFFEEF0E4, navigationSelected = 0xFF53691A,
        navAccent = 0xFF53691A, mastheadAccent = 0xFF53691A, readerOverlayAccent = 0xFF53691A,
        accentProgress = 0xFF845C17, accentStreak = 0xFFA34B2B, accentDiscovery = 0xFF285F92,
        accentAnnotation = 0xFF785395,
    ),
    FolioArtDirection(
        id = "acid", light = false,
        backgroundBase = 0xFF11150E, backgroundSecondary = 0xFF172421, surface = 0xFF253027,
        surfaceElevated = 0xFF354036, primaryAccent = 0xFFCEE880, secondaryAccent = 0xFFE3BF73,
        tertiaryAccent = 0xFFC5B8EB, warmAccent = 0xFFF1A77C, guillochePrimary = 0xFFCEE880,
        guillocheSecondary = 0xFF89C9F5, guillocheHighlight = 0xFFC1B1EC, glowPrimary = 0xFFCEE880,
        glowSecondary = 0xFF89C9F5, progress = 0xFFE3BF73, progressTrack = 0xFF3E4331,
        textPrimary = 0xFFEDF2DF, textSecondary = 0xFFB9C7A7, textTertiary = 0xFF94A58B,
        border = 0xFF536646, borderHighlight = 0xFF2F3A30, navigationSelected = 0xFFCEE880,
        navAccent = 0xFFCEE880, mastheadAccent = 0xFFCEE880, readerOverlayAccent = 0xFFCEE880,
        accentProgress = 0xFFE3BF73, accentStreak = 0xFFF1A77C, accentDiscovery = 0xFF89C9F5,
        accentAnnotation = 0xFFC1B1EC,
    ),
    FolioArtDirection(
        id = "ceramic", light = true,
        backgroundBase = 0xFFDDE2F2, backgroundSecondary = 0xFFD0D9EA, surface = 0xFFF8F9FF,
        surfaceElevated = 0xFFFFFFFF, primaryAccent = 0xFF3557A0, secondaryAccent = 0xFF95501F,
        tertiaryAccent = 0xFF704F8C, warmAccent = 0xFFA1434F, guillochePrimary = 0xFF3557A0,
        guillocheSecondary = 0xFF256A70, guillocheHighlight = 0xFF795395, glowPrimary = 0xFF3557A0,
        glowSecondary = 0xFF256A70, progress = 0xFF95501F, progressTrack = 0xFFEEE8E9,
        textPrimary = 0xFF202A3F, textSecondary = 0xFF52627C, textTertiary = 0xFF73819A,
        border = 0xFFB2BDD0, borderHighlight = 0xFFEDEFF5, navigationSelected = 0xFF3557A0,
        navAccent = 0xFF3557A0, mastheadAccent = 0xFF3557A0, readerOverlayAccent = 0xFF3557A0,
        accentProgress = 0xFF95501F, accentStreak = 0xFFA1434F, accentDiscovery = 0xFF256A70,
        accentAnnotation = 0xFF795395,
    ),
    FolioArtDirection(
        id = "ceramic", light = false,
        backgroundBase = 0xFF121821, backgroundSecondary = 0xFF1D2936, surface = 0xFF293446,
        surfaceElevated = 0xFF39475C, primaryAccent = 0xFFA5BFF4, secondaryAccent = 0xFFF0BE8C,
        tertiaryAccent = 0xFFD6BEEC, warmAccent = 0xFFEEA2AE, guillochePrimary = 0xFFA5BFF4,
        guillocheSecondary = 0xFF9BCFD2, guillocheHighlight = 0xFFD3B8EB, glowPrimary = 0xFFA5BFF4,
        glowSecondary = 0xFF9BCFD2, progress = 0xFFF0BE8C, progressTrack = 0xFF43464F,
        textPrimary = 0xFFEDF1F9, textSecondary = 0xFFB5C1D4, textTertiary = 0xFF929FB7,
        border = 0xFF546784, borderHighlight = 0xFF333D4F, navigationSelected = 0xFFA5BFF4,
        navAccent = 0xFFA5BFF4, mastheadAccent = 0xFFA5BFF4, readerOverlayAccent = 0xFFA5BFF4,
        accentProgress = 0xFFF0BE8C, accentStreak = 0xFFEEA2AE, accentDiscovery = 0xFF9BCFD2,
        accentAnnotation = 0xFFD3B8EB,
    ),
    FolioArtDirection(
        id = "copper", light = true,
        backgroundBase = 0xFFE9E2D9, backgroundSecondary = 0xFFDBE1E3, surface = 0xFFFAF8F3,
        surfaceElevated = 0xFFFFFDF8, primaryAccent = 0xFF955137, secondaryAccent = 0xFF416879,
        tertiaryAccent = 0xFF705180, warmAccent = 0xFF994B58, guillochePrimary = 0xFF955137,
        guillocheSecondary = 0xFF346C64, guillocheHighlight = 0xFF78558A, glowPrimary = 0xFF955137,
        glowSecondary = 0xFF346C64, progress = 0xFF416879, progressTrack = 0xFFE8EAE7,
        textPrimary = 0xFF2D292A, textSecondary = 0xFF635B5C, textTertiary = 0xFF7C7675,
        border = 0xFFBEB4AD, borderHighlight = 0xFFF0EEE9, navigationSelected = 0xFF955137,
        navAccent = 0xFF955137, mastheadAccent = 0xFF955137, readerOverlayAccent = 0xFF955137,
        accentProgress = 0xFF416879, accentStreak = 0xFF994B58, accentDiscovery = 0xFF346C64,
        accentAnnotation = 0xFF78558A,
    ),
    FolioArtDirection(
        id = "copper", light = false,
        backgroundBase = 0xFF171B1D, backgroundSecondary = 0xFF202C31, surface = 0xFF2B3539,
        surfaceElevated = 0xFF3C464A, primaryAccent = 0xFFE4B092, secondaryAccent = 0xFFA4CDD9,
        tertiaryAccent = 0xFFD7BDE7, warmAccent = 0xFFEAA6AE, guillochePrimary = 0xFFE4B092,
        guillocheSecondary = 0xFFAED1B3, guillocheHighlight = 0xFFD4B8E7, glowPrimary = 0xFFE4B092,
        glowSecondary = 0xFFAED1B3, progress = 0xFFA4CDD9, progressTrack = 0xFF3B494E,
        textPrimary = 0xFFF1EBE3, textSecondary = 0xFFC3BAB0, textTertiary = 0xFFA19891,
        border = 0xFF5D6869, borderHighlight = 0xFF353E42, navigationSelected = 0xFFE4B092,
        navAccent = 0xFFE4B092, mastheadAccent = 0xFFE4B092, readerOverlayAccent = 0xFFE4B092,
        accentProgress = 0xFFA4CDD9, accentStreak = 0xFFEAA6AE, accentDiscovery = 0xFFAED1B3,
        accentAnnotation = 0xFFD4B8E7,
    ),
)

internal fun folioArtDirection(id: String, light: Boolean): FolioColors =
    FOLIO_ART_DIRECTIONS.first { it.id == id && it.light == light }.toFolioColors()
