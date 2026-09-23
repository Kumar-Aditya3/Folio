package com.folio.reader.ui.render

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Builds the single document a continuous-layout reader scrolls: every chapter
 * of the window becomes a `<section data-folio-spine=… data-folio-chapter=…>`
 * under one root, and each chapter's own `<head>` (publisher styles) is merged
 * into the combined head. Chapter styles are near-always identical layout CSS,
 * so a merged head keeps every section visually consistent.
 *
 * Sections are the only thing the ContinuousEngine understands — it reports
 * progress per section and asks the host to extend the window at either end.
 */
object ReaderWindowAssembler {

    // Compiled once: splitDocument runs per section, per assemble/fragment, so per-call regex
    // compilation here was avoidable work on the chapter-turn / window-extend path.
    private val HEAD_BLOCK = Regex("(?is)<head[^>]*>(.*?)</head>")
    private val BODY_BLOCK = Regex("(?is)<body[^>]*>(.*?)</body>")
    private val DOCTYPE_PREFIX = Regex("(?is)^<!DOCTYPE[^>]*>")
    private val HTML_TAGS = Regex("(?is)</?html[^>]*>")

    /** Full document for a window load (or a single-section continuous load). */
    fun assemble(sections: List<ReaderSection>): String {
        val heads = StringBuilder()
        val bodies = StringBuilder()
        sections.forEach { section ->
            val (head, body) = splitDocument(section.html)
            if (head.isNotBlank()) heads.append(head)
            bodies.append(wrap(section, body))
        }
        return "<!DOCTYPE html><html><head>" + heads + "</head>" +
            "<body><div id=\"folio-continuous-root\">" + bodies + "</div></body></html>"
    }

    /** Body fragment for DOM injection (append/prepend): no head, wrapper only. */
    fun sectionFragment(section: ReaderSection): String =
        wrap(section, splitDocument(section.html).second)

    /** Embeds a string as a JSON string literal for evaluateJavascript/executeJS. */
    fun jsStringLiteral(s: String): String =
        Json.encodeToString(String.serializer(), s)

    private fun wrap(section: ReaderSection, body: String): String =
        "<section class=\"folio-chapter\" data-folio-spine=\"${section.spineIndex}\"" +
            " data-folio-chapter=\"${section.chapterId}\">$body</section>"

    /** Light head/body split; fragments without either pass through as body. */
    internal fun splitDocument(html: String): Pair<String, String> {
        if (html.isBlank()) return "" to ""
        val headMatch = HEAD_BLOCK.find(html)
        val head = headMatch?.groupValues?.get(1)?.trim().orEmpty()
        val rest = if (headMatch != null) {
            html.replaceRange(headMatch.range, "")
        } else {
            html
        }
        val bodyMatch = BODY_BLOCK.find(rest)
        val body = (bodyMatch?.groupValues?.get(1) ?: rest)
            .replace(DOCTYPE_PREFIX, "")
            .replace(HTML_TAGS, "")
            .trim()
        return head to body
    }
}
