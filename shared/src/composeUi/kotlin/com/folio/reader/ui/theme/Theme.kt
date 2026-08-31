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
    primary = Color(0xFFBF8C3E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8C8),
    onPrimaryContainer = Color(0xFF4A3210),
    secondary = Color(0xFF8B7355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E6D0),
    onSecondaryContainer = Color(0xFF3E2E18),
    tertiary = Color(0xFFC47A3A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC0),
    onTertiaryContainer = Color(0xFF5C2E08),
    background = Color(0xFFFFFBF0),
    onBackground = Color(0xFF3B2A14),
    surface = Color(0xFFFFF8EC),
    onSurface = Color(0xFF3B2A14),
    surfaceVariant = Color(0xFFF5ECD8),
    onSurfaceVariant = Color(0xFF7A6544),
    surfaceContainerHighest = Color(0xFFE8D9BE),
    outline = Color(0xFFD4C4A4),
    outlineVariant = Color(0xFFE8D9BE),
    inverseSurface = Color(0xFF3B2A14),
    inverseOnSurface = Color(0xFFFFFBF0),
    statusBar = Color(0xFF2E1E0A)
)

private val MatchaFolioColors = LightFolioColors.copy(
    primary = Color(0xFF4A8C3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F0C8),
    onPrimaryContainer = Color(0xFF1A3A12),
    secondary = Color(0xFF6B8C5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4F0DA),
    onSecondaryContainer = Color(0xFF2A3A20),
    tertiary = Color(0xFF3A8C6A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8F0DC),
    onTertiaryContainer = Color(0xFF0A3A28),
    background = Color(0xFFF4FAF0),
    onBackground = Color(0xFF1E2E18),
    surface = Color(0xFFF8FCF4),
    onSurface = Color(0xFF1E2E18),
    surfaceVariant = Color(0xFFE4F0DA),
    onSurfaceVariant = Color(0xFF4A6438),
    surfaceContainerHighest = Color(0xFFCCE0BC),
    outline = Color(0xFFB4CCA4),
    outlineVariant = Color(0xFFCCE0BC),
    inverseSurface = Color(0xFF1E2E18),
    inverseOnSurface = Color(0xFFF4FAF0),
    statusBar = Color(0xFF12200C)
)

private val ArcticFolioColors = LightFolioColors.copy(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D3B6E),
    secondary = Color(0xFF5C7C9A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEAF4),
    onSecondaryContainer = Color(0xFF1A3048),
    tertiary = Color(0xFF0097A7),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2EBF2),
    onTertiaryContainer = Color(0xFF004D57),
    background = Color(0xFFF5FAFF),
    onBackground = Color(0xFF0D2137),
    surface = Color(0xFFF8FCFF),
    onSurface = Color(0xFF0D2137),
    surfaceVariant = Color(0xFFE0EFFA),
    onSurfaceVariant = Color(0xFF3A5A78),
    surfaceContainerHighest = Color(0xFFBCD4EA),
    outline = Color(0xFF9CC0DC),
    outlineVariant = Color(0xFFBCD4EA),
    inverseSurface = Color(0xFF0D2137),
    inverseOnSurface = Color(0xFFF5FAFF),
    statusBar = Color(0xFF061424)
)

private val SakuraFolioColors = LightFolioColors.copy(
    primary = Color(0xFFD8467A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD6E4),
    onPrimaryContainer = Color(0xFF5C1430),
    secondary = Color(0xFF9A6078),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E0EA),
    onSecondaryContainer = Color(0xFF4A2030),
    tertiary = Color(0xFFC0508A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFCCE0),
    onTertiaryContainer = Color(0xFF501438),
    background = Color(0xFFFFF5F8),
    onBackground = Color(0xFF3A1424),
    surface = Color(0xFFFFF8FA),
    onSurface = Color(0xFF3A1424),
    surfaceVariant = Color(0xFFFCE4EC),
    onSurfaceVariant = Color(0xFF7A4058),
    surfaceContainerHighest = Color(0xFFF0C4D4),
    outline = Color(0xFFE0A8BC),
    outlineVariant = Color(0xFFF0C4D4),
    inverseSurface = Color(0xFF3A1424),
    inverseOnSurface = Color(0xFFFFF5F8),
    statusBar = Color(0xFF2A0C18)
)

private val HoneyFolioColors = LightFolioColors.copy(
    primary = Color(0xFFE6A817),
    onPrimary = Color(0xFF3E2800),
    primaryContainer = Color(0xFFFFECB3),
    onPrimaryContainer = Color(0xFF4A3400),
    secondary = Color(0xFF9A8040),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5ECD0),
    onSecondaryContainer = Color(0xFF3E3010),
    tertiary = Color(0xFFD4960A),
    onTertiary = Color(0xFF3E2800),
    tertiaryContainer = Color(0xFFFFE0A0),
    onTertiaryContainer = Color(0xFF4A3000),
    background = Color(0xFFFFFCE8),
    onBackground = Color(0xFF3A2C08),
    surface = Color(0xFFFFFDF0),
    onSurface = Color(0xFF3A2C08),
    surfaceVariant = Color(0xFFF8F0D0),
    onSurfaceVariant = Color(0xFF7A6828),
    surfaceContainerHighest = Color(0xFFE8DCA8),
    outline = Color(0xFFD4C888),
    outlineVariant = Color(0xFFE8DCA8),
    inverseSurface = Color(0xFF3A2C08),
    inverseOnSurface = Color(0xFFFFFCE8),
    statusBar = Color(0xFF2A1E04)
)

private val MossFolioColors = LightFolioColors.copy(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E0CC),
    onPrimaryContainer = Color(0xFF0A3020),
    secondary = Color(0xFF5A7A6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8ECE2),
    onSecondaryContainer = Color(0xFF1A3028),
    tertiary = Color(0xFF3A7A50),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC0E8D0),
    onTertiaryContainer = Color(0xFF0A3018),
    background = Color(0xFFF2F8F4),
    onBackground = Color(0xFF142820),
    surface = Color(0xFFF8FCFA),
    onSurface = Color(0xFF142820),
    surfaceVariant = Color(0xFFDCE8E0),
    onSurfaceVariant = Color(0xFF3A5A48),
    surfaceContainerHighest = Color(0xFFC0D8CA),
    outline = Color(0xFFA4C4B0),
    outlineVariant = Color(0xFFC0D8CA),
    inverseSurface = Color(0xFF142820),
    inverseOnSurface = Color(0xFFF2F8F4),
    statusBar = Color(0xFF0A1A14)
)

// ── DARK THEMES ─────────────────────────────────────────────────────────

private val DuskFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFB8A0F0),
    onPrimary = Color(0xFF2A1850),
    primaryContainer = Color(0xFF3C2870),
    onPrimaryContainer = Color(0xFFE0D4FF),
    secondary = Color(0xFF9A90C0),
    onSecondary = Color(0xFF1A1430),
    secondaryContainer = Color(0xFF2A2048),
    onSecondaryContainer = Color(0xFFD0C8E8),
    tertiary = Color(0xFFC8A0E8),
    onTertiary = Color(0xFF301848),
    tertiaryContainer = Color(0xFF402060),
    onTertiaryContainer = Color(0xFFE8D0FF),
    background = Color(0xFF120E20),
    onBackground = Color(0xFFE4DCF8),
    surface = Color(0xFF1A1430),
    onSurface = Color(0xFFE4DCF8),
    surfaceVariant = Color(0xFF241C40),
    onSurfaceVariant = Color(0xFF9888C0),
    surfaceContainerHighest = Color(0xFF302858),
    outline = Color(0xFF403868),
    outlineVariant = Color(0xFF302858),
    inverseSurface = Color(0xFFE4DCF8),
    inverseOnSurface = Color(0xFF1A1430),
    statusBar = Color(0xFF080610)
)

private val EspressoFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFD4A870),
    onPrimary = Color(0xFF3E2810),
    primaryContainer = Color(0xFF5C3C18),
    onPrimaryContainer = Color(0xFFF0DCC0),
    secondary = Color(0xFFB09878),
    onSecondary = Color(0xFF2A1E10),
    secondaryContainer = Color(0xFF3C2E1C),
    onSecondaryContainer = Color(0xFFE0D0B8),
    tertiary = Color(0xFFC89860),
    onTertiary = Color(0xFF3E2808),
    tertiaryContainer = Color(0xFF503410),
    onTertiaryContainer = Color(0xFFF0D8B0),
    background = Color(0xFF18100A),
    onBackground = Color(0xFFF0E4D4),
    surface = Color(0xFF221810),
    onSurface = Color(0xFFF0E4D4),
    surfaceVariant = Color(0xFF2E2218),
    onSurfaceVariant = Color(0xFFB0A088),
    surfaceContainerHighest = Color(0xFF3C2E20),
    outline = Color(0xFF4E3E2C),
    outlineVariant = Color(0xFF3C2E20),
    inverseSurface = Color(0xFFF0E4D4),
    inverseOnSurface = Color(0xFF221810),
    statusBar = Color(0xFF0E0804)
)

private val MidnightFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF6090D0),
    onPrimary = Color(0xFF0A2040),
    primaryContainer = Color(0xFF183860),
    onPrimaryContainer = Color(0xFFC0D4F0),
    secondary = Color(0xFF8098B8),
    onSecondary = Color(0xFF0A1828),
    secondaryContainer = Color(0xFF182840),
    onSecondaryContainer = Color(0xFFC0D0E8),
    tertiary = Color(0xFF5888C0),
    onTertiary = Color(0xFF082040),
    tertiaryContainer = Color(0xFF143058),
    onTertiaryContainer = Color(0xFFB8D0F0),
    background = Color(0xFF080E1A),
    onBackground = Color(0xFFD0DCF0),
    surface = Color(0xFF0E1624),
    onSurface = Color(0xFFD0DCF0),
    surfaceVariant = Color(0xFF162034),
    onSurfaceVariant = Color(0xFF7890B0),
    surfaceContainerHighest = Color(0xFF203048),
    outline = Color(0xFF304060),
    outlineVariant = Color(0xFF203048),
    inverseSurface = Color(0xFFD0DCF0),
    inverseOnSurface = Color(0xFF0E1624),
    statusBar = Color(0xFF040810)
)

private val OceanFolioColors = DarkFolioColors.copy(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003D38),
    primaryContainer = Color(0xFF00564E),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF80A8A0),
    onSecondary = Color(0xFF002824),
    secondaryContainer = Color(0xFF103834),
    onSecondaryContainer = Color(0xFFC0E0D8),
    tertiary = Color(0xFF40A898),
    onTertiary = Color(0xFF003830),
    tertiaryContainer = Color(0xFF004D44),
    onTertiaryContainer = Color(0xFFA8E0D4),
    background = Color(0xFF061414),
    onBackground = Color(0xFFD0EAE8),
    surface = Color(0xFF0C1E1E),
    onSurface = Color(0xFFD0EAE8),
    surfaceVariant = Color(0xFF142A28),
    onSurfaceVariant = Color(0xFF78A8A0),
    surfaceContainerHighest = Color(0xFF1E3A38),
    outline = Color(0xFF2E4E4A),
    outlineVariant = Color(0xFF1E3A38),
    inverseSurface = Color(0xFFD0EAE8),
    inverseOnSurface = Color(0xFF0C1E1E),
    statusBar = Color(0xFF020A0A)
)

private val GrapeFolioColors = DarkFolioColors.copy(
    primary = Color(0xFFD080C0),
    onPrimary = Color(0xFF401038),
    primaryContainer = Color(0xFF602050),
    onPrimaryContainer = Color(0xFFF0C8E8),
    secondary = Color(0xFFB080A8),
    onSecondary = Color(0xFF301028),
    secondaryContainer = Color(0xFF401838),
    onSecondaryContainer = Color(0xFFE0C0D8),
    tertiary = Color(0xFFC870B0),
    onTertiary = Color(0xFF401030),
    tertiaryContainer = Color(0xFF581848),
    onTertiaryContainer = Color(0xFFF0B8E0),
    background = Color(0xFF180C18),
    onBackground = Color(0xFFF0D8EC),
    surface = Color(0xFF221424),
    onSurface = Color(0xFFF0D8EC),
    surfaceVariant = Color(0xFF2E1C30),
    onSurfaceVariant = Color(0xFFA880A0),
    surfaceContainerHighest = Color(0xFF3E2840),
    outline = Color(0xFF503858),
    outlineVariant = Color(0xFF3E2840),
    inverseSurface = Color(0xFFF0D8EC),
    inverseOnSurface = Color(0xFF221424),
    statusBar = Color(0xFF0E060E)
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
    primary = Color(0xFFE87840),
    onPrimary = Color(0xFF3E1408),
    primaryContainer = Color(0xFF6E2810),
    onPrimaryContainer = Color(0xFFFFDCC8),
    secondary = Color(0xFFC09070),
    onSecondary = Color(0xFF301808),
    secondaryContainer = Color(0xFF482410),
    onSecondaryContainer = Color(0xFFF0D0B8),
    tertiary = Color(0xFFD86830),
    onTertiary = Color(0xFF3E1404),
    tertiaryContainer = Color(0xFF5C2008),
    onTertiaryContainer = Color(0xFFFFCCA8),
    background = Color(0xFF1A0E08),
    onBackground = Color(0xFFF8E4D4),
    surface = Color(0xFF26140C),
    onSurface = Color(0xFFF8E4D4),
    surfaceVariant = Color(0xFF341C10),
    onSurfaceVariant = Color(0xFFC09878),
    surfaceContainerHighest = Color(0xFF442818),
    outline = Color(0xFF583820),
    outlineVariant = Color(0xFF442818),
    inverseSurface = Color(0xFFF8E4D4),
    inverseOnSurface = Color(0xFF26140C),
    statusBar = Color(0xFF100604)
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
    onSecondary = Color.White,
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
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF5C1E00),
    secondary = Color(0xFF2ED9A3),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFFC4F8E4),
    onSecondaryContainer = Color(0xFF0A4430),
    tertiary = Color(0xFF4DC3FF),
    onTertiary = Color.White,
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
    val radiusCard = 20.dp
    val radiusControl = 14.dp
    val radiusChip = 10.dp

    val space1 = 8.dp
    val space2 = 12.dp
    val space3 = 16.dp
    val space4 = 24.dp

    val barHeight = 56.dp
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
