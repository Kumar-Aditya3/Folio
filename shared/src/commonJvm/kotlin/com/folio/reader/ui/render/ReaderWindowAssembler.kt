package com.folio.reader.ui.render

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Builds the single document a continuous-layout reader scrolls: every chapter
 * of the window becomes a `<section data-folio-spine=… data-folio-chapter=…>`
 * under one root.
 *
 * Two assembly modes exist:
 *  - `shadow = true` (default, Shadow-DOM overhaul): each section carries its OWN
 *    publisher CSS as an inert `<style type="text/folio-pub">` node — the first
 *    child of the section — so the CSS does not apply globally before the JS
 *    engine moves the section into its own Shadow DOM and converts the style
 *    there. `type="text/folio-pub"` is a type the browser ignores (only empty or
 *    `text/css` types apply), keeping it inert until shadowification. The document
 *    `<head>` is left empty; publisher heads are NOT merged.
 *  - `shadow = false` (legacy): each chapter's own `<head>` (publisher styles) is
 *    merged into one combined head and sections carry body only. Chapter styles are
 *    near-always identical layout CSS, so a merged head keeps sections consistent.
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

    // Matches any <style …>…</style> block (DOTALL + case-insensitive). Used to lift publisher
    // CSS out of the head/body into a single inert per-section node for Shadow-DOM isolation.
    private val STYLE_BLOCK = Regex("(?is)<style[^>]*>(.*?)</style>")

    /** Full document for a window load (or a single-section continuous load). */
    fun assemble(sections: List<ReaderSection>, shadow: Boolean = true): String {
        val heads = StringBuilder()
        val bodies = StringBuilder()
        sections.forEach { section ->
            val (head, body) = splitDocument(section.html)
            if (shadow) {
                val (css, cleanBody) = extractPublisherCss(head, body)
                bodies.append(wrap(section, cleanBody, css))
            } else {
                if (head.isNotBlank()) heads.append(head)
                bodies.append(wrap(section, body))
            }
        }
        return "<!DOCTYPE html><html><head>" + heads + "</head>" +
            "<body><div id=\"folio-continuous-root\">" + bodies + "</div></body></html>"
    }

    /**
     * Fragment for DOM injection (append/prepend) — deliberately **not** wrapped in a `<section>`.
     *
     * `ContinuousEngine.markSection` builds the `<section class="folio-chapter"
     * data-folio-spine=…>` around whatever the host hands it, so wrapping here too nested two
     * sections under one spine. The engine's own duplicate repair then saw two elements per
     * chapter and deleted one, which is how an appended chapter ended up a zero-height shell and
     * the reader stopped being able to cross the chapter boundary.
     *
     * When `shadow` is true the fragment leads with the inert `<style type="text/folio-pub">`
     * node so the appended/prepended section carries its own publisher CSS into its Shadow DOM,
     * matching the full-document assembly.
     */
    fun sectionFragment(section: ReaderSection, shadow: Boolean = true): String {
        val (head, body) = splitDocument(section.html)
        return if (shadow) {
            val (css, cleanBody) = extractPublisherCss(head, body)
            "<style type=\"text/folio-pub\">$css</style>$cleanBody"
        } else {
            body
        }
    }

    /**
     * The `data-folio-fxl` attribute for a fixed-layout section, or `""` for a
     * reflowable one. `W:H` when both page dimensions are known, else the bare
     * `1` flag. Rendered with a leading space so it slots straight into the
     * `<section …>` open tag; harmless in the non-shadow branch where the JS
     * simply ignores an unknown attribute.
     */
    private fun fxlAttr(section: ReaderSection): String {
        if (!section.isFixedLayout) return ""
        val value = if (section.fxlWidth > 0 && section.fxlHeight > 0) {
            "${section.fxlWidth}:${section.fxlHeight}"
        } else {
            "1"
        }
        return " data-folio-fxl=\"$value\""
    }

    /** Embeds a string as a JSON string literal for evaluateJavascript/executeJS. */
    fun jsStringLiteral(s: String): String =
        Json.encodeToString(String.serializer(), s)

    /**
     * Lifts every `<style>` block's CSS out of the head (first) then body (preserving order),
     * concatenates the CSS text, and strips those blocks from the body. `url(...)` references are
     * left untouched — they are rebased upstream. Returns `concatenatedCss to bodyWithoutStyles`.
     */
    private fun extractPublisherCss(head: String, body: String): Pair<String, String> {
        val css = StringBuilder()
        STYLE_BLOCK.findAll(head).forEach { css.append(it.groupValues[1]) }
        STYLE_BLOCK.findAll(body).forEach { css.append(it.groupValues[1]) }
        val bodyWithoutStyles = body.replace(STYLE_BLOCK, "")
        return css.toString() to bodyWithoutStyles
    }

    private fun wrap(section: ReaderSection, body: String, pubCss: String? = null): String {
        val style = if (pubCss != null) "<style type=\"text/folio-pub\">$pubCss</style>" else ""
        return "<section class=\"folio-chapter\" data-folio-spine=\"${section.spineIndex}\"" +
            " data-folio-chapter=\"${section.chapterId}\"${fxlAttr(section)}>$style$body</section>"
    }

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
