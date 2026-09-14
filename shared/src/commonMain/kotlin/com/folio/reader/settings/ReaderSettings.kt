package com.folio.reader.settings

import com.folio.reader.model.Book
import com.folio.reader.model.FormattingMode
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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
    /**
     * How solid the app's glass surfaces are: the fill alpha itself, where 1 is
     * a solid surface nothing bleeds through. The designed glass look therefore
     * sits *partway* along each range, which is why these defaults are not 1 —
     * they mirror `FolioSurfaceOpacity.BAR_GLASS`/`NAV_GLASS`/`PANEL_GLASS`,
     * duplicated here because this module cannot see the compose layer.
     */
    val topBarOpacity: Float = 0.35f,
    val navBarOpacity: Float = 0.9f,
    val panelOpacity: Float = 0.97f,
    /** Reader bars, rails, drawers and the reader settings sheet; solid by default. */
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

/**
 * Diff/apply for the **global settings row**. Unlike [changedFields] (which is
 * the per-book override vocabulary), these are serializer-driven so a field
 * added to [ReaderSettings] tomorrow is covered automatically — a hand-listed
 * version would drift, and a drifted list is exactly the whole-row clobber
 * this exists to prevent. Defaults are omitted from the encoded form, so a
 * field restored to its default is absent from the JSON and counts as changed.
 */
private val settingsMergeJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

/** Names of fields whose encoded value differs between two global rows. */
fun ReaderSettings.diffFields(other: ReaderSettings): Set<String> {
    val mine = settingsMergeJson.encodeToJsonElement(ReaderSettings.serializer(), this).jsonObject
    val theirs = settingsMergeJson.encodeToJsonElement(ReaderSettings.serializer(), other).jsonObject
    return (mine.keys + theirs.keys).filterTo(mutableSetOf()) { mine[it] != theirs[it] }
}

/**
 * This row with the named fields replaced by `from`'s values. A field `from`
 * omits (holds its default) is *removed*, restoring the default rather than
 * keeping this row's value.
 */
fun ReaderSettings.withFieldsFrom(fields: Set<String>, from: ReaderSettings): ReaderSettings {
    if (fields.isEmpty()) return this
    val mine = settingsMergeJson.encodeToJsonElement(ReaderSettings.serializer(), this).jsonObject
    val theirs = settingsMergeJson.encodeToJsonElement(ReaderSettings.serializer(), from).jsonObject
    val merged = JsonObject(
        mine.toMutableMap().apply {
            for (name in fields) {
                val value = theirs[name]
                if (value != null) put(name, value) else remove(name)
            }
        }
    )
    return settingsMergeJson.decodeFromJsonElement(ReaderSettings.serializer(), merged)
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
        /** The built-in themes; the data itself lives in ReaderThemes.kt (§6). */
        val PRESETS: Map<String, Theme> get() = ReaderThemePresets

        fun getPreset(id: String): Theme = ReaderThemePresets[id] ?: ReaderThemePresets["paper"]!!

        /** What every theme picker offers, in PRESETS declaration order. */
        val PICKER: List<Theme> by lazy { ReaderThemePresets.values.toList() }
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
