package com.folio.reader.epub

import com.folio.reader.model.*
import com.folio.reader.platform.FolioPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class EpubParser(
    private val platform: FolioPlatform? = null
) {
    private val xmlFactory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    suspend fun parseEpub(filePath: String): ParsedEpub {
        return withContext(Dispatchers.IO) {
            val zipFile = ZipFile(filePath)
            try {
                // 1. Read container.xml to find OPF
                val opfPath = findOpfPath(zipFile)
                    ?: throw EpubParseException("No OPF file found in container.xml")

                // 2. Parse OPF
                val opfData = readZipEntry(zipFile, opfPath)
                val (metadata, manifest, spine) = parseOpf(opfData, opfPath)

                // 3. Parse TOC/Navigation
                val toc = parseToc(zipFile, manifest, opfPath)

                // 4. Extract cover
                val (coverData, coverMimeType) = extractCover(zipFile, manifest, metadata, opfPath)

                // 5. Read all HTML content
                val htmlContent = readHtmlContent(zipFile, manifest, spine, opfPath)

                // 6. Normalize chapters
                val chapters = normalizeChapters(spine, manifest, toc, htmlContent, metadata)

                // 7. Calculate totals
                val (totalChars, totalWords) = calculateTotals(chapters)

                return@withContext ParsedEpub(
                    metadata = metadata,
                    manifest = manifest,
                    spine = spine,
                    toc = toc,
                    coverData = coverData,
                    coverMimeType = coverMimeType,
                    chapters = chapters,
                    totalCharacters = totalChars,
                    totalWords = totalWords,
                    rawHtmlByHref = htmlContent
                )
            } finally {
                zipFile.close()
            }
        }
    }

    /**
     * The book's own navigation entries as Contents rows, aimed at positions in [chapters].
     *
     * Reads only container.xml, the OPF and the nav document — no chapter content — so this is
     * cheap enough to run every time a book opens. A book that splits each chapter over several
     * spine files otherwise lists every fragment, and names the ones it has no label for.
     *
     * When the book's own nav is degenerate — most often an EPUB2 NCX stubbed down to a single
     * "Start" navPoint — two fallbacks recover a real Contents: the full HTML Contents page linked
     * from the OPF <guide>, and, failing that, the chapter headings scanned out of the spine
     * documents themselves (for pdftohtml/Calibre conversions that carry no structured nav at all).
     *
     * Returns empty when the book has no usable nav, so the caller falls back to chapter titles.
     */
    suspend fun parseBookToc(filePath: String, chapters: List<Chapter>): List<BookTocRow> =
        withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(filePath).use { zip ->
                    val opfPath = findOpfPath(zip) ?: return@use emptyList<BookTocRow>()
                    val opfData = readZipEntry(zip, opfPath)
                    val (_, manifest, _) = parseOpf(opfData, opfPath)

                    val primaryRows = projectTocRows(parseToc(zip, manifest, opfPath), chapters)
                    // A healthy nav names the whole book; keep it untouched. Only a degenerate nav
                    // (0–1 usable rows, e.g. a stub NCX pointing only at the cover) is worth the
                    // extra work of looking for a better Contents source.
                    if (primaryRows.size > 1) return@use primaryRows

                    // 1) The full HTML Contents page the OPF <guide> points at (EPUB2 publishers
                    //    often ship one even when the NCX is a stub).
                    val guideRows = runCatching {
                        val guideHref = findGuideTocHref(opfData)
                            ?.substringBefore("#")?.substringBefore("?")
                            ?: return@runCatching emptyList<BookTocRow>()
                        val entry = findZipEntry(zip, opfPath, guideHref)
                            ?: return@runCatching emptyList<BookTocRow>()
                        projectTocRows(parseHtmlToc(zip, readZipEntry(zip, entry), opfPath), chapters)
                    }.getOrDefault(emptyList())
                    if (guideRows.size > 1) return@use guideRows

                    // 2) No structured Contents anywhere (stub NCX, empty guide) — the common shape
                    //    of a pdftohtml/Calibre conversion where the whole book is a handful of
                    //    oversized spine files and the chapter headings survive only as styled
                    //    paragraphs. Recover the Contents by scanning those headings, each aimed at
                    //    its own paragraph so navigation still lands on the chapter.
                    val contentRows = runCatching { scanContentToc(zip, opfPath, chapters) }
                        .getOrDefault(emptyList())
                    if (contentRows.size > 1 && contentRows.size > primaryRows.size) return@use contentRows

                    if (guideRows.size > primaryRows.size) guideRows else primaryRows
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Projects a parsed TOC onto spine positions: one clean-labelled row per chapter it names,
     * de-duplicated (an entry reached by several fragments emits once), spine files it never names
     * dropped, and nesting flattened. This is the shared "optimised" Contents shape; both the
     * book's own nav and the guide-HTML fallback pass through it identically.
     */
    private fun projectTocRows(toc: List<EpubTocItem>, chapters: List<Chapter>): List<BookTocRow> {
        if (toc.isEmpty()) return emptyList()
        val rows = mutableListOf<BookTocRow>()
        val emitted = mutableSetOf<EpubTocItem>()
        chapters.forEachIndexed { index, chapter ->
            val match = findTocMatch(chapter.href, toc)
                ?.takeIf { it.label.isNotBlank() && emitted.add(it) } ?: return@forEachIndexed
            rows += BookTocRow(match.label.trim(), index)
        }
        return rows
    }

    private fun findOpfPath(zipFile: ZipFile): String? {
        val containerEntry = zipFile.getEntry("META-INF/container.xml")
            ?: return null

        zipFile.getInputStream(containerEntry).use { input ->
            val parser = xmlFactory.newPullParser()
            parser.setInput(ByteArrayInputStream(bomFree(input.readBytes())), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return parser.getAttributeValue(null, "full-path")
                }
                eventType = parser.next()
            }
        }
        return null
    }

    private fun readZipEntry(zipFile: ZipFile, entryPath: String): ByteArray {
        val entry = zipFile.getEntry(entryPath)
            ?: throw EpubParseException("Entry not found: $entryPath")
        // Bounded read: a maliciously/ pathologically compressed entry (zip bomb) could otherwise
        // allocate gigabytes via readBytes() and OOM the app on import. Mirrors the Office path.
        return zipFile.getInputStream(entry).use { readBounded(it, MAX_EPUB_ENTRY_BYTES) }
    }

    private fun parseOpf(opfData: ByteArray, opfPath: String): Triple<EpubMetadata, List<EpubManifestItem>, List<EpubSpineItem>> {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(opfData)), declaredCharset(opfData) ?: "UTF-8")

        var metadata = EpubMetadata(title = "")
        val manifest = mutableListOf<EpubManifestItem>()
        val spine = mutableListOf<EpubSpineItem>()

        var currentElement = ""
        var inMetadata = false
        var inManifest = false
        var inSpine = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentElement = parser.name
                    when (parser.name) {
                        "metadata" -> inMetadata = true
                        "manifest" -> inManifest = true
                        "spine" -> inSpine = true
                        "meta" -> {
                            if (inMetadata) {
                                val nameAttr = parser.getAttributeValue(null, "name")
                                if (nameAttr == "cover") {
                                    val content = parser.getAttributeValue(null, "content")
                                    if (!content.isNullOrBlank()) {
                                        metadata = metadata.copy(coverHref = content)
                                    }
                                }
                            }
                        }
                        "item" -> {
                            if (inManifest) {
                                val id = parser.getAttributeValue(null, "id") ?: ""
                                val href = parser.getAttributeValue(null, "href") ?: ""
                                val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                                val properties = parser.getAttributeValue(null, "properties")
                                manifest.add(EpubManifestItem(id, href, mediaType, properties))
                            }
                        }
                        "itemref" -> {
                            if (inSpine) {
                                val idref = parser.getAttributeValue(null, "idref") ?: ""
                                // EPUB spec uses yes/no; tolerate true/false too.
                                val linear = when (parser.getAttributeValue(null, "linear")?.lowercase()) {
                                    "no", "false" -> false
                                    else -> true
                                }
                                val properties = parser.getAttributeValue(null, "properties")
                                spine.add(EpubSpineItem(idref, linear, properties))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inMetadata) {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            metadata = updateMetadata(metadata, currentElement, text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "metadata" -> inMetadata = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
            }
            eventType = parser.next()
        }

        val finalMetadata = if (metadata.title.isBlank()) metadata.copy(title = "Unknown") else metadata

        return Triple(
            finalMetadata,
            manifest.map { it.copy(href = resolveHref(opfPath, it.href)) },
            spine
        )
    }

    private fun updateMetadata(metadata: EpubMetadata, element: String, text: String): EpubMetadata {
        return when (element) {
            "title" -> metadata.copy(title = text)
            "subtitle" -> metadata.copy(subtitle = text)
            "creator" -> metadata.copy(authors = metadata.authors + text)
            "publisher" -> metadata.copy(publisher = text)
            "language" -> metadata.copy(language = text)
            "identifier" -> {
                val normalized = text.replace("-", "").replace(" ", "").trim()
                val isbn = if (normalized.matches(Regex("\\d{13}|\\d{10}"))) normalized else null
                metadata.copy(isbn = isbn ?: metadata.isbn, identifier = metadata.identifier + text)
            }
            "description" -> metadata.copy(description = text)
            "date" -> metadata.copy(
                date = text,
                publicationDate = parseDate(text) ?: metadata.publicationDate
            )
            "subject" -> metadata.copy(subject = metadata.subject + text)
            "contributor" -> metadata.copy(contributor = metadata.contributor + text)
            "format" -> metadata.copy(format = text)
            "rights" -> metadata.copy(rights = text)
            "source" -> metadata.copy(source = text)
            "type" -> metadata.copy(type = text)
            "cover" -> {
                val coverHref = text
                metadata.copy(coverHref = coverHref)
            }
            else -> metadata
        }
    }

    private fun parseToc(zipFile: ZipFile, manifest: List<EpubManifestItem>, opfPath: String): List<EpubTocItem> {
        // Try NAV (EPUB3) first
        val navItem = manifest.firstOrNull { it.properties?.contains("nav") == true }
            ?: manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" } // NCX (EPUB2)

        if (navItem == null) return emptyList()

        val navEntry = findZipEntry(zipFile, opfPath, navItem.href)
            ?: throw EpubParseException("TOC entry not found: ${navItem.href}")
        val navData = readZipEntry(zipFile, navEntry)

        return if (navItem.mediaType == "application/xhtml+xml" || navItem.href.endsWith(".xhtml") || navItem.href.endsWith(".html")) {
            parseNav(navData)
        } else {
            parseNcx(navData)
        }
    }

    /**
     * The href of the human-readable Contents page an EPUB2 OPF <guide> points at
     * (<reference type="toc" .../>), or null. Used only as a fallback when the machine nav
     * (NCX/EPUB3 nav) is degenerate; many EPUB2 books ship a stub NCX yet a complete HTML TOC.
     */
    private fun findGuideTocHref(opfData: ByteArray): String? {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(opfData)), declaredCharset(opfData) ?: "UTF-8")
        var inGuide = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "guide" -> inGuide = true
                    "reference" -> if (inGuide) {
                        val type = parser.getAttributeValue(null, "type")?.trim()?.lowercase()
                        if (type == "toc") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) return href
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "guide") inGuide = false
            }
            eventType = parser.next()
        }
        return null
    }

    /**
     * Reads a human-readable HTML Contents page into TOC entries: every <a href> with real text,
     * in document order. Anchor hrefs are relative to the Contents page, so each is resolved to its
     * actual zip entry (matching the resolved form of chapter hrefs) before matching. Nesting is
     * left flat — [projectTocRows] flattens anyway.
     */
    private fun parseHtmlToc(zipFile: ZipFile, htmlData: ByteArray, opfPath: String): List<EpubTocItem> {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(htmlData)), declaredCharset(htmlData) ?: "UTF-8")
        val toc = mutableListOf<EpubTocItem>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "a") {
                val rawHref = parser.getAttributeValue(null, "href") ?: ""
                // Accumulate the anchor's full text, including nested markup (e.g. <a>Preview of
                // <em>Ship of Magic</em></a>), by walking to the matching </a>.
                val sb = StringBuilder()
                var depth = 1
                eventType = parser.next()
                while (eventType != XmlPullParser.END_DOCUMENT && depth > 0) {
                    when (eventType) {
                        XmlPullParser.TEXT -> sb.append(parser.text).append(" ")
                        XmlPullParser.START_TAG -> depth++
                        XmlPullParser.END_TAG -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    if (depth > 0) eventType = parser.next() else break
                }
                val title = sb.toString().trim().replace(RE_WS, " ")
                if (title.isNotEmpty() && rawHref.isNotEmpty() && !rawHref.startsWith("#")) {
                    val clean = rawHref.substringBefore("#").substringBefore("?")
                    val resolved = findZipEntry(zipFile, opfPath, clean) ?: clean
                    toc.add(EpubTocItem(label = title, href = resolved))
                }
                eventType = if (eventType == XmlPullParser.END_DOCUMENT) eventType else parser.next()
                continue
            }
            eventType = parser.next()
        }
        return toc
    }

    /**
     * Last-resort Contents for books with no structured nav (stub NCX, empty guide): scan each
     * spine document for chapter headings that survive only as styled paragraphs — the hallmark of
     * a pdftohtml/Calibre conversion where the whole book is a few oversized files and the print
     * chapter breaks were never turned into markup.
     *
     * Each recovered row is aimed at the paragraph its heading sits on, as a 0-based ordinal into
     * the document's `<p>` elements. That is the exact grammar the reader's `p:<n>` seek uses, so a
     * tap lands on the chapter even though several chapters share one spine file. A heading is taken
     * only when its paragraph is upper-case (a real heading style like "CHAPTER ONE - OF PRIESTS…"),
     * which is what separates it from Title-Case front matter ("Book design by …").
     */
    private fun scanContentToc(
        zipFile: ZipFile,
        opfPath: String,
        chapters: List<Chapter>
    ): List<BookTocRow> {
        val rows = mutableListOf<BookTocRow>()
        chapters.forEachIndexed { index, chapter ->
            val entry = findZipEntry(zipFile, opfPath, chapter.href) ?: return@forEachIndexed
            val html = runCatching { decodeEpubBytes(readZipEntry(zipFile, entry)) }.getOrNull()
                ?: return@forEachIndexed
            val paragraphs = RE_P_BLOCK.findAll(html).toList()
            val total = paragraphs.size.coerceAtLeast(1)
            paragraphs.forEachIndexed { ordinal, m ->
                val text = m.groupValues[1].replace(RE_TAG, " ").replace(RE_WS, " ").trim()
                if (isChapterHeadingParagraph(text)) {
                    rows += BookTocRow(
                        title = chapterHeadingLabel(text),
                        chapterIndex = index,
                        paragraph = ordinal,
                        fraction = ordinal.toFloat() / total
                    )
                }
            }
        }
        return rows
    }

    /** An all-caps paragraph that opens like a chapter heading, short enough to be one. */
    private fun isChapterHeadingParagraph(text: String): Boolean {
        if (text.length !in 3..79) return false
        if (!RE_CHAPTER_MARKER.containsMatchIn(text)) return false
        // Upper-case heading style: at least one letter, and no lower-case letters. Title-case
        // front matter ("Book One of the Liveship Traders Trilogy") is rejected here.
        return text.any { it.isLetter() } && text.none { it.isLowerCase() }
    }

    /** "PROLOGUE - THE TANGLE" -> "Prologue"; "CHAPTER TWENTY-ONE - VISITORS" -> "Chapter Twenty-One". */
    private fun chapterHeadingLabel(text: String): String {
        val designator = text.split(RE_HEADING_SEP, limit = 2).first().trim()
        return designator.lowercase().split(' ').joinToString(" ") { word ->
            word.split('-').joinToString("-") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }

    private fun parseNav(navData: ByteArray): List<EpubTocItem> {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(navData)), "UTF-8")

        val toc = mutableListOf<EpubTocItem>()
        val stack = mutableListOf<MutableList<EpubTocItem>>()
        stack.add(toc)
        var depth = 0
        // An EPUB3 nav document holds several <nav> lists: the reading-order `toc`, plus
        // `page-list` (one entry per PRINT PAGE, labelled with the page number) and
        // `landmarks` (cover/toc/bodymatter anchors). Collecting every <a> — as this used
        // to — folded the page-list's bare "20", "29"… numbers into the Contents, so an
        // illustration spine file whose only nav reference is its page number was named
        // after that number. Only the `toc` nav is the Contents; skip the others.
        var skipNav = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "nav" -> {
                            val navType = (parser.getAttributeValue(NS_OPS, "type")
                                ?: parser.getAttributeValue(null, "type"))?.trim()?.lowercase()
                            val role = parser.getAttributeValue(null, "role")?.trim()?.lowercase()
                            skipNav = navType == "page-list" || navType == "landmarks" ||
                                role == "doc-pagelist"
                        }
                        "ol", "ul" -> {
                            depth++
                            stack.add(mutableListOf())
                        }
                        "a" -> {
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val title = runCatching { parser.nextText().trim() }.getOrDefault("")
                            if (!skipNav && title.isNotEmpty() && href.isNotEmpty()) {
                                val parent = stack.lastOrNull() ?: toc
                                parent.add(EpubTocItem(label = title, href = href, level = (depth - 1).coerceAtLeast(0)))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "nav" -> skipNav = false
                        "ol", "ul" -> {
                            depth--
                            if (stack.size > 1) {
                                val children = stack.removeAt(stack.lastIndex)
                                val parentList = stack.last()
                                // Attach nested entries as children of the last entry of the parent list.
                                val attachTo = parentList.lastOrNull()
                                if (attachTo != null) {
                                    parentList[parentList.lastIndex] =
                                        attachTo.copy(children = attachTo.children + children)
                                } else {
                                    parentList.addAll(children)
                                }
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return toc
    }

    private fun parseNcx(ncxData: ByteArray): List<EpubTocItem> {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(ncxData)), "UTF-8")

        data class NavPointContext(val targetList: MutableList<EpubTocItem>, val level: Int)

        val toc = mutableListOf<EpubTocItem>()
        val contexts = ArrayDeque<NavPointContext>()
        var pendingTitle = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "navPoint" -> {
                            val level = contexts.size
                            contexts.addLast(
                                NavPointContext(
                                    targetList = contexts.lastOrNull()?.targetList ?: toc,
                                    level = level
                                )
                            )
                            pendingTitle = ""
                        }
                        "text" -> {
                            if (contexts.isNotEmpty()) {
                                pendingTitle = runCatching { parser.nextText().trim() }.getOrDefault("")
                            }
                        }
                        "content" -> {
                            val ctx = contexts.lastOrNull()
                            if (ctx != null) {
                                val src = parser.getAttributeValue(null, "src") ?: ""
                                ctx.targetList.add(
                                    EpubTocItem(
                                        label = pendingTitle.ifBlank { "Untitled" },
                                        href = src,
                                        level = (ctx.level - 1).coerceAtLeast(0)
                                    )
                                )
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "navPoint" && contexts.isNotEmpty()) {
                        contexts.removeLast()
                    }
                }
            }
            eventType = parser.next()
        }
        return toc
    }

    private fun extractCover(
        zipFile: ZipFile,
        manifest: List<EpubManifestItem>,
        metadata: EpubMetadata,
        opfPath: String
    ): Pair<ByteArray?, String?> {
        // 1. Check metadata cover reference
        metadata.coverHref?.let { coverHref ->
            val coverItem = manifest.firstOrNull { it.id == coverHref || it.href.endsWith(coverHref) }
            coverItem?.let { item ->
                val coverEntry = findZipEntry(zipFile, opfPath, item.href)
                if (coverEntry != null) {
                    return readZipEntry(zipFile, coverEntry) to item.mediaType
                }
            }
        }

        // 2. Check manifest for cover-image property
        val coverItem = manifest.firstOrNull { it.properties?.contains("cover-image") == true }
        coverItem?.let { item ->
            val coverEntry = findZipEntry(zipFile, opfPath, item.href)
            if (coverEntry != null) {
                return readZipEntry(zipFile, coverEntry) to item.mediaType
            }
        }

        // 3. Fallback: first image in manifest
        val imageItem = manifest.firstOrNull { it.mediaType.startsWith("image/") }
        imageItem?.let { item ->
            val coverEntry = findZipEntry(zipFile, opfPath, item.href)
            if (coverEntry != null) {
                return readZipEntry(zipFile, coverEntry) to item.mediaType
            }
        }

        return null to null
    }

    private fun readHtmlContent(zipFile: ZipFile, manifest: List<EpubManifestItem>, spine: List<EpubSpineItem>, opfPath: String): Map<String, String> {
        val htmlMap = mutableMapOf<String, String>()
        val htmlItems = manifest.filter { it.mediaType == "application/xhtml+xml" || it.href.endsWith(".xhtml") || it.href.endsWith(".html") }
            .associateBy { it.id }

        for (spineItem in spine) {
            val manifestItem = htmlItems[spineItem.idref]
            manifestItem?.let { item ->
                try {
                    val entryPath = findZipEntry(zipFile, opfPath, item.href) ?: return@let
                    val content = decodeEpubBytes(readZipEntry(zipFile, entryPath))
                    htmlMap[item.href] = content
                } catch (e: Exception) {
                    // Skip unreadable content
                }
            }
        }
        return htmlMap
    }

    private fun normalizeChapters(
        spine: List<EpubSpineItem>,
        manifest: List<EpubManifestItem>,
        toc: List<EpubTocItem>,
        htmlContent: Map<String, String>,
        metadata: EpubMetadata
    ): List<Chapter> {
        val manifestMap = manifest.associateBy { it.id }
        val chapters = mutableListOf<Chapter>()
        var charOffset = 0L
        var spineIndex = 0
        // Chapter numbers for otherwise-unlabeled prose pages come from their position in the
        // book's CONTENT sequence, not the raw spine index: covers, the TOC page, colour plates
        // and other non-prose leaves must not inflate the number (that produced "Chapter 13" for
        // the 13th file). Only pages that actually carry prose advance this count.
        var contentChapterNo = 0

        for (spineItem in spine) {
            val manifestItem = manifestMap[spineItem.idref]
                ?: continue

            // Non-linear items (footnotes, etc.) are kept but marked as non-linear
            // They won't appear in normal chapter navigation but can be accessed via links

            val html = htmlContent[manifestItem.href] ?: ""
            val (text, wordCount) = extractText(html)
            val charCount = text.length.toLong()
            val isBody = wordCount >= FrontMatterLabels.MIN_BODY_WORDS
            if (isBody) contentChapterNo++

            val tocMatch = findTocMatch(manifestItem.href, toc)
            // Ignore generic toc labels that equal the book title (common in NCX fallback)
            val tocLabel = tocMatch?.label?.takeIf { it.trim() != metadata.title.trim() && it.isNotBlank() }
            val title = tocLabel ?: extractTitle(html, metadata.title)
                ?: FrontMatterLabels.label(html, manifestItem.href, metadata.title)
                ?: if (isBody) "Chapter $contentChapterNo" else "Untitled"

            val chapter = Chapter(
                id = manifestItem.id,
                bookId = "", // Set later
                title = title,
                href = manifestItem.href,
                spineIndex = spineIndex,
                level = tocMatch?.level ?: 0,
                characterCount = charCount,
                wordCount = wordCount,
                startOffset = charOffset,
                endOffset = charOffset + charCount
            )

            chapters.add(chapter)
            charOffset += charCount
            spineIndex++
        }

        // Build hierarchy from TOC
        return buildChapterHierarchy(chapters, toc)
    }

    private fun extractText(html: String): Pair<String, Long> {
        // Simple HTML text extraction - in production use a proper HTML parser
        val text = html
            .replace(RE_SCRIPT, "")
            .replace(RE_STYLE, "")
            .replace(RE_TAG, " ")
            .replace(RE_WS, " ")
            .trim()
        // Whitespace is already collapsed to single spaces above, so word count is (spaces + 1)
        // for non-empty text — no need to materialise a full split list of every word.
        val words = if (text.isEmpty()) 0L else (text.count { it == ' ' } + 1).toLong()
        return text to words
    }

    /**
     * First heading (h1-h6) whose text is not just the book title. Chapter files in
     * many EPUBs open with <h1>Book Title</h1> before the real "Chapter N" heading;
     * the book title is useless as a per-chapter name (every TOC row would match).
     */
    private fun extractTitle(html: String, bookTitle: String): String? {
        return try {
            val parser = xmlFactory.newPullParser()
            parser.setInput(ByteArrayInputStream(bomFree(html.toByteArray())), "UTF-8")
            val bookTitleNorm = bookTitle.trim().lowercase()
            var eventType = parser.eventType
            val candidates = mutableListOf<String>()
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                    val tag = parser.name
                    val sb = StringBuilder()
                    var depth = 1
                    eventType = parser.next()
                    while (eventType != XmlPullParser.END_DOCUMENT && depth > 0) {
                        when (eventType) {
                            XmlPullParser.TEXT -> sb.append(parser.text).append(" ")
                            XmlPullParser.START_TAG -> depth++
                            XmlPullParser.END_TAG -> {
                                depth--
                                if (depth == 0) break
                            }
                        }
                        if (depth > 0) eventType = parser.next() else break
                    }
                    val candidate = sb.toString().trim().replace(RE_WS, " ")
                    val candidateNorm = candidate.lowercase()
                    
                    // Skip if empty, exactly the book title, or has no letters at all:
                    // publisher markup puts bare page numbers ("8") and printer's
                    // marks in headings, and those are not chapter titles.
                    if (candidate.isNotEmpty() && candidateNorm != bookTitleNorm &&
                        candidate.any { it.isLetter() }
                    ) {
                        candidates.add(candidate)
                    }
                    
                    eventType = if (eventType == XmlPullParser.END_DOCUMENT) eventType else parser.next()
                    continue
                }
                eventType = parser.next()
            }
            
            // Return the first valid candidate, preferring those that look like chapter titles
            candidates.firstOrNull { candidate ->
                val norm = candidate.lowercase()
                norm.contains("chapter") || 
                norm.contains("prologue") || 
                norm.contains("epilogue") ||
                norm.matches(RE_TITLE_PART) ||
                norm.matches(RE_TITLE_NUM) // Starts with number
            } ?: candidates.firstOrNull() // Fall back to first heading if no chapter-like title found
        } catch (_: Exception) {
            null
        }
    }

    private fun findTocMatch(href: String, toc: List<EpubTocItem>): EpubTocItem? {
        val cleanHref = href.substringBefore("#").substringBefore("?")
        for (item in toc) {
            val cleanItem = item.href.substringBefore("#").substringBefore("?")
            if (cleanItem == cleanHref || cleanItem.endsWith(cleanHref) || cleanHref.endsWith(cleanItem)) {
                return item
            }
            val found = findTocMatch(href, item.children)
            if (found != null) return found
        }
        return null
    }

    private fun buildChapterHierarchy(chapters: List<Chapter>, toc: List<EpubTocItem>): List<Chapter> {
        // Simple flat list for now - hierarchy building is complex
        // In production, map TOC structure to spine items
        return chapters
    }

    private fun calculateTotals(chapters: List<Chapter>): Pair<Long, Long> {
        val totalChars = chapters.sumOf { it.characterCount }
        val totalWords = chapters.sumOf { it.wordCount }
        return totalChars to totalWords
    }

    private fun resolveHref(base: String, href: String): String {
        if (href.startsWith("/")) return href.substring(1)
        if (href.contains("://")) return href
        val stripped = base.trimEnd('/')
        val lastSlash = stripped.lastIndexOf('/')
        // Base may be a file path, a dir path, or empty (root-level OPF).
        val basePath = if (lastSlash >= 0) stripped.substring(0, lastSlash + 1) else ""
        return (basePath + href).replace(RE_DOT_SEG, "/").replace(RE_DOTDOT_SEG, "$1/")
    }

    private fun bomFree(data: ByteArray): ByteArray =
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            data.copyOfRange(3, data.size)
        } else {
            data
        }

    /**
     * Manifest hrefs are stored already resolved against the OPF base dir, but some
     * call sites still pass raw-relative or previously-resolved values. Try the value
     * as-is first, then the OPF-resolved variant. Percent-encoded hrefs
     * ("Chapter%201.xhtml") additionally get a URL-decoded attempt.
     */
    private fun findZipEntry(zipFile: ZipFile, opfPath: String, href: String): String? {
        if (href.isNotBlank() && zipFile.getEntry(href) != null) return href
        urlDecoded(href)?.let { decoded ->
            if (zipFile.getEntry(decoded) != null) return decoded
        }
        if (href.startsWith("/") || href.contains("://")) return null
        val resolved = resolveHref(opfPath, href)
        if (resolved != href && zipFile.getEntry(resolved) != null) return resolved
        urlDecoded(resolved)?.let { decoded ->
            if (zipFile.getEntry(decoded) != null) return decoded
        }
        return null
    }

    private fun urlDecoded(value: String): String? {
        if (!value.contains('%')) return null
        val decoded = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return null
        return decoded.takeIf { it != value }
    }

    private fun parseDate(dateStr: String): Instant? {
        val trimmed = dateStr.trim()
        // Strict ISO-8601 instant first, then the common EPUB2 shapes.
        runCatching { Instant.parse(trimmed) }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(trimmed).atStartOfDayIn(TimeZone.UTC) }.getOrNull()?.let { return it }
        Regex("^([0-9]{4})-([0-9]{2})$").find(trimmed)?.let { m ->
            runCatching {
                LocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1).atStartOfDayIn(TimeZone.UTC)
            }.getOrNull()?.let { return it }
        }
        Regex("^([0-9]{4})$").find(trimmed)?.let { m ->
            runCatching {
                LocalDate(m.groupValues[1].toInt(), 1, 1).atStartOfDayIn(TimeZone.UTC)
            }.getOrNull()?.let { return it }
        }
        return null
    }
}

class EpubParseException(message: String) : Exception(message)

// Hoisted so per-chapter extraction/title/href/charset work (run once per spine item, on both the
// import path and the reader's per-chapter fast read) does not recompile these patterns each call.
private val RE_SCRIPT = Regex("(?s)<script.*?</script>")
private val RE_STYLE = Regex("(?s)<style.*?</style>")
private val RE_TAG = Regex("<[^>]+>")
private val RE_WS = Regex("\\s+")
private val RE_TITLE_PART = Regex(".*\\b(part|book|section)\\s+\\d+.*", RegexOption.IGNORE_CASE)
private val RE_TITLE_NUM = Regex("\\d+[.:)].*")
/** A `<p>...</p>` block, used to walk a heading-less chapter's paragraphs in document order. */
private val RE_P_BLOCK = Regex("(?is)<p\\b[^>]*>(.*?)</p>")
/** Opening of a chapter heading rendered as a paragraph in a heading-less (pdftohtml) book. */
private val RE_CHAPTER_MARKER = Regex("^(chapter|prologue|epilogue|part|book)\\b", RegexOption.IGNORE_CASE)
/** Separator between a chapter designator and its subtitle: " - ", " – ", " — ". */
private val RE_HEADING_SEP = Regex("\\s[-–—]\\s")
/** EPUB Open Packaging namespace, the binding for the `epub:type` attribute on nav lists. */
private const val NS_OPS = "http://www.idpf.org/2007/ops"
private val RE_DOT_SEG = Regex("/\\./")
private val RE_DOTDOT_SEG = Regex("([^/])/\\.\\./")
private val RE_CHARSET_ENCODING = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
private val RE_CHARSET_META = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)

/** Per-entry decompressed-size ceiling for EPUB parsing, guarding against zip bombs on import. */
private const val MAX_EPUB_ENTRY_BYTES = 64L * 1024L * 1024L

private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw EpubParseException("EPUB entry exceeds $maxBytes bytes (possible zip bomb)")
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

/**
 * Scans the ASCII-safe prefix of a document for an XML prolog or meta-charset
 * encoding declaration. EPUB2 books commonly declare ISO-8859-1/Windows-1252;
 * forcing UTF-8 on those turns accented text into mojibake.
 */
internal fun declaredCharset(data: ByteArray): String? {
    val head = String(data, 0, minOf(data.size, 1024), Charsets.ISO_8859_1)
    RE_CHARSET_ENCODING.find(head)?.let { return it.groupValues[1] }
    RE_CHARSET_META.find(head)?.let { return it.groupValues[1] }
    return null
}

/** Decodes document bytes with their declared charset, defaulting to UTF-8. */
internal fun decodeEpubBytes(data: ByteArray): String {
    val bomFree =
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            data.copyOfRange(3, data.size)
        } else {
            data
        }
    val charset = declaredCharset(data)?.let { name ->
        runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
    } ?: Charsets.UTF_8
    return String(bomFree, charset)
}