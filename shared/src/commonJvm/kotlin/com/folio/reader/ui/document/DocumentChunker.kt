package com.folio.reader.ui.document

import com.folio.reader.ui.render.ReaderSection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Splits one generated reflowable-document `index.html` into ordered,
 * roughly screen-sized chunks so the reader can *window* them exactly like the
 * EPUB reader windows chapters (see `ReaderContentLoader`).
 *
 * Rendering the whole document as a single WebView section overruns the device
 * GPU tile budget on large/image-heavy docs ("tile memory limits exceeded"),
 * which is what drove the continuous dim/undim and image flicker. Feeding the
 * document as N ordered [ReaderSection]s lets the windowing machinery keep only
 * a few chunks in the DOM at once, growing/trimming them as the reader scrolls.
 *
 * Each chunk is a standalone HTML document that reuses the shared `<head>`
 * markup, so the contract the windowing code assumes holds:
 *  - `spineIndex` is unique, contiguous and monotonic in reading order (0..N-1).
 *  - `chapterId` is unique (`document-chunk-<i>`).
 *  - `href` is `generated/index.html` for every chunk, so relative `src`/`url()`
 *    references still resolve through `DocumentReaderViewModel.resolveResource`.
 */
object DocumentChunker {
    /** Roughly one to two screens of prose before a fresh chunk is started. */
    private const val TEXT_BUDGET = 1800

    /** A single chunk never gathers more than this many top-level elements. */
    private const val MAX_TOP_LEVEL = 12

    /** Media elements: two in one chunk is the boundary that starts a new one. */
    private val MEDIA_TAGS = setOf("img", "figure", "table")

    private const val HREF = "generated/index.html"

    /**
     * Splits [indexHtml] into ordered chunks. Always returns at least one chunk;
     * on any parse failure (or a body with no element children) it degrades to a
     * single section carrying the whole document, i.e. today's behaviour.
     */
    fun chunk(indexHtml: String): List<ReaderSection> =
        try {
            chunkInternal(indexHtml).ifEmpty { listOf(fallback(indexHtml)) }
        } catch (_: Throwable) {
            listOf(fallback(indexHtml))
        }

    private fun chunkInternal(indexHtml: String): List<ReaderSection> {
        val doc = Jsoup.parse(indexHtml)
        // Only the publisher <style>/<link> markup is reused across chunks; the
        // reader's own stylesheet is injected by the surface, not carried here.
        val headHtml = doc.head()?.select("style, link")?.joinToString("") { it.outerHtml() }.orEmpty()
        val body = doc.body() ?: return listOf(fallback(indexHtml))
        val children = body.children()
        if (children.isEmpty()) return listOf(fallback(indexHtml))

        val groups = mutableListOf<List<Element>>()
        var current = mutableListOf<Element>()
        var textLength = 0
        var hasMedia = false

        for (child in children) {
            val childHasMedia = child.hasMedia()
            // Never split inside an element: the boundary is only ever *between*
            // top-level children, and only once the current chunk holds at least one.
            if (current.isNotEmpty() &&
                (textLength >= TEXT_BUDGET ||
                    (hasMedia && childHasMedia) ||
                    current.size >= MAX_TOP_LEVEL)
            ) {
                groups.add(current)
                current = mutableListOf()
                textLength = 0
                hasMedia = false
            }
            current.add(child)
            textLength += child.text().length
            if (childHasMedia) hasMedia = true
        }
        if (current.isNotEmpty()) groups.add(current)

        return groups.mapIndexed { index, group ->
            val bodyHtml = group.joinToString("") { it.outerHtml() }
            ReaderSection(
                spineIndex = index,
                chapterId = "document-chunk-$index",
                href = HREF,
                html = "<!DOCTYPE html><html><head>$headHtml</head><body>$bodyHtml</body></html>"
            )
        }
    }

    /** True when [element] is, or contains, an image/figure/table. */
    private fun Element.hasMedia(): Boolean =
        tagName().lowercase() in MEDIA_TAGS || selectFirst("img, figure, table") != null

    private fun fallback(indexHtml: String): ReaderSection =
        ReaderSection(
            spineIndex = 0,
            chapterId = "document-chunk-0",
            href = HREF,
            html = indexHtml
        )
}
