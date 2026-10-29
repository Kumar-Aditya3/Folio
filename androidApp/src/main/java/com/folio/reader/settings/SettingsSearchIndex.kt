package com.folio.reader.settings

import java.text.Normalizer

internal data class SettingsSearchEntry(
    val route: String,
    val title: String,
    val section: String,
    val summary: String,
    val keywords: String,
)

internal val settingsSearchEntries = listOf(
    SettingsSearchEntry(FolioSettingsCategory.READING_SURFACES, "Widgets & notifications", "Reading", "Home-screen widgets and quiet reading reminders", "widget widgets notification notifications reminder daily goal home screen continue reading rhythm"),
    SettingsSearchEntry(FolioSettingsCategory.TEXT_AND_PAGE, "Text & page", "Reading", "Typeface, measure, alignment, hyphenation", "font fonts size weight line paragraph spacing margins layout scroll paginated formatting original hybrid normalized justify serif"),
    SettingsSearchEntry(FolioSettingsCategory.TRANSPARENCY, "Transparency", "Appearance", "Bars, cards, reader controls and widgets", "opacity transparent translucent glass material reader panels metallic sheen vividness vibrancy blur widget widgets home screen wallpaper"),
    SettingsSearchEntry(FolioSettingsCategory.THEMES, "Themes", "Appearance", "Theme packs, typeface pairing, fonts", "colors colours dark light palette typeface font pairing appearance classic charcoal paper ember forest midnight ocean abyss editorial crimson blush vaporwave acid rave synthwave porcelain cobalt linen copper reading desk home hero revolving"),
    SettingsSearchEntry(FolioSettingsCategory.DISCOVER, "Discover", "Manga", "Usual suggestions, selected extensions, or hidden", "home recommendations sources extensions whitelist allowlist choose hide disable remove selected"),
    SettingsSearchEntry(FolioSettingsCategory.READING, "Reader behavior", "Reading", "Chapter title, progress, clock, brightness", "reader controls navigation gestures screen brightness keep awake chapter progress clock eye protection editorial"),
    SettingsSearchEntry(FolioSettingsCategory.NARRATION, "Narration", "Reading", "Offline English voice and listening", "tts text speech audio voice narration listen download phonon sleep timer"),
    SettingsSearchEntry(FolioSettingsCategory.DEFAULTS, "Reader defaults", "Reading", "What a new book or manga starts with", "default book manga reader mode rtl ltr webtoon page opening start"),
    SettingsSearchEntry(FolioSettingsCategory.DOCUMENTS, "Documents", "Reading", "How PDFs and Word files are shown", "pdf word docx html txt document reader text page default"),
    SettingsSearchEntry(FolioSettingsCategory.CUSTOM_THEME, "Custom theme", "Appearance", "Author your own palette and gradient", "custom colours colors palette gradient background"),
    SettingsSearchEntry(FolioSettingsCategory.ANIMATIONS, "Animations", "Appearance", "How much the app and the background move", "motion animation reduce reduced speed field movement background accessibility"),
    SettingsSearchEntry(FolioSettingsCategory.CLOUD_SYNC, "Cloud sync", "Sync & backup", "Firebase project and sync preferences", "cloud sync firebase account credentials devices project login"),
    SettingsSearchEntry(FolioSettingsCategory.ADVANCED, "Backup & data", "Sync & backup", "Backups, annotation export, storage", "backup restore export import annotations data reset"),
    SettingsSearchEntry(FolioSettingsCategory.STORAGE, "Storage", "Sync & backup", "What's using space, by category", "disk space cache size storage download files"),
    SettingsSearchEntry(FolioSettingsCategory.STATS, "Exclusions", "Library tools", "Keep titles out of Stats and Home", "stats statistics exclude excluded hidden manga books source extension category recommendations"),
    SettingsSearchEntry(FolioSettingsCategory.LIBRARY_SCAN, "Library scanning", "Library tools", "Import ebooks and documents automatically", "scan folder device import automatic files storage ebook epub pdf docx"),
    SettingsSearchEntry(FolioSettingsCategory.SEMANTIC_SEARCH, "Semantic search", "Library tools", "Find passages by meaning, on this device", "semantic search model index download echoes ai offline meaning"),
    SettingsSearchEntry(FolioSettingsCategory.MANGA, "Manga updates", "Manga", "Background chapter checks", "manga notifications updates background check schedule chapters"),
    SettingsSearchEntry("@tags", "Tags", "Library tools", "Organise books with tags", "tag labels library collections categories organise organize"),
    SettingsSearchEntry("@quotes", "Quotes", "Library tools", "Saved passages across books", "quote highlights annotations saved passages export"),
    SettingsSearchEntry("@revisit", "Revisit", "Library tools", "Books worth returning to", "revisit review notes bookmarks finished return"),
    SettingsSearchEntry("@extensions", "Extensions", "Manga", "Browse and manage manga sources", "extension source install uninstall repository repos trust browse"),
    SettingsSearchEntry("@downloads", "Downloads", "Manga", "Downloaded chapters and storage", "downloads offline queue chapters files cache"),
    SettingsSearchEntry("@history", "History", "Manga", "Recently read chapters", "history recent manga chapters reading"),
)

private fun String.searchForm(): String = Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** Every token must match. Titles outrank incidental keywords; ties preserve the curated order. */
internal fun searchSettings(query: String): List<SettingsSearchEntry> {
    val tokens = query.searchForm().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return settingsSearchEntries.take(8)
    return settingsSearchEntries.mapIndexedNotNull { index, entry ->
        val title = entry.title.searchForm()
        val body = "${entry.title} ${entry.section} ${entry.summary} ${entry.keywords}".searchForm()
        if (tokens.any { it !in body }) null else Triple(entry,
            tokens.sumOf { if (title.startsWith(it)) 12 else if (it in title) 6 else 1 }, index)
    }.sortedWith(compareByDescending<Triple<SettingsSearchEntry, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
}
