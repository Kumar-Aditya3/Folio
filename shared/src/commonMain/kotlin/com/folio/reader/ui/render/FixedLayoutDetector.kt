package com.folio.reader.ui.render

/**
 * Detects whether a chapter's HTML is a fixed-layout (pre-paginated) page and,
 * when it can, the page's intrinsic pixel size.
 *
 * Detection order (highest-confidence first), matching the EPUB reality:
 *  1. Per-chapter `<meta name="viewport" content="width=1200, height=1600">` in
 *     the document `<head>`. This is the reliable FXL signal for EPUB3
 *     pre-paginated pages (Calibre/Kobo/Apple exports) AND it carries the page
 *     dimensions, so it is preferred. A `width=device-width` viewport is a
 *     *reflowable* hint and is deliberately ignored — only integer width AND
 *     height count as fixed layout.
 *  2. An inlined `rendition:layout` / `-pre-paginated` marker or the classic
 *     absolutely-positioned page-layer body (`class="w"` + `position:absolute`)
 *     used by Calibre/Kobo jacket/imprint plates. These carry no reliable
 *     dimensions, so they resolve to FXL with unknown size (emitted as
 *     `data-folio-fxl="1"`).
 *
 * The function is pure and side-effect free so every `ReaderSection` build site
 * (window loader, single-chapter, reflowable document) can call it on the HTML
 * it already holds. The rendering JS has its own runtime heuristic too; this is
 * the authoring-time hint that lets it size the page without guessing.
 */
object FixedLayoutDetector {

    data class Result(val isFixedLayout: Boolean, val width: Int, val height: Int) {
        companion object {
            val NONE = Result(false, 0, 0)
        }
    }

    // Only scan the head — viewport/rendition live there and it bounds the regex work.
    private val HEAD_BLOCK = Regex("(?is)<head[^>]*>(.*?)</head>")
    private val VIEWPORT_META =
        Regex("(?is)<meta\\b[^>]*\\bname\\s*=\\s*[\"']\\s*viewport\\s*[\"'][^>]*>")
    private val CONTENT_ATTR = Regex("(?is)\\bcontent\\s*=\\s*[\"']([^\"']*)[\"']")
    private val VP_WIDTH = Regex("(?i)\\bwidth\\s*=\\s*(\\d+)")
    private val VP_HEIGHT = Regex("(?i)\\bheight\\s*=\\s*(\\d+)")

    // Fallback FXL markers (dimensions unknown).
    private val RENDITION_PREPAGINATED =
        Regex("(?i)rendition:layout[^\"'>]*pre-?paginated|pre-?paginated")
    private val ABSOLUTE_LAYER = Regex("(?i)position\\s*:\\s*absolute")
    private val W_LAYER = Regex("(?is)<div\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\bw\\b")

    fun detect(html: String): Result {
        if (html.isBlank()) return Result.NONE
        val head = HEAD_BLOCK.find(html)?.groupValues?.get(1) ?: return detectFallback(html, headOnly = "")

        // 1. Viewport meta with explicit pixel dimensions — the authoritative FXL signal.
        VIEWPORT_META.find(head)?.let { meta ->
            val content = CONTENT_ATTR.find(meta.value)?.groupValues?.get(1).orEmpty()
            val w = VP_WIDTH.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = VP_HEIGHT.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) return Result(true, w, h)
        }

        return detectFallback(html, headOnly = head)
    }

    /** FXL-without-dimensions markers: rendition:layout hint, or absolutely-positioned page layers. */
    private fun detectFallback(html: String, headOnly: String): Result {
        // Inlined rendition:layout / pre-paginated marker in the head.
        if (headOnly.isNotEmpty() && RENDITION_PREPAGINATED.containsMatchIn(headOnly)) {
            return Result(true, 0, 0)
        }
        // Classic Calibre/Kobo page plate: a `class="w"` layer with absolute positioning.
        // Both must be present to avoid reflowing ordinary chapters that merely use
        // an absolute-positioned decoration somewhere.
        if (W_LAYER.containsMatchIn(html) && ABSOLUTE_LAYER.containsMatchIn(html)) {
            return Result(true, 0, 0)
        }
        return Result.NONE
    }
}
