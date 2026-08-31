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

val LightFolioColors = FolioColors()

val DarkFolioColors = FolioColors(
    primary = Color(0xFF8B9DC3),
    onPrimary = Color(0xFF0F0F0F),
    primaryContainer = Color(0xFF2D2F33),
    onPrimaryContainer = Color(0xFFD4D6DA),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF0F0F0F),
    secondaryContainer = Color(0xFF27282C),
    onSecondaryContainer = Color(0xFFD1D5DB),
    tertiary = Color(0xFF6EE7B7),
    onTertiary = Color(0xFF0F0F0F),
    tertiaryContainer = Color(0xFF1E2825),
    onTertiaryContainer = Color(0xFFD1F4E5),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF0F0F0F),
    errorContainer = Color(0xFF5D1F1A),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    shadow = Color.Black,
    scrim = Color.Black,
    inverseSurface = Color(0xFFE5E7EB),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF5B5D61),
    statusBar = Color(0xFF000000)
)

private val WarmFolioColors = LightFolioColors.copy(
    background = Color(0xFFF7EEDD),
    onBackground = Color(0xFF33291C),
    surface = Color(0xFFFFF9EC),
    onSurface = Color(0xFF33291C),
    surfaceVariant = Color(0xFFEEDFC6),
    onSurfaceVariant = Color(0xFF75603F),
    surfaceContainerHighest = Color(0xFFE6D3B2),
    outline = Color(0xFFCDB489),
    outlineVariant = Color(0xFFE6D3B2),
    inverseSurface = Color(0xFF33291C),
    inverseOnSurface = Color(0xFFF7EEDD),
    statusBar = Color(0xFF241B0E)
)

private val MatchaFolioColors = LightFolioColors.copy(
    background = Color(0xFFE9F2DC),
    onBackground = Color(0xFF26301A),
    surface = Color(0xFFF7FBEE),
    onSurface = Color(0xFF26301A),
    surfaceVariant = Color(0xFFDCEBC8),
    onSurfaceVariant = Color(0xFF5A6B41),
    surfaceContainerHighest = Color(0xFFCBDFB0),
    outline = Color(0xFFA8C487),
    outlineVariant = Color(0xFFCBDFB0),
    inverseSurface = Color(0xFF26301A),
    inverseOnSurface = Color(0xFFE9F2DC),
    statusBar = Color(0xFF182209)
)

private val ArcticFolioColors = LightFolioColors.copy(
    background = Color(0xFFE3F1F8),
    onBackground = Color(0xFF16293A),
    surface = Color(0xFFF3FBFF),
    onSurface = Color(0xFF16293A),
    surfaceVariant = Color(0xFFD3E8F2),
    onSurfaceVariant = Color(0xFF41617A),
    surfaceContainerHighest = Color(0xFFBCDCEA),
    outline = Color(0xFF93BDD3),
    outlineVariant = Color(0xFFBCDCEA),
    inverseSurface = Color(0xFF16293A),
    inverseOnSurface = Color(0xFFE3F1F8),
    statusBar = Color(0xFF0A1D2C)
)

private val SakuraFolioColors = LightFolioColors.copy(
    background = Color(0xFFFBF5F6),
    onBackground = Color(0xFF2E2024),
    surface = Color(0xFFFFF9FA),
    onSurface = Color(0xFF2E2024),
    surfaceVariant = Color(0xFFF5E8EB),
    onSurfaceVariant = Color(0xFF705A60),
    surfaceContainerHighest = Color(0xFFECDDE1),
    outline = Color(0xFFDBCACF),
    outlineVariant = Color(0xFFECDDE1),
    inverseSurface = Color(0xFF2E2024),
    inverseOnSurface = Color(0xFFFBF5F6),
    statusBar = Color(0xFF24181C)
)

private val HoneyFolioColors = LightFolioColors.copy(
    background = Color(0xFFFAF6EE),
    onBackground = Color(0xFF2C2517),
    surface = Color(0xFFFFFCF5),
    onSurface = Color(0xFF2C2517),
    surfaceVariant = Color(0xFFF2EADB),
    onSurfaceVariant = Color(0xFF6D6049),
    surfaceContainerHighest = Color(0xFFE8DDC9),
    outline = Color(0xFFD9CCB4),
    outlineVariant = Color(0xFFE8DDC9),
    inverseSurface = Color(0xFF2C2517),
    inverseOnSurface = Color(0xFFFAF6EE),
    statusBar = Color(0xFF221C0F)
)

private val DuskFolioColors = DarkFolioColors.copy(
    background = Color(0xFF141120),
    onBackground = Color(0xFFE4E0F2),
    surface = Color(0xFF1C1830),
    onSurface = Color(0xFFE4E0F2),
    surfaceVariant = Color(0xFF251F3C),
    onSurfaceVariant = Color(0xFF9E97BC),
    surfaceContainerHighest = Color(0xFF2F2848),
    primaryContainer = Color(0xFF292244),
    secondaryContainer = Color(0xFF251F3C),
    outline = Color(0xFF3B3357),
    outlineVariant = Color(0xFF2F2848),
    inverseSurface = Color(0xFFE4E0F2),
    inverseOnSurface = Color(0xFF1C1830),
    statusBar = Color(0xFF0A0814)
)

private val EspressoFolioColors = DarkFolioColors.copy(
    background = Color(0xFF161210),
    onBackground = Color(0xFFE9E1D6),
    surface = Color(0xFF1F1915),
    onSurface = Color(0xFFE9E1D6),
    surfaceVariant = Color(0xFF28211B),
    onSurfaceVariant = Color(0xFFA79A8C),
    surfaceContainerHighest = Color(0xFF322A22),
    primaryContainer = Color(0xFF2E261E),
    secondaryContainer = Color(0xFF28211B),
    outline = Color(0xFF3D342B),
    outlineVariant = Color(0xFF322A22),
    inverseSurface = Color(0xFFE9E1D6),
    inverseOnSurface = Color(0xFF1F1915),
    statusBar = Color(0xFF0C0906)
)

private val MidnightFolioColors = DarkFolioColors.copy(
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFDCE3EF),
    surface = Color(0xFF131C2E),
    onSurface = Color(0xFFDCE3EF),
    surfaceVariant = Color(0xFF1B263C),
    onSurfaceVariant = Color(0xFF93A1B8),
    surfaceContainerHighest = Color(0xFF24334D),
    primaryContainer = Color(0xFF1E2A40),
    secondaryContainer = Color(0xFF1B263C),
    outline = Color(0xFF33425C),
    outlineVariant = Color(0xFF24334D),
    inverseSurface = Color(0xFFDCE3EF),
    inverseOnSurface = Color(0xFF131C2E),
    statusBar = Color(0xFF060B16)
)

private val OceanFolioColors = DarkFolioColors.copy(
    background = Color(0xFF0A1518),
    onBackground = Color(0xFFD8E6EA),
    surface = Color(0xFF111E23),
    onSurface = Color(0xFFD8E6EA),
    surfaceVariant = Color(0xFF182A31),
    onSurfaceVariant = Color(0xFF8DAAB5),
    surfaceContainerHighest = Color(0xFF20363F),
    primaryContainer = Color(0xFF1A2E36),
    secondaryContainer = Color(0xFF182A31),
    outline = Color(0xFF2E444D),
    outlineVariant = Color(0xFF20363F),
    inverseSurface = Color(0xFFD8E6EA),
    inverseOnSurface = Color(0xFF111E23),
    statusBar = Color(0xFF050C0E)
)

private val GrapeFolioColors = DarkFolioColors.copy(
    background = Color(0xFF16101A),
    onBackground = Color(0xFFE8DCF0),
    surface = Color(0xFF1F1724),
    onSurface = Color(0xFFE8DCF0),
    surfaceVariant = Color(0xFF2A1F30),
    onSurfaceVariant = Color(0xFFA998B5),
    surfaceContainerHighest = Color(0xFF35283D),
    primaryContainer = Color(0xFF2E2236),
    secondaryContainer = Color(0xFF2A1F30),
    outline = Color(0xFF40334A),
    outlineVariant = Color(0xFF35283D),
    inverseSurface = Color(0xFFE8DCF0),
    inverseOnSurface = Color(0xFF1F1724),
    statusBar = Color(0xFF0C0810)
)

private val OledFolioColors = DarkFolioColors.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF0C0C0C),
    surfaceVariant = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    primaryContainer = Color(0xFF1A1D24),
    secondaryContainer = Color(0xFF17181B),
    tertiaryContainer = Color(0xFF121A18),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1A1A1A),
    statusBar = Color(0xFF000000)
)

private val MossFolioColors = LightFolioColors.copy(
    background = Color(0xFFF2F7F4),
    onBackground = Color(0xFF212B26),
    surface = Color(0xFFFBFEFC),
    onSurface = Color(0xFF212B26),
    surfaceVariant = Color(0xFFE3EEE7),
    onSurfaceVariant = Color(0xFF5A6B62),
    surfaceContainerHighest = Color(0xFFD6E5DC),
    outline = Color(0xFFBBD0C4),
    outlineVariant = Color(0xFFD6E5DC),
    inverseSurface = Color(0xFF212B26),
    inverseOnSurface = Color(0xFFF2F7F4),
    statusBar = Color(0xFF16211B)
)

private val EmberFolioColors = DarkFolioColors.copy(
    background = Color(0xFF1A1210),
    onBackground = Color(0xFFF2E4DC),
    surface = Color(0xFF241815),
    onSurface = Color(0xFFF2E4DC),
    surfaceVariant = Color(0xFF2E201B),
    onSurfaceVariant = Color(0xFFBBA196),
    surfaceContainerHighest = Color(0xFF392822),
    primaryContainer = Color(0xFF3D2A20),
    secondaryContainer = Color(0xFF2E201B),
    outline = Color(0xFF4A362C),
    outlineVariant = Color(0xFF392822),
    inverseSurface = Color(0xFFF2E4DC),
    inverseOnSurface = Color(0xFF241815),
    statusBar = Color(0xFF100A08)
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
    DARK("dark", "Dark", true, DarkFolioColors),
    MIDNIGHT("midnight", "Midnight", true, MidnightFolioColors),
    DUSK("dusk", "Dusk", true, DuskFolioColors),
    ESPRESSO("espresso", "Espresso", true, EspressoFolioColors),
    OCEAN("ocean", "Ocean", true, OceanFolioColors),
    GRAPE("grape", "Grape", true, GrapeFolioColors),
    EMBER("ember", "Ember", true, EmberFolioColors),
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
