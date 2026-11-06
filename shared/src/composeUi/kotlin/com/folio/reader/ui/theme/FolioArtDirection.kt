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
 * separation are checked by `ThemeSchemeTest` and `ElementColorRolesTest`.
 */
internal data class FolioArtDirection(
    val id: String,
    val light: Boolean,
    // ── planes ──
    val backgroundBase: Long,
    val backgroundSecondary: Long,
    val surface: Long,
    val surfaceElevated: Long,
    val paperSurface: Long = surface,
    val referenceSurface: Long = backgroundSecondary,
    val chromeSurface: Long = surfaceElevated,
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
        paperSurface = Color(paperSurface),
        referenceSurface = Color(referenceSurface),
        chromeSurface = Color(chromeSurface),
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
/** Twelve paired families with contrasting foundation and interaction colors.
 * Actions and selected navigation share one chromatic interaction accent per face.
 * That accent opposes the bar/foundation hue, as orange does against Afterprint's
 * Prussian blue and yellow against Blacklight's ultraviolet. Paper, reference,
 * chrome and supporting inks complete the combination; they are individually authored.
 * Classic/Charcoal keep neutral foundations with colored oxblood/amber interaction.
 */
internal val FOLIO_ART_DIRECTIONS: List<FolioArtDirection> = listOf(
    FolioArtDirection(
        id = "gallery", light = true,
        backgroundBase = 0xFFF1F2F0, backgroundSecondary = 0xFFE4E9ED, surface = 0xFFFDFDFD,
        paperSurface = 0xFFFCFCFC, referenceSurface = 0xFFE0E7EE, chromeSurface = 0xFFCAD7E3,
        surfaceElevated = 0xFFFFFFFF, primaryAccent = 0xFF6B1736, secondaryAccent = 0xFF2E5D8A,
        tertiaryAccent = 0xFF557440, warmAccent = 0xFF80621C, guillochePrimary = 0xFF6B1736,
        guillocheSecondary = 0xFF2E5D8A, guillocheHighlight = 0xFF557440, glowPrimary = 0xFF6B1736,
        glowSecondary = 0xFF2E5D8A, progress = 0xFF6B1736, progressTrack = 0xFFE1D8DA,
        textPrimary = 0xFF171717, textSecondary = 0xFF555555, textTertiary = 0xFF737373,
        border = 0xFFB3B3B3, borderHighlight = 0xFFFEFEFE, navigationSelected = 0xFF6B1736,
        navAccent = 0xFF6B1736, mastheadAccent = 0xFF6B1736, readerOverlayAccent = 0xFF6B1736,
        accentProgress = 0xFF661734, accentStreak = 0xFF7E601C, accentDiscovery = 0xFF2E5D8A,
        accentAnnotation = 0xFF4F6C3C,
    ),
    FolioArtDirection(
        id = "gallery", light = false,
        backgroundBase = 0xFF080A10, backgroundSecondary = 0xFF151D29, surface = 0xFF1C232C,
        paperSurface = 0xFF2B2923, referenceSurface = 0xFF252F3D, chromeSurface = 0xFF303A49,
        surfaceElevated = 0xFF2D3540, primaryAccent = 0xFFF2C15C, secondaryAccent = 0xFF9BCDEB,
        tertiaryAccent = 0xFFE8A4BC, warmAccent = 0xFF9BD6BE, guillochePrimary = 0xFFF2C15C,
        guillocheSecondary = 0xFF9BCDEB, guillocheHighlight = 0xFFE8A4BC, glowPrimary = 0xFFF2C15C,
        glowSecondary = 0xFF9BCDEB, progress = 0xFFF2C15C, progressTrack = 0xFF322B1E,
        textPrimary = 0xFFF1F1F1, textSecondary = 0xFFBEBEBE, textTertiary = 0xFF969696,
        border = 0xFF565B61, borderHighlight = 0xFF181E25, navigationSelected = 0xFFF2C15C,
        navAccent = 0xFFF2C15C, mastheadAccent = 0xFFF2C15C, readerOverlayAccent = 0xFFF2C15C,
        accentProgress = 0xFFF2C465, accentStreak = 0xFF9BD6BE, accentDiscovery = 0xFF9BCDEB,
        accentAnnotation = 0xFFE8A4BC,
    ),
    FolioArtDirection(
        id = "manuscript", light = true,
        backgroundBase = 0xFFF3EFE5, backgroundSecondary = 0xFFE8E0D1, surface = 0xFFFCF8ED,
        paperSurface = 0xFFFFF8E8, referenceSurface = 0xFFE8EBEE, chromeSurface = 0xFFE0E1E0,
        surfaceElevated = 0xFFFFFCF5, primaryAccent = 0xFF493E8E, secondaryAccent = 0xFF87542E,
        tertiaryAccent = 0xFF2B6A69, warmAccent = 0xFF9A3D76, guillochePrimary = 0xFF493E8E,
        guillocheSecondary = 0xFF87542E, guillocheHighlight = 0xFF2B6A69, glowPrimary = 0xFF493E8E,
        glowSecondary = 0xFF87542E, progress = 0xFF493E8E, progressTrack = 0xFFDFDADB,
        textPrimary = 0xFF27251F, textSecondary = 0xFF5A574E, textTertiary = 0xFF767166,
        border = 0xFFB8B4AB, borderHighlight = 0xFFFDFAF3, navigationSelected = 0xFF493E8E,
        navAccent = 0xFF493E8E, mastheadAccent = 0xFF493E8E, readerOverlayAccent = 0xFF493E8E,
        accentProgress = 0xFF473C87, accentStreak = 0xFF9A3D76, accentDiscovery = 0xFF87542E,
        accentAnnotation = 0xFF2B6A69,
    ),
    FolioArtDirection(
        id = "manuscript", light = false,
        backgroundBase = 0xFF261508, backgroundSecondary = 0xFF382237, surface = 0xFF402814,
        paperSurface = 0xFF3B2518, referenceSurface = 0xFF353041, chromeSurface = 0xFF4C3B36,
        surfaceElevated = 0xFF513B25, primaryAccent = 0xFF91D4ED, secondaryAccent = 0xFFFFC36B,
        tertiaryAccent = 0xFFC9BBFF, warmAccent = 0xFFFFA394, guillochePrimary = 0xFF91D4ED,
        guillocheSecondary = 0xFFFFC36B, guillocheHighlight = 0xFFC9BBFF, glowPrimary = 0xFF91D4ED,
        glowSecondary = 0xFFFFC36B, progress = 0xFF91D4ED, progressTrack = 0xFF393731,
        textPrimary = 0xFFFFF1DB, textSecondary = 0xFFCEBCA3, textTertiary = 0xFFA48E76,
        border = 0xFF745E4A, borderHighlight = 0xFF362211, navigationSelected = 0xFF91D4ED,
        navAccent = 0xFF91D4ED, mastheadAccent = 0xFF91D4ED, readerOverlayAccent = 0xFF91D4ED,
        accentProgress = 0xFF98D6EC, accentStreak = 0xFFFFA394, accentDiscovery = 0xFFFFC36B,
        accentAnnotation = 0xFFC9BBFF,
    ),
    FolioArtDirection(
        id = "forest", light = true,
        backgroundBase = 0xFFDDEBD4, backgroundSecondary = 0xFFD9E5D5, surface = 0xFFF0F6E7,
        paperSurface = 0xFFEEF3D8, referenceSurface = 0xFFDCE5CD, chromeSurface = 0xFFD2DEC3,
        surfaceElevated = 0xFFF8FBEF, primaryAccent = 0xFFAB4828, secondaryAccent = 0xFF23603A,
        tertiaryAccent = 0xFF3D4A92, warmAccent = 0xFF81701E, guillochePrimary = 0xFFAB4828,
        guillocheSecondary = 0xFF23603A, guillocheHighlight = 0xFF3D4A92, glowPrimary = 0xFFAB4828,
        glowSecondary = 0xFF23603A, progress = 0xFFAB4828, progressTrack = 0xFFD7D7BF,
        textPrimary = 0xFF172A1B, textSecondary = 0xFF4B604C, textTertiary = 0xFF6D826A,
        border = 0xFFABB5A6, borderHighlight = 0xFFF5F9EF, navigationSelected = 0xFFAB4828,
        navAccent = 0xFFAB4828, mastheadAccent = 0xFFAB4828, readerOverlayAccent = 0xFFAB4828,
        accentProgress = 0xFF974426, accentStreak = 0xFF6C621D, accentDiscovery = 0xFF23603A,
        accentAnnotation = 0xFF3D4A92,
    ),
    FolioArtDirection(
        id = "forest", light = false,
        backgroundBase = 0xFF0D241A, backgroundSecondary = 0xFF193229, surface = 0xFF233D2B,
        paperSurface = 0xFF303925, referenceSurface = 0xFF19362D, chromeSurface = 0xFF254638,
        surfaceElevated = 0xFF334F39, primaryAccent = 0xFFFFAB86, secondaryAccent = 0xFF88D0A0,
        tertiaryAccent = 0xFFAEC2FF, warmAccent = 0xFFE4CF77, guillochePrimary = 0xFFFFAB86,
        guillocheSecondary = 0xFF88D0A0, guillocheHighlight = 0xFFAEC2FF, glowPrimary = 0xFFFFAB86,
        glowSecondary = 0xFF88D0A0, progress = 0xFFFFAB86, progressTrack = 0xFF393C2D,
        textPrimary = 0xFFE9F3DC, textSecondary = 0xFFAEC4AD, textTertiary = 0xFF8AA38E,
        border = 0xFF586E5B, borderHighlight = 0xFF1E3425, navigationSelected = 0xFFFFAB86,
        navAccent = 0xFFFFAB86, mastheadAccent = 0xFFFFAB86, readerOverlayAccent = 0xFFFFAB86,
        accentProgress = 0xFFFEAF8B, accentStreak = 0xFFE4CF77, accentDiscovery = 0xFF88D0A0,
        accentAnnotation = 0xFFAEC2FF,
    ),
    FolioArtDirection(
        id = "ocean", light = true,
        backgroundBase = 0xFFD6F0EF, backgroundSecondary = 0xFFD5E8F3, surface = 0xFFEDFBF8,
        paperSurface = 0xFFE6F7F4, referenceSurface = 0xFFD0E9ED, chromeSurface = 0xFFBBDDE3,
        surfaceElevated = 0xFFF9FFFF, primaryAccent = 0xFFA32E50, secondaryAccent = 0xFF006B84,
        tertiaryAccent = 0xFF665493, warmAccent = 0xFF817027, guillochePrimary = 0xFFA32E50,
        guillocheSecondary = 0xFF006B84, guillocheHighlight = 0xFF665493, glowPrimary = 0xFFA32E50,
        glowSecondary = 0xFF006B84, progress = 0xFFA32E50, progressTrack = 0xFFD0D9DC,
        textPrimary = 0xFF123039, textSecondary = 0xFF46626A, textTertiary = 0xFF66848A,
        border = 0xFFA7BABB, borderHighlight = 0xFFF3FCFA, navigationSelected = 0xFFA32E50,
        navAccent = 0xFFA32E50, mastheadAccent = 0xFFA32E50, readerOverlayAccent = 0xFFA32E50,
        accentProgress = 0xFF9A2E4F, accentStreak = 0xFF6D642A, accentDiscovery = 0xFF006B84,
        accentAnnotation = 0xFF665493,
    ),
    FolioArtDirection(
        id = "ocean", light = false,
        backgroundBase = 0xFF06252D, backgroundSecondary = 0xFF10353F, surface = 0xFF153D48,
        paperSurface = 0xFF1E3942, referenceSurface = 0xFF123B4B, chromeSurface = 0xFF1C4C59,
        surfaceElevated = 0xFF26525F, primaryAccent = 0xFFFF9EA9, secondaryAccent = 0xFF50DAE5,
        tertiaryAccent = 0xFFC1BEFF, warmAccent = 0xFFE9D18B, guillochePrimary = 0xFFFF9EA9,
        guillocheSecondary = 0xFF50DAE5, guillocheHighlight = 0xFFC1BEFF, glowPrimary = 0xFFFF9EA9,
        glowSecondary = 0xFF50DAE5, progress = 0xFFFF9EA9, progressTrack = 0xFF333B43,
        textPrimary = 0xFFE4F8F7, textSecondary = 0xFFA5CDD0, textTertiary = 0xFF79A8B4,
        border = 0xFF4D6F77, borderHighlight = 0xFF12343D, navigationSelected = 0xFFFF9EA9,
        navAccent = 0xFFFF9EA9, mastheadAccent = 0xFFFF9EA9, readerOverlayAccent = 0xFFFF9EA9,
        accentProgress = 0xFFFDA3AE, accentStreak = 0xFFE9D18B, accentDiscovery = 0xFF50DAE5,
        accentAnnotation = 0xFFC1BEFF,
    ),
    FolioArtDirection(
        id = "editorial", light = true,
        backgroundBase = 0xFFF5EEE9, backgroundSecondary = 0xFFE9DED9, surface = 0xFFFFFAF6,
        paperSurface = 0xFFF1F1ED, referenceSurface = 0xFFE6DFD7, chromeSurface = 0xFFD9CBC7,
        surfaceElevated = 0xFFFFFFFC, primaryAccent = 0xFF006B67, secondaryAccent = 0xFFA02B35,
        tertiaryAccent = 0xFF6E5687, warmAccent = 0xFF8E6A22, guillochePrimary = 0xFF006B67,
        guillocheSecondary = 0xFFA02B35, guillocheHighlight = 0xFF6E5687, glowPrimary = 0xFF006B67,
        glowSecondary = 0xFFA02B35, progress = 0xFF006B67, progressTrack = 0xFFD8DED9,
        textPrimary = 0xFF2B2023, textSecondary = 0xFF675052, textTertiary = 0xFF8B7174,
        border = 0xFFBBB4B2, borderHighlight = 0xFFFFFCF9, navigationSelected = 0xFF006B67,
        navAccent = 0xFF006B67, mastheadAccent = 0xFF006B67, readerOverlayAccent = 0xFF006B67,
        accentProgress = 0xFF075F5D, accentStreak = 0xFF7A5B22, accentDiscovery = 0xFFA02B35,
        accentAnnotation = 0xFF6E5687,
    ),
    FolioArtDirection(
        id = "editorial", light = false,
        backgroundBase = 0xFF2A0F13, backgroundSecondary = 0xFF3C1A20, surface = 0xFF49252B,
        paperSurface = 0xFF3B252B, referenceSurface = 0xFF42322F, chromeSurface = 0xFF563137,
        surfaceElevated = 0xFF5A3339, primaryAccent = 0xFF7FE1BF, secondaryAccent = 0xFFFF8984,
        tertiaryAccent = 0xFFCCB8F3, warmAccent = 0xFFE8C77C, guillochePrimary = 0xFF7FE1BF,
        guillocheSecondary = 0xFFFF8984, guillocheHighlight = 0xFFCCB8F3, glowPrimary = 0xFF7FE1BF,
        glowSecondary = 0xFFFF8984, progress = 0xFF7FE1BF, progressTrack = 0xFF393532,
        textPrimary = 0xFFFCEDE7, textSecondary = 0xFFD4B4B2, textTertiary = 0xFFAC8C8E,
        border = 0xFF795B5E, borderHighlight = 0xFF3E1F25, navigationSelected = 0xFF7FE1BF,
        navAccent = 0xFF7FE1BF, mastheadAccent = 0xFF7FE1BF, readerOverlayAccent = 0xFF7FE1BF,
        accentProgress = 0xFF86E2C1, accentStreak = 0xFFE8C77C, accentDiscovery = 0xFFFF8984,
        accentAnnotation = 0xFFCCB8F3,
    ),
    FolioArtDirection(
        id = "blush", light = true,
        backgroundBase = 0xFFFBE2EC, backgroundSecondary = 0xFFEED6EA, surface = 0xFFFFF1F7,
        paperSurface = 0xFFFAE8F1, referenceSurface = 0xFFF0DBE7, chromeSurface = 0xFFE8C7DD,
        surfaceElevated = 0xFFFFF9FC, primaryAccent = 0xFF006D6F, secondaryAccent = 0xFFA52977,
        tertiaryAccent = 0xFF654BA0, warmAccent = 0xFFA04B20, guillochePrimary = 0xFF006D6F,
        guillocheSecondary = 0xFFA52977, guillocheHighlight = 0xFF654BA0, glowPrimary = 0xFF006D6F,
        glowSecondary = 0xFFA52977, progress = 0xFF006D6F, progressTrack = 0xFFDDD4DD,
        textPrimary = 0xFF321F30, textSecondary = 0xFF70536A, textTertiary = 0xFF95738D,
        border = 0xFFBDAEB7, borderHighlight = 0xFFFFF6FA, navigationSelected = 0xFF006D6F,
        navAccent = 0xFF006D6F, mastheadAccent = 0xFF006D6F, readerOverlayAccent = 0xFF006D6F,
        accentProgress = 0xFF086065, accentStreak = 0xFF9C4921, accentDiscovery = 0xFFA52977,
        accentAnnotation = 0xFF654BA0,
    ),
    FolioArtDirection(
        id = "blush", light = false,
        backgroundBase = 0xFF280D35, backgroundSecondary = 0xFF331844, surface = 0xFF43214C,
        paperSurface = 0xFF391D35, referenceSurface = 0xFF3D2044, chromeSurface = 0xFF51295A,
        surfaceElevated = 0xFF57325E, primaryAccent = 0xFF7DE3D5, secondaryAccent = 0xFFFF7BD4,
        tertiaryAccent = 0xFFC5ACFF, warmAccent = 0xFFFFB381, guillochePrimary = 0xFF7DE3D5,
        guillocheSecondary = 0xFFFF7BD4, guillocheHighlight = 0xFFC5ACFF, glowPrimary = 0xFF7DE3D5,
        glowSecondary = 0xFFFF7BD4, progress = 0xFF7DE3D5, progressTrack = 0xFF373452,
        textPrimary = 0xFFFCEAF7, textSecondary = 0xFFDAB6D4, textTertiary = 0xFFAE88B3,
        border = 0xFF75577A, borderHighlight = 0xFF391C41, navigationSelected = 0xFF7DE3D5,
        navAccent = 0xFF7DE3D5, mastheadAccent = 0xFF7DE3D5, readerOverlayAccent = 0xFF7DE3D5,
        accentProgress = 0xFF85E3D7, accentStreak = 0xFFFFB381, accentDiscovery = 0xFFFF7BD4,
        accentAnnotation = 0xFFC5ACFF,
    ),
    FolioArtDirection(
        id = "acid", light = true,
        backgroundBase = 0xFFEEF5BE, backgroundSecondary = 0xFFE3EAC8, surface = 0xFFF7FCD9,
        paperSurface = 0xFFF4FFD8, referenceSurface = 0xFFE4ECC4, chromeSurface = 0xFFD5E2A6,
        surfaceElevated = 0xFFFEFFF0, primaryAccent = 0xFF9B2F8C, secondaryAccent = 0xFF315B9F,
        tertiaryAccent = 0xFF5C7115, warmAccent = 0xFFA34919, guillochePrimary = 0xFF9B2F8C,
        guillocheSecondary = 0xFF315B9F, guillocheHighlight = 0xFF5C7115, glowPrimary = 0xFF9B2F8C,
        glowSecondary = 0xFF315B9F, progress = 0xFF9B2F8C, progressTrack = 0xFFE4DDB8,
        textPrimary = 0xFF25300D, textSecondary = 0xFF56623B, textTertiary = 0xFF7B8655,
        border = 0xFFB4BB98, borderHighlight = 0xFFFAFDE6, navigationSelected = 0xFF9B2F8C,
        navAccent = 0xFF9B2F8C, mastheadAccent = 0xFF9B2F8C, readerOverlayAccent = 0xFF9B2F8C,
        accentProgress = 0xFF942F84, accentStreak = 0xFFA34919, accentDiscovery = 0xFF315B9F,
        accentAnnotation = 0xFF596D15,
    ),
    FolioArtDirection(
        id = "acid", light = false,
        backgroundBase = 0xFF141C00, backgroundSecondary = 0xFF1C2B14, surface = 0xFF273516,
        paperSurface = 0xFF273400, referenceSurface = 0xFF213019, chromeSurface = 0xFF35452A,
        surfaceElevated = 0xFF3A4927, primaryAccent = 0xFFFF8AD5, secondaryAccent = 0xFF83BFFF,
        tertiaryAccent = 0xFFDAED75, warmAccent = 0xFFFFA66B, guillochePrimary = 0xFFFF8AD5,
        guillocheSecondary = 0xFF83BFFF, guillocheHighlight = 0xFFDAED75, glowPrimary = 0xFFFF8AD5,
        glowSecondary = 0xFF83BFFF, progress = 0xFFFF8AD5, progressTrack = 0xFF3E3026,
        textPrimary = 0xFFF1F9D5, textSecondary = 0xFFC2CFA1, textTertiary = 0xFF95A875,
        border = 0xFF5E6A4A, borderHighlight = 0xFF212D13, navigationSelected = 0xFFFF8AD5,
        navAccent = 0xFFFF8AD5, mastheadAccent = 0xFFFF8AD5, readerOverlayAccent = 0xFFFF8AD5,
        accentProgress = 0xFFFE91D5, accentStreak = 0xFFFFA66B, accentDiscovery = 0xFF83BFFF,
        accentAnnotation = 0xFFDAED75,
    ),
    FolioArtDirection(
        id = "ceramic", light = true,
        backgroundBase = 0xFFE2E9FF, backgroundSecondary = 0xFFE1E7F1, surface = 0xFFF5F8FF,
        paperSurface = 0xFFF4F7FF, referenceSurface = 0xFFD8E4FA, chromeSurface = 0xFFD1DFFF,
        surfaceElevated = 0xFFFCFEFF, primaryAccent = 0xFF8D6000, secondaryAccent = 0xFF2549B8,
        tertiaryAccent = 0xFF975367, warmAccent = 0xFF316D68, guillochePrimary = 0xFF8D6000,
        guillocheSecondary = 0xFF2549B8, guillocheHighlight = 0xFF975367, glowPrimary = 0xFF8D6000,
        glowSecondary = 0xFF2549B8, progress = 0xFF8D6000, progressTrack = 0xFFD8D9E0,
        textPrimary = 0xFF202B45, textSecondary = 0xFF4D6085, textTertiary = 0xFF7284A2,
        border = 0xFFB1B6C3, borderHighlight = 0xFFF8FAFF, navigationSelected = 0xFF8D6000,
        navAccent = 0xFF8D6000, mastheadAccent = 0xFF8D6000, readerOverlayAccent = 0xFF8D6000,
        accentProgress = 0xFF7E5909, accentStreak = 0xFF316C68, accentDiscovery = 0xFF2549B8,
        accentAnnotation = 0xFF8D5064,
    ),
    FolioArtDirection(
        id = "ceramic", light = false,
        backgroundBase = 0xFF0F1B37, backgroundSecondary = 0xFF192D4A, surface = 0xFF243754,
        paperSurface = 0xFF202F4B, referenceSurface = 0xFF243B61, chromeSurface = 0xFF2C446C,
        surfaceElevated = 0xFF354B6A, primaryAccent = 0xFFF9CE67, secondaryAccent = 0xFF8BA9FF,
        tertiaryAccent = 0xFFEFA2BC, warmAccent = 0xFF8FDBC8, guillochePrimary = 0xFFF9CE67,
        guillocheSecondary = 0xFF8BA9FF, guillocheHighlight = 0xFFEFA2BC, glowPrimary = 0xFFF9CE67,
        glowSecondary = 0xFF8BA9FF, progress = 0xFFF9CE67, progressTrack = 0xFF393B40,
        textPrimary = 0xFFECF2FF, textSecondary = 0xFFB4C6E5, textTertiary = 0xFF8D9FBE,
        border = 0xFF5A6982, borderHighlight = 0xFF1F2F47, navigationSelected = 0xFFF9CE67,
        navAccent = 0xFFF9CE67, mastheadAccent = 0xFFF9CE67, readerOverlayAccent = 0xFFF9CE67,
        accentProgress = 0xFFF8D070, accentStreak = 0xFF8FDBC8, accentDiscovery = 0xFF8BA9FF,
        accentAnnotation = 0xFFEFA2BC,
    ),
    FolioArtDirection(
        id = "copper", light = true,
        backgroundBase = 0xFFDCE9E6, backgroundSecondary = 0xFFDBE3D2, surface = 0xFFEDF4E9,
        paperSurface = 0xFFF5E3C7, referenceSurface = 0xFFD6E9E4, chromeSurface = 0xFFC3DCD4,
        surfaceElevated = 0xFFF8FCF1, primaryAccent = 0xFFA24B3C, secondaryAccent = 0xFF2D6C61,
        tertiaryAccent = 0xFF637128, warmAccent = 0xFF5A4D9A, guillochePrimary = 0xFFA24B3C,
        guillocheSecondary = 0xFF2D6C61, guillocheHighlight = 0xFF637128, glowPrimary = 0xFFA24B3C,
        glowSecondary = 0xFF2D6C61, progress = 0xFFA24B3C, progressTrack = 0xFFD5D6D2,
        textPrimary = 0xFF30271F, textSecondary = 0xFF6D5C4F, textTertiary = 0xFF8A7966,
        border = 0xFFB1B2A8, borderHighlight = 0xFFF3F8F1, navigationSelected = 0xFFA24B3C,
        navAccent = 0xFFA24B3C, mastheadAccent = 0xFFA24B3C, readerOverlayAccent = 0xFFA24B3C,
        accentProgress = 0xFF8F4537, accentStreak = 0xFF5A4D9A, accentDiscovery = 0xFF2D6C61,
        accentAnnotation = 0xFF5D6927,
    ),
    FolioArtDirection(
        id = "copper", light = false,
        backgroundBase = 0xFF25211F, backgroundSecondary = 0xFF2A3731, surface = 0xFF3E3731,
        paperSurface = 0xFF44312A, referenceSurface = 0xFF243D3C, chromeSurface = 0xFF3B4B44,
        surfaceElevated = 0xFF50463D, primaryAccent = 0xFFEDAF86, secondaryAccent = 0xFF7ED5C5,
        tertiaryAccent = 0xFFC0D096, warmAccent = 0xFFA9C8F4, guillochePrimary = 0xFFEDAF86,
        guillocheSecondary = 0xFF7ED5C5, guillocheHighlight = 0xFFC0D096, glowPrimary = 0xFFEDAF86,
        glowSecondary = 0xFF7ED5C5, progress = 0xFFEDAF86, progressTrack = 0xFF493B32,
        textPrimary = 0xFFF9EDDD, textSecondary = 0xFFCFBDA8, textTertiary = 0xFFA59380,
        border = 0xFF70685F, borderHighlight = 0xFF352F2A, navigationSelected = 0xFFEDAF86,
        navAccent = 0xFFEDAF86, mastheadAccent = 0xFFEDAF86, readerOverlayAccent = 0xFFEDAF86,
        accentProgress = 0xFFEEB38B, accentStreak = 0xFFA9C8F4, accentDiscovery = 0xFF7ED5C5,
        accentAnnotation = 0xFFC0D096,
    ),
    FolioArtDirection(
        id = "risograph", light = true,
        backgroundBase = 0xFFF8E8DC, backgroundSecondary = 0xFFE6DFD1, surface = 0xFFFFF4E9,
        paperSurface = 0xFFFFE7D3, referenceSurface = 0xFFDCE7F4, chromeSurface = 0xFFE6C9B1,
        surfaceElevated = 0xFFFFFBF3, primaryAccent = 0xFF1A537F, secondaryAccent = 0xFFBC391D,
        tertiaryAccent = 0xFF5D4E91, warmAccent = 0xFF716124, guillochePrimary = 0xFF1A537F,
        guillocheSecondary = 0xFFBC391D, guillocheHighlight = 0xFF5D4E91, glowPrimary = 0xFF1A537F,
        glowSecondary = 0xFFBC391D, progress = 0xFF1A537F, progressTrack = 0xFFDDD6D1,
        textPrimary = 0xFF29252B, textSecondary = 0xFF665660, textTertiary = 0xFF8B7380,
        border = 0xFFBBB2AC, borderHighlight = 0xFFFFF8F1, navigationSelected = 0xFF1A537F,
        navAccent = 0xFF1A537F, mastheadAccent = 0xFF1A537F, readerOverlayAccent = 0xFF1A537F,
        accentProgress = 0xFF1B507A, accentStreak = 0xFF716124, accentDiscovery = 0xFFB5381E,
        accentAnnotation = 0xFF5D4E91,
    ),
    FolioArtDirection(
        id = "risograph", light = false,
        backgroundBase = 0xFF071D32, backgroundSecondary = 0xFF102F43, surface = 0xFF16364D,
        paperSurface = 0xFF263B51, referenceSurface = 0xFF193C51, chromeSurface = 0xFF224D62,
        surfaceElevated = 0xFF285168, primaryAccent = 0xFFFF9564, secondaryAccent = 0xFFDAD39A,
        tertiaryAccent = 0xFF82DFD4, warmAccent = 0xFFC4B4FF, guillochePrimary = 0xFFFF9564,
        guillocheSecondary = 0xFFDAD39A, guillocheHighlight = 0xFF82DFD4, glowPrimary = 0xFFFF9564,
        glowSecondary = 0xFFDAD39A, progress = 0xFFFF9564, progressTrack = 0xFF34333B,
        textPrimary = 0xFFF4F0DE, textSecondary = 0xFFAEC9D4, textTertiary = 0xFF88A5BB,
        border = 0xFF526874, borderHighlight = 0xFF132E41, navigationSelected = 0xFFFF9564,
        navAccent = 0xFFFF9564, mastheadAccent = 0xFFFF9564, readerOverlayAccent = 0xFFFF9564,
        accentProgress = 0xFFFDA57A, accentStreak = 0xFFC4B4FF, accentDiscovery = 0xFFDAD39A,
        accentAnnotation = 0xFF82DFD4,
    ),
    FolioArtDirection(
        id = "celadon", light = true,
        backgroundBase = 0xFFD8E9E1, backgroundSecondary = 0xFFE2DDEB, surface = 0xFFF1F7EE,
        paperSurface = 0xFFE9F1E5, referenceSurface = 0xFFE8DEF0, chromeSurface = 0xFFBCD8C9,
        surfaceElevated = 0xFFFAFFF6, primaryAccent = 0xFF72408C, secondaryAccent = 0xFF236454,
        tertiaryAccent = 0xFF9A4D69, warmAccent = 0xFF8D6C21, guillochePrimary = 0xFF72408C,
        guillocheSecondary = 0xFF236454, guillocheHighlight = 0xFF9A4D69, glowPrimary = 0xFF72408C,
        glowSecondary = 0xFF236454, progress = 0xFF72408C, progressTrack = 0xFFCCD5D7,
        textPrimary = 0xFF292733, textSecondary = 0xFF5C5D6C, textTertiary = 0xFF7C7C8B,
        border = 0xFFB1B4B2, borderHighlight = 0xFFF6FAF4, navigationSelected = 0xFF72408C,
        navAccent = 0xFF72408C, mastheadAccent = 0xFF72408C, readerOverlayAccent = 0xFF72408C,
        accentProgress = 0xFF6E3E87, accentStreak = 0xFF785E25, accentDiscovery = 0xFF236454,
        accentAnnotation = 0xFF924A65,
    ),
    FolioArtDirection(
        id = "celadon", light = false,
        backgroundBase = 0xFF25102F, backgroundSecondary = 0xFF30253D, surface = 0xFF3B2047,
        paperSurface = 0xFF3D3534, referenceSurface = 0xFF323A50, chromeSurface = 0xFF493350,
        surfaceElevated = 0xFF51385D, primaryAccent = 0xFF7BDFB4, secondaryAccent = 0xFFD4A4F4,
        tertiaryAccent = 0xFFF3A0B8, warmAccent = 0xFFE2D6A0, guillochePrimary = 0xFF7BDFB4,
        guillocheSecondary = 0xFFD4A4F4, guillocheHighlight = 0xFFF3A0B8, glowPrimary = 0xFF7BDFB4,
        glowSecondary = 0xFFD4A4F4, progress = 0xFF7BDFB4, progressTrack = 0xFF343547,
        textPrimary = 0xFFF4E9F5, textSecondary = 0xFFC9B4D3, textTertiary = 0xFFA38AAC,
        border = 0xFF6D5676, borderHighlight = 0xFF321B3C, navigationSelected = 0xFF7BDFB4,
        navAccent = 0xFF7BDFB4, mastheadAccent = 0xFF7BDFB4, readerOverlayAccent = 0xFF7BDFB4,
        accentProgress = 0xFF82E0B8, accentStreak = 0xFFE2D6A0, accentDiscovery = 0xFFD4A4F4,
        accentAnnotation = 0xFFF3A0B8,
    ),
    FolioArtDirection(
        id = "mineral", light = true,
        backgroundBase = 0xFFF3EAB6, backgroundSecondary = 0xFFEADFE9, surface = 0xFFFFF9D9,
        paperSurface = 0xFFFFF6C5, referenceSurface = 0xFFE3E5EF, chromeSurface = 0xFFE5D7A3,
        surfaceElevated = 0xFFFFFEEC, primaryAccent = 0xFF6A3F9D, secondaryAccent = 0xFF7C5E00,
        tertiaryAccent = 0xFF256D82, warmAccent = 0xFFA04B43, guillochePrimary = 0xFF6A3F9D,
        guillocheSecondary = 0xFF7C5E00, guillocheHighlight = 0xFF256D82, glowPrimary = 0xFF6A3F9D,
        glowSecondary = 0xFF7C5E00, progress = 0xFF6A3F9D, progressTrack = 0xFFE3D5B3,
        textPrimary = 0xFF332B32, textSecondary = 0xFF6D5E64, textTertiary = 0xFF91808B,
        border = 0xFFBEB7A4, borderHighlight = 0xFFFFFBE6, navigationSelected = 0xFF6A3F9D,
        navAccent = 0xFF6A3F9D, mastheadAccent = 0xFF6A3F9D, readerOverlayAccent = 0xFF6A3F9D,
        accentProgress = 0xFF673E97, accentStreak = 0xFF9E4A43, accentDiscovery = 0xFF7C5E00,
        accentAnnotation = 0xFF256C81,
    ),
    FolioArtDirection(
        id = "mineral", light = false,
        backgroundBase = 0xFF1E103E, backgroundSecondary = 0xFF2C2052, surface = 0xFF362257,
        paperSurface = 0xFF30334A, referenceSurface = 0xFF352052, chromeSurface = 0xFF4B3470,
        surfaceElevated = 0xFF4B3671, primaryAccent = 0xFFF7D639, secondaryAccent = 0xFFCEACFF,
        tertiaryAccent = 0xFF79D9EC, warmAccent = 0xFFFFAB86, guillochePrimary = 0xFFF7D639,
        guillocheSecondary = 0xFFCEACFF, guillocheHighlight = 0xFF79D9EC, glowPrimary = 0xFFF7D639,
        glowSecondary = 0xFFCEACFF, progress = 0xFFF7D639, progressTrack = 0xFF45343D,
        textPrimary = 0xFFF8F1DE, textSecondary = 0xFFC7BBDD, textTertiary = 0xFFA18FBF,
        border = 0xFF6A5A7B, borderHighlight = 0xFF2E1D4A, navigationSelected = 0xFFF7D639,
        navAccent = 0xFFF7D639, mastheadAccent = 0xFFF7D639, readerOverlayAccent = 0xFFF7D639,
        accentProgress = 0xFFF7D843, accentStreak = 0xFFFFAB86, accentDiscovery = 0xFFCEACFF,
        accentAnnotation = 0xFF79D9EC,
    ),
)

internal fun folioArtDirection(id: String, light: Boolean): FolioColors =
    FOLIO_ART_DIRECTIONS.first { it.id == id && it.light == light }.toFolioColors()
