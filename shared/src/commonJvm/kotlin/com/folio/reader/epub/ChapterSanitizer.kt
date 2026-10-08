package com.folio.reader.epub

/**
 * Neutralizes executable content in EPUB chapter HTML before it is handed to the
 * reader surfaces (WebView on Android, embedded Chromium on desktop). Folio never
 * needs EPUB scripts — its own bridge is injected by the host after load — but a
 * malicious book could otherwise run script with file access.
 */
object ChapterSanitizer {

    private val SCRIPT_BLOCK = Regex("(?is)<script\\b[^>]*>.*?</script>")
    private val SCRIPT_SELF = Regex("(?is)<script\\b[^>]*/>")
    private val IFRAME_BLOCK = Regex("(?is)<iframe\\b[^>]*>.*?</iframe>")
    private val IFRAME_SELF = Regex("(?is)<iframe\\b[^>]*/?>")
    private val OBJECT_BLOCK = Regex("(?is)<object\\b[^>]*>.*?</object>")
    private val EMBED_SELF = Regex("(?is)<embed\\b[^>]*/?>")
    private val JS_HREF = Regex("(?i)(href|src)\\s*=\\s*(\"javascript:[^\"]*\"|'javascript:[^']*')")

    // Inline handlers inside a tag: <a onclick="…" onmouseover="…">. Stripping one
    // shifts the remainder, so re-run until stable (rarely more than two passes).
    private val INLINE_HANDLER = Regex("(?is)(<[^>]*?)\\s+on[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")

    fun sanitize(html: String): String {
        var out = html
        out = SCRIPT_BLOCK.replace(out, "")
        out = SCRIPT_SELF.replace(out, "")
        out = IFRAME_BLOCK.replace(out, "")
        out = IFRAME_SELF.replace(out, "")
        out = OBJECT_BLOCK.replace(out, "")
        out = EMBED_SELF.replace(out, "")
        out = JS_HREF.replace(out, "$1=\"\"")
        while (true) {
            val stripped = INLINE_HANDLER.replace(out, "$1")
            if (stripped == out) break
            out = stripped
        }
        return out
    }
}
