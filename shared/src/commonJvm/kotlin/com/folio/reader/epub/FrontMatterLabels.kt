package com.folio.reader.epub

/**
 * Front- and back-matter pages (credits, acknowledgements, dedication) name themselves with
 * styled paragraphs rather than `h1..h6`, so heading extraction misses them. They then fell
 * through to a label built from the spine position, which printed "Chapter 10" in the middle of
 * the front matter, between "Acknowledgments" and "Dramatis Personae".
 *
 * Two rules, most confident first: a known matter name appearing in the document title, the
 * filename, or one of the page's opening lines; otherwise the page's own leading line, which is
 * where a chapter heading sits when the publisher used a paragraph instead of a heading tag.
 * Neither rule guesses — no match returns null and the caller decides what to label the page.
 */
internal object FrontMatterLabels {

    /** Below this many words a page is book furniture, not a chapter worth numbering. */
    const val MIN_BODY_WORDS = 150L

    /**
     * Matched keyword-by-keyword in this order, so "publishing history" resolves before
     * "contents" can be found inside a longer phrase. Values are the display form, which keeps
     * the list free of a publisher's inconsistent capitalisation.
     */
    private val vocabulary = listOf(
        "publishing history" to "Copyright",
        "all rights reserved" to "Copyright",
        "copyright" to "Copyright",
        "table of contents" to "Contents",
        "contents" to "Contents",
        "acknowledg" to "Acknowledgments",
        "title page" to "Title Page",
        "titlepage" to "Title Page",
        "half title" to "Half Title",
        "halftitle" to "Half Title",
        "dedication" to "Dedication",
        "epigraph" to "Epigraph",
        "foreword" to "Foreword",
        "preface" to "Preface",
        "author's note" to "Author's Note",
        "note to readers" to "Note to Readers",
        "introduction" to "Introduction",
        "dramatis personae" to "Dramatis Personae",
        "also by" to "Also By",
        "by the same author" to "Also By",
        "about the author" to "About the Author",
        "about this ebook" to "About This eBook",
        "glossary" to "Glossary",
        "appendix" to "Appendix",
        "illustrations" to "Illustrations"
    )

    /** Publisher filename conventions — Random House / Spectra name files with opaque tokens. */
    private val abbreviations = mapOf(
        "cvi" to "Cover",
        "cov" to "Cover",
        "cover" to "Cover",
        "backcover" to "Back Cover",
        "tp" to "Title Page",
        "ti" to "Title Page",
        "title" to "Title Page",
        "titlepage" to "Title Page",
        "half" to "Half Title",
        "cop" to "Copyright",
        "copyright" to "Copyright",
        "toc" to "Contents",
        "contents" to "Contents",
        "ded" to "Dedication",
        "dedication" to "Dedication",
        "ack" to "Acknowledgments",
        "map" to "Map",
        "maps" to "Map",
        "itr" to "Introduction",
        "intro" to "Introduction",
        "pre" to "Preface",
        "preface" to "Preface",
        "fore" to "Foreword",
        "foreword" to "Foreword",
        "eps" to "Epigraph",
        "ata" to "About the Author",
        "adc" to "Also By",
        "appa" to "Appendix",
        "glo" to "Glossary"
    )

    private val blockEnd = Regex("(?i)</(?:p|div|h[1-6]|blockquote|li|td)>")
    private val tags = Regex("<[^>]+>")
    private val whitespace = Regex("\\s+")
    private val fileExtension = Regex("(?i)\\.(x?html?|htm|xml)$")

    fun label(html: String, href: String, bookTitle: String): String? =
        namedMatter(html, href, bookTitle) ?: leadingLine(html, bookTitle)

    private fun namedMatter(html: String, href: String, bookTitle: String): String? {
        val stem = href.substringBefore('#').substringBefore('?')
            .substringAfterLast('/')
            .substringBeforeLast('.')
        val match = abbreviations[stem.lowercase()]
            ?: stem.lowercase().split(Regex("[^a-z0-9]+")).firstOrNull { abbreviations.containsKey(it) }
                ?.let { abbreviations[it] }
        if (match != null) return match

        val candidates = mutableListOf<String>()
        documentTitle(html)?.let { candidates.add(it) }
        stem.replace(Regex("[^A-Za-z0-9]+"), " ").trim().let { if (it.isNotEmpty()) candidates.add(it) }
        candidates.addAll(blocks(html).take(10).filter { it.length < 70 })

        val title = bookTitle.trim().lowercase()
        for ((keyword, name) in vocabulary) {
            for (candidate in candidates) {
                val normalized = candidate.lowercase().trim()
                if (normalized.isEmpty() || normalized == title) continue
                if (normalized.startsWith(keyword) || normalized.contains(keyword)) return name
            }
        }
        return null
    }

    /**
     * The page's first short line. Bounded by length and word count because that is what
     * separates a heading rendered as a paragraph from the prose or epigraph that follows it.
     */
    private fun leadingLine(html: String, bookTitle: String): String? {
        val title = bookTitle.trim().lowercase()
        return blocks(html).take(4).firstOrNull { line ->
            line.length <= 60 && line.split(' ').size <= 8 && line.lowercase() != title &&
                !line.endsWith(".") && !line.endsWith(",") && !line.endsWith(";") && !line.endsWith(":") &&
                !line.endsWith(".com") && !line.endsWith(".net") && !line.endsWith(".org")
        }
    }

    private fun documentTitle(html: String): String? {
        val raw = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1) ?: return null
        return plainText(raw).replace(fileExtension, "").takeIf { it.isNotEmpty() }
    }

    private fun blocks(html: String): List<String> {
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        val body = if (bodyStart >= 0) html.substring(bodyStart) else html
        return body.split(blockEnd).map { plainText(it) }.filter { it.isNotEmpty() }
    }

    private fun plainText(markup: String): String =
        markup.replace(tags, " ").replace(whitespace, " ").trim()
}
