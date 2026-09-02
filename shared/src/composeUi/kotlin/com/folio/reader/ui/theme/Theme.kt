package com.folio.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

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
    val statusBar: Color = Color(0xFF0F172A)
)

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
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF8F9FA),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerHighest = Color(0xFFE8EAED),
    outline = Color(0xFFDADCE0),
    outlineVariant = Color(0xFFE8EAED),
    shadow = Color.Black,
    scrim = Color.Black,
    inverseSurface = Color(0xFF202124),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF8AB4F8),
    statusBar = Color(0xFF202124)
)

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
    surface = Color(0xFF0A0A0A),
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
    statusBar = Color(0xFF000000)
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
    tertiary = Color(0xFF009C9C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB5F5F0),
    onTertiaryContainer = Color(0xFF003B3B),
    background = Color(0xFFFFF4DA),
    onBackground = Color(0xFF3A2100),
    surface = Color(0xFFFFF9EA),
    onSurface = Color(0xFF3A2100),
    surfaceVariant = Color(0xFFF7E4BB),
    onSurfaceVariant = Color(0xFF6F4A10),
    surfaceContainerHighest = Color(0xFFEAC983),
    outline = Color(0xFFCB9A38),
    outlineVariant = Color(0xFFEAC983),
    inverseSurface = Color(0xFF3A2100),
    inverseOnSurface = Color(0xFFFFF4DA),
    inversePrimary = Color(0xFFFFC44D),
    statusBar = Color(0xFF241400)
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
    tertiary = Color(0xFF00BFA5),
    onTertiary = Color(0xFF00332B),
    tertiaryContainer = Color(0xFFA6F5E4),
    onTertiaryContainer = Color(0xFF004038),
    background = Color(0xFFECFAE0),
    onBackground = Color(0xFF0A2410),
    surface = Color(0xFFF6FEED),
    onSurface = Color(0xFF0A2410),
    surfaceVariant = Color(0xFFD6F3BE),
    onSurfaceVariant = Color(0xFF2F6112),
    surfaceContainerHighest = Color(0xFFB6E494),
    outline = Color(0xFF7AB855),
    outlineVariant = Color(0xFFB6E494),
    inverseSurface = Color(0xFF0A2410),
    inverseOnSurface = Color(0xFFECFAE0),
    inversePrimary = Color(0xFF7CF08A),
    statusBar = Color(0xFF041808)
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
    tertiary = Color(0xFF4A14FF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8CCFF),
    onTertiaryContainer = Color(0xFF1A0060),
    background = Color(0xFFDBEFFF),
    onBackground = Color(0xFF00172E),
    surface = Color(0xFFEEF8FF),
    onSurface = Color(0xFF00172E),
    surfaceVariant = Color(0xFFC2E3FB),
    onSurfaceVariant = Color(0xFF144A70),
    surfaceContainerHighest = Color(0xFF9BD0F5),
    outline = Color(0xFF4E9BC9),
    outlineVariant = Color(0xFF9BD0F5),
    inverseSurface = Color(0xFF00172E),
    inverseOnSurface = Color(0xFFDBEFFF),
    inversePrimary = Color(0xFF7FC4FF),
    statusBar = Color(0xFF00101F)
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
    tertiary = Color(0xFFFF3D9E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFB2D8),
    onTertiaryContainer = Color(0xFF4A0028),
    background = Color(0xFFFFE2EF),
    onBackground = Color(0xFF3C001C),
    surface = Color(0xFFFFF2F7),
    onSurface = Color(0xFF3C001C),
    surfaceVariant = Color(0xFFFFCFE2),
    onSurfaceVariant = Color(0xFF7A1840),
    surfaceContainerHighest = Color(0xFFF5A8C8),
    outline = Color(0xFFCE6A96),
    outlineVariant = Color(0xFFF5A8C8),
    inverseSurface = Color(0xFF3C001C),
    inverseOnSurface = Color(0xFFFFE2EF),
    inversePrimary = Color(0xFFFF9EC4),
    statusBar = Color(0xFF280012)
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
    tertiary = Color(0xFFE65100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFCCAA),
    onTertiaryContainer = Color(0xFF3E1000),
    background = Color(0xFFFFF0B8),
    onBackground = Color(0xFF332200),
    surface = Color(0xFFFFF7D6),
    onSurface = Color(0xFF332200),
    surfaceVariant = Color(0xFFF8E39A),
    onSurfaceVariant = Color(0xFF6B4E00),
    surfaceContainerHighest = Color(0xFFEAC85C),
    outline = Color(0xFFB8912A),
    outlineVariant = Color(0xFFEAC85C),
    inverseSurface = Color(0xFF332200),
    inverseOnSurface = Color(0xFFFFF0B8),
    inversePrimary = Color(0xFFFFD54F),
    statusBar = Color(0xFF1F1400)
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
    tertiary = Color(0xFF00BFA0),
    onTertiary = Color(0xFF00332A),
    tertiaryContainer = Color(0xFFA6F5E0),
    onTertiaryContainer = Color(0xFF003D32),
    background = Color(0xFFDDF5E4),
    onBackground = Color(0xFF002115),
    surface = Color(0xFFEFFBF2),
    onSurface = Color(0xFF002115),
    surfaceVariant = Color(0xFFC4EBD2),
    onSurfaceVariant = Color(0xFF1C5A3A),
    surfaceContainerHighest = Color(0xFFA0DDB8),
    outline = Color(0xFF5AA67C),
    outlineVariant = Color(0xFFA0DDB8),
    inverseSurface = Color(0xFF002115),
    inverseOnSurface = Color(0xFFDDF5E4),
    inversePrimary = Color(0xFF6BF0AC),
    statusBar = Color(0xFF00160E)
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
    tertiary = Color(0xFF00E5FF),
    onTertiary = Color(0xFF00303A),
    tertiaryContainer = Color(0xFF005C6E),
    onTertiaryContainer = Color(0xFFBFFAFF),
    background = Color(0xFF150036),
    onBackground = Color(0xFFF0DCFF),
    surface = Color(0xFF210847),
    onSurface = Color(0xFFF0DCFF),
    surfaceVariant = Color(0xFF2E1260),
    onSurfaceVariant = Color(0xFFC4A2F0),
    surfaceContainerHighest = Color(0xFF3E1C7C),
    outline = Color(0xFF6A3AAE),
    outlineVariant = Color(0xFF3E1C7C),
    inverseSurface = Color(0xFFF0DCFF),
    inverseOnSurface = Color(0xFF210847),
    inversePrimary = Color(0xFF6A00BF),
    statusBar = Color(0xFF0A001C)
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
    tertiary = Color(0xFFFFD54F),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF6E5200),
    onTertiaryContainer = Color(0xFFFFF0B8),
    background = Color(0xFF1C0E04),
    onBackground = Color(0xFFFFE6CC),
    surface = Color(0xFF2A1608),
    onSurface = Color(0xFFFFE6CC),
    surfaceVariant = Color(0xFF3A2010),
    onSurfaceVariant = Color(0xFFD8AC7C),
    surfaceContainerHighest = Color(0xFF4E2C16),
    outline = Color(0xFF7A4A26),
    outlineVariant = Color(0xFF4E2C16),
    inverseSurface = Color(0xFFFFE6CC),
    inverseOnSurface = Color(0xFF2A1608),
    inversePrimary = Color(0xFFB25A00),
    statusBar = Color(0xFF0E0602)
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
    tertiary = Color(0xFF8C6BFF),
    onTertiary = Color(0xFF15004D),
    tertiaryContainer = Color(0xFF33209E),
    onTertiaryContainer = Color(0xFFDED2FF),
    background = Color(0xFF001038),
    onBackground = Color(0xFFDCEAFF),
    surface = Color(0xFF0A1E52),
    onSurface = Color(0xFFDCEAFF),
    surfaceVariant = Color(0xFF142C66),
    onSurfaceVariant = Color(0xFF9FC0F0),
    surfaceContainerHighest = Color(0xFF1E3C7E),
    outline = Color(0xFF3A62AE),
    outlineVariant = Color(0xFF1E3C7E),
    inverseSurface = Color(0xFFDCEAFF),
    inverseOnSurface = Color(0xFF0A1E52),
    inversePrimary = Color(0xFF0047A3),
    statusBar = Color(0xFF00081F)
)

private val OceanFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF00E5C4),
    onPrimary = Color(0xFF003028),
    primaryContainer = Color(0xFF00685A),
    onPrimaryContainer = Color(0xFFB0FFF0),
    secondary = Color(0xFF29B6FF),
    onSecondary = Color(0xFF002438),
    secondaryContainer = Color(0xFF00507A),
    onSecondaryContainer = Color(0xFFBEEAFF),
    tertiary = Color(0xFF00FFAA),
    onTertiary = Color(0xFF003A26),
    tertiaryContainer = Color(0xFF006B47),
    onTertiaryContainer = Color(0xFFB8FFDE),
    background = Color(0xFF00211C),
    onBackground = Color(0xFFCFFFF0),
    surface = Color(0xFF002E28),
    onSurface = Color(0xFFCFFFF0),
    surfaceVariant = Color(0xFF0A3E38),
    onSurfaceVariant = Color(0xFF7FDCCB),
    surfaceContainerHighest = Color(0xFF14504A),
    outline = Color(0xFF2E7D72),
    outlineVariant = Color(0xFF14504A),
    inverseSurface = Color(0xFFCFFFF0),
    inverseOnSurface = Color(0xFF002E28),
    inversePrimary = Color(0xFF00877A),
    statusBar = Color(0xFF001410)
)

private val GrapeFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFE040FB),
    onPrimary = Color(0xFF2C0038),
    primaryContainer = Color(0xFF6A0FA0),
    onPrimaryContainer = Color(0xFFF8D4FF),
    secondary = Color(0xFF7C4DFF),
    onSecondary = Color(0xFF120047),
    secondaryContainer = Color(0xFF321894),
    onSecondaryContainer = Color(0xFFDCCEFF),
    tertiary = Color(0xFFFF2ED1),
    onTertiary = Color(0xFF3B0030),
    tertiaryContainer = Color(0xFF700A5C),
    onTertiaryContainer = Color(0xFFFFC8F2),
    background = Color(0xFF1B0033),
    onBackground = Color(0xFFF2DCFF),
    surface = Color(0xFF28054D),
    onSurface = Color(0xFFF2DCFF),
    surfaceVariant = Color(0xFF360F63),
    onSurfaceVariant = Color(0xFFC79BF0),
    surfaceContainerHighest = Color(0xFF481A7E),
    outline = Color(0xFF7438AE),
    outlineVariant = Color(0xFF481A7E),
    inverseSurface = Color(0xFFF2DCFF),
    inverseOnSurface = Color(0xFF28054D),
    inversePrimary = Color(0xFFA000CC),
    statusBar = Color(0xFF0F001C)
)

private val OledFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF90A4AE),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFCFD8DC),
    secondary = Color(0xFF78909C),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF141414),
    onSecondaryContainer = Color(0xFFB0BEC5),
    tertiary = Color(0xFF80CBC4),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF0A1A18),
    onTertiaryContainer = Color(0xFFB2DFDB),
    background = Color(0xFF000000),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF080808),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFF90A4AE),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    outline = Color(0xFF262626),
    outlineVariant = Color(0xFF1A1A1A),
    shadow = Color.Black,
    scrim = Color.Black,
    inverseSurface = Color(0xFFECEFF1),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF546E7A),
    statusBar = Color(0xFF000000)
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
    tertiary = Color(0xFFFFC107),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFF6E5200),
    onTertiaryContainer = Color(0xFFFFF0B8),
    background = Color(0xFF1E0702),
    onBackground = Color(0xFFFFE0CC),
    surface = Color(0xFF2E0E04),
    onSurface = Color(0xFFFFE0CC),
    surfaceVariant = Color(0xFF40140A),
    onSurfaceVariant = Color(0xFFE0A480),
    surfaceContainerHighest = Color(0xFF561E0C),
    outline = Color(0xFF8A3A1E),
    outlineVariant = Color(0xFF561E0C),
    inverseSurface = Color(0xFFFFE0CC),
    inverseOnSurface = Color(0xFF2E0E04),
    inversePrimary = Color(0xFFB73A00),
    statusBar = Color(0xFF100301)
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
    tertiary = Color(0xFF7C4DFF),
    onTertiary = Color(0xFF1A0844),
    tertiaryContainer = Color(0xFF341678),
    onTertiaryContainer = Color(0xFFDCD0FF),
    background = Color(0xFF0D0221),
    onBackground = Color(0xFFF0E6FF),
    surface = Color(0xFF150535),
    onSurface = Color(0xFFF0E6FF),
    surfaceVariant = Color(0xFF221048),
    onSurfaceVariant = Color(0xFFA88CD0),
    surfaceContainerHighest = Color(0xFF2E185C),
    outline = Color(0xFF44267E),
    outlineVariant = Color(0xFF2E185C),
    inverseSurface = Color(0xFFF0E6FF),
    inverseOnSurface = Color(0xFF150535),
    inversePrimary = Color(0xFFC0109E),
    statusBar = Color(0xFF070112)
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
    tertiary = Color(0xFFFFB300),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFFFFE9B8),
    onTertiaryContainer = Color(0xFF4A3400),
    background = Color(0xFFFFF0F6),
    onBackground = Color(0xFF4A1033),
    surface = Color(0xFFFFF8FB),
    onSurface = Color(0xFF4A1033),
    surfaceVariant = Color(0xFFFCE0EE),
    onSurfaceVariant = Color(0xFF8A4A6E),
    surfaceContainerHighest = Color(0xFFF4CCE0),
    outline = Color(0xFFE4A8C8),
    outlineVariant = Color(0xFFF0C4DA),
    inverseSurface = Color(0xFF4A1033),
    inverseOnSurface = Color(0xFFFFF0F6),
    statusBar = Color(0xFF2E0A20)
)

private val AcidFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFCCFF00),
    onPrimary = Color(0xFF1E2800),
    primaryContainer = Color(0xFF3A4A00),
    onPrimaryContainer = Color(0xFFECFFB0),
    secondary = Color(0xFFFF2ED1),
    onSecondary = Color(0xFF3A002E),
    secondaryContainer = Color(0xFF58084A),
    onSecondaryContainer = Color(0xFFFFC8F0),
    tertiary = Color(0xFF00FFAA),
    onTertiary = Color(0xFF003824),
    tertiaryContainer = Color(0xFF0A5238),
    onTertiaryContainer = Color(0xFFC0FFE6),
    background = Color(0xFF060806),
    onBackground = Color(0xFFE8FFD6),
    surface = Color(0xFF0E120C),
    onSurface = Color(0xFFE8FFD6),
    surfaceVariant = Color(0xFF182014),
    onSurfaceVariant = Color(0xFF98B878),
    surfaceContainerHighest = Color(0xFF24301C),
    outline = Color(0xFF3A4C2A),
    outlineVariant = Color(0xFF24301C),
    inverseSurface = Color(0xFFE8FFD6),
    inverseOnSurface = Color(0xFF0E120C),
    inversePrimary = Color(0xFF5E7A00),
    statusBar = Color(0xFF020302)
)

private val LavaFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFFF5C1E),
    onPrimary = Color(0xFF3E0E00),
    primaryContainer = Color(0xFF6E2200),
    onPrimaryContainer = Color(0xFFFFD8C4),
    secondary = Color(0xFFFF2E63),
    onSecondary = Color(0xFF3E0014),
    secondaryContainer = Color(0xFF640A28),
    onSecondaryContainer = Color(0xFFFFC8D6),
    tertiary = Color(0xFFFFB300),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFE6AE),
    background = Color(0xFF160504),
    onBackground = Color(0xFFFFE4D4),
    surface = Color(0xFF220B06),
    onSurface = Color(0xFFFFE4D4),
    surfaceVariant = Color(0xFF321408),
    onSurfaceVariant = Color(0xFFC89878),
    surfaceContainerHighest = Color(0xFF46200E),
    outline = Color(0xFF5E3018),
    outlineVariant = Color(0xFF46200E),
    inverseSurface = Color(0xFFFFE4D4),
    inverseOnSurface = Color(0xFF220B06),
    inversePrimary = Color(0xFFB03A00),
    statusBar = Color(0xFF0C0202)
)

private val SherbetFolioColors = LightFolioColors.copy(
    primary = Color(0xFFFF7A4D),
    onPrimary = Color(0xFF3E1400),
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF5C1E00),
    secondary = Color(0xFF2ED9A3),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFFC4F8E4),
    onSecondaryContainer = Color(0xFF0A4430),
    tertiary = Color(0xFF4DC3FF),
    onTertiary = Color(0xFF00304D),
    tertiaryContainer = Color(0xFFD4EEFF),
    onTertiaryContainer = Color(0xFF003A58),
    background = Color(0xFFFFF7EE),
    onBackground = Color(0xFF4A2C1A),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF4A2C1A),
    surfaceVariant = Color(0xFFFFEDD9),
    onSurfaceVariant = Color(0xFF8A6A4D),
    surfaceContainerHighest = Color(0xFFF6DFC2),
    outline = Color(0xFFE4C8A4),
    outlineVariant = Color(0xFFF0DCC4),
    inverseSurface = Color(0xFF4A2C1A),
    inverseOnSurface = Color(0xFFFFF7EE),
    statusBar = Color(0xFF341C0C)
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
    tertiary = Color(0xFFB86BFF),
    onTertiary = Color(0xFF1F004D),
    tertiaryContainer = Color(0xFF4A1E94),
    onTertiaryContainer = Color(0xFFE8D4FF),
    background = Color(0xFF1A0733),
    onBackground = Color(0xFFF2DCFF),
    surface = Color(0xFF260E47),
    onSurface = Color(0xFFF2DCFF),
    surfaceVariant = Color(0xFF341660),
    onSurfaceVariant = Color(0xFFBE9BF0),
    surfaceContainerHighest = Color(0xFF46227E),
    outline = Color(0xFF6E3AAE),
    outlineVariant = Color(0xFF46227E),
    inverseSurface = Color(0xFFF2DCFF),
    inverseOnSurface = Color(0xFF260E47),
    inversePrimary = Color(0xFFC2008A),
    statusBar = Color(0xFF0E0320)
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
    tertiary = Color(0xFF3DFFFF),
    onTertiary = Color(0xFF00333D),
    tertiaryContainer = Color(0xFF006170),
    onTertiaryContainer = Color(0xFFC8FBFF),
    background = Color(0xFF0A0018),
    onBackground = Color(0xFFE8DAFF),
    surface = Color(0xFF140026),
    onSurface = Color(0xFFE8DAFF),
    surfaceVariant = Color(0xFF1F0A38),
    onSurfaceVariant = Color(0xFFAE8CE0),
    surfaceContainerHighest = Color(0xFF2C1250),
    outline = Color(0xFF4E2A82),
    outlineVariant = Color(0xFF2C1250),
    inverseSurface = Color(0xFFE8DAFF),
    inverseOnSurface = Color(0xFF140026),
    inversePrimary = Color(0xFFB0005C),
    statusBar = Color(0xFF05000C)
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
    tertiary = Color(0xFF00D9FF),
    onTertiary = Color(0xFF00333D),
    tertiaryContainer = Color(0xFFB4F2FF),
    onTertiaryContainer = Color(0xFF00303A),
    background = Color(0xFFFFDCF2),
    onBackground = Color(0xFF33001F),
    surface = Color(0xFFFFF0F9),
    onSurface = Color(0xFF33001F),
    surfaceVariant = Color(0xFFFFCCEB),
    onSurfaceVariant = Color(0xFF7A0A4D),
    surfaceContainerHighest = Color(0xFFFFA6DD),
    outline = Color(0xFFC94E9E),
    outlineVariant = Color(0xFFFFA6DD),
    inverseSurface = Color(0xFF33001F),
    inverseOnSurface = Color(0xFFFFDCF2),
    inversePrimary = Color(0xFFFF7ADB),
    statusBar = Color(0xFF200013)
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
    tertiary = Color(0xFFFFA300),
    onTertiary = Color(0xFF3A2200),
    tertiaryContainer = Color(0xFF6E4600),
    onTertiaryContainer = Color(0xFFFFE4B0),
    background = Color(0xFF0A1200),
    onBackground = Color(0xFFE4FFCC),
    surface = Color(0xFF121C02),
    onSurface = Color(0xFFE4FFCC),
    surfaceVariant = Color(0xFF1C2A06),
    onSurfaceVariant = Color(0xFFA8CC74),
    surfaceContainerHighest = Color(0xFF283A0A),
    outline = Color(0xFF46621A),
    outlineVariant = Color(0xFF283A0A),
    inverseSurface = Color(0xFFE4FFCC),
    inverseOnSurface = Color(0xFF121C02),
    inversePrimary = Color(0xFF5E8400),
    statusBar = Color(0xFF050800)
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
    tertiary = Color(0xFFFFC93D),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF6E5000),
    onTertiaryContainer = Color(0xFFFFF0BC),
    background = Color(0xFF22052E),
    onBackground = Color(0xFFFFE2CC),
    surface = Color(0xFF300A38),
    onSurface = Color(0xFFFFE2CC),
    surfaceVariant = Color(0xFF3E1044),
    onSurfaceVariant = Color(0xFFE4A0A8),
    surfaceContainerHighest = Color(0xFF52184E),
    outline = Color(0xFF8A3A6E),
    outlineVariant = Color(0xFF52184E),
    inverseSurface = Color(0xFFFFE2CC),
    inverseOnSurface = Color(0xFF300A38),
    inversePrimary = Color(0xFFB73E00),
    statusBar = Color(0xFF14031C)
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
    tertiary = Color(0xFF9D5CFF),
    onTertiary = Color(0xFF1E004D),
    tertiaryContainer = Color(0xFF45198F),
    onTertiaryContainer = Color(0xFFE4CCFF),
    background = Color(0xFF001A24),
    onBackground = Color(0xFFC8FAFF),
    surface = Color(0xFF002530),
    onSurface = Color(0xFFC8FAFF),
    surfaceVariant = Color(0xFF0A3340),
    onSurfaceVariant = Color(0xFF78D2E4),
    surfaceContainerHighest = Color(0xFF144454),
    outline = Color(0xFF2A6E80),
    outlineVariant = Color(0xFF144454),
    inverseSurface = Color(0xFFC8FAFF),
    inverseOnSurface = Color(0xFF002530),
    inversePrimary = Color(0xFF008A7C),
    statusBar = Color(0xFF000F16)
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
    tertiary = Color(0xFF00C853),
    onTertiary = Color(0xFF003A15),
    tertiaryContainer = Color(0xFFBDF5CD),
    onTertiaryContainer = Color(0xFF00391A),
    background = Color(0xFFFFE3EF),
    onBackground = Color(0xFF38001C),
    surface = Color(0xFFFFF4F8),
    onSurface = Color(0xFF38001C),
    surfaceVariant = Color(0xFFFFD4C2),
    onSurfaceVariant = Color(0xFF7A2A00),
    surfaceContainerHighest = Color(0xFFFFC7A8),
    outline = Color(0xFFCB6A4E),
    outlineVariant = Color(0xFFFFC7A8),
    inverseSurface = Color(0xFF38001C),
    inverseOnSurface = Color(0xFFFFE3EF),
    inversePrimary = Color(0xFFFF8FB0),
    statusBar = Color(0xFF240012)
)

private val MidnightNeonFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF7CFF3D),
    onPrimary = Color(0xFF12300A),
    primaryContainer = Color(0xFF2A5C12),
    onPrimaryContainer = Color(0xFFD8FFC2),
    secondary = Color(0xFFFF3DDB),
    onSecondary = Color(0xFF3D0034),
    secondaryContainer = Color(0xFF7A0A68),
    onSecondaryContainer = Color(0xFFFFC8F2),
    tertiary = Color(0xFF3DDCFF),
    onTertiary = Color(0xFF00303D),
    tertiaryContainer = Color(0xFF0A5C78),
    onTertiaryContainer = Color(0xFFC4F4FF),
    background = Color(0xFF04060C),
    onBackground = Color(0xFFDCF5FF),
    surface = Color(0xFF0A0E18),
    onSurface = Color(0xFFDCF5FF),
    surfaceVariant = Color(0xFF121826),
    onSurfaceVariant = Color(0xFF88B4CC),
    surfaceContainerHighest = Color(0xFF1C2436),
    outline = Color(0xFF2E3C56),
    outlineVariant = Color(0xFF1C2436),
    inverseSurface = Color(0xFFDCF5FF),
    inverseOnSurface = Color(0xFF0A0E18),
    inversePrimary = Color(0xFF3EA814),
    statusBar = Color(0xFF020306)
)

enum class AppPalette(
    val id: String,
    val label: String,
    val isDark: Boolean,
    val colors: FolioColors
) {
    LIGHT("light", "Light", false, LightFolioColors),
    WARM("warm", "Warm", false, WarmFolioColors),
    MATCHA("matcha", "Matcha", false, MatchaFolioColors),
    ARCTIC("arctic", "Arctic", false, ArcticFolioColors),
    SAKURA("sakura", "Sakura", false, SakuraFolioColors),
    HONEY("honey", "Honey", false, HoneyFolioColors),
    MOSS("moss", "Moss", false, MossFolioColors),
    BUBBLEGUM("bubblegum", "Bubblegum", false, BubblegumFolioColors),
    SHERBET("sherbet", "Sherbet", false, SherbetFolioColors),
    HYPERPOP("hyperpop", "Hyperpop", false, HyperpopFolioColors),
    RAINBOW("rainbow", "Rainbow Candy", false, RainbowCandyFolioColors),
    DARK("dark", "Dark", true, DarkFolioColors),
    MIDNIGHT("midnight", "Midnight", true, MidnightFolioColors),
    DUSK("dusk", "Dusk", true, DuskFolioColors),
    ESPRESSO("espresso", "Espresso", true, EspressoFolioColors),
    OCEAN("ocean", "Ocean", true, OceanFolioColors),
    GRAPE("grape", "Grape", true, GrapeFolioColors),
    EMBER("ember", "Ember", true, EmberFolioColors),
    SYNTHWAVE("synthwave", "Synthwave", true, SynthwaveFolioColors),
    ACID("acid", "Acid Rave", true, AcidFolioColors),
    LAVA("lava", "Lava", true, LavaFolioColors),
    VAPORWAVE("vaporwave", "Vaporwave", true, VaporwaveFolioColors),
    NEON_TOKYO("neontokyo", "Neon Tokyo", true, NeonTokyoFolioColors),
    TOXIC_LIME("toxiclime", "Toxic Lime", true, ToxicLimeFolioColors),
    RETRO_SUNSET("retrosunset", "Retro Sunset", true, RetroSunsetFolioColors),
    PEACOCK("peacock", "Peacock", true, PeacockFolioColors),
    MIDNIGHT_NEON("midnightneon", "Midnight Neon", true, MidnightNeonFolioColors),
    OLED("oled", "Black", true, OledFolioColors);

    companion object {
        fun byId(id: String): AppPalette = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

data class ThemePack(
    val id: String,
    val name: String,
    val appPaletteId: String,
    val readerThemeId: String,
) {
    companion object {
        val ALL = listOf(
            ThemePack("gallery", "Gallery", "light", "white"),
            ThemePack("manuscript", "Manuscript", "warm", "sepia"),
            ThemePack("matcha", "Matcha", "matcha", "matcha"),
            ThemePack("arctic", "Arctic", "arctic", "arctic"),
            ThemePack("sakura", "Sakura", "sakura", "paper"),
            ThemePack("honey", "Honey", "honey", "sepia"),
            ThemePack("moss", "Moss", "moss", "moss"),
            ThemePack("dark", "Dark", "dark", "dark"),
            ThemePack("nocturne", "Nocturne", "midnight", "dark"),
            ThemePack("dusk", "Dusk", "dusk", "dusk"),
            ThemePack("espresso", "Espresso", "espresso", "espresso"),
            ThemePack("ocean", "Ocean", "ocean", "dark"),
            ThemePack("grape", "Grape", "grape", "dusk"),
            ThemePack("ember", "Ember", "ember", "ember"),
            ThemePack("obsidian", "Obsidian", "oled", "oled_black"),
            ThemePack("synthwave", "Synthwave", "synthwave", "synthwave"),
            ThemePack("bubblegum", "Bubblegum", "bubblegum", "bubblegum"),
            ThemePack("acid", "Acid Rave", "acid", "acid"),
            ThemePack("lava", "Lava", "lava", "lava"),
            ThemePack("sherbet", "Sherbet", "sherbet", "sherbet"),
            ThemePack("vaporwave", "Vaporwave", "vaporwave", "vaporwave"),
            ThemePack("neontokyo", "Neon Tokyo", "neontokyo", "neontokyo"),
            ThemePack("toxiclime", "Toxic Lime", "toxiclime", "toxiclime"),
            ThemePack("retrosunset", "Retro Sunset", "retrosunset", "retrosunset"),
            ThemePack("peacock", "Peacock", "peacock", "peacock"),
            ThemePack("midnightneon", "Midnight Neon", "midnightneon", "midnightneon"),
            ThemePack("hyperpop", "Hyperpop", "hyperpop", "candypop"),
            ThemePack("rainbow", "Rainbow Candy", "rainbow", "rainbow"),
        )
    }
}

data class FolioTypography(val fontTheme: FontTheme = FontTheme.CLASSIC) {
    val displayLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 96f),
        fontWeight = FontWeight.W600,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.02).em
    )
    val displayMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 72f),
        fontWeight = FontWeight.W600,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.015).em
    )
    val displaySmall: TextStyle = TextStyle(
        fontFamily = UiFonts.display(fontTheme, weight = 600, opticalSize = 60f),
        fontWeight = FontWeight.W600,
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
        fontFamily = UiFonts.text(fontTheme, weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    val labelMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    )
    val labelSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(fontTheme, weight = 600),
        fontWeight = FontWeight.W600,
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

    // Motion durations – no literal durations elsewhere in new code
    val motionFast = 120L         // State flips: chip select, checkbox toggle
    val motionStandard = 220L     // Enter/exit, crossfade, panel slide
    val motionEmphasis = 320L     // Bottom sheet, full-screen transition

    // Progress ring diameter when decorating covers
    val ringSmall = 28.dp

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
        content: @Composable () -> Unit
    ) {
        CompositionLocalProvider(
            LocalFolioColors provides colors,
            LocalFolioTypography provides typography
        ) {
            androidx.compose.material3.MaterialTheme(
                colorScheme = colors.toColorScheme(),
                typography = typography.toTypography(),
                shapes = Shapes(),
                content = content
            )
        }
    }

    @Composable
    fun AppTheme(
        palette: AppPalette,
        fontTheme: FontTheme = FontTheme.CLASSIC,
        content: @Composable () -> Unit
    ) {
        val typo = FolioTypography(fontTheme)
        MaterialTheme(darkTheme = palette.isDark, colors = palette.colors, typography = typo, content = content)
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
