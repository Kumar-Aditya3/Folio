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
    val primary: Color = Color(0xFF4F46E5),           // indigo-600
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFE0E7FF),  // indigo-100
    val onPrimaryContainer: Color = Color(0xFF1E1B4B),// indigo-900
    val secondary: Color = Color(0xFF475569),         // slate-600
    val onSecondary: Color = Color.White,
    val secondaryContainer: Color = Color(0xFFF1F5F9),// slate-100
    val onSecondaryContainer: Color = Color(0xFF0F172A),// slate-900
    val tertiary: Color = Color(0xFF0D9488),          // teal-600
    val onTertiary: Color = Color.White,
    val tertiaryContainer: Color = Color(0xFFCCFBF1), // teal-100
    val onTertiaryContainer: Color = Color(0xFF134E4A),// teal-900
    val error: Color = Color(0xFFB3261E),
    val onError: Color = Color.White,
    val errorContainer: Color = Color(0xFFF9DEDC),
    val onErrorContainer: Color = Color(0xFF410E0B),
    val background: Color = Color(0xFFF8FAFC),        // slate-50
    val onBackground: Color = Color(0xFF0F172A),      // slate-950
    val surface: Color = Color.White,
    val onSurface: Color = Color(0xFF0F172A),
    val surfaceVariant: Color = Color(0xFFF1F5F9),    // slate-100
    val onSurfaceVariant: Color = Color(0xFF64748B),  // slate-500
    val surfaceContainerHighest: Color = Color(0xFFE2E8F0), // slate-200
    val outline: Color = Color(0xFFCBD5E1),           // slate-300
    val outlineVariant: Color = Color(0xFFE2E8F0),
    val shadow: Color = Color.Black,
    val scrim: Color = Color.Black,
    val inverseSurface: Color = Color(0xFF0F172A),
    val inverseOnSurface: Color = Color(0xFFF8FAFC),
    val inversePrimary: Color = Color(0xFF818CF8),    // indigo-400
    /** Darker themed band behind the OS status bar on non-reader screens. */
    val statusBar: Color = Color(0xFF0F172A)
)

val LightFolioColors = FolioColors()

val DarkFolioColors = FolioColors(
    primary = Color(0xFF8B9DC3),          // Muted indigo-gray (keeping some character)
    onPrimary = Color(0xFF0F0F0F),
    primaryContainer = Color(0xFF2D2F33), // Neutral dark gray
    onPrimaryContainer = Color(0xFFD4D6DA),
    secondary = Color(0xFF9CA3AF),        // Neutral gray
    onSecondary = Color(0xFF0F0F0F),
    secondaryContainer = Color(0xFF27282C),
    onSecondaryContainer = Color(0xFFD1D5DB),
    tertiary = Color(0xFF6EE7B7),         // Accent teal
    onTertiary = Color(0xFF0F0F0F),
    tertiaryContainer = Color(0xFF1E2825),
    onTertiaryContainer = Color(0xFFD1F4E5),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF0F0F0F),
    errorContainer = Color(0xFF5D1F1A),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F0F0F),       // Pure dark background
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF1A1A1A),          // Neutral dark charcoal
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF232323),   // Slightly lighter neutral
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

/**
 * Chrome-only neutrals. Each app palette overrides background/surface/outline and
 * leaves the accent alone, so switching the app theme changes the room the app is in
 * without changing its identity — and never touches the page, which has its own
 * theme entirely.
 */
private val WarmFolioColors = LightFolioColors.copy(
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF2B2622),
    surface = Color(0xFFFFFDFA),
    onSurface = Color(0xFF2B2622),
    surfaceVariant = Color(0xFFF1EAE1),
    onSurfaceVariant = Color(0xFF6B6157),
    surfaceContainerHighest = Color(0xFFE7DED3),
    outline = Color(0xFFD8CEC2),
    outlineVariant = Color(0xFFE7DED3),
    inverseSurface = Color(0xFF2B2622),
    inverseOnSurface = Color(0xFFFAF7F2),
    statusBar = Color(0xFF241F1A)
)

private val MatchaFolioColors = LightFolioColors.copy(
    background = Color(0xFFF5F8F0),
    onBackground = Color(0xFF252E1F),
    surface = Color(0xFFFCFEF9),
    onSurface = Color(0xFF252E1F),
    surfaceVariant = Color(0xFFE8EFDF),
    onSurfaceVariant = Color(0xFF5F6D52),
    surfaceContainerHighest = Color(0xFFDCE7CF),
    outline = Color(0xFFC4D2B4),
    outlineVariant = Color(0xFFDCE7CF),
    inverseSurface = Color(0xFF252E1F),
    inverseOnSurface = Color(0xFFF5F8F0),
    statusBar = Color(0xFF1B2415)
)

private val ArcticFolioColors = LightFolioColors.copy(
    background = Color(0xFFF1F7FA),
    onBackground = Color(0xFF1E2B33),
    surface = Color(0xFFFAFDFE),
    onSurface = Color(0xFF1E2B33),
    surfaceVariant = Color(0xFFE1EDF3),
    onSurfaceVariant = Color(0xFF587080),
    surfaceContainerHighest = Color(0xFFD3E4EC),
    outline = Color(0xFFB9CFDA),
    outlineVariant = Color(0xFFD3E4EC),
    inverseSurface = Color(0xFF1E2B33),
    inverseOnSurface = Color(0xFFF1F7FA),
    statusBar = Color(0xFF0F202B)
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

/** The app's own selectable chrome themes, entirely separate from [Theme]. */
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
    DARK("dark", "Dark", true, DarkFolioColors),
    MIDNIGHT("midnight", "Midnight", true, MidnightFolioColors),
    DUSK("dusk", "Dusk", true, DuskFolioColors),
    ESPRESSO("espresso", "Espresso", true, EspressoFolioColors),
    OLED("oled", "Black", true, OledFolioColors);

    companion object {
        fun byId(id: String): AppPalette = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

/**
 * A curated pair of app chrome + reading page so the two moods match without being
 * the same theme. Applying a pack sets both; each side stays independently editable
 * afterwards.
 */
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
            ThemePack("nocturne", "Nocturne", "midnight", "dark"),
            ThemePack("dusk", "Dusk", "dusk", "dusk"),
            ThemePack("espresso", "Espresso", "espresso", "espresso"),
            ThemePack("obsidian", "Obsidian", "oled", "oled_black"),
        )
    }
}

/**
 * The type scale. Two faces only: Fraunces for the display voice (wordmark, book
 * titles, screen headers, stat numbers, quotations) and Manrope for everything
 * functional. Sizes and line heights are unchanged from the previous scale, so this
 * shifts no layout — the difference is made of weight and tracking.
 */
data class FolioTypography(
    val displayLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 96f),
        fontWeight = FontWeight.W600,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.02).em
    ),
    val displayMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 72f),
        fontWeight = FontWeight.W600,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.015).em
    ),
    val displaySmall: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 60f),
        fontWeight = FontWeight.W600,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.01).em
    ),
    val headlineLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 44f),
        fontWeight = FontWeight.W600,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.01).em
    ),
    val headlineMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 36f),
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    val headlineSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 28f),
        fontWeight = FontWeight.W600,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    // Book titles and screen headers: the serif is the "book" in a book app.
    val titleLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 600, opticalSize = 22f),
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    val titleMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    val titleSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val bodyLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 400),
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 400),
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.05.sp
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 400),
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    ),
    val labelLarge: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val labelMedium: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    // Section labels ("READING STATS", chips): small, wide-tracked sentence case —
    // the quiet-luxury step that needs no third face.
    val labelSmall: TextStyle = TextStyle(
        fontFamily = UiFonts.text(weight = 600),
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    /** Quotations and revisit items: the display face in its italic cut. */
    val quote: TextStyle = TextStyle(
        fontFamily = UiFonts.display(weight = 400, italic = true, opticalSize = 24f),
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
)

val LocalFolioColors = staticCompositionLocalOf { LightFolioColors }

/** Shared design vocabulary: one radius/spacing scale for the whole app. */
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

    /**
     * Built once: the scale is immutable and the interface faces are installed before
     * the first frame, so screens can share it instead of rebuilding ~18 TextStyles on
     * every read.
     */
    private val sharedTypography: FolioTypography by lazy { FolioTypography() }

    val typography: FolioTypography
        get() = sharedTypography

    @Composable
    fun MaterialTheme(
        darkTheme: Boolean = false,
        colors: FolioColors = if (darkTheme) DarkFolioColors else LightFolioColors,
        typography: FolioTypography = sharedTypography,
        content: @Composable () -> Unit
    ) {
        CompositionLocalProvider(LocalFolioColors provides colors) {
            androidx.compose.material3.MaterialTheme(
                colorScheme = colors.toColorScheme(),
                typography = typography.toTypography(),
                shapes = Shapes(),
                content = content
            )
        }
    }

    /**
     * The app's own chrome theme, chosen by [AppPalette].
     *
     * A reading theme describes the page: paper colour, text colour, typeface. It is
     * a per-book reading decision and must not leak into the app, and equally the app
     * must not repaint the page. Every surface that is not book text — library,
     * settings, reader bars, panels and the in-page overlays — comes from here.
     */
    @Composable
    fun AppTheme(palette: AppPalette, content: @Composable () -> Unit) {
        MaterialTheme(darkTheme = palette.isDark, colors = palette.colors, content = content)
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