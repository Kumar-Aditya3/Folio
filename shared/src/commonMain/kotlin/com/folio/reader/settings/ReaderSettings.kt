package com.folio.reader.settings

import com.folio.reader.model.Book
import com.folio.reader.model.FormattingMode
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ReaderSettings(
    val fontFamily: String = "Literata",
    val fontSize: Float = 18f,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.5f,
    val letterSpacing: Float = 0f,
    val wordSpacing: Float = 0f,
    val paragraphSpacing: Float = 1.0f,
    val margins: Margins = Margins(),
    val textWidth: TextWidth = TextWidth.MEDIUM,
    val alignment: TextAlignment = TextAlignment.LEFT,
    val hyphenation: Boolean = true,
    val themeId: String = "paper",
    val layoutMode: LayoutMode = LayoutMode.CONTINUOUS,
    val formattingMode: FormattingMode = FormattingMode.HYBRID,
    val showChapterTitle: Boolean = true,
    val showProgress: Boolean = true,
    val showClock: Boolean = false,
    val customTheme: Theme? = null,
    val useEmbeddedFonts: Boolean = true,
    val appThemeId: String = "light",
    val fontThemeId: String = "classic",
    val customFonts: List<CustomFont> = emptyList(),
    // Index into the active theme's highlightColors palette used for new highlights.
    val highlightColorIndex: Int = 0,
    val firebaseApiKey: String = "",
    val firebaseProjectId: String = "",
    // Cloud sync settings — default true so existing installs with credentials already stored continue syncing
    val cloudSyncEnabled: Boolean = true,
    val syncAccountEmail: String = "",
    val syncAccountPassword: String = "",
    val syncPositions: Boolean = true,
    val syncAnnotations: Boolean = true,
    val syncSettings: Boolean = true,
    val syncEpubs: Boolean = false, // Opt-in for EPUB cloud storage
    val autoSyncInterval: Int = 15 // minutes
) {
    fun copyWith(bookSettings: BookReaderSettings): ReaderSettings {
        return copy(
            fontFamily = bookSettings.fontFamily ?: fontFamily,
            fontSize = bookSettings.fontSize ?: fontSize,
            fontWeight = bookSettings.fontWeight ?: fontWeight,
            lineHeight = bookSettings.lineHeight ?: lineHeight,
            letterSpacing = bookSettings.letterSpacing ?: letterSpacing,
            wordSpacing = bookSettings.wordSpacing ?: wordSpacing,
            paragraphSpacing = bookSettings.paragraphSpacing ?: paragraphSpacing,
            margins = bookSettings.margins ?: margins,
            textWidth = bookSettings.textWidth ?: textWidth,
            alignment = bookSettings.alignment ?: alignment,
            hyphenation = bookSettings.hyphenation ?: hyphenation,
            themeId = bookSettings.themeId ?: themeId,
            layoutMode = bookSettings.layoutMode ?: layoutMode,
            formattingMode = bookSettings.formattingMode ?: formattingMode,
            showChapterTitle = bookSettings.showChapterTitle ?: showChapterTitle,
            showProgress = bookSettings.showProgress ?: showProgress,
            showClock = bookSettings.showClock ?: showClock,
            customTheme = bookSettings.customTheme ?: customTheme
        )
    }
}

@Serializable
data class BookReaderSettings(
    val fontFamily: String? = null,
    val fontSize: Float? = null,
    val fontWeight: Int? = null,
    val lineHeight: Float? = null,
    val letterSpacing: Float? = null,
    val wordSpacing: Float? = null,
    val paragraphSpacing: Float? = null,
    val margins: Margins? = null,
    val textWidth: TextWidth? = null,
    val alignment: TextAlignment? = null,
    val hyphenation: Boolean? = null,
    val themeId: String? = null,
    val layoutMode: LayoutMode? = null,
    val formattingMode: FormattingMode? = null,
    val showChapterTitle: Boolean? = null,
    val showProgress: Boolean? = null,
    val showClock: Boolean? = null,
    val customTheme: Theme? = null
) {
    fun toReaderSettings(base: ReaderSettings): ReaderSettings {
        return base.copyWith(this)
    }
}

@Serializable
data class Margins(
    val left: Float = 24f,
    val right: Float = 24f,
    val top: Float = 32f,
    val bottom: Float = 32f
)

enum class TextWidth {
    NARROW,   // ~55ch
    MEDIUM,   // ~65ch
    WIDE,     // ~75ch
    FULL,     // 100% - margins
    CUSTOM    // Custom pixel value
}

enum class TextAlignment {
    LEFT,
    JUSTIFIED,
    CENTER
}

enum class LayoutMode {
    CONTINUOUS,     // Vertical scrolling
    PAGINATED,      // Page-based (horizontal or vertical)
    TWO_COLUMN,     // Two columns side by side
    FOCUS           // Distraction-free, minimal UI
}

@Serializable
data class Theme(
    val id: String,
    val name: String,
    val background: Int, // ARGB
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val headingText: Int,
    val link: Int,
    val selection: Int,
    val bookmark: Int,
    val highlightColors: List<Int>, // 8 colors
    val progress: Int,
    val divider: Int,
    val isDark: Boolean
) {
    companion object {
        val PRESETS = mapOf(
            "paper" to Theme(
                id = "paper", name = "Paper",
                background = 0xFFFDF6E3.toInt(), surface = 0xFFFFFFFF.toInt(),
                primaryText = 0xFF333333.toInt(), secondaryText = 0xFF666666.toInt(),
                headingText = 0xFF1A1A1A.toInt(), link = 0xFF268BD2.toInt(),
                selection = 0xFFB58900.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFFFFF00.toInt(), 0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF3B30.toInt(),
                    0xFFAF52DE.toInt(), 0xFFFF9F0A.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF2D92.toInt()
                ),
                progress = 0xFF268BD2.toInt(), divider = 0xFFE0DCCF.toInt(),
                isDark = false
            ),
            "white" to Theme(
                id = "white", name = "White",
                background = 0xFFFFFFFF.toInt(), surface = 0xFFF5F5F5.toInt(),
                primaryText = 0xFF1A1A1A.toInt(), secondaryText = 0xFF555555.toInt(),
                headingText = 0xFF000000.toInt(), link = 0xFF0066CC.toInt(),
                selection = 0xFFB3D9FF.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFFFFF00.toInt(), 0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF3B30.toInt(),
                    0xFFAF52DE.toInt(), 0xFFFF9F0A.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF2D92.toInt()
                ),
                progress = 0xFF0066CC.toInt(), divider = 0xFFE0E0E0.toInt(),
                isDark = false
            ),
            "sepia" to Theme(
                id = "sepia", name = "Sepia",
                background = 0xFFF4ECD8.toInt(), surface = 0xFFEFE8D0.toInt(),
                primaryText = 0xFF3D3328.toInt(), secondaryText = 0xFF6B5B44.toInt(),
                headingText = 0xFF2D251E.toInt(), link = 0xFF8B6B3A.toInt(),
                selection = 0xFFD4A843.toInt(), bookmark = 0xFFC0504D.toInt(),
                highlightColors = listOf(
                    0xFFE8C84D.toInt(), 0xFF4D8FC7.toInt(), 0xFF5DAE5D.toInt(), 0xFFD65F5F.toInt(),
                    0xFF9B7BC7.toInt(), 0xFFD4A843.toInt(), 0xFF5DB8B8.toInt(), 0xFFD67AB5.toInt()
                ),
                progress = 0xFF8B6B3A.toInt(), divider = 0xFFE0D4B8.toInt(),
                isDark = false
            ),
            "gray" to Theme(
                id = "gray", name = "Gray",
                background = 0xFFE8E8E8.toInt(), surface = 0xFFDDDDDD.toInt(),
                primaryText = 0xFF2A2A2A.toInt(), secondaryText = 0xFF555555.toInt(),
                headingText = 0xFF1A1A1A.toInt(), link = 0xFF006699.toInt(),
                selection = 0xFFB0D0F0.toInt(), bookmark = 0xFFCC3366.toInt(),
                highlightColors = listOf(
                    0xFFFFD700.toInt(), 0xFF4A90D9.toInt(), 0xFF50C878.toInt(), 0xFFE04040.toInt(),
                    0xFF9B59B6.toInt(), 0xFFF39C12.toInt(), 0xFF1ABC9C.toInt(), 0xFFE91E63.toInt()
                ),
                progress = 0xFF006699.toInt(), divider = 0xFFCCCCCC.toInt(),
                isDark = false
            ),
            "dark" to Theme(
                id = "dark", name = "Dark",
                background = 0xFF1E1E1E.toInt(), surface = 0xFF2D2D2D.toInt(),
                primaryText = 0xFFE0E0E0.toInt(), secondaryText = 0xFF9E9E9E.toInt(),
                headingText = 0xFFFFFFFF.toInt(), link = 0xFF64B5F6.toInt(),
                selection = 0xFF3A3A3A.toInt(), bookmark = 0xFFE91E63.toInt(),
                highlightColors = listOf(
                    0xFFFFD54F.toInt(), 0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFE57373.toInt(),
                    0xFFCE93D8.toInt(), 0xFFFFB74D.toInt(), 0xFF4DD0E1.toInt(), 0xFFF06292.toInt()
                ),
                progress = 0xFF64B5F6.toInt(), divider = 0xFF3D3D3D.toInt(),
                isDark = true
            ),
            "oled_black" to Theme(
                id = "oled_black", name = "OLED Black",
                background = 0xFF000000.toInt(), surface = 0xFF121212.toInt(),
                primaryText = 0xFFFFFFFF.toInt(), secondaryText = 0xFFB0B0B0.toInt(),
                headingText = 0xFFFFFFFF.toInt(), link = 0xFF82B1FF.toInt(),
                selection = 0xFF2A2A2A.toInt(), bookmark = 0xFFF06292.toInt(),
                highlightColors = listOf(
                    0xFFFFEB3B.toInt(), 0xFF64B5F6.toInt(), 0xFFA5D6A7.toInt(), 0xFFEF9A9A.toInt(),
                    0xFFD1C4E9.toInt(), 0xFFFFCC80.toInt(), 0xFF80DEEA.toInt(), 0xFFF8BBD0.toInt()
                ),
                progress = 0xFF82B1FF.toInt(), divider = 0xFF272727.toInt(),
                isDark = true
            ),
            "high_contrast" to Theme(
                id = "high_contrast", name = "High Contrast",
                background = 0xFF000000.toInt(), surface = 0xFF1A1A1A.toInt(),
                primaryText = 0xFFFFFFFF.toInt(), secondaryText = 0xFFCCCCCC.toInt(),
                headingText = 0xFFFFFF00.toInt(), link = 0xFF00FFFF.toInt(),
                selection = 0xFF333333.toInt(), bookmark = 0xFFFF00FF.toInt(),
                highlightColors = listOf(
                    0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(),
                    0xFFFF00FF.toInt(), 0xFFFFA500.toInt(), 0xFF00F5FF.toInt(), 0xFFFF69B4.toInt()
                ),
                progress = 0xFF00FFFF.toInt(), divider = 0xFF444444.toInt(),
                isDark = true
            ),
            "solarized_light" to Theme(
                id = "solarized_light", name = "Solarized Light",
                background = 0xFFFDF6E3.toInt(), surface = 0xFFEEE8D5.toInt(),
                primaryText = 0xFF657B83.toInt(), secondaryText = 0xFF93A1A1.toInt(),
                headingText = 0xFF586E75.toInt(), link = 0xFF268BD2.toInt(),
                selection = 0xFF93A1A1.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0xFF859900.toInt(), 0xFFDC322F.toInt(),
                    0xFFD33682.toInt(), 0xFFCB4B16.toInt(), 0xFF2AA198.toInt(), 0xFF6C71C4.toInt()
                ),
                progress = 0xFF268BD2.toInt(), divider = 0xFFEEE8D5.toInt(),
                isDark = false
            ),
            "solarized_dark" to Theme(
                id = "solarized_dark", name = "Solarized Dark",
                background = 0xFF002B36.toInt(), surface = 0xFF073642.toInt(),
                primaryText = 0xFF839496.toInt(), secondaryText = 0xFF586E75.toInt(),
                headingText = 0xFFEEE8D5.toInt(), link = 0xFF2AA198.toInt(),
                selection = 0xFF586E75.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0xFF859900.toInt(), 0xFFDC322F.toInt(),
                    0xFFD33682.toInt(), 0xFFCB4B16.toInt(), 0xFF2AA198.toInt(), 0xFF6C71C4.toInt()
                ),
                progress = 0xFF2AA198.toInt(), divider = 0xFF073642.toInt(),
                isDark = true
            ),
            "nord_light" to Theme(
                id = "nord_light", name = "Nord Light",
                background = 0xFFECEFF4.toInt(), surface = 0xFFE5E9F0.toInt(),
                primaryText = 0xFF2E3440.toInt(), secondaryText = 0xFF4C566A.toInt(),
                headingText = 0xFF3B4252.toInt(), link = 0xFF5E81AC.toInt(),
                selection = 0xFFD8DEE9.toInt(), bookmark = 0xFFBF616A.toInt(),
                highlightColors = listOf(
                    0xFFBF616A.toInt(), 0xFFD08770.toInt(), 0xFFEBCB8B.toInt(), 0xFFA3BE8C.toInt(),
                    0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFF81A1C1.toInt(), 0xFF5E81AC.toInt()
                ),
                progress = 0xFF5E81AC.toInt(), divider = 0xFFD8DEE9.toInt(),
                isDark = false
            ),
            "nord_dark" to Theme(
                id = "nord_dark", name = "Nord Dark",
                background = 0xFF2E3440.toInt(), surface = 0xFF3B4252.toInt(),
                primaryText = 0xFFECEFF4.toInt(), secondaryText = 0xFFD8DEE9.toInt(),
                headingText = 0xFFECEFF4.toInt(), link = 0xFF88C0D0.toInt(),
                selection = 0xFF434C5E.toInt(), bookmark = 0xFFBF616A.toInt(),
                highlightColors = listOf(
                    0xFFBF616A.toInt(), 0xFFD08770.toInt(), 0xFFEBCB8B.toInt(), 0xFFA3BE8C.toInt(),
                    0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFF81A1C1.toInt(), 0xFF5E81AC.toInt()
                ),
                progress = 0xFF88C0D0.toInt(), divider = 0xFF434C5E.toInt(),
                isDark = true
            ),
            "dracula" to Theme(
                id = "dracula", name = "Dracula",
                background = 0xFF282A36.toInt(), surface = 0xFF44475A.toInt(),
                primaryText = 0xFFF8F8F2.toInt(), secondaryText = 0xFF6272A4.toInt(),
                headingText = 0xFFF8F8F2.toInt(), link = 0xFFBD93F9.toInt(),
                selection = 0xFF44475A.toInt(), bookmark = 0xFFFF79C6.toInt(),
                highlightColors = listOf(
                    0xFFFF5555.toInt(), 0xFFFFB86C.toInt(), 0xFFF1FA8C.toInt(), 0xFF50FA7B.toInt(),
                    0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt()
                ),
                progress = 0xFFBD93F9.toInt(), divider = 0xFF44475A.toInt(),
                isDark = true
            ),
            "monokai" to Theme(
                id = "monokai", name = "Monokai",
                background = 0xFF272822.toInt(), surface = 0xFF3E3D32.toInt(),
                primaryText = 0xFFF8F8F2.toInt(), secondaryText = 0xFF75715E.toInt(),
                headingText = 0xFFF8F8F2.toInt(), link = 0xFFA6E22E.toInt(),
                selection = 0xFF49483E.toInt(), bookmark = 0xFFF92672.toInt(),
                highlightColors = listOf(
                    0xFFF92672.toInt(), 0xFFFD971F.toInt(), 0xFFE6DB74.toInt(), 0xFFA6E22E.toInt(),
                    0xFFAE81FF.toInt(), 0xFF66D9EF.toInt(), 0xFFF92672.toInt(), 0xFFFD5FF0.toInt()
                ),
                progress = 0xFFA6E22E.toInt(), divider = 0xFF3E3D32.toInt(),
                isDark = true
            ),
            "gruvbox_light" to Theme(
                id = "gruvbox_light", name = "Gruvbox Light",
                background = 0xFFFBF1C7.toInt(), surface = 0xFFEBDBB2.toInt(),
                primaryText = 0xFF3C3836.toInt(), secondaryText = 0xFF7C6F64.toInt(),
                headingText = 0xFF282828.toInt(), link = 0xFFAF3A03.toInt(),
                selection = 0xFFD5C4A1.toInt(), bookmark = 0xFF9D0006.toInt(),
                highlightColors = listOf(
                    0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(), 0xFF458588.toInt(),
                    0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFD65D0E.toInt(), 0xFF7C6F64.toInt()
                ),
                progress = 0xFFAF3A03.toInt(), divider = 0xFFD5C4A1.toInt(),
                isDark = false
            ),
            "gruvbox_dark" to Theme(
                id = "gruvbox_dark", name = "Gruvbox Dark",
                background = 0xFF282828.toInt(), surface = 0xFF3C3836.toInt(),
                primaryText = 0xFFEBDBB2.toInt(), secondaryText = 0xFFA89984.toInt(),
                headingText = 0xFFFBF1C7.toInt(), link = 0xFFFB4934.toInt(),
                selection = 0xFF504945.toInt(), bookmark = 0xFFFB4934.toInt(),
                highlightColors = listOf(
                    0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(), 0xFF83A598.toInt(),
                    0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFFE8019.toInt(), 0xFFA89984.toInt()
                ),
                progress = 0xFFFB4934.toInt(), divider = 0xFF3C3836.toInt(),
                isDark = true
            ),
            "matcha" to Theme(
                id = "matcha", name = "Matcha",
                background = 0xFFF3F7EC.toInt(), surface = 0xFFE9F0DC.toInt(),
                primaryText = 0xFF2B3524.toInt(), secondaryText = 0xFF5D6B50.toInt(),
                headingText = 0xFF1F2A18.toInt(), link = 0xFF47773B.toInt(),
                selection = 0xFFC8DFAF.toInt(), bookmark = 0xFFB0563B.toInt(),
                highlightColors = listOf(
                    0xFFA3C76D.toInt(), 0xFF5B9AA8.toInt(), 0xFFD9B84A.toInt(), 0xFFC96F5E.toInt(),
                    0xFF9B87C9.toInt(), 0xFF7BAF7A.toInt(), 0xFF6FB5C9.toInt(), 0xFFC97BA8.toInt()
                ),
                progress = 0xFF47773B.toInt(), divider = 0xFFDCE7CC.toInt(),
                isDark = false
            ),
            "dusk" to Theme(
                id = "dusk", name = "Dusk",
                background = 0xFF161325.toInt(), surface = 0xFF1F1B33.toInt(),
                primaryText = 0xFFDCD7EC.toInt(), secondaryText = 0xFF9A93B8.toInt(),
                headingText = 0xFFF0EDFA.toInt(), link = 0xFFA79DF0.toInt(),
                selection = 0xFF332C52.toInt(), bookmark = 0xFFE08BB0.toInt(),
                highlightColors = listOf(
                    0xFFE5C06B.toInt(), 0xFF7FB4D9.toInt(), 0xFF8FC98F.toInt(), 0xFFD98C8C.toInt(),
                    0xFFB39DE0.toInt(), 0xFFD9B84A.toInt(), 0xFF7FC9C9.toInt(), 0xFFD98BB8.toInt()
                ),
                progress = 0xFFA79DF0.toInt(), divider = 0xFF2A2442.toInt(),
                isDark = true
            ),
            "espresso" to Theme(
                id = "espresso", name = "Espresso",
                background = 0xFF181310.toInt(), surface = 0xFF221B16.toInt(),
                primaryText = 0xFFE9DFD2.toInt(), secondaryText = 0xFFA5978A.toInt(),
                headingText = 0xFFF6EEE2.toInt(), link = 0xFFD2A878.toInt(),
                selection = 0xFF3B2E22.toInt(), bookmark = 0xFFC96F5E.toInt(),
                highlightColors = listOf(
                    0xFFD9B84A.toInt(), 0xFF8FB4C9.toInt(), 0xFFA8C98F.toInt(), 0xFFD98C7B.toInt(),
                    0xFFB89BC9.toInt(), 0xFFD9A86B.toInt(), 0xFF8FC9BF.toInt(), 0xFFC98FA8.toInt()
                ),
                progress = 0xFFD2A878.toInt(), divider = 0xFF2E261E.toInt(),
                isDark = true
            ),
            "arctic" to Theme(
                id = "arctic", name = "Arctic",
                background = 0xFFF2F8FB.toInt(), surface = 0xFFE7F1F6.toInt(),
                primaryText = 0xFF22313A.toInt(), secondaryText = 0xFF52666F.toInt(),
                headingText = 0xFF14232B.toInt(), link = 0xFF2277A8.toInt(),
                selection = 0xFFBBDCEA.toInt(), bookmark = 0xFFB0563B.toInt(),
                highlightColors = listOf(
                    0xFFE0C34E.toInt(), 0xFF4E93C0.toInt(), 0xFF63B088.toInt(), 0xFFD07A6B.toInt(),
                    0xFF9C8CC9.toInt(), 0xFFD9A84E.toInt(), 0xFF6BB5CC.toInt(), 0xFFCC7BA3.toInt()
                ),
                progress = 0xFF2277A8.toInt(), divider = 0xFFD8E7EE.toInt(),
                isDark = false
            )
        )

        fun getPreset(id: String): Theme = PRESETS[id] ?: PRESETS["paper"]!!
    }
}

@Serializable
data class CustomFont(
    val id: String,
    val name: String,
    val fileName: String, // Relative to app fonts directory
    val familyName: String, // Actual font family name
    val weight: Int = 400,
    val isVariable: Boolean = false,
    val addedAt: Instant = Clock.System.now()
)

