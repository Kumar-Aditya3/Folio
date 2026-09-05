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
    // Honouring this means not forcing the reader's family, so the book's own faces
    // show. Off by default: that matches what the renderer has always done.
    val useEmbeddedFonts: Boolean = false,
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
    val autoSyncInterval: Int = 15, // minutes
    /** Daily reading goal in minutes, shown as a ring on the Stats tab. */
    val dailyGoalMinutes: Int = 60,
    /** Hours between background manga chapter update checks; 0 = updates off (§11.3). */
    val mangaUpdateIntervalHours: Int = 0,
    /** §13.3: tint the Home hero from the current book's cover. Contrast-guarded, so on by default. */
    val homeCoverTint: Boolean = true,
    /**
     * User-authored app palette. When present it replaces the palette named by
     * [appThemeId] everywhere; the atmosphere (field gradient, colour pools,
     * shadows) is re-derived from it, so the gradient applies to custom colours
     * exactly as it does to a built-in pack.
     */
    val customAppTheme: CustomAppTheme? = null,
    /** How solid the app's glass surfaces are, 0 = fully see-through, 1 = as designed. */
    val topBarOpacity: Float = 1f,
    val navBarOpacity: Float = 1f,
    val panelOpacity: Float = 1f,
    /** Reader bars, rails, drawers and the reader settings sheet. */
    val readerChromeOpacity: Float = 1f
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
            themeId = bookSettings.themeId ?: themeId,
            layoutMode = bookSettings.layoutMode ?: layoutMode,
            showChapterTitle = bookSettings.showChapterTitle ?: showChapterTitle,
            showProgress = bookSettings.showProgress ?: showProgress,
            showClock = bookSettings.showClock ?: showClock,
            highlightColorIndex = bookSettings.highlightColorIndex ?: highlightColorIndex,
            customTheme = bookSettings.customTheme ?: customTheme
        )
    }

    /**
     * A copy of the per-book fields, used when the current global defaults are
     * snapshotted onto a book so it owns its settings from that moment on.
     * Later edits to the global defaults never leak into the snapshot.
     *
     * The Main Settings → Formatting panel fields ([alignment], [formattingMode]
     * and [hyphenation]) are deliberately NOT snapshotted: no in-reader UI can
     * change them, so freezing them at first-open time made the panel look dead —
     * a book opened before the change kept the old values while a freshly opened
     * book picked the new ones up (the "justify works on Windows but not on
     * Android" asymmetry, which was per-device data, not code). They always
     * follow the global defaults now.
     */
    fun toBookSettings(): BookReaderSettings = BookReaderSettings(
        fontFamily = fontFamily,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        wordSpacing = wordSpacing,
        paragraphSpacing = paragraphSpacing,
        margins = margins,
        textWidth = textWidth,
        themeId = themeId,
        layoutMode = layoutMode,
        showChapterTitle = showChapterTitle,
        showProgress = showProgress,
        showClock = showClock,
        highlightColorIndex = highlightColorIndex,
        customTheme = customTheme
    )
}

/**
 * Fields a single book may override from the global defaults. [alignment],
 * [formattingMode] and [hyphenation] remain declared only for JSON schema
 * stability (older snapshots and backups still decode) — [ReaderSettings.copyWith]
 * deliberately ignores them so the Main Settings → Formatting panel governs
 * those on every book.
 */
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
    val highlightColorIndex: Int? = null,
    val customTheme: Theme? = null
) {
    fun toReaderSettings(base: ReaderSettings): ReaderSettings {
        return base.copyWith(this)
    }
}

/**
 * Fields this book overrides, for the reader panel's override dots. Only fields
 * [ReaderSettings.copyWith] honours can count — the inert formatting-panel fields
 * (alignment, formattingMode, hyphenation) never differ in effect, so legacy
 * non-null values for them must not surface as overrides.
 */
fun BookReaderSettings.overriddenFields(global: ReaderSettings): Set<String> {
    val fields = mutableSetOf<String>()
    if (fontFamily != null && fontFamily != global.fontFamily) fields += "fontFamily"
    if (fontSize != null && fontSize != global.fontSize) fields += "fontSize"
    if (fontWeight != null && fontWeight != global.fontWeight) fields += "fontWeight"
    if (lineHeight != null && lineHeight != global.lineHeight) fields += "lineHeight"
    if (letterSpacing != null && letterSpacing != global.letterSpacing) fields += "letterSpacing"
    if (wordSpacing != null && wordSpacing != global.wordSpacing) fields += "wordSpacing"
    if (paragraphSpacing != null && paragraphSpacing != global.paragraphSpacing) fields += "paragraphSpacing"
    if (margins != null && margins != global.margins) fields += "margins"
    if (textWidth != null && textWidth != global.textWidth) fields += "textWidth"
    if (themeId != null && themeId != global.themeId) fields += "themeId"
    if (layoutMode != null && layoutMode != global.layoutMode) fields += "layoutMode"
    if (showChapterTitle != null && showChapterTitle != global.showChapterTitle) fields += "showChapterTitle"
    if (showProgress != null && showProgress != global.showProgress) fields += "showProgress"
    if (showClock != null && showClock != global.showClock) fields += "showClock"
    if (highlightColorIndex != null && highlightColorIndex != global.highlightColorIndex) fields += "highlightColorIndex"
    if (customTheme != null && customTheme != global.customTheme) fields += "customTheme"
    return fields
}

/** Drops the named fields so the book follows the global defaults again. */
fun BookReaderSettings.clearing(fields: Set<String>): BookReaderSettings = copy(
    fontFamily = if ("fontFamily" in fields) null else fontFamily,
    fontSize = if ("fontSize" in fields) null else fontSize,
    fontWeight = if ("fontWeight" in fields) null else fontWeight,
    lineHeight = if ("lineHeight" in fields) null else lineHeight,
    letterSpacing = if ("letterSpacing" in fields) null else letterSpacing,
    wordSpacing = if ("wordSpacing" in fields) null else wordSpacing,
    paragraphSpacing = if ("paragraphSpacing" in fields) null else paragraphSpacing,
    margins = if ("margins" in fields) null else margins,
    textWidth = if ("textWidth" in fields) null else textWidth,
    alignment = if ("alignment" in fields) null else alignment,
    hyphenation = if ("hyphenation" in fields) null else hyphenation,
    themeId = if ("themeId" in fields) null else themeId,
    layoutMode = if ("layoutMode" in fields) null else layoutMode,
    formattingMode = if ("formattingMode" in fields) null else formattingMode,
    showChapterTitle = if ("showChapterTitle" in fields) null else showChapterTitle,
    showProgress = if ("showProgress" in fields) null else showProgress,
    showClock = if ("showClock" in fields) null else showClock,
    highlightColorIndex = if ("highlightColorIndex" in fields) null else highlightColorIndex,
    customTheme = if ("customTheme" in fields) null else customTheme
)

/**
 * Fields whose value differs between two global settings snapshots, for the
 * reader panel's "All books" write: the open book's override is cleared only
 * for the fields actually being changed. Names mirror
 * [BookReaderSettings.overriddenFields] so [BookReaderSettings.clearing]
 * accepts the result directly.
 */
fun ReaderSettings.changedFields(other: ReaderSettings): Set<String> {
    val fields = mutableSetOf<String>()
    if (fontFamily != other.fontFamily) fields += "fontFamily"
    if (fontSize != other.fontSize) fields += "fontSize"
    if (fontWeight != other.fontWeight) fields += "fontWeight"
    if (lineHeight != other.lineHeight) fields += "lineHeight"
    if (letterSpacing != other.letterSpacing) fields += "letterSpacing"
    if (wordSpacing != other.wordSpacing) fields += "wordSpacing"
    if (paragraphSpacing != other.paragraphSpacing) fields += "paragraphSpacing"
    if (margins != other.margins) fields += "margins"
    if (textWidth != other.textWidth) fields += "textWidth"
    if (themeId != other.themeId) fields += "themeId"
    if (layoutMode != other.layoutMode) fields += "layoutMode"
    if (showChapterTitle != other.showChapterTitle) fields += "showChapterTitle"
    if (showProgress != other.showProgress) fields += "showProgress"
    if (showClock != other.showClock) fields += "showClock"
    if (highlightColorIndex != other.highlightColorIndex) fields += "highlightColorIndex"
    if (customTheme != other.customTheme) fields += "customTheme"
    return fields
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
    PAGINATED,      // Page-based horizontal flips
    SPREAD,         // Desktop-only: two-page spread (side-by-side paginated columns)
    TWO_COLUMN,     // Retired: normalizes to PAGINATED (kept for old persisted settings)
    FOCUS           // Retired: normalizes to CONTINUOUS (kept for old persisted settings)
}

/** Retired modes map onto their nearest living equivalent; SPREAD passes through (platform gating happens at the UI layer). */
val LayoutMode.normalized: LayoutMode
    get() = when (this) {
        LayoutMode.TWO_COLUMN -> LayoutMode.PAGINATED
        LayoutMode.FOCUS -> LayoutMode.CONTINUOUS
        else -> this
    }

/**
 * A user-authored app palette, kept deliberately small: six roles that carry
 * meaning, with the other ~30 structural roles derived from them at the UI layer
 * (see `CustomAppTheme.toFolioColors`). Editing six colours is a design task;
 * editing forty is data entry.
 *
 * The three accents are the ones the page's colour pools are drawn from, so
 * changing them changes the background gradient too — which is the point.
 */
@Serializable
data class CustomAppTheme(
    val name: String = "Custom",
    /** Page ground. Its lightness decides whether the whole theme reads dark. */
    val background: Int = 0xFFFFFFFF.toInt(),
    /** Cards, bars and sheets. */
    val surface: Int = 0xFFF2F2F2.toInt(),
    /** Interactive colour: selection, switches, focus rings. */
    val primary: Int = 0xFF1A73E8.toInt(),
    /** Progress/continuity accent — also the first background pool. */
    val accentProgress: Int = 0xFF0B57D0.toInt(),
    /** Discovery accent — the second pool. */
    val accentDiscovery: Int = 0xFF006C63.toInt(),
    /** Streak/urgency accent — the third pool. */
    val accentStreak: Int = 0xFFB3261E.toInt()
) {
    /** Matches the atmosphere's own threshold so lighting and text agree. */
    val isDark: Boolean
        get() {
            val r = ((background shr 16) and 0xFF) / 255f
            val g = ((background shr 8) and 0xFF) / 255f
            val b = (background and 0xFF) / 255f
            return (r * 0.2126f + g * 0.7152f + b * 0.0722f) < 0.45f
        }
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
            // ── LIGHT READER THEMES ─────────────────────────────────────
            "white" to Theme(
                id = "white", name = "White",
                background = 0xFFFFFFFF.toInt(), surface = 0xFFF5F5F5.toInt(),
                primaryText = 0xFF1A1A1A.toInt(), secondaryText = 0xFF555555.toInt(),
                headingText = 0xFF000000.toInt(), link = 0xFF1A73E8.toInt(),
                selection = 0xFFB3D9FF.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFFFFF00.toInt(), 0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF3B30.toInt(),
                    0xFFAF52DE.toInt(), 0xFFFF9F0A.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF2D92.toInt()
                ),
                progress = 0xFF1A73E8.toInt(), divider = 0xFFE0E0E0.toInt(),
                isDark = false
            ),
            "paper" to Theme(
                id = "paper", name = "Paper",
                background = 0xFFFFF8EC.toInt(), surface = 0xFFF5ECD8.toInt(),
                primaryText = 0xFF3B2A14.toInt(), secondaryText = 0xFF7A6544.toInt(),
                headingText = 0xFF2E1E0A.toInt(), link = 0xFF8A5A00.toInt(),
                selection = 0xFFD4C4A4.toInt(), bookmark = 0xFFC0504D.toInt(),
                highlightColors = listOf(
                    0xFFE8C84D.toInt(), 0xFF4D8FC7.toInt(), 0xFF5DAE5D.toInt(), 0xFFD65F5F.toInt(),
                    0xFF9B7BC7.toInt(), 0xFFD4A843.toInt(), 0xFF5DB8B8.toInt(), 0xFFD67AB5.toInt()
                ),
                progress = 0xFF8A5A00.toInt(), divider = 0xFFE8D9BE.toInt(),
                isDark = false
            ),
            "sepia" to Theme(
                id = "sepia", name = "Sepia",
                background = 0xFFF5E6C8.toInt(), surface = 0xFFE8D4AA.toInt(),
                primaryText = 0xFF3E2810.toInt(), secondaryText = 0xFF6B5030.toInt(),
                headingText = 0xFF2A1A08.toInt(), link = 0xFF8B6B3A.toInt(),
                selection = 0xFFD4A843.toInt(), bookmark = 0xFFC0504D.toInt(),
                highlightColors = listOf(
                    0xFFE8C84D.toInt(), 0xFF4D8FC7.toInt(), 0xFF5DAE5D.toInt(), 0xFFD65F5F.toInt(),
                    0xFF9B7BC7.toInt(), 0xFFD4A843.toInt(), 0xFF5DB8B8.toInt(), 0xFFD67AB5.toInt()
                ),
                progress = 0xFF8B6B3A.toInt(), divider = 0xFFD9C090.toInt(),
                isDark = false
            ),
            "gray" to Theme(
                id = "gray", name = "Gray",
                background = 0xFFF0F0F0.toInt(), surface = 0xFFE0E0E0.toInt(),
                primaryText = 0xFF222222.toInt(), secondaryText = 0xFF505050.toInt(),
                headingText = 0xFF111111.toInt(), link = 0xFF006699.toInt(),
                selection = 0xFFB0D0F0.toInt(), bookmark = 0xFFCC3366.toInt(),
                highlightColors = listOf(
                    0xFFFFD700.toInt(), 0xFF4A90D9.toInt(), 0xFF50C878.toInt(), 0xFFE04040.toInt(),
                    0xFF9B59B6.toInt(), 0xFFF39C12.toInt(), 0xFF1ABC9C.toInt(), 0xFFE91E63.toInt()
                ),
                progress = 0xFF006699.toInt(), divider = 0xFFC8C8C8.toInt(),
                isDark = false
            ),
            "matcha" to Theme(
                id = "matcha", name = "Matcha",
                background = 0xFFEAFBE0.toInt(), surface = 0xFFD2F3C0.toInt(),
                primaryText = 0xFF0F2A08.toInt(), secondaryText = 0xFF4A7A34.toInt(),
                headingText = 0xFF2FB121.toInt(), link = 0xFF00A97A.toInt(),
                selection = 0xFFBFF58C.toInt(), bookmark = 0xFFFF6B2C.toInt(),
                highlightColors = listOf(
                    0xFFB6F03C.toInt(), 0xFF12DCA0.toInt(), 0xFFFFD400.toInt(), 0xFFFF6B4A.toInt(),
                    0xFFB46BFF.toInt(), 0xFF39DE7A.toInt(), 0xFF1FC7E6.toInt(), 0xFFFF6FD8.toInt()
                ),
                progress = 0xFF14B864.toInt(), divider = 0xFFC9ECB4.toInt(),
                isDark = false
            ),
            "arctic" to Theme(
                id = "arctic", name = "Arctic",
                background = 0xFFF2FAFF.toInt(), surface = 0xFFD9F0FD.toInt(),
                primaryText = 0xFF06202E.toInt(), secondaryText = 0xFF35708F.toInt(),
                headingText = 0xFF0A87C7.toInt(), link = 0xFF00B4E6.toInt(),
                selection = 0xFFB0EBFF.toInt(), bookmark = 0xFFFF4F8B.toInt(),
                highlightColors = listOf(
                    0xFFFFE04D.toInt(), 0xFF22C3E6.toInt(), 0xFF3DDC97.toInt(), 0xFFFF6B6B.toInt(),
                    0xFF9B6BFF.toInt(), 0xFF00D1D1.toInt(), 0xFF4DA8FF.toInt(), 0xFFFF6FD8.toInt()
                ),
                progress = 0xFF00A9DB.toInt(), divider = 0xFFC4E7F8.toInt(),
                isDark = false
            ),
            "moss" to Theme(
                id = "moss", name = "Moss",
                background = 0xFFEAF7EE.toInt(), surface = 0xFFC9EBD5.toInt(),
                primaryText = 0xFF0C2317.toInt(), secondaryText = 0xFF357A50.toInt(),
                headingText = 0xFF0EA35C.toInt(), link = 0xFF00A6A6.toInt(),
                selection = 0xFF9FE9C4.toInt(), bookmark = 0xFFFF7A2E.toInt(),
                highlightColors = listOf(
                    0xFF7BE04A.toInt(), 0xFF00C2A8.toInt(), 0xFFFFD23F.toInt(), 0xFFFF6B4A.toInt(),
                    0xFFA67CFF.toInt(), 0xFF39D98A.toInt(), 0xFF00B4D4.toInt(), 0xFFF45BA8.toInt()
                ),
                progress = 0xFF0EA35C.toInt(), divider = 0xFFBFE4CB.toInt(),
                isDark = false
            ),
            "solarized_light" to Theme(
                id = "solarized_light", name = "Solarized Light",
                background = 0xFFFDF6E3.toInt(), surface = 0xFFEEE8D5.toInt(),
                primaryText = 0xFF586E75.toInt(), secondaryText = 0xFF839496.toInt(),
                headingText = 0xFF073642.toInt(), link = 0xFF268BD2.toInt(),
                selection = 0xFF93A1A1.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0xFF859900.toInt(), 0xFFDC322F.toInt(),
                    0xFFD33682.toInt(), 0xFFCB4B16.toInt(), 0xFF2AA198.toInt(), 0xFF6C71C4.toInt()
                ),
                progress = 0xFF268BD2.toInt(), divider = 0xFFEEE8D5.toInt(),
                isDark = false
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
            // ── DARK READER THEMES ──────────────────────────────────────
            "dark" to Theme(
                id = "dark", name = "Dark",
                background = 0xFF121212.toInt(), surface = 0xFF1E1E1E.toInt(),
                primaryText = 0xFFE8EAED.toInt(), secondaryText = 0xFF9AA0A6.toInt(),
                headingText = 0xFFFFFFFF.toInt(), link = 0xFF8AB4F8.toInt(),
                selection = 0xFF3C4043.toInt(), bookmark = 0xFFE91E63.toInt(),
                highlightColors = listOf(
                    0xFFFFD54F.toInt(), 0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFE57373.toInt(),
                    0xFFCE93D8.toInt(), 0xFFFFB74D.toInt(), 0xFF4DD0E1.toInt(), 0xFFF06292.toInt()
                ),
                progress = 0xFF8AB4F8.toInt(), divider = 0xFF3C4043.toInt(),
                isDark = true
            ),
            "oled_black" to Theme(
                id = "oled_black", name = "OLED Black",
                background = 0xFF000000.toInt(), surface = 0xFF0A0A0A.toInt(),
                primaryText = 0xFFECEFF1.toInt(), secondaryText = 0xFF90A4AE.toInt(),
                headingText = 0xFFFFFFFF.toInt(), link = 0xFF82B1FF.toInt(),
                selection = 0xFF1A1A1A.toInt(), bookmark = 0xFFF06292.toInt(),
                highlightColors = listOf(
                    0xFFFFEB3B.toInt(), 0xFF64B5F6.toInt(), 0xFFA5D6A7.toInt(), 0xFFEF9A9A.toInt(),
                    0xFFD1C4E9.toInt(), 0xFFFFCC80.toInt(), 0xFF80DEEA.toInt(), 0xFFF8BBD0.toInt()
                ),
                progress = 0xFF82B1FF.toInt(), divider = 0xFF1A1A1A.toInt(),
                isDark = true
            ),
            "graphite" to Theme(
                id = "graphite", name = "Graphite",
                background = 0xFF1A1B1D.toInt(), surface = 0xFF242628.toInt(),
                primaryText = 0xFFEDEEEF.toInt(), secondaryText = 0xFFA8AEB5.toInt(),
                headingText = 0xFFF5F6F7.toInt(), link = 0xFF7FB0D9.toInt(),
                selection = 0xFF3E4247.toInt(), bookmark = 0xFFC89BB5.toInt(),
                highlightColors = listOf(
                    0xFFE0C070.toInt(), 0xFF7FB0D9.toInt(), 0xFF8FBF9F.toInt(), 0xFFD98F9F.toInt(),
                    0xFFC89BB5.toInt(), 0xFF9BC4E8.toInt(), 0xFFA8D0B8.toInt(), 0xFFD4A843.toInt()
                ),
                progress = 0xFF7FB0D9.toInt(), divider = 0xFF35383D.toInt(),
                isDark = true
            ),
            "blossom" to Theme(
                id = "blossom", name = "Blossom",
                background = 0xFF0A0508.toInt(), surface = 0xFF141013.toInt(),
                primaryText = 0xFFF2E6EA.toInt(), secondaryText = 0xFFB9A6AE.toInt(),
                headingText = 0xFFF7EFF2.toInt(), link = 0xFFF8A0B4.toInt(),
                selection = 0xFF4A2530.toInt(), bookmark = 0xFFE8748C.toInt(),
                highlightColors = listOf(
                    0xFFF8A0B4.toInt(), 0xFFE8748C.toInt(), 0xFFF0C088.toInt(), 0xFFC9A0D8.toInt(),
                    0xFFD96A8A.toInt(), 0xFFF5C1CE.toInt(), 0xFFB890C8.toInt(), 0xFFE8A87D.toInt()
                ),
                progress = 0xFFF8A0B4.toInt(), divider = 0xFF241C21.toInt(),
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
            "dusk" to Theme(
                id = "dusk", name = "Dusk",
                background = 0xFF120E20.toInt(), surface = 0xFF1A1430.toInt(),
                primaryText = 0xFFE4DCF8.toInt(), secondaryText = 0xFF9888C0.toInt(),
                headingText = 0xFFD9C6FF.toInt(), link = 0xFF9B6BFF.toInt(),
                selection = 0xFF43307F.toInt(), bookmark = 0xFFFF4FA3.toInt(),
                highlightColors = listOf(
                    0xFFFFD400.toInt(), 0xFF4DC3FF.toInt(), 0xFF3DDE8A.toInt(), 0xFFFF5C5C.toInt(),
                    0xFFB14DFF.toInt(), 0xFFFF9E2C.toInt(), 0xFF22D9EE.toInt(), 0xFFFF6FD8.toInt()
                ),
                progress = 0xFFB14DFF.toInt(), divider = 0xFF302858.toInt(),
                isDark = true
            ),
            "espresso" to Theme(
                id = "espresso", name = "Espresso",
                background = 0xFF18100A.toInt(), surface = 0xFF221810.toInt(),
                primaryText = 0xFFF0E4D4.toInt(), secondaryText = 0xFFB0A088.toInt(),
                headingText = 0xFFFFE8C9.toInt(), link = 0xFFFFA94D.toInt(),
                selection = 0xFF55391F.toInt(), bookmark = 0xFFFF5C3A.toInt(),
                highlightColors = listOf(
                    0xFFFFD400.toInt(), 0xFF59C2FF.toInt(), 0xFF8CE644.toInt(), 0xFFFF5C3A.toInt(),
                    0xFFC77DFF.toInt(), 0xFFFF9E2C.toInt(), 0xFF22E0C8.toInt(), 0xFFFF6FA8.toInt()
                ),
                progress = 0xFFFFA94D.toInt(), divider = 0xFF3C2E20.toInt(),
                isDark = true
            ),
            "ember" to Theme(
                id = "ember", name = "Ember",
                background = 0xFF1A0E08.toInt(), surface = 0xFF26140C.toInt(),
                primaryText = 0xFFF8E4D4.toInt(), secondaryText = 0xFFC09878.toInt(),
                headingText = 0xFFFFCFA0.toInt(), link = 0xFFFF7A2E.toInt(),
                selection = 0xFF442818.toInt(), bookmark = 0xFFFF2E63.toInt(),
                highlightColors = listOf(
                    0xFFFFB300.toInt(), 0xFF4DD9FF.toInt(), 0xFF8CE644.toInt(), 0xFFFF453A.toInt(),
                    0xFFC77DFF.toInt(), 0xFFFF7A2E.toInt(), 0xFF22E0C8.toInt(), 0xFFFF2E88.toInt()
                ),
                progress = 0xFFFF7A2E.toInt(), divider = 0xFF442818.toInt(),
                isDark = true
            ),
            "solarized_dark" to Theme(
                id = "solarized_dark", name = "Solarized Dark",
                background = 0xFF002B36.toInt(), surface = 0xFF073642.toInt(),
                primaryText = 0xFF93A1A1.toInt(), secondaryText = 0xFF657B83.toInt(),
                headingText = 0xFFEEE8D5.toInt(), link = 0xFF2AA198.toInt(),
                selection = 0xFF586E75.toInt(), bookmark = 0xFFD33682.toInt(),
                highlightColors = listOf(
                    0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0xFF859900.toInt(), 0xFFDC322F.toInt(),
                    0xFFD33682.toInt(), 0xFFCB4B16.toInt(), 0xFF2AA198.toInt(), 0xFF6C71C4.toInt()
                ),
                progress = 0xFF2AA198.toInt(), divider = 0xFF073642.toInt(),
                isDark = true
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
            "synthwave" to Theme(
                id = "synthwave", name = "Synthwave",
                background = 0xFF0D0221.toInt(), surface = 0xFF1A0B38.toInt(),
                primaryText = 0xFFF0E6FF.toInt(), secondaryText = 0xFF9D8BC4.toInt(),
                headingText = 0xFFFF7BE5.toInt(), link = 0xFF00E5FF.toInt(),
                selection = 0xFF3D1A6E.toInt(), bookmark = 0xFFFF2FD6.toInt(),
                highlightColors = listOf(
                    0xFFFF2FD6.toInt(), 0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFF00FF9F.toInt(),
                    0xFFFFE600.toInt(), 0xFFFF6B35.toInt(), 0xFF4D9DFF.toInt(), 0xFFFF8AE2.toInt()
                ),
                progress = 0xFFFF2FD6.toInt(), divider = 0xFF2A1454.toInt(),
                isDark = true
            ),
            "bubblegum" to Theme(
                id = "bubblegum", name = "Bubblegum",
                background = 0xFFFFF0F6.toInt(), surface = 0xFFFFE3EF.toInt(),
                primaryText = 0xFF4A1033.toInt(), secondaryText = 0xFF9A5A78.toInt(),
                headingText = 0xFFD8467A.toInt(), link = 0xFF2ECFA0.toInt(),
                selection = 0xFFFFC6DE.toInt(), bookmark = 0xFFFF4FA3.toInt(),
                highlightColors = listOf(
                    0xFFFF4FA3.toInt(), 0xFF2ECFA0.toInt(), 0xFFFFB300.toInt(), 0xFF7A6CFF.toInt(),
                    0xFF4DC3FF.toInt(), 0xFFFF85B5.toInt(), 0xFF9CE86B.toInt(), 0xFFE88AFF.toInt()
                ),
                progress = 0xFFFF4FA3.toInt(), divider = 0xFFF5CCDE.toInt(),
                isDark = false
            ),
            "acid" to Theme(
                id = "acid", name = "Acid Rave",
                background = 0xFF060806.toInt(), surface = 0xFF0E120C.toInt(),
                primaryText = 0xFFE8FFD6.toInt(), secondaryText = 0xFF98B878.toInt(),
                headingText = 0xFFCCFF00.toInt(), link = 0xFFFF2ED1.toInt(),
                selection = 0xFF2A3A18.toInt(), bookmark = 0xFFCCFF00.toInt(),
                highlightColors = listOf(
                    0xFFCCFF00.toInt(), 0xFFFF2ED1.toInt(), 0xFF00FFAA.toInt(), 0xFF3D8BFF.toInt(),
                    0xFFFFE600.toInt(), 0xFFFF5C1E.toInt(), 0xFFB04DFF.toInt(), 0xFF6BFF3D.toInt()
                ),
                progress = 0xFFCCFF00.toInt(), divider = 0xFF1A2414.toInt(),
                isDark = true
            ),
            "lava" to Theme(
                id = "lava", name = "Lava",
                background = 0xFF160504.toInt(), surface = 0xFF240B06.toInt(),
                primaryText = 0xFFFFE4D4.toInt(), secondaryText = 0xFFC08870.toInt(),
                headingText = 0xFFFF8A3D.toInt(), link = 0xFFFF2E63.toInt(),
                selection = 0xFF4A1808.toInt(), bookmark = 0xFFFF5C1E.toInt(),
                highlightColors = listOf(
                    0xFFFF5C1E.toInt(), 0xFFFF2E63.toInt(), 0xFFFFB300.toInt(), 0xFFFF8A3D.toInt(),
                    0xFFD84040.toInt(), 0xFFFF6B6B.toInt(), 0xFFE8960A.toInt(), 0xFFFF3D9A.toInt()
                ),
                progress = 0xFFFF5C1E.toInt(), divider = 0xFF38120A.toInt(),
                isDark = true
            ),
            "sherbet" to Theme(
                id = "sherbet", name = "Sherbet",
                background = 0xFFFFF7EE.toInt(), surface = 0xFFFFEEDA.toInt(),
                primaryText = 0xFF4A2E14.toInt(), secondaryText = 0xFF9A7854.toInt(),
                headingText = 0xFFE05A2B.toInt(), link = 0xFF2ED9A3.toInt(),
                selection = 0xFFFFDCB8.toInt(), bookmark = 0xFFFF7A4D.toInt(),
                highlightColors = listOf(
                    0xFFFF7A4D.toInt(), 0xFF2ED9A3.toInt(), 0xFF4DC3FF.toInt(), 0xFFFFB340.toInt(),
                    0xFFFF5C8A.toInt(), 0xFF9CE86B.toInt(), 0xFFB085FF.toInt(), 0xFF40D0E8.toInt()
                ),
                progress = 0xFFFF7A4D.toInt(), divider = 0xFFF0DCC0.toInt(),
                isDark = false
            ),
            // ── VIBRANT READER THEMES (mirror the vibrant app palettes) ──
            "vaporwave" to Theme(
                id = "vaporwave", name = "Vaporwave",
                background = 0xFF1A0733.toInt(), surface = 0xFF260E47.toInt(),
                primaryText = 0xFFF2DCFF.toInt(), secondaryText = 0xFFBE9BF0.toInt(),
                headingText = 0xFFFF5AC8.toInt(), link = 0xFF00E5FF.toInt(),
                selection = 0xFF4A1E94.toInt(), bookmark = 0xFFB86BFF.toInt(),
                highlightColors = listOf(
                    0xFFFF5AC8.toInt(), 0xFF00E5FF.toInt(), 0xFFB86BFF.toInt(), 0xFFFFE600.toInt(),
                    0xFF00FF9F.toInt(), 0xFFFF6B35.toInt(), 0xFF4D9DFF.toInt(), 0xFFFF8AE2.toInt()
                ),
                progress = 0xFFFF5AC8.toInt(), divider = 0xFF341660.toInt(),
                isDark = true
            ),
            "neontokyo" to Theme(
                id = "neontokyo", name = "Neon Tokyo",
                background = 0xFF0A0620.toInt(), surface = 0xFF150B33.toInt(),
                primaryText = 0xFFE6F0FF.toInt(), secondaryText = 0xFF8A9AC8.toInt(),
                headingText = 0xFF00E5FF.toInt(), link = 0xFFFF2FD6.toInt(),
                selection = 0xFF2A1A5E.toInt(), bookmark = 0xFFFFD400.toInt(),
                highlightColors = listOf(
                    0xFF00E5FF.toInt(), 0xFFFF2FD6.toInt(), 0xFF7C4DFF.toInt(), 0xFF00FF9F.toInt(),
                    0xFFFFE600.toInt(), 0xFFFF6B35.toInt(), 0xFFFF4D9D.toInt(), 0xFF4DFFB0.toInt()
                ),
                progress = 0xFF00E5FF.toInt(), divider = 0xFF1F1145.toInt(),
                isDark = true
            ),
            "toxiclime" to Theme(
                id = "toxiclime", name = "Toxic Lime",
                background = 0xFF050A02.toInt(), surface = 0xFF0D1808.toInt(),
                primaryText = 0xFFE2FFD0.toInt(), secondaryText = 0xFF88B060.toInt(),
                headingText = 0xFFB6FF00.toInt(), link = 0xFF32E6A0.toInt(),
                selection = 0xFF223A0A.toInt(), bookmark = 0xFFFF00A0.toInt(),
                highlightColors = listOf(
                    0xFFB6FF00.toInt(), 0xFF32E6A0.toInt(), 0xFF00E5FF.toInt(), 0xFFFFFF00.toInt(),
                    0xFFFF4FD8.toInt(), 0xFFFF9E2C.toInt(), 0xFF7CFF3D.toInt(), 0xFF00FFD1.toInt()
                ),
                progress = 0xFFB6FF00.toInt(), divider = 0xFF16240A.toInt(),
                isDark = true
            ),
            "retrosunset" to Theme(
                id = "retrosunset", name = "Retro Sunset",
                background = 0xFF1C0A18.toInt(), surface = 0xFF2C0F26.toInt(),
                primaryText = 0xFFFFE8DC.toInt(), secondaryText = 0xFFB87F92.toInt(),
                headingText = 0xFFFF6B35.toInt(), link = 0xFFFFD400.toInt(),
                selection = 0xFF4A1638.toInt(), bookmark = 0xFFFF2E63.toInt(),
                highlightColors = listOf(
                    0xFFFF6B35.toInt(), 0xFFFFD400.toInt(), 0xFFFF2E63.toInt(), 0xFF00E5FF.toInt(),
                    0xFFB6FF00.toInt(), 0xFFFF7BE5.toInt(), 0xFFFF9E2C.toInt(), 0xFF4DC3FF.toInt()
                ),
                progress = 0xFFFF6B35.toInt(), divider = 0xFF3A1130.toInt(),
                isDark = true
            ),
            "peacock" to Theme(
                id = "peacock", name = "Peacock",
                background = 0xFF04161A.toInt(), surface = 0xFF0A2630.toInt(),
                primaryText = 0xFFDFF7F5.toInt(), secondaryText = 0xFF6FA8A8.toInt(),
                headingText = 0xFF00D9B5.toInt(), link = 0xFF3FA9FF.toInt(),
                selection = 0xFF0E3A45.toInt(), bookmark = 0xFFFFB300.toInt(),
                highlightColors = listOf(
                    0xFF00D9B5.toInt(), 0xFF3FA9FF.toInt(), 0xFFFFB300.toInt(), 0xFFFF5C8A.toInt(),
                    0xFF7C4DFF.toInt(), 0xFF00FFC2.toInt(), 0xFFFF8A3D.toInt(), 0xFF4DD9FF.toInt()
                ),
                progress = 0xFF00D9B5.toInt(), divider = 0xFF0E3038.toInt(),
                isDark = true
            ),
            "midnightneon" to Theme(
                id = "midnightneon", name = "Midnight Neon",
                background = 0xFF020412.toInt(), surface = 0xFF0A0E26.toInt(),
                primaryText = 0xFFE0E8FF.toInt(), secondaryText = 0xFF7A88B8.toInt(),
                headingText = 0xFF5B8CFF.toInt(), link = 0xFF00E5FF.toInt(),
                selection = 0xFF1A2450.toInt(), bookmark = 0xFFFF4FA3.toInt(),
                highlightColors = listOf(
                    0xFF5B8CFF.toInt(), 0xFF00E5FF.toInt(), 0xFFFF4FA3.toInt(), 0xFFB14DFF.toInt(),
                    0xFF3DDE8A.toInt(), 0xFFFFD400.toInt(), 0xFFFF6B35.toInt(), 0xFFFF8AE2.toInt()
                ),
                progress = 0xFF5B8CFF.toInt(), divider = 0xFF121838.toInt(),
                isDark = true
            ),
            "candypop" to Theme(
                id = "candypop", name = "Candy Pop",
                background = 0xFFFFF4FA.toInt(), surface = 0xFFFFE4F2.toInt(),
                primaryText = 0xFF3A0A2A.toInt(), secondaryText = 0xFF9A5A80.toInt(),
                headingText = 0xFFFF1F8E.toInt(), link = 0xFF0091FF.toInt(),
                selection = 0xFFFFC2E5.toInt(), bookmark = 0xFF7A2EFF.toInt(),
                highlightColors = listOf(
                    0xFFFF1F8E.toInt(), 0xFF00A3FF.toInt(), 0xFF7A2EFF.toInt(), 0xFFFFD400.toInt(),
                    0xFF00D9A3.toInt(), 0xFFFF6B35.toInt(), 0xFF4DC3FF.toInt(), 0xFF3DDE8A.toInt()
                ),
                progress = 0xFFFF1F8E.toInt(), divider = 0xFFF7CFE6.toInt(),
                isDark = false
            ),
            "rainbow" to Theme(
                id = "rainbow", name = "Rainbow",
                background = 0xFFFFFBF0.toInt(), surface = 0xFFFFF0D8.toInt(),
                primaryText = 0xFF2A1A08.toInt(), secondaryText = 0xFF8A6A48.toInt(),
                headingText = 0xFFFF2E63.toInt(), link = 0xFF0091FF.toInt(),
                selection = 0xFFFFE08A.toInt(), bookmark = 0xFF8A2EFF.toInt(),
                highlightColors = listOf(
                    0xFFFF2E63.toInt(), 0xFFFF9E2C.toInt(), 0xFFFFD400.toInt(), 0xFF3DDE8A.toInt(),
                    0xFF00A3FF.toInt(), 0xFF8A2EFF.toInt(), 0xFFFF4FD8.toInt(), 0xFF00C2C7.toInt()
                ),
                progress = 0xFFFF2E63.toInt(), divider = 0xFFF2E2C4.toInt(),
                isDark = false
            )
        )

        fun getPreset(id: String): Theme = PRESETS[id] ?: PRESETS["paper"]!!

        /** What every theme picker offers, in PRESETS declaration order. */
        val PICKER: List<Theme> by lazy { PRESETS.values.toList() }
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

