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
    val appDarkTheme: Boolean = false,
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

@Serializable
data class GlobalSettings(
    val themeId: String = "paper",
    val fontFamily: String = "Literata",
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.5f,
    val layoutMode: LayoutMode = LayoutMode.CONTINUOUS,
    val formattingMode: FormattingMode = FormattingMode.HYBRID,
    val autoDownloadBooks: Boolean = false,
    val syncOnWifiOnly: Boolean = true,
    val storageStrategy: StorageStrategy = StorageStrategy.WIFI_EPUB,
    val keepScreenOn: Boolean = false,
    val screenBehavior: ScreenBehavior = ScreenBehavior.SYSTEM,
    val gestures: GestureSettings = GestureSettings(),
    val keyboardShortcuts: KeyboardShortcuts = KeyboardShortcuts(),
    val accessibility: AccessibilitySettings = AccessibilitySettings()
)

@Serializable
data class GestureSettings(
    val tapCenter: TapAction = TapAction.TOGGLE_CONTROLS,
    val tapLeft: TapAction = TapAction.PREVIOUS_PAGE,
    val tapRight: TapAction = TapAction.NEXT_PAGE,
    val doubleTap: TapAction = TapAction.ZOOM,
    val longPress: TapAction = TapAction.SELECT_TEXT,
    val swipeHorizontal: SwipeAction = SwipeAction.PAGE_NAVIGATION,
    val swipeVertical: SwipeAction = SwipeAction.SCROLL,
    val volumeKeys: VolumeKeyAction = VolumeKeyAction.PAGE_NAVIGATION
)

enum class TapAction {
    TOGGLE_CONTROLS,
    PREVIOUS_PAGE,
    NEXT_PAGE,
    ZOOM,
    SELECT_TEXT,
    BOOKMARK,
    HIGHLIGHT,
    SEARCH,
    TOC,
    NONE
}

enum class SwipeAction {
    PAGE_NAVIGATION,
    SCROLL,
    CHAPTER_NAVIGATION,
    NONE
}

enum class VolumeKeyAction {
    PAGE_NAVIGATION,
    VOLUME,
    NONE
}

@Serializable
data class KeyboardShortcuts(
    val nextPage: String = "Space",
    val prevPage: String = "Shift+Space",
    val nextChapter: String = "N",
    val prevChapter: String = "P",
    val openToc: String = "T",
    val search: String = "Ctrl+F",
    val toggleSidebar: String = "B",
    val bookmark: String = "Ctrl+B",
    val highlight: String = "Ctrl+H",
    val increaseFont: String = "Ctrl+=",
    val decreaseFont: String = "Ctrl+-",
    val openSettings: String = "Ctrl+,",
    val focusMode: String = "F11",
    val toggleTheme: String = "Ctrl+T"
)

enum class ScreenBehavior {
    SYSTEM,
    KEEP_AWAKE_READING,
    KEEP_AWAKE_ACTIVE
}

enum class StorageStrategy {
    METADATA_ONLY,
    WIFI_EPUB,
    ALWAYS_EPUB
}

@Serializable
data class AccessibilitySettings(
    val highContrast: Boolean = false,
    val largeText: Boolean = false,
    val reducedMotion: Boolean = false,
    val increasedLineSpacing: Boolean = false,
    val increasedLetterSpacing: Boolean = false,
    val fontOverride: String? = null,
    val systemFontScale: Float = 1.0f
)

@Serializable
data class AppSettings(
    val global: GlobalSettings = GlobalSettings(),
    val bookSettings: Map<String, BookReaderSettings> = emptyMap(),
    val deviceId: String = "unset-device",
    val deviceName: String = "Unknown Device",
    val platform: String = "unknown",
    val appVersion: String = "1.0.0",
    val lastSyncAt: Instant? = null
)