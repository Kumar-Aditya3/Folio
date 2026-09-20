package com.folio.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.folio.reader.settings.ReaderSettings

// §12.6: the multi-series/chart hue role. Borrowed from the reader's paper
// highlighters and reordered so the first six (the genre breakdown's cap) are
// pairwise distinct. Reassign or override per palette HERE; feature files never
// hardcode chart hues.
private val chartSeriesDefault: List<Color> = listOf(
    0xFFE8C84D, 0xFF4D8FC7, 0xFF5DAE5D, 0xFFD65F5F,
    0xFF9B7BC7, 0xFFD67AB5, 0xFF5DB8B8, 0xFFD4A843
).map { Color(it) }

data class FolioColors(
    val primary: Color = Color(0xFF4F46E5),
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFE0E7FF),
    val onPrimaryContainer: Color = Color(0xFF1E1B4B),
    val secondary: Color = Color(0xFF475569),
    val onSecondary: Color = Color.White,
    val secondaryContainer: Color = Color(0xFFF1F5F9),
    val onSecondaryContainer: Color = Color(0xFF0F172A),
    val tertiary: Color = Color(0xFF0D9488),
    val onTertiary: Color = Color.White,
    val tertiaryContainer: Color = Color(0xFFCCFBF1),
    val onTertiaryContainer: Color = Color(0xFF134E4A),
    val error: Color = Color(0xFFB3261E),
    val onError: Color = Color.White,
    val errorContainer: Color = Color(0xFFF9DEDC),
    val onErrorContainer: Color = Color(0xFF410E0B),
    val background: Color = Color(0xFFF8FAFC),
    val onBackground: Color = Color(0xFF0F172A),
    val surface: Color = Color.White,
    val onSurface: Color = Color(0xFF0F172A),
    val surfaceVariant: Color = Color(0xFFF1F5F9),
    val onSurfaceVariant: Color = Color(0xFF64748B),
    val surfaceContainerHighest: Color = Color(0xFFE2E8F0),
    val outline: Color = Color(0xFFCBD5E1),
    val outlineVariant: Color = Color(0xFFE2E8F0),
    val shadow: Color = Color.Black,
    val scrim: Color = Color.Black,
    val inverseSurface: Color = Color(0xFF0F172A),
    val inverseOnSurface: Color = Color(0xFFF8FAFC),
    val inversePrimary: Color = Color(0xFF818CF8),
    val statusBar: Color = Color(0xFF0F172A),
    // §12.3 semantic accent roles. The defaults only keep old callers compiling;
    // every AppPalette assigns all four explicitly (ThemeSchemeTest enforces it).
    val accentProgress: Color = primary,
    val accentStreak: Color = primary,
    val accentDiscovery: Color = tertiary,
    val accentAnnotation: Color = primary,
    // §12.6: shared multi-series/chart hues (reader highlighter palette).
    val chartSeries: List<Color> = chartSeriesDefault
)

// §15 Rule 22: LIGHT is achromatic by design — the paper look is the product.
// Its planes separate by lightness (background #FFFFFF vs surface #F2F2F2,
// ΔL* ≈ 4.5), not hue; it is in the ThemeSchemeTest allowlist for that reason.
val LightFolioColors = FolioColors(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E3FC),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F3F4),
    onSecondaryContainer = Color(0xFF202124),
    tertiary = Color(0xFF00897B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2DFDB),
    onTertiaryContainer = Color(0xFF004D40),
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF5F2120),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF202124),
    surface = Color(0xFFF2F2F2),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerHighest = Color(0xFFDEE0E4),
    outline = Color(0xFF9AA0A6),
    outlineVariant = Color(0xFFDEE0E4),
    shadow = Color.Black,
    scrim = Color.Black,
    inverseSurface = Color(0xFF202124),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF8AB4F8),
    statusBar = Color(0xFF202124),
    accentProgress = Color(0xFF0B57D0),
    accentStreak = Color(0xFFB3261E),
    accentDiscovery = Color(0xFF006C63),
    accentAnnotation = Color(0xFF7B1FA2)
)

// §15 Rule 22: DARK is achromatic by design — a true-black base cannot carry
// hue, so its planes separate by lightness (background #000000 vs surface
// #101010, ΔL* ≈ 4.7); it is in the ThemeSchemeTest allowlist for that reason.
val DarkFolioColors = FolioColors(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF174EA6),
    onPrimaryContainer = Color(0xFFD2E3FC),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF3C4043),
    onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFF78D9EC),
    onTertiary = Color(0xFF003B42),
    tertiaryContainer = Color(0xFF004D57),
    onTertiaryContainer = Color(0xFFB2EBF2),
    error = Color(0xFFEE675C),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFCE8E6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceContainerHighest = Color(0xFF282828),
    outline = Color(0xFF3C4043),
    outlineVariant = Color(0xFF282828),
    shadow = Color.Black,
    scrim = Color.Black,
    inverseSurface = Color(0xFFE8EAED),
    inverseOnSurface = Color(0xFF0A0A0A),
    inversePrimary = Color(0xFF1A73E8),
    statusBar = Color(0xFF000000),
    accentProgress = Color(0xFF42A5F5),
    accentStreak = Color(0xFFFFB300),
    accentDiscovery = Color(0xFF26C6DA),
    accentAnnotation = Color(0xFFF06292)
)

// ── LIGHT THEMES ────────────────────────────────────────────────────────

private val WarmFolioColors = LightFolioColors.copy(
    primary = Color(0xFFE08A00),
    onPrimary = Color(0xFF341D00),
    primaryContainer = Color(0xFFFFDC9B),
    onPrimaryContainer = Color(0xFF452700),
    secondary = Color(0xFFC93D77),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD6E7),
    onSecondaryContainer = Color(0xFF470021),
    tertiary = Color(0xFF007F96),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF77EDFF),
    onTertiaryContainer = Color(0xFF003B3B),
    background = Color(0xFFFFF9EC),
    onBackground = Color(0xFF3A2100),
    // Surface family deepened to ~4.6 ΔL* below background (was ~0.4, so cards
    // melted into the page). Hue/chroma preserved; ink still 12.7:1.
    surface = Color(0xFFF3EEC0),
    onSurface = Color(0xFF3A2100),
    surfaceVariant = Color(0xFFE4E8B3),
    onSurfaceVariant = Color(0xFF6B593B),
    surfaceContainerHighest = Color(0xFFDBDFA7),
    outline = Color(0xFFCB9A38),
    outlineVariant = Color(0xFFE7EBB2),
    inverseSurface = Color(0xFF3A2100),
    inverseOnSurface = Color(0xFFFFF9EC),
    inversePrimary = Color(0xFFFFC44D),
    statusBar = Color(0xFF241400),
    accentProgress = Color(0xFF005F5F),
    accentStreak = Color(0xFF9A5B00),
    accentDiscovery = Color(0xFFA82553),
    accentAnnotation = Color(0xFF5B3D8F)
)

private val MatchaFolioColors = LightFolioColors.copy(
    primary = Color(0xFF1BA625),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F58C),
    onPrimaryContainer = Color(0xFF07360F),
    secondary = Color(0xFF9ACD00),
    onSecondary = Color(0xFF223300),
    secondaryContainer = Color(0xFFE8F7A8),
    onSecondaryContainer = Color(0xFF2A3B00),
    tertiary = Color(0xFF93A5F9),
    onTertiary = Color(0xFF00332B),
    tertiaryContainer = Color(0xFFCCD8FF),
    onTertiaryContainer = Color(0xFF004038),
    background = Color(0xFFF3FBEC),
    onBackground = Color(0xFF0A2410),
    // Surface family deepened to ~4.5 ΔL* below background (was ~0.7). Hue kept.
    surface = Color(0xFFE6F0C7),
    onSurface = Color(0xFF0A2410),
    surfaceVariant = Color(0xFFE1E5B7),
    onSurfaceVariant = Color(0xFF515C3C),
    surfaceContainerHighest = Color(0xFFD7DCAB),
    outline = Color(0xFF7AB855),
    outlineVariant = Color(0xFFE6EBB9),
    inverseSurface = Color(0xFF0A2410),
    inverseOnSurface = Color(0xFFF3FBEC),
    inversePrimary = Color(0xFF7CF08A),
    statusBar = Color(0xFF041808),
    accentProgress = Color(0xFF166534),
    accentStreak = Color(0xFF92400E),
    accentDiscovery = Color(0xFF1D4ED8),
    accentAnnotation = Color(0xFF6D28D9)
)

private val ArcticFolioColors = LightFolioColors.copy(
    primary = Color(0xFF0066FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8DCFF),
    onPrimaryContainer = Color(0xFF002352),
    secondary = Color(0xFF00C2FF),
    onSecondary = Color(0xFF002430),
    secondaryContainer = Color(0xFFB0EDFF),
    onSecondaryContainer = Color(0xFF00303D),
    tertiary = Color(0xFF705000),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD791),
    onTertiaryContainer = Color(0xFF1A0060),
    background = Color(0xFFECF6FF),
    onBackground = Color(0xFF00172E),
    // Surface family deepened to ~4.5 ΔL* below background (was ~2.4). Hue kept.
    surface = Color(0xFFE0E8F9),
    onSurface = Color(0xFF00172E),
    surfaceVariant = Color(0xFFC2CAEE),
    onSurfaceVariant = Color(0xFF5F5C70),
    surfaceContainerHighest = Color(0xFFB6BEE5),
    outline = Color(0xFF4E9BC9),
    outlineVariant = Color(0xFFBCC4EB),
    inverseSurface = Color(0xFF00172E),
    inverseOnSurface = Color(0xFFECF6FF),
    inversePrimary = Color(0xFF7FC4FF),
    statusBar = Color(0xFF00101F),
    accentProgress = Color(0xFF0747A6),
    accentStreak = Color(0xFF92400E),
    accentDiscovery = Color(0xFF0C6A62),
    accentAnnotation = Color(0xFF6D28D9)
)

private val SakuraFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFF1F7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFC2DC),
    onPrimaryContainer = Color(0xFF4E0022),
    secondary = Color(0xFF8A2BE2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6CCFF),
    onSecondaryContainer = Color(0xFF2A0050),
    tertiary = Color(0xFF00864E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF99EFBD),
    onTertiaryContainer = Color(0xFF4A0028),
    background = Color(0xFFFFF1F7),
    onBackground = Color(0xFF3C001C),
    // Surface family deepened to ~4.5 ΔL* below background (was ~2.7). Hue kept.
    surface = Color(0xFFF9E1F1),
    onSurface = Color(0xFF3C001C),
    surfaceVariant = Color(0xFFEFC5E7),
    onSurfaceVariant = Color(0xFF66585B),
    surfaceContainerHighest = Color(0xFFE6B9DD),
    outline = Color(0xFFCE6A96),
    outlineVariant = Color(0xFFEBBEE2),
    inverseSurface = Color(0xFF3C001C),
    inverseOnSurface = Color(0xFFFFF1F7),
    inversePrimary = Color(0xFFFF9EC4),
    statusBar = Color(0xFF280012),
    accentProgress = Color(0xFFBE185D),
    accentStreak = Color(0xFF9A3412),
    accentDiscovery = Color(0xFF6D28D9),
    accentAnnotation = Color(0xFF0C6A62)
)

private val HoneyFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF3A2600),
    primaryContainer = Color(0xFFFFE08A),
    onPrimaryContainer = Color(0xFF3D2A00),
    secondary = Color(0xFFF57C00),
    onSecondary = Color(0xFF2E1400),
    secondaryContainer = Color(0xFFFFDCA8),
    onSecondaryContainer = Color(0xFF412000),
    tertiary = Color(0xFF007AA9),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF61EEFF),
    onTertiaryContainer = Color(0xFF3E1000),
    background = Color(0xFFFFF6D8),
    onBackground = Color(0xFF332200),
    // Surface family deepened to ~4.5 ΔL* below background (was ~1.4). Hue kept.
    surface = Color(0xFFEEECB4),
    onSurface = Color(0xFF332200),
    surfaceVariant = Color(0xFFDBE3A9),
    onSurfaceVariant = Color(0xFF69603A),
    surfaceContainerHighest = Color(0xFFD1DA9E),
    outline = Color(0xFFB8912A),
    outlineVariant = Color(0xFFE2EBAE),
    inverseSurface = Color(0xFF332200),
    inverseOnSurface = Color(0xFFFFF6D8),
    inversePrimary = Color(0xFFFFD54F),
    statusBar = Color(0xFF1F1400),
    accentProgress = Color(0xFF1D4ED8),
    accentStreak = Color(0xFF9C4907),
    accentDiscovery = Color(0xFF0F766E),
    accentAnnotation = Color(0xFF7C3AED)
)

private val MossFolioColors = LightFolioColors.copy(
    primary = Color(0xFF00A65E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF98F5C8),
    onPrimaryContainer = Color(0xFF003520),
    secondary = Color(0xFF76C400),
    onSecondary = Color(0xFF1B2E00),
    secondaryContainer = Color(0xFFDCF5AE),
    onSecondaryContainer = Color(0xFF223A00),
    tertiary = Color(0xFFB29CF2),
    onTertiary = Color(0xFF00332A),
    tertiaryContainer = Color(0xFFE7D2FF),
    onTertiaryContainer = Color(0xFF003D32),
    background = Color(0xFFF1FFF5),
    onBackground = Color(0xFF002115),
    // Surface family deepened to ~4.7 ΔL* below background (was ~1.3). Hue kept.
    surface = Color(0xFFD7F5E6),
    onSurface = Color(0xFF002115),
    surfaceVariant = Color(0xFFCDEAE0),
    onSurfaceVariant = Color(0xFF4A6959),
    surfaceContainerHighest = Color(0xFFC1E1D6),
    outline = Color(0xFF5AA67C),
    outlineVariant = Color(0xFFCAEBE0),
    inverseSurface = Color(0xFF002115),
    inverseOnSurface = Color(0xFFF1FFF5),
    inversePrimary = Color(0xFF6BF0AC),
    statusBar = Color(0xFF00160E),
    accentProgress = Color(0xFF14532D),
    accentStreak = Color(0xFF9C4907),
    accentDiscovery = Color(0xFF1D4ED8),
    accentAnnotation = Color(0xFF6D28D9)
)

// DAWN — Dusk's light face: violet-washed paper under a pink-tinted ground.
// The pair shares a family (violet ink, magenta accents); the paper keeps the
// morning side while Dusk keeps the night.
private val DawnFolioColors = LightFolioColors.copy(
    primary = Color(0xFF7C3AED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4D4FF),
    onPrimaryContainer = Color(0xFF25005F),
    secondary = Color(0xFFD9579A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD6E8),
    onSecondaryContainer = Color(0xFF3D0030),
    tertiary = Color(0xFF7B7337),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADE9B),
    onTertiaryContainer = Color(0xFF004D40),
    background = Color(0xFFFAF4F9),
    onBackground = Color(0xFF2B1240),
    // Surface ladder rebuilt: was inverted (surfaceVariant sat *above* surface)
    // and ran ~8.5 ΔL* deep. Now a clean descending violet ramp ~4.7/8/11.6 ΔL*
    // below background, hue/chroma preserved; ink still 13.6:1.
    surface = Color(0xFFEDE5F6),
    onSurface = Color(0xFF2B1240),
    surfaceVariant = Color(0xFFE7DAF4),
    onSurfaceVariant = Color(0xFF5E5170),
    surfaceContainerHighest = Color(0xFFDCD0EA),
    outline = Color(0xFF9A86B8),
    outlineVariant = Color(0xFFDCD0EA),
    inverseSurface = Color(0xFF2B1240),
    inverseOnSurface = Color(0xFFFAF4F9),
    inversePrimary = Color(0xFFB39DFF),
    statusBar = Color(0xFF180A28),
    accentProgress = Color(0xFF5B21B6),
    accentStreak = Color(0xFF9A3412),
    accentDiscovery = Color(0xFF115E59),
    accentAnnotation = Color(0xFFB31656)
)

// SILVER — Graphite's light face: zero hue, maximum legibility on paper-grey.
// Achromatic by design (ThemeSchemeTest allowlist); its planes separate by
// lightness (background #F1F2F4 vs surface #E2E4E8, ΔL* ≈ 4.9), mirroring
// Graphite's own ladder from the other end. Accents stay desaturated on
// purpose — on a neutral field a little colour goes far.
private val SilverFolioColors = LightFolioColors.copy(
    primary = Color(0xFF43464B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3D6DA),
    onPrimaryContainer = Color(0xFF26282C),
    secondary = Color(0xFF5F6469),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E3E7),
    onSecondaryContainer = Color(0xFF1E2024),
    tertiary = Color(0xFF007881),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF79EEF7),
    onTertiaryContainer = Color(0xFF10181E),
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF1B1D20),
    surface = Color(0xFFE2E4E8),
    onSurface = Color(0xFF1B1D20),
    surfaceVariant = Color(0xFFD8DBE0),
    onSurfaceVariant = Color(0xFF5C6169),
    surfaceContainerHighest = Color(0xFFC9CCD2),
    outline = Color(0xFF9AA0A8),
    outlineVariant = Color(0xFFC9CCD2),
    inverseSurface = Color(0xFF1B1D20),
    inverseOnSurface = Color(0xFFF1F2F4),
    inversePrimary = Color(0xFFC3C8CE),
    statusBar = Color(0xFF141518),
    accentProgress = Color(0xFF35618E),
    accentStreak = Color(0xFF6E5522),
    accentDiscovery = Color(0xFF3F6B45),
    accentAnnotation = Color(0xFF8A4C64)
)

// ── DARK THEMES ─────────────────────────────────────────────────────────

private val DuskFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFB45CFF),
    onPrimary = Color(0xFF26004D),
    primaryContainer = Color(0xFF4A1894),
    onPrimaryContainer = Color(0xFFEED4FF),
    secondary = Color(0xFFFF4FA3),
    onSecondary = Color(0xFF3D0020),
    secondaryContainer = Color(0xFF6E0A3C),
    onSecondaryContainer = Color(0xFFFFD0E6),
    tertiary = Color(0xFFD9D47D),
    onTertiary = Color(0xFF00303A),
    tertiaryContainer = Color(0xFF4E4E03),
    onTertiaryContainer = Color(0xFFBFFAFF),
    background = Color(0xFF150036),
    onBackground = Color(0xFFF0DCFF),
    surface = Color(0xFF2C0847),
    onSurface = Color(0xFFF0DCFF),
    surfaceVariant = Color(0xFF4A1260),
    onSurfaceVariant = Color(0xFFC4A2F0),
    surfaceContainerHighest = Color(0xFF611C7C),
    outline = Color(0xFF6A3AAE),
    outlineVariant = Color(0xFF611C7C),
    inverseSurface = Color(0xFFF0DCFF),
    inverseOnSurface = Color(0xFF210847),
    inversePrimary = Color(0xFF6A00BF),
    statusBar = Color(0xFF0A001C),
    accentProgress = Color(0xFF00E5FF),
    accentStreak = Color(0xFFFF4FA3),
    accentDiscovery = Color(0xFFFFB74D),
    accentAnnotation = Color(0xFF69F0AE)
)

private val EspressoFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFFA245),
    onPrimary = Color(0xFF3D1E00),
    primaryContainer = Color(0xFF8A4700),
    onPrimaryContainer = Color(0xFFFFE0BC),
    secondary = Color(0xFFFF6E5A),
    onSecondary = Color(0xFF3C0900),
    secondaryContainer = Color(0xFF8A2410),
    onSecondaryContainer = Color(0xFFFFD8CE),
    tertiary = Color(0xFF00F1FF),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF00596F),
    onTertiaryContainer = Color(0xFFFFF0B8),
    background = Color(0xFF1C0E04),
    onBackground = Color(0xFFFFE6CC),
    surface = Color(0xFF2A1C08),
    onSurface = Color(0xFFFFE6CC),
    surfaceVariant = Color(0xFF3A3010),
    onSurfaceVariant = Color(0xFFD8AC7C),
    surfaceContainerHighest = Color(0xFF4E4016),
    outline = Color(0xFF7A4A26),
    outlineVariant = Color(0xFF4E4016),
    inverseSurface = Color(0xFFFFE6CC),
    inverseOnSurface = Color(0xFF2A1608),
    inversePrimary = Color(0xFFB25A00),
    statusBar = Color(0xFF0E0602),
    accentProgress = Color(0xFF4DB6AC),
    accentStreak = Color(0xFFFF6E5A),
    accentDiscovery = Color(0xFFFF5C8A),
    accentAnnotation = Color(0xFFD7B377)
)

private val MidnightFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF3D8BFF),
    onPrimary = Color(0xFF001634),
    primaryContainer = Color(0xFF0A3A82),
    onPrimaryContainer = Color(0xFFCFE3FF),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF002C33),
    secondaryContainer = Color(0xFF00505C),
    onSecondaryContainer = Color(0xFFBDF7FF),
    tertiary = Color(0xFFB1792A),
    onTertiary = Color(0xFF15004D),
    tertiaryContainer = Color(0xFF694308),
    onTertiaryContainer = Color(0xFFDED2FF),
    background = Color(0xFF001038),
    onBackground = Color(0xFFDCEAFF),
    surface = Color(0xFF0A1152),
    onSurface = Color(0xFFDCEAFF),
    surfaceVariant = Color(0xFF181466),
    onSurfaceVariant = Color(0xFF9FC0F0),
    surfaceContainerHighest = Color(0xFF231E7E),
    outline = Color(0xFF3A62AE),
    outlineVariant = Color(0xFF231E7E),
    inverseSurface = Color(0xFFDCEAFF),
    inverseOnSurface = Color(0xFF0A1E52),
    inversePrimary = Color(0xFF0047A3),
    statusBar = Color(0xFF00081F),
    accentProgress = Color(0xFF00B8D9),
    accentStreak = Color(0xFFFFB74D),
    accentDiscovery = Color(0xFFB39DFF),
    accentAnnotation = Color(0xFFF06292)
)

// ── Removed with the light/dark pairing: OCEAN and GRAPE folded into their
// nearest surviving families (Peacock, Dusk); their persisted ids are remapped
// in AppPalette.byId.

// §15.4.1 GRAPHITE — the deliberate opposite of the saturated palettes: zero hue,
// maximum legibility. In the ThemeSchemeTest allowlist; planes separate by lightness
// (background #17181A vs surface #1F2124, ΔL* ≈ 4.4). Accents are desaturated on
// purpose — on a neutral field a little colour goes far.
private val GraphiteFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFE8EAED),
    onPrimary = Color(0xFF17181A),
    primaryContainer = Color(0xFF3C4043),
    onPrimaryContainer = Color(0xFFE8EAED),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color(0xFF17181A),
    secondaryContainer = Color(0xFF35383D),
    onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFF33B3BC),
    onTertiary = Color(0xFF10181E),
    tertiaryContainer = Color(0xFF005861),
    onTertiaryContainer = Color(0xFFD6E8F2),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFF5F6F7),
    surface = Color(0xFF1F2124),
    onSurface = Color(0xFFF5F6F7),
    surfaceVariant = Color(0xFF2A2D31),
    onSurfaceVariant = Color(0xFFA8AEB5),
    surfaceContainerHighest = Color(0xFF35383D),
    outline = Color(0xFF3E4247),
    outlineVariant = Color(0xFF2A2D31),
    inverseSurface = Color(0xFFF5F6F7),
    inverseOnSurface = Color(0xFF1F2124),
    inversePrimary = Color(0xFF43464B),
    statusBar = Color(0xFF101114),
    accentProgress = Color(0xFF7FB0D9),
    accentStreak = Color(0xFFE0C070),
    accentDiscovery = Color(0xFF8FBF9F),
    accentAnnotation = Color(0xFFC89BB5)
)

// §15.4.2 BLOSSOM — true black + one warm rose accent family. The true-black
// background cannot carry hue, so it is in the ThemeSchemeTest allowlist; the
// planes separate by lightness (ΔL* ≈ 5.1) and surface stays warm-shifted per spec.
private val BlossomFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFF8A0B4),
    onPrimary = Color(0xFF2B0A14),
    primaryContainer = Color(0xFF6E2438),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFD9A8B8),
    onSecondary = Color(0xFF33101C),
    secondaryContainer = Color(0xFF4A2530),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFF58AD7D),
    onTertiary = Color(0xFF2A0E38),
    tertiaryContainer = Color(0xFF005830),
    onTertiaryContainer = Color(0xFFE4CCFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF7EFF2),
    surface = Color(0xFF141013),
    onSurface = Color(0xFFF7EFF2),
    surfaceVariant = Color(0xFF241C21),
    onSurfaceVariant = Color(0xFFB9A6AE),
    surfaceContainerHighest = Color(0xFF33272E),
    outline = Color(0xFF45353D),
    outlineVariant = Color(0xFF241C21),
    inverseSurface = Color(0xFFF7EFF2),
    inverseOnSurface = Color(0xFF141013),
    inversePrimary = Color(0xFFB0526C),
    statusBar = Color(0xFF06030A),
    accentProgress = Color(0xFFFFC2D1),
    accentStreak = Color(0xFFF0C088),
    accentDiscovery = Color(0xFFE8748C),
    accentAnnotation = Color(0xFFC9A0D8),
    chartSeries = listOf(
        0xFFF8A0B4, 0xFF8FB8E8, 0xFFA8D8A8, 0xFFE8C87D,
        0xFFC3A6E8, 0xFF7FD4C8, 0xFFE89A7D, 0xFFC9A9C4
    ).map { Color(it) }
)

private val EmberFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF6D1A),
    onPrimary = Color(0xFF3D1000),
    primaryContainer = Color(0xFF8A2E00),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFFF2E5B),
    onSecondary = Color(0xFF3D0010),
    secondaryContainer = Color(0xFF840A28),
    onSecondaryContainer = Color(0xFFFFCEDA),
    tertiary = Color(0xFF00E4EE),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFF005A62),
    onTertiaryContainer = Color(0xFFFFF0B8),
    background = Color(0xFF1E0702),
    onBackground = Color(0xFFFFE0CC),
    surface = Color(0xFF2E1304),
    onSurface = Color(0xFFFFE0CC),
    surfaceVariant = Color(0xFF40260A),
    onSurfaceVariant = Color(0xFFE0A480),
    surfaceContainerHighest = Color(0xFF56320C),
    outline = Color(0xFF8A3A1E),
    outlineVariant = Color(0xFF56320C),
    inverseSurface = Color(0xFFFFE0CC),
    inverseOnSurface = Color(0xFF2E0E04),
    inversePrimary = Color(0xFFB73A00),
    statusBar = Color(0xFF100301),
    accentProgress = Color(0xFFFFD54F),
    accentStreak = Color(0xFFFF2E5B),
    accentDiscovery = Color(0xFFFF8A65),
    accentAnnotation = Color(0xFFB39DDB)
)

// ── FUN / UNUSUAL THEMES ───────────────────────────────────────────────

private val SynthwaveFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF2FD6),
    onPrimary = Color(0xFF38002E),
    primaryContainer = Color(0xFF5C0A4E),
    onPrimaryContainer = Color(0xFFFFD0F2),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF002A30),
    secondaryContainer = Color(0xFF084854),
    onSecondaryContainer = Color(0xFFC4F8FF),
    tertiary = Color(0xFF618E34),
    onTertiary = Color(0xFF1A0844),
    tertiaryContainer = Color(0xFF345413),
    onTertiaryContainer = Color(0xFFDCD0FF),
    background = Color(0xFF0D0221),
    onBackground = Color(0xFFF0E6FF),
    surface = Color(0xFF1F0535),
    onSurface = Color(0xFFF0E6FF),
    surfaceVariant = Color(0xFF371048),
    onSurfaceVariant = Color(0xFFA88CD0),
    surfaceContainerHighest = Color(0xFF47185C),
    outline = Color(0xFF44267E),
    outlineVariant = Color(0xFF47185C),
    inverseSurface = Color(0xFFF0E6FF),
    inverseOnSurface = Color(0xFF150535),
    inversePrimary = Color(0xFFC0109E),
    statusBar = Color(0xFF070112),
    accentProgress = Color(0xFF00E5FF),
    accentStreak = Color(0xFFFF6E9C),
    accentDiscovery = Color(0xFFB388FF),
    accentAnnotation = Color(0xFF69F0AE)
)

private val BubblegumFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFF4FA3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD0E6),
    onPrimaryContainer = Color(0xFF5C0A34),
    secondary = Color(0xFF2ECFA0),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFFC8F6E6),
    onSecondaryContainer = Color(0xFF0A3A2C),
    tertiary = Color(0xFF7ED587),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFFABEDAF),
    onTertiaryContainer = Color(0xFF4A3400),
    background = Color(0xFFFFF7FA),
    onBackground = Color(0xFF4A1033),
    // Surface family lifted from ~7.0 to ~4.7 ΔL* below background so the pink
    // cards layer without dropping into a second, muddier tone. Hue kept.
    surface = Color(0xFFFFE4F5),
    onSurface = Color(0xFF4A1033),
    surfaceVariant = Color(0xFFF6DAF1),
    onSurfaceVariant = Color(0xFF694F5C),
    surfaceContainerHighest = Color(0xFFEECEE8),
    outline = Color(0xFFC591AD),
    outlineVariant = Color(0xFFEECEE8),
    inverseSurface = Color(0xFF4A1033),
    inverseOnSurface = Color(0xFFFFF7FA),
    inversePrimary = Color(0xFFFF9EC4),
    statusBar = Color(0xFF2E0A20),
    accentProgress = Color(0xFF00695C),
    accentStreak = Color(0xFF9B4807),
    accentDiscovery = Color(0xFF6A3ABF),
    accentAnnotation = Color(0xFF01579B)
)

// ── Removed with the light/dark pairing: ACID and LAVA folded into their
// nearest surviving families (Toxic Lime, Ember); their persisted ids are
// remapped in AppPalette.byId.

private val SherbetFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFF7A4D),
    onPrimary = Color(0xFF3E1400),
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF5C1E00),
    secondary = Color(0xFF2ED9A3),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFFC4F8E4),
    onSecondaryContainer = Color(0xFF0A4430),
    tertiary = Color(0xFF1BCCCB),
    onTertiary = Color(0xFF00304D),
    tertiaryContainer = Color(0xFF62F2EF),
    onTertiaryContainer = Color(0xFF003A58),
    background = Color(0xFFFFFBF6),
    onBackground = Color(0xFF4A2C1A),
    surface = Color(0xFFFFECE3),
    onSurface = Color(0xFF4A2C1A),
    surfaceVariant = Color(0xFFF4DBD6),
    onSurfaceVariant = Color(0xFF69574A),
    surfaceContainerHighest = Color(0xFFEBCFCA),
    outline = Color(0xFFB7A184),
    outlineVariant = Color(0xFFEBCFCA),
    inverseSurface = Color(0xFF4A2C1A),
    inverseOnSurface = Color(0xFFFFFBF6),
    inversePrimary = Color(0xFFFFB68F),
    statusBar = Color(0xFF341C0C),
    accentProgress = Color(0xFF007A5E),
    accentStreak = Color(0xFFB3300C),
    accentDiscovery = Color(0xFF026FB0),
    accentAnnotation = Color(0xFF6A1B9A)
)

// ── NEW VIVID THEMES ───────────────────────────────────────────────────

private val VaporwaveFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF5AC8),
    onPrimary = Color(0xFF3D0030),
    primaryContainer = Color(0xFF7A1460),
    onPrimaryContainer = Color(0xFFFFD2EE),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF00303A),
    secondaryContainer = Color(0xFF0A5C6E),
    onSecondaryContainer = Color(0xFFBFFAFF),
    tertiary = Color(0xFF669E48),
    onTertiary = Color(0xFF1F004D),
    tertiaryContainer = Color(0xFF2C5518),
    onTertiaryContainer = Color(0xFFE8D4FF),
    background = Color(0xFF1A0733),
    onBackground = Color(0xFFF2DCFF),
    surface = Color(0xFF310E47),
    onSurface = Color(0xFFF2DCFF),
    surfaceVariant = Color(0xFF4F1660),
    onSurfaceVariant = Color(0xFFBE9BF0),
    surfaceContainerHighest = Color(0xFF68227E),
    outline = Color(0xFF6E3AAE),
    outlineVariant = Color(0xFF68227E),
    inverseSurface = Color(0xFFF2DCFF),
    inverseOnSurface = Color(0xFF260E47),
    inversePrimary = Color(0xFFC2008A),
    statusBar = Color(0xFF0E0320),
    accentProgress = Color(0xFF00E5FF),
    accentStreak = Color(0xFFFFB74D),
    accentDiscovery = Color(0xFFD1B3FF),
    accentAnnotation = Color(0xFF69F0AE)
)

private val NeonTokyoFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF007A),
    onPrimary = Color(0xFF3D0020),
    primaryContainer = Color(0xFF82004A),
    onPrimaryContainer = Color(0xFFFFCFE4),
    secondary = Color(0xFF00FFD5),
    onSecondary = Color(0xFF003830),
    secondaryContainer = Color(0xFF006B5E),
    onSecondaryContainer = Color(0xFFB0FFF2),
    tertiary = Color(0xFF96FCBF),
    onTertiary = Color(0xFF00333D),
    tertiaryContainer = Color(0xFF00582F),
    onTertiaryContainer = Color(0xFFC8FBFF),
    background = Color(0xFF0A0018),
    onBackground = Color(0xFFE8DAFF),
    // Surface raised from ~1.7 to ~4.5 ΔL* above the near-black page so cards
    // separate without losing the midnight base. Ramp brightened in step.
    surface = Color(0xFF220039),
    onSurface = Color(0xFFE8DAFF),
    surfaceVariant = Color(0xFF330C41),
    onSurfaceVariant = Color(0xFFAE8CE0),
    surfaceContainerHighest = Color(0xFF451357),
    outline = Color(0xFF4E2A82),
    outlineVariant = Color(0xFF451357),
    inverseSurface = Color(0xFFE8DAFF),
    inverseOnSurface = Color(0xFF140026),
    inversePrimary = Color(0xFFB0005C),
    statusBar = Color(0xFF05000C),
    accentProgress = Color(0xFF00FFD5),
    accentStreak = Color(0xFFFFB300),
    accentDiscovery = Color(0xFFE040FB),
    accentAnnotation = Color(0xFFF48FB1)
)

private val HyperpopFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFF00A8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFC2EC),
    onPrimaryContainer = Color(0xFF4A0030),
    secondary = Color(0xFF6200FF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCC8FF),
    onSecondaryContainer = Color(0xFF1D0056),
    tertiary = Color(0xFF99D68F),
    onTertiary = Color(0xFF00333D),
    tertiaryContainer = Color(0xFFB3EBA9),
    onTertiaryContainer = Color(0xFF00303A),
    background = Color(0xFFFFECF8),
    onBackground = Color(0xFF33001F),
    // Surface deepened from ~3.3 to ~4.8 ΔL* below background for cleaner
    // card layering on the hot-pink page. Hue kept.
    surface = Color(0xFFFFD8F8),
    onSurface = Color(0xFF33001F),
    surfaceVariant = Color(0xFFF2D1F4),
    onSurfaceVariant = Color(0xFF694A61),
    surfaceContainerHighest = Color(0xFFE9C5EB),
    outline = Color(0xFFC94E9E),
    outlineVariant = Color(0xFFE9C5EB),
    inverseSurface = Color(0xFF33001F),
    inverseOnSurface = Color(0xFFFFECF8),
    inversePrimary = Color(0xFFFF7ADB),
    statusBar = Color(0xFF200013),
    accentProgress = Color(0xFF6200FF),
    accentStreak = Color(0xFFC2185B),
    accentDiscovery = Color(0xFF006064),
    accentAnnotation = Color(0xFF9A3412)
)

private val ToxicLimeFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFB6FF00),
    onPrimary = Color(0xFF1B2E00),
    primaryContainer = Color(0xFF4A6B00),
    onPrimaryContainer = Color(0xFFE6FFB8),
    secondary = Color(0xFF00FF9D),
    onSecondary = Color(0xFF003A24),
    secondaryContainer = Color(0xFF006B45),
    onSecondaryContainer = Color(0xFFB4FFE2),
    tertiary = Color(0xFF60BCFF),
    onTertiary = Color(0xFF3A2200),
    tertiaryContainer = Color(0xFF004F8A),
    onTertiaryContainer = Color(0xFFFFE4B0),
    background = Color(0xFF0A1200),
    onBackground = Color(0xFFE4FFCC),
    surface = Color(0xFF0C1C02),
    onSurface = Color(0xFFE4FFCC),
    surfaceVariant = Color(0xFF0E2A06),
    onSurfaceVariant = Color(0xFFA8CC74),
    surfaceContainerHighest = Color(0xFF153A0A),
    outline = Color(0xFF46621A),
    outlineVariant = Color(0xFF153A0A),
    inverseSurface = Color(0xFFE4FFCC),
    inverseOnSurface = Color(0xFF121C02),
    inversePrimary = Color(0xFF5E8400),
    statusBar = Color(0xFF050800),
    accentProgress = Color(0xFF00FF9D),
    accentStreak = Color(0xFFFFA300),
    accentDiscovery = Color(0xFFB388FF),
    accentAnnotation = Color(0xFFF48FB1)
)

private val RetroSunsetFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF6B35),
    onPrimary = Color(0xFF3D1000),
    primaryContainer = Color(0xFF8A2A00),
    onPrimaryContainer = Color(0xFFFFD8C0),
    secondary = Color(0xFFFF2E88),
    onSecondary = Color(0xFF3D001E),
    secondaryContainer = Color(0xFF820A44),
    onSecondaryContainer = Color(0xFFFFCEDF),
    tertiary = Color(0xFF00EAEB),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF005A5C),
    onTertiaryContainer = Color(0xFFFFF0BC),
    background = Color(0xFF22052E),
    onBackground = Color(0xFFFFE2CC),
    surface = Color(0xFF330A38),
    onSurface = Color(0xFFFFE2CC),
    surfaceVariant = Color(0xFF441042),
    onSurfaceVariant = Color(0xFFE4A0A8),
    surfaceContainerHighest = Color(0xFF521850),
    outline = Color(0xFF8A3A6E),
    outlineVariant = Color(0xFF521850),
    inverseSurface = Color(0xFFFFE2CC),
    inverseOnSurface = Color(0xFF300A38),
    inversePrimary = Color(0xFFB73E00),
    statusBar = Color(0xFF14031C),
    accentProgress = Color(0xFF8C9CE0),
    accentStreak = Color(0xFFFF2E88),
    accentDiscovery = Color(0xFFFFD97A),
    accentAnnotation = Color(0xFF80CBC4)
)

private val PeacockFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF00D7C4),
    onPrimary = Color(0xFF00332D),
    primaryContainer = Color(0xFF00635A),
    onPrimaryContainer = Color(0xFFB2FFF5),
    secondary = Color(0xFFFF8A00),
    onSecondary = Color(0xFF3A1C00),
    secondaryContainer = Color(0xFF7A3E00),
    onSecondaryContainer = Color(0xFFFFDEB8),
    tertiary = Color(0xFFBC60AA),
    onTertiary = Color(0xFF1E004D),
    tertiaryContainer = Color(0xFF743368),
    onTertiaryContainer = Color(0xFFE4CCFF),
    background = Color(0xFF001A24),
    onBackground = Color(0xFFC8FAFF),
    // Surface raised from ~0.8 to ~4.7 ΔL* above background — the card plane was
    // nearly indistinguishable from the deep-teal page. Ramp brightened in step.
    surface = Color(0xFF00223E),
    onSurface = Color(0xFFC8FAFF),
    surfaceVariant = Color(0xFF0E2B5A),
    onSurfaceVariant = Color(0xFF78D2E4),
    surfaceContainerHighest = Color(0xFF19396B),
    outline = Color(0xFF2A6E80),
    outlineVariant = Color(0xFF19396B),
    inverseSurface = Color(0xFFC8FAFF),
    inverseOnSurface = Color(0xFF002530),
    inversePrimary = Color(0xFF008A7C),
    statusBar = Color(0xFF000F16),
    accentProgress = Color(0xFF26C6DA),
    accentStreak = Color(0xFFFF8A00),
    accentDiscovery = Color(0xFF69F0AE),
    accentAnnotation = Color(0xFFF06292)
)

private val RainbowCandyFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFF3D7F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD0E2),
    onPrimaryContainer = Color(0xFF4D0022),
    secondary = Color(0xFF00B2FF),
    onSecondary = Color(0xFF002C40),
    secondaryContainer = Color(0xFFB8E8FF),
    onSecondaryContainer = Color(0xFF002338),
    tertiary = Color(0xFF48C387),
    onTertiary = Color(0xFF003A15),
    tertiaryContainer = Color(0xFF97EFBE),
    onTertiaryContainer = Color(0xFF00391A),
    background = Color(0xFFFFF0F6),
    onBackground = Color(0xFF38001C),
    // Surface deepened from ~2.4 to ~4.9 ΔL* below background so cards read as
    // a distinct plane on the near-white pink page. Hue kept.
    surface = Color(0xFFFFDCF3),
    onSurface = Color(0xFF38001C),
    surfaceVariant = Color(0xFFFFD4C2),
    onSurfaceVariant = Color(0xFF804C63),
    surfaceContainerHighest = Color(0xFFFFC7A8),
    outline = Color(0xFFCB6A4E),
    outlineVariant = Color(0xFFFFC7A8),
    inverseSurface = Color(0xFF38001C),
    inverseOnSurface = Color(0xFFFFE3EF),
    inversePrimary = Color(0xFFFF8FB0),
    statusBar = Color(0xFF240012),
    accentProgress = Color(0xFF01579B),
    accentStreak = Color(0xFFC2185B),
    accentDiscovery = Color(0xFF2B742E),
    accentAnnotation = Color(0xFF6A1B9A)
)

// ── CLAY / COCOA ────────────────────────────────────────────────────────────
// A new editorial pack (2026-09): a warm-neutral daylight face (Clay) paired with
// a deep-roast night face (Cocoa). Chromatic — background and surface separate by
// hue as well as lightness (§15 Rule 22) — so it is not on the achromatic
// allowlist. Terracotta/ochre/sage/plum accents give the four semantic roles real
// separation while staying inside the earthenware mood.

val ClayFolioColors = LightFolioColors.copy(
    primary = Color(0xFFB4460F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9C2),
    onPrimaryContainer = Color(0xFF3A1400),
    secondary = Color(0xFF6E5A44),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E2CE),
    onSecondaryContainer = Color(0xFF2A1E0E),
    tertiary = Color(0xFF007175),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF7BEFF2),
    onTertiaryContainer = Color(0xFF14290A),
    background = Color(0xFFF4ECDC),
    onBackground = Color(0xFF33261A),
    surface = Color(0xFFEAD7C2),
    onSurface = Color(0xFF33261A),
    surfaceVariant = Color(0xFFE0D0B8),
    onSurfaceVariant = Color(0xFF6A5636),
    surfaceContainerHighest = Color(0xFFD6C4A8),
    outline = Color(0xFFA98C68),
    outlineVariant = Color(0xFFD6C4A8),
    inverseSurface = Color(0xFF33261A),
    inverseOnSurface = Color(0xFFF4ECDC),
    inversePrimary = Color(0xFFFFB68F),
    statusBar = Color(0xFF241A10),
    accentProgress = Color(0xFF8F3408),
    accentStreak = Color(0xFF6E5000),
    accentDiscovery = Color(0xFF1E5E40),
    accentAnnotation = Color(0xFF7B3F8F),
)

val CocoaFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF9E5C),
    onPrimary = Color(0xFF3A1800),
    primaryContainer = Color(0xFF7A3A10),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFD8B48C),
    onSecondary = Color(0xFF33220E),
    secondaryContainer = Color(0xFF4A3620),
    onSecondaryContainer = Color(0xFFF0E0CC),
    tertiary = Color(0xFF00D3E4),
    onTertiary = Color(0xFF16290A),
    tertiaryContainer = Color(0xFF005A68),
    onTertiaryContainer = Color(0xFFD6E8C4),
    background = Color(0xFF1E110A),
    onBackground = Color(0xFFF0E2D2),
    surface = Color(0xFF2C1E0E),
    onSurface = Color(0xFFF0E2D2),
    surfaceVariant = Color(0xFF3C2E1A),
    onSurfaceVariant = Color(0xFFD0B896),
    surfaceContainerHighest = Color(0xFF4E3C22),
    outline = Color(0xFF7A6242),
    outlineVariant = Color(0xFF3C2E1A),
    inverseSurface = Color(0xFFF0E2D2),
    inverseOnSurface = Color(0xFF2A1A0C),
    inversePrimary = Color(0xFFB25A18),
    statusBar = Color(0xFF120A04),
    accentProgress = Color(0xFFFF9E4D),
    accentStreak = Color(0xFFFF7A9C),
    accentDiscovery = Color(0xFF6FE0A8),
    accentAnnotation = Color(0xFFD9A0E0),
)

// ── LAGOON / ABYSS ────────────────────────────────────────────────────────
// A tidewater pack (2026-09): a pale-aqua daylight face (Lagoon) paired with a
// deep bioluminescent-sea night face (Abyss). Chromatic on both sides — the
// background rides a shallower green-teal while the surface leans cyan, so the
// planes separate by hue as well as lightness (§15 Rule 22). Teal ink, coral
// counter-accent: the reef against the water.

val LagoonFolioColors = LightFolioColors.copy(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F0E8),
    onPrimaryContainer = Color(0xFF00332C),
    secondary = Color(0xFF00687F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB4E8F5),
    onSecondaryContainer = Color(0xFF00323F),
    tertiary = Color(0xFFB0445F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD4DE),
    onTertiaryContainer = Color(0xFF3E0014),
    background = Color(0xFFECFBF7),
    onBackground = Color(0xFF082A26),
    surface = Color(0xFFD6F0F2),
    onSurface = Color(0xFF082A26),
    surfaceVariant = Color(0xFFCDE8E4),
    onSurfaceVariant = Color(0xFF3D6560),
    surfaceContainerHighest = Color(0xFFC0DED9),
    outline = Color(0xFF5CA69C),
    outlineVariant = Color(0xFFC0DED9),
    inverseSurface = Color(0xFF082A26),
    inverseOnSurface = Color(0xFFECFBF7),
    inversePrimary = Color(0xFF6BE0CE),
    statusBar = Color(0xFF06201C),
    accentProgress = Color(0xFF00594E),
    accentStreak = Color(0xFFB3300C),
    accentDiscovery = Color(0xFF1565C0),
    accentAnnotation = Color(0xFF7B3F8F)
)

val AbyssFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF3DD6C4),
    onPrimary = Color(0xFF00332C),
    primaryContainer = Color(0xFF005248),
    onPrimaryContainer = Color(0xFFA8FFF2),
    secondary = Color(0xFF4DC3FF),
    onSecondary = Color(0xFF00293A),
    secondaryContainer = Color(0xFF00506E),
    onSecondaryContainer = Color(0xFFC4EEFF),
    tertiary = Color(0xFFFF8A9E),
    onTertiary = Color(0xFF3D0014),
    tertiaryContainer = Color(0xFF7A2036),
    onTertiaryContainer = Color(0xFFFFD9DF),
    background = Color(0xFF061E18),
    onBackground = Color(0xFFC8F5F0),
    surface = Color(0xFF07242C),
    onSurface = Color(0xFFC8F5F0),
    surfaceVariant = Color(0xFF0E3038),
    onSurfaceVariant = Color(0xFF8FC4C2),
    surfaceContainerHighest = Color(0xFF163E44),
    outline = Color(0xFF2E6E74),
    outlineVariant = Color(0xFF0E3038),
    inverseSurface = Color(0xFFC8F5F0),
    inverseOnSurface = Color(0xFF06201C),
    inversePrimary = Color(0xFF00897B),
    statusBar = Color(0xFF020E12),
    accentProgress = Color(0xFF3DE0C8),
    accentStreak = Color(0xFFFF7A9C),
    accentDiscovery = Color(0xFFFFC24D),
    accentAnnotation = Color(0xFFB39DFF)
)

// ── ROUGE / GARNET ──────────────────────────────────────────────────────────
// A crimson editorial pack (2026-09): a warm paper-white daylight face (Rouge)
// paired with a deep-wine night face (Garnet). Chromatic — the paper carries a
// warm-red cast while the surface warms toward rose, and Garnet's true-wine
// base warms one step to its surface, so both faces separate by hue as well as
// lightness (§15 Rule 22). Teal/gold/indigo counter-accents keep the four
// semantic roles apart against the single-hero red.

val RougeFolioColors = LightFolioColors.copy(
    primary = Color(0xFFC1121F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD6D2),
    onPrimaryContainer = Color(0xFF48000A),
    secondary = Color(0xFF9A3324),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8CE),
    onSecondaryContainer = Color(0xFF3D0A00),
    tertiary = Color(0xFF8A6D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9A8),
    onTertiaryContainer = Color(0xFF2A2000),
    background = Color(0xFFFFF4F1),
    onBackground = Color(0xFF3D0A0A),
    surface = Color(0xFFFCE4E8),
    onSurface = Color(0xFF3D0A0A),
    surfaceVariant = Color(0xFFF2D6D2),
    onSurfaceVariant = Color(0xFF6E4A46),
    surfaceContainerHighest = Color(0xFFEBC7C2),
    outline = Color(0xFFC08A84),
    outlineVariant = Color(0xFFEBC7C2),
    inverseSurface = Color(0xFF3D0A0A),
    inverseOnSurface = Color(0xFFFFF4F1),
    inversePrimary = Color(0xFFFF9A94),
    statusBar = Color(0xFF2A0606),
    accentProgress = Color(0xFF0F766E),
    accentStreak = Color(0xFFB3261E),
    accentDiscovery = Color(0xFF1D4ED8),
    accentAnnotation = Color(0xFF7B3F8F)
)

val GarnetFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF7A8C),
    onPrimary = Color(0xFF3D0011),
    primaryContainer = Color(0xFF7A1528),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE0A38C),
    onSecondary = Color(0xFF3D1A0A),
    secondaryContainer = Color(0xFF5C3020),
    onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFD4A94D),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF5C4600),
    onTertiaryContainer = Color(0xFFFFE9A8),
    background = Color(0xFF1E0810),
    onBackground = Color(0xFFF7DCE0),
    surface = Color(0xFF2C0E14),
    onSurface = Color(0xFFF7DCE0),
    surfaceVariant = Color(0xFF44161E),
    onSurfaceVariant = Color(0xFFD4A0A8),
    surfaceContainerHighest = Color(0xFF5A2028),
    outline = Color(0xFF8A4A54),
    outlineVariant = Color(0xFF44161E),
    inverseSurface = Color(0xFFF7DCE0),
    inverseOnSurface = Color(0xFF2C0E14),
    inversePrimary = Color(0xFFB03048),
    statusBar = Color(0xFF120409),
    accentProgress = Color(0xFF4DD0C0),
    accentStreak = Color(0xFFFFB3C0),
    accentDiscovery = Color(0xFFFFC24D),
    accentAnnotation = Color(0xFFB39DFF)
)

// ── CITRINE / ONYX ──────────────────────────────────────────────────────────
// A golden pack (2026-09): a lemon-cream daylight face (Citrine) paired with a
// warm gold-on-charcoal night face (Onyx). Chromatic — Citrine's warm-cream
// ground gives way to a paler chartreuse surface, and Onyx's warm near-black
// tilts olive at the surface, so neither face is a single hue at two
// lightnesses (§15 Rule 22). A violet counter-hero keeps the gold from reading
// as one note.

val CitrineFolioColors = LightFolioColors.copy(
    primary = Color(0xFF8A6D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9A0),
    onPrimaryContainer = Color(0xFF2A2000),
    secondary = Color(0xFF6E6300),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E8A8),
    onSecondaryContainer = Color(0xFF201E00),
    tertiary = Color(0xFF7B3F8F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEED4F5),
    onTertiaryContainer = Color(0xFF2E0038),
    background = Color(0xFFFFFBE8),
    onBackground = Color(0xFF322A08),
    surface = Color(0xFFEEF0C0),
    onSurface = Color(0xFF322A08),
    surfaceVariant = Color(0xFFECE6B8),
    onSurfaceVariant = Color(0xFF6A5E30),
    surfaceContainerHighest = Color(0xFFE0DAA8),
    outline = Color(0xFFB0A448),
    outlineVariant = Color(0xFFE0DAA8),
    inverseSurface = Color(0xFF322A08),
    inverseOnSurface = Color(0xFFFFFBE8),
    inversePrimary = Color(0xFFFFD54F),
    statusBar = Color(0xFF221C04),
    accentProgress = Color(0xFF1D4ED8),
    accentStreak = Color(0xFF8F4000),
    accentDiscovery = Color(0xFF0F766E),
    accentAnnotation = Color(0xFF6A1B9A)
)

val OnyxFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFF0C860),
    onPrimary = Color(0xFF2E2400),
    primaryContainer = Color(0xFF5C4A00),
    onPrimaryContainer = Color(0xFFFFE9A8),
    secondary = Color(0xFFD8C89A),
    onSecondary = Color(0xFF322A0E),
    secondaryContainer = Color(0xFF48401E),
    onSecondaryContainer = Color(0xFFF0E8C8),
    tertiary = Color(0xFF9CC7E0),
    onTertiary = Color(0xFF0A2430),
    tertiaryContainer = Color(0xFF244452),
    onTertiaryContainer = Color(0xFFD4E8F2),
    background = Color(0xFF16130C),
    onBackground = Color(0xFFF2E8CC),
    surface = Color(0xFF1C1E10),
    onSurface = Color(0xFFF2E8CC),
    surfaceVariant = Color(0xFF33301E),
    onSurfaceVariant = Color(0xFFC0B48C),
    surfaceContainerHighest = Color(0xFF454026),
    outline = Color(0xFF6E6544),
    outlineVariant = Color(0xFF33301E),
    inverseSurface = Color(0xFFF2E8CC),
    inverseOnSurface = Color(0xFF201B10),
    inversePrimary = Color(0xFFB09030),
    statusBar = Color(0xFF0C0A06),
    accentProgress = Color(0xFF6FE0C0),
    accentStreak = Color(0xFFFFD97A),
    accentDiscovery = Color(0xFF7FB0FF),
    accentAnnotation = Color(0xFFE0A0D8)
)

// ── Removed with the light/dark pairing: MIDNIGHT NEON folded into Neon
// Tokyo; its persisted id is remapped in AppPalette.byId.

enum class AppPalette(
    val id: String,
    val label: String,
    val isDark: Boolean,
    val colors: FolioColors
) {
    LIGHT("light", "Light", false, LightFolioColors),
    WARM("warm", "Warm", false, WarmFolioColors),
    SILVER("silver", "Silver", false, SilverFolioColors),
    MATCHA("matcha", "Matcha", false, MatchaFolioColors),
    ARCTIC("arctic", "Arctic", false, ArcticFolioColors),
    SAKURA("sakura", "Sakura", false, SakuraFolioColors),
    HONEY("honey", "Honey", false, HoneyFolioColors),
    MOSS("moss", "Moss", false, MossFolioColors),
    SHERBET("sherbet", "Sherbet", false, SherbetFolioColors),
    BUBBLEGUM("bubblegum", "Bubblegum", false, BubblegumFolioColors),
    DAWN("dawn", "Dawn", false, DawnFolioColors),
    HYPERPOP("hyperpop", "Hyperpop", false, HyperpopFolioColors),
    RAINBOW("rainbow", "Rainbow Candy", false, RainbowCandyFolioColors),
    CLAY("clay", "Clay", false, ClayFolioColors),
    DARK("dark", "Dark", true, DarkFolioColors),
    MIDNIGHT("midnight", "Midnight", true, MidnightFolioColors),
    DUSK("dusk", "Dusk", true, DuskFolioColors),
    ESPRESSO("espresso", "Espresso", true, EspressoFolioColors),
    EMBER("ember", "Ember", true, EmberFolioColors),
    SYNTHWAVE("synthwave", "Synthwave", true, SynthwaveFolioColors),
    VAPORWAVE("vaporwave", "Vaporwave", true, VaporwaveFolioColors),
    NEON_TOKYO("neontokyo", "Neon Tokyo", true, NeonTokyoFolioColors),
    TOXIC_LIME("toxiclime", "Toxic Lime", true, ToxicLimeFolioColors),
    RETRO_SUNSET("retrosunset", "Retro Sunset", true, RetroSunsetFolioColors),
    PEACOCK("peacock", "Peacock", true, PeacockFolioColors),
    COCOA("cocoa", "Cocoa", true, CocoaFolioColors),
    GRAPHITE("graphite", "Graphite", true, GraphiteFolioColors),
    BLOSSOM("blossom", "Blossom", true, BlossomFolioColors),
    LAGOON("lagoon", "Lagoon", false, LagoonFolioColors),
    ABYSS("abyss", "Abyss", true, AbyssFolioColors),
    ROUGE("rouge", "Rouge", false, RougeFolioColors),
    GARNET("garnet", "Garnet", true, GarnetFolioColors),
    CITRINE("citrine", "Citrine", false, CitrineFolioColors),
    ONYX("onyx", "Onyx", true, OnyxFolioColors),

    /**
     * §16 Material You. The wallpaper-derived palette: on a supported device the
     * host intercepts these ids and swaps in colors derived from
     * `dynamicLightColorScheme`/`dynamicDarkColorScheme` (see
     * [deriveSystemPalette]); the entries below are the fallbacks a device below
     * API 31 renders instead — the default pack's faces, per the degradation
     * ladder. Two entries so the pack mechanism (and flipThemeMode) treat the
     * System palette's faces exactly like every other pack's.
     */
    SYSTEM("system", "System", false, LightFolioColors),
    SYSTEM_DARK("systemdark", "System", true, DarkFolioColors);

    companion object {
        /**
         * Palettes removed when packs gained light/dark faces — near-duplicates
         * of a surviving family. Old persisted settings still name them, so
         * their ids land on the nearest survivor instead of dropping the user
         * onto LIGHT.
         */
        private val legacyIds: Map<String, AppPalette> = mapOf(
            "grape" to DUSK,
            "lava" to EMBER,
            "acid" to TOXIC_LIME,
            "ocean" to PEACOCK,
            "midnightneon" to NEON_TOKYO,
            "oled" to DARK,
        )

        fun byId(id: String): AppPalette =
            entries.firstOrNull { it.id == id } ?: legacyIds[id] ?: LIGHT
    }
}

/**
 * One theme, two faces: every pack carries a light side and a dark side, and
 * the Settings toggle applies the other face of the same pack. The two names
 * are the pair's identity — "Honey" is the same theme as "Ember", at morning
 * and at night — and the picker crossfades between them as the mode flips.
 */
data class ThemePack(
    val id: String,
    val lightName: String,
    val darkName: String,
    val lightAppPaletteId: String,
    val darkAppPaletteId: String,
    val lightReaderThemeId: String,
    val darkReaderThemeId: String,
) {
    fun name(dark: Boolean): String = if (dark) darkName else lightName
    fun appPaletteId(dark: Boolean): String = if (dark) darkAppPaletteId else lightAppPaletteId
    fun readerThemeId(dark: Boolean): String = if (dark) darkReaderThemeId else lightReaderThemeId

    companion object {
        val ALL = listOf(
            // §16: first, and only shown where the platform can derive it —
            // GeneralSettingsPanel hides the card when no dynamic scheme exists.
            ThemePack("system", "System", "System", "system", "systemdark", "paper", "dark"),
            ThemePack("gallery", "Gallery", "Dark", "light", "dark", "white", "dark"),
            ThemePack("manuscript", "Manuscript", "Espresso", "warm", "espresso", "sepia", "espresso"),
            ThemePack("silver", "Silver", "Graphite", "silver", "graphite", "gray", "graphite"),
            ThemePack("matcha", "Matcha", "Toxic Lime", "matcha", "toxiclime", "matcha", "toxiclime"),
            ThemePack("arctic", "Arctic", "Midnight", "arctic", "midnight", "arctic", "dark"),
            ThemePack("sakura", "Sakura", "Blossom", "sakura", "blossom", "paper", "blossom"),
            ThemePack("honey", "Honey", "Ember", "honey", "ember", "sepia", "ember"),
            ThemePack("moss", "Moss", "Peacock", "moss", "peacock", "moss", "peacock"),
            ThemePack("sherbet", "Sherbet", "Retro Sunset", "sherbet", "retrosunset", "sherbet", "retrosunset"),
            ThemePack("bubblegum", "Bubblegum", "Vaporwave", "bubblegum", "vaporwave", "bubblegum", "vaporwave"),
            ThemePack("dawn", "Dawn", "Dusk", "dawn", "dusk", "dawn", "dusk"),
            ThemePack("hyperpop", "Hyperpop", "Synthwave", "hyperpop", "synthwave", "candypop", "synthwave"),
            ThemePack("rainbow", "Rainbow Candy", "Neon Tokyo", "rainbow", "neontokyo", "rainbow", "neontokyo"),
            ThemePack("clay", "Clay", "Cocoa", "clay", "cocoa", "clay", "cocoa"),
            ThemePack("lagoon", "Lagoon", "Abyss", "lagoon", "abyss", "lagoon", "abyss"),
            ThemePack("rouge", "Rouge", "Garnet", "rouge", "garnet", "rouge", "garnet"),
            ThemePack("citrine", "Citrine", "Onyx", "citrine", "onyx", "citrine", "onyx"),
        )
    }
}

/**
 * The light/dark switch: applies the active pack's other face. The app palette
 * always flips; the page follows only when it is this pack's own page — a
 * reader theme chosen independently (Dracula, Nord…) is a deliberate decision
 * the switch must not stomp. A custom app theme defines its own polarity, so
 * the switch is a no-op while one is active; the UI disables it then.
 *
 * The active pack is found through [AppPalette.byId] on purpose: a persisted
 * `appThemeId` may still name a removed palette, and the remap must land on
 * the surviving family's pack, not on "no pack".
 */
fun flipThemeMode(settings: ReaderSettings): ReaderSettings {
    if (settings.customAppTheme != null) return settings
    val resolved = AppPalette.byId(settings.appThemeId)
    val pack = ThemePack.ALL.firstOrNull {
        it.lightAppPaletteId == resolved.id || it.darkAppPaletteId == resolved.id
    } ?: return settings
    val dark = resolved.isDark
    val readerFollows = settings.customTheme == null &&
        (settings.themeId == pack.readerThemeId(dark) || settings.themeId == pack.readerThemeId(!dark))
    return settings.copy(
        appThemeId = pack.appPaletteId(!dark),
        themeId = if (readerFollows) pack.readerThemeId(!dark) else settings.themeId
    )
}

data class FolioTypography(val fontTheme: FontTheme = FontTheme.CLASSIC) {
    val displayLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 300, opticalSize = 96f),
        fontWeight = FontWeight.W300,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.02).em
    )
    val displayMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 300, opticalSize = 72f),
        fontWeight = FontWeight.W300,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.015).em
    )
    val displaySmall: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 300, opticalSize = 60f),
        fontWeight = FontWeight.W300,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.01).em
    )
    val headlineLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 44f),
        fontWeight = FontWeight.W600,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.01).em
    )
    val headlineMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 36f),
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        lineHeight = 36.sp
    )
    val headlineSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 28f),
        fontWeight = FontWeight.W600,
        fontSize = 24.sp,
        lineHeight = 32.sp
    )
    val titleLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 22f),
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp
    )
    val titleMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    )
    val titleSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    val bodyLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 400),
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    )
    val bodyMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 400),
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.05.sp
    )
    val bodySmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 400),
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    )
    val labelLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 700),
        fontWeight = FontWeight.W700,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    val labelMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 700),
        fontWeight = FontWeight.W700,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    )
    val labelSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 700),
        fontWeight = FontWeight.W700,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    val quote: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 400, italic = true, opticalSize = 24f),
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
}

val LocalFolioColors = staticCompositionLocalOf { LightFolioColors }
val LocalFolioTypography = staticCompositionLocalOf { FolioTypography() }

object FolioTokens {
    // Border radiuses for UI components
    val radiusCard = 20.dp
    val radiusControl = 14.dp
    val radiusChip = 10.dp

    // Spacing scale – use only these throughout the app
    val space1 = 8.dp     // Small gaps, icon padding
    val space2 = 12.dp    // Row gaps inside cards
    val space3 = 16.dp    // Standard horizontal padding, sibling gaps
    val space4 = 24.dp    // Large sections, card margins

    // Navigation bar height
    val barHeight = 56.dp

    /**
     * How far the masthead's glass fades out under its hairline. Small on
     * purpose: enough that the bar's foot dissolves into the page, not enough to
     * become a second band. Anything past ~14dp starts reading as a toolbar
     * shadow again.
     */
    val barGlassFade = 10.dp

    // Motion durations – no literal durations elsewhere in new code
    val motionFast = 120L         // State flips: chip select, checkbox toggle
    val motionStandard = 220L     // Enter/exit, crossfade, panel slide
    val motionEmphasis = 320L     // Bottom sheet, full-screen transition

    /**
     * §13.6 cover morph: list thumbnail ↔ detail/reader plate, and the paired
     * title/author text. The longest motion in the app, deliberately — a shared
     * element travels across the whole viewport, and at `motionEmphasis` the eye
     * reads a snap-and-settle rather than one continuous object moving. It also
     * has to outlast the navigation cross-fade so the arriving screen is already
     * composed when the plate lands, otherwise the morph is cut off mid-flight.
     */
    val motionMorph = 450L

    val chipAutoDismiss = 4000L   // End-of-chapter chip dwell before fading out

    /**
     * One shimmer sweep across an unarrived pane. Fast on purpose: §13.4 holds the
     * hero mesh at 18–30s precisely so it never reads as a loading state, and this
     * is the opposite end of that scale. Past ~2s the band stops reading as
     * activity and starts reading as a slow gradient animation.
     */
    val motionShimmer = 1200L

    /**
     * §17: the specular band that crosses the nav capsule when the selected tab
     * changes. Between the shimmer and the sheet morph on purpose — long enough
     * to read as the glass catching light, short enough that the eye lands on the
     * destination before the light does.
     */
    val motionLiquidSweep = 420L

    // Progress ring diameter when decorating covers
    val ringSmall = 28.dp
    // Progress ring diameter on the Home daily-goal card
    val ringLarge = 96.dp

    // Override indicator dot size
    val dotIndicator = 6.dp

    // Chart geometry — one definition shared by stats charts & the book-detail sparkline
    val chartHeight = 112.dp
    val chartBarBase = 28.dp
    val chartBarSpan = 48.dp
    val chartTrack = 6.dp
    val chartSparkGap = 2.dp
    val chartBarRadiusTop = 6.dp
    val chartBarRadiusBottom = 2.dp

    // Highlight-density heat strip on book detail
    val heatStrip = 14.dp

    // §12 surface tiers
    val radiusHero = 28.dp          // hero cards sit above radiusCard's 20
    val heroCoverMin = 120.dp       // Rule 17 hero cover floor
    val listCoverMin = 64.dp        // Rule 17 list cover floor

    // §12 data visuals
    val chartPeakCap = 3.dp         // brighter cap marking a peak bar
    val ringStrokeHero = 8f         // hero ring stroke (compact strip uses 4f)
    val sparkHeight = 32.dp         // inline sparkline height
    val gradientMinAlpha = 0.55f    // floor for chart gradients (Rule 15)
    val weekChartHeight = 150.dp    // the smooth-curve week chart in Stats

    // ── Redesign: material elevation ladder ────────────────────────────────
    // Four steps, not a continuum. Anything between two of these reads as an
    // accident. FolioAtmosphere.shadowScale trims all four on dark palettes,
    // where the same physical shadow reads much heavier.
    val elevationFlat = 0.dp        // embedded / on-page: no shadow at all
    val elevationPanel = 2.dp       // resting surface
    val elevationVeil = 10.dp       // glass over content (bars, nav, sheets)
    val elevationRaised = 18.dp     // the floating hero

    // ── Redesign: spatial rhythm ───────────────────────────────────────────
    // Screens breathe unevenly on purpose: a tight gap groups, a wide gap
    // separates movements. Uniform spacing is what made every screen read as
    // one undifferentiated stack.
    val spaceHair = 4.dp            // inside a figure, label→value
    val spaceBeat = 20.dp           // between related blocks
    val spaceMovement = 36.dp       // between sections of different kinds
    val gutter = 20.dp              // screen side margin
    val heroBleed = 12.dp           // how far a cover overhangs its surface

    // ── Redesign: cover scale ladder ───────────────────────────────────────
    // Covers are the app's strongest asset, so their sizes are a deliberate
    // ladder rather than whatever fitted the row.
    val coverAnchor = 148.dp        // Home's one anchoring cover
    val coverFeature = 112.dp       // a featured shelf entry
    val coverShelf = 84.dp          // standard shelf/grid entry
    val coverInline = 52.dp         // inline list rows
    val coverAspect = 1.5f          // height = width × this (2:3 printed trim)

    // ── Manga cover panes ──────────────────────────────────────────────────
    // A shimmer skeleton is only worth having if covers land in the cells it
    // already drew, so the pane's geometry lives here rather than as a literal
    // repeated at every grid. `coverPaneRatio` is the `Modifier.aspectRatio`
    // (width ÷ height) form of `coverAspect` above.
    val coverPaneRatio = 0.68f      // manga cover tile, width ÷ height
    val coverRailWidth = 96.dp      // pane width in a horizontal results rail
    val coverGridMin = 110.dp       // adaptive-grid minimum pane width

    // Floating navigation capsule
    // 54/42 rather than 62/46: the capsule reads as a control, and a control that
    // is 62dp tall over a page reads as a bar that has been rounded off.
    val navFloatHeight = 54.dp
    val navFloatInset = 12.dp

    /**
     * Width ceiling for the floating nav capsule. Four items need ~360dp at most;
     * beyond that the capsule would stretch back into a full-width bar on tablets
     * and large windows, which is exactly what it is not.
     */
    val navFloatMaxWidth = 420.dp

    // ── §16 liquid glass ───────────────────────────────────────────────────
    // Rule 2: the blur material's numbers live here, never as literals at the
    // effect sites. The radius cap is the whole restraint — past ~28dp a blur
    // stops reading as thick glass and starts reading as a rendering fault.
    val blurRadius = 20.dp
    val blurRadiusMax = 28.dp

    /** Haze's noise factor for blurred glass. */
    const val glassNoise = 0.12f

    /** Alpha of the deterministic grain on glass that cannot blur. */
    const val glassGrainAlpha = 0.035f

    /** The resting leading sweep of the reader's end-edge glass sheets. */
    val radiusSheetSweep = 26.dp

    /** The capsule radius those sheets enter reading as, before settling. */
    val radiusSheetCapsule = 96.dp
}

object FolioTheme {
    val colors: FolioColors
        @Composable
        get() = LocalFolioColors.current

    val typography: FolioTypography
        @Composable
        get() = LocalFolioTypography.current

    @Composable
    fun MaterialTheme(
        darkTheme: Boolean = false,
        colors: FolioColors = if (darkTheme) DarkFolioColors else LightFolioColors,
        typography: FolioTypography = FolioTypography(),
        // Inherited, not reset. Screens legitimately re-enter the theme to swap
        // palettes mid-tree (the reader re-themes its chrome, the Appearance
        // previews show one theme inside another). Defaulting to `Default` made
        // every such nesting silently throw the user's opacity away.
        opacity: FolioSurfaceOpacity = LocalFolioSurfaceOpacity.current,
        // Inherited like `opacity`, for the same reason: the reader and the
        // Appearance previews re-enter the theme mid-tree, and a nested theme
        // must not reset the room's clock. The live value is read once at the
        // true root (AppTheme) and flows down from there.
        daylight: FolioDaylight = LocalFolioDaylight.current,
        content: @Composable () -> Unit
    ) {
        // §17 contrast pass: the ink roles deepened toward the palette's own
        // extreme, once, at the seam every themed tree flows through — the app
        // root, the reader's re-themed chrome and the Appearance previews all
        // inherit the same derivation. The raw palettes stay exactly as
        // authored and as tested; only what the eye sees moves.
        val ink = remember(colors) { deepenInkRoles(colors) }
        CompositionLocalProvider(
            LocalFolioColors provides ink,
            LocalFolioTypography provides typography,
            LocalFolioSurfaceOpacity provides opacity,
            LocalFolioDaylight provides daylight,
            // Material3 defaults LocalContentColor to pure black, and Folio's
            // panels are not wrapped in `Surface`, so every unstyled Text/Icon
            // rendered black — unreadable on any dark palette. Anchor the default
            // to the palette's own foreground instead.
            androidx.compose.material3.LocalContentColor provides ink.onSurface
        ) {
            androidx.compose.material3.MaterialTheme(
                colorScheme = ink.toColorScheme(),
                typography = typography.toTypography(),
                shapes = Shapes(),
                content = content
            )
        }
    }

    /**
     * [colors] and [isDark] are overridable so a user's custom palette can stand
     * in for [palette] without inventing a synthetic `AppPalette` enum entry; the
     * previews in Appearance settings use the same door to show one theme inside
     * another.
     */
    @Composable
    fun AppTheme(
        palette: AppPalette,
        fontTheme: FontTheme = FontTheme.CLASSIC,
        colors: FolioColors = palette.colors,
        isDark: Boolean = palette.isDark,
        opacity: FolioSurfaceOpacity = LocalFolioSurfaceOpacity.current,
        // AppTheme is the app's true root, so this is where the room reads the
        // reader's clock; MaterialTheme below inherits it. Null means "the live
        // hour" (the normal case); a caller may pin one to preview 7am against 9pm.
        daylight: FolioDaylight? = null,
        content: @Composable () -> Unit
    ) {
        val typo = FolioTypography(fontTheme)
        MaterialTheme(
            darkTheme = isDark,
            colors = colors,
            typography = typo,
            opacity = opacity,
            daylight = daylight ?: rememberDaylight(),
            content = content
        )
    }
}

private fun FolioColors.toColorScheme(): androidx.compose.material3.ColorScheme {
    return androidx.compose.material3.ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary,
        surfaceTint = primary,
        surfaceDim = surface,
        surfaceBright = surface,
        surfaceContainer = surface,
        surfaceContainerLow = surface,
        surfaceContainerLowest = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceContainerHighest
    )
}

private fun FolioTypography.toTypography(): Typography {
    return Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall,
        headlineLarge = headlineLarge,
        headlineMedium = headlineMedium,
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall
    )
}
