package com.folio.reader.experience

enum class CardTemplate(val title: String) { EDITORIAL("Editorial"), ATMOSPHERE("Atmosphere"), MARGINALIA("Marginalia") }
enum class CardFormat(val title: String, val height: Int) { SQUARE("Square", 1080), PORTRAIT("Portrait", 1350), STORY("Story", 1920) }
data class QuoteCardOptions(val template: CardTemplate = CardTemplate.EDITORIAL, val format: CardFormat = CardFormat.PORTRAIT,
    val inkFriendly: Boolean = false, val title: Boolean = true, val author: Boolean = true, val chapter: Boolean = true,
    val cover: Boolean = false, val note: Boolean = false, val fontThemeId: String = "literary")
data class QuoteCardPage(val source: ExportSource, val text: String, val note: String? = null, val number: Int = 1, val total: Int = 1)
data class JournalOptions(val letter: Boolean = false, val inkSaver: Boolean = false, val chapters: Set<String>? = null,
    val highlights: Boolean = true, val associatedNotes: Boolean = true, val standaloneNotes: Boolean = true,
    val reflections: Boolean = true, val cover: Boolean = true, val readingSummary: Boolean = false, val ruledSpace: Boolean = false)

private val locatorNumbers = Regex("\\d+")
fun exportPassageOrder(locator: String?): String = locatorNumbers.replace(locator.orEmpty()) { it.value.padStart(12, '0') }

/** Split at grapheme-safe word boundaries; never discard or abbreviate source text. */
fun paginateExportText(text: String, fits: (String) -> Boolean): List<String> {
    if (text.isEmpty()) return emptyList()
    val breaks = java.text.BreakIterator.getLineInstance(java.util.Locale.ROOT).apply { setText(text) }
    val boundaries = mutableListOf<Int>()
    var next = breaks.first()
    while (next != java.text.BreakIterator.DONE) { if (next > 0) boundaries += next; next = breaks.next() }
    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val candidates = boundaries.filter { it > start }
        var low = 0; var high = candidates.lastIndex; var end = start
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = candidates[mid]
            if (fits(text.substring(start, candidate))) { end = candidate; low = mid + 1 } else high = mid - 1
        }
        if (end == start) {
            val chars = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
            end = chars.following(start)
            var after = chars.next()
            while (after != java.text.BreakIterator.DONE && fits(text.substring(start, after))) { end = after; after = chars.next() }
        }
        pages += text.substring(start, end); start = end
    }
    return pages
}

fun escapeJournalText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

fun readingJournalHtml(title: String, author: String, sources: List<ExportSource>, options: JournalOptions,
    summary: String = "", fontCss: String = "", coverData: String? = null): String {
    fun html(value: String) = escapeJournalText(value).replace("\n", "<br>")
    val filtered = sources.filter { options.chapters == null || it.chapterId.orEmpty() in options.chapters }
        .filter { if (it.isReflection) options.reflections else if (it.isPersonalNote) options.standaloneNotes else options.highlights }
    return buildString {
        append("<!doctype html><html data-paper=\"").append(if (options.letter) "letter" else "a4").append("\"><head><meta charset=\"utf-8\"><style>")
        append(fontCss)
        append("@page{size:").append(if (options.letter) "Letter" else "A4").append(";margin:18mm}")
        append("*{box-sizing:border-box}body{font:11pt/1.5 FolioText,serif;color:#27251f;background:")
        append(if (options.inkSaver) "#fff" else "#fffdf8").append("}h1,h2{font-family:FolioDisplay,serif;font-weight:500;line-height:1.2}h1{font-size:34pt}h2{font-size:19pt;break-after:avoid}p{orphans:3;widows:3}blockquote{margin:8mm 0;padding:0 0 0 5mm;border-left:1pt solid #81745f}.titlepage{break-after:page;padding-top:25mm}.eyebrow{font:9pt FolioText,sans-serif;letter-spacing:.15em;text-transform:uppercase}.chapter{margin-top:12mm}.note{padding:4mm 0;color:#494237}.label{font:8pt FolioText,sans-serif;letter-spacing:.08em;text-transform:uppercase}.rule{height:8mm;border-bottom:.3pt solid #c8c2b7}.cover{max-height:80mm;max-width:55mm;margin-bottom:10mm}</style></head><body>")
        append("<section class='titlepage'><p class='eyebrow'>Folio · Reading journal</p>")
        if (options.cover && coverData != null) append("<img class='cover' src='data:image/png;base64,").append(coverData).append("'>")
        append("<h1>").append(html(title)).append("</h1><p>").append(html(author)).append("</p>")
        if (options.readingSummary && summary.isNotBlank()) append("<p>").append(html(summary)).append("</p>")
        append("</section>")
        filtered.groupBy { it.chapter }.forEach { (chapter, entries) ->
            append("<h2 class='chapter'>").append(html(chapter.ifBlank { "Saved writing" })).append("</h2>")
            for (source in entries) {
                if (source.isPersonalNote) append("<p class='label'>").append(if (source.isReflection) "Reflection" else "Personal note").append("</p><p>").append(html(source.text)).append("</p>")
                else append("<blockquote dir='auto'>").append(html(source.text)).append("</blockquote>")
                if (options.associatedNotes && !source.note.isNullOrBlank()) append("<div class='note'><p class='label'>My note</p><p dir='auto'>").append(html(source.note)).append("</p></div>")
            }
            if (options.ruledSpace) { append("<p class='label'>Reflections</p>"); repeat(6) { append("<div class='rule'></div>") } }
        }
        if (filtered.isEmpty()) append("<p>No saved writing in the selected chapters.</p>")
        append("</body></html>")
    }
}
