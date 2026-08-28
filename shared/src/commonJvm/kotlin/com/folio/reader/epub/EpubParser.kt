package com.folio.reader.epub

import com.folio.reader.model.*
import com.folio.reader.platform.FolioPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
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
                val htmlContent = readHtmlContent(zipFile, manifest, spine)

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
        return zipFile.getInputStream(entry).readBytes()
    }

    private fun parseOpf(opfData: ByteArray, opfPath: String): Triple<EpubMetadata, List<EpubManifestItem>, List<EpubSpineItem>> {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(opfData)), "UTF-8")

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

    private fun parseNav(navData: ByteArray): List<EpubTocItem> {
        val parser = xmlFactory.newPullParser()
        parser.setInput(ByteArrayInputStream(bomFree(navData)), "UTF-8")

        val toc = mutableListOf<EpubTocItem>()
        val stack = mutableListOf<MutableList<EpubTocItem>>()
        stack.add(toc)
        var depth = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "ol", "ul" -> {
                            depth++
                            stack.add(mutableListOf())
                        }
                        "a" -> {
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val title = runCatching { parser.nextText().trim() }.getOrDefault("")
                            if (title.isNotEmpty() && href.isNotEmpty()) {
                                val parent = stack.lastOrNull() ?: toc
                                parent.add(EpubTocItem(label = title, href = href, level = (depth - 1).coerceAtLeast(0)))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
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

    private fun readHtmlContent(zipFile: ZipFile, manifest: List<EpubManifestItem>, spine: List<EpubSpineItem>): Map<String, String> {
        val htmlMap = mutableMapOf<String, String>()
        val htmlItems = manifest.filter { it.mediaType == "application/xhtml+xml" || it.href.endsWith(".xhtml") || it.href.endsWith(".html") }
            .associateBy { it.id }

        for (spineItem in spine) {
            val manifestItem = htmlItems[spineItem.idref]
            manifestItem?.let { item ->
                try {
                    val content = readZipEntry(zipFile, item.href).decodeToString()
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

        for (spineItem in spine) {
            val manifestItem = manifestMap[spineItem.idref]
                ?: continue

            // Non-linear items (footnotes, etc.) are kept but marked as non-linear
            // They won't appear in normal chapter navigation but can be accessed via links

            val html = htmlContent[manifestItem.href] ?: ""
            val (text, wordCount) = extractText(html)
            val charCount = text.length.toLong()

            val tocMatch = findTocMatch(manifestItem.href, toc)
            // Ignore generic toc labels that equal the book title (common in NCX fallback)
            val tocLabel = tocMatch?.label?.takeIf { it.trim() != metadata.title.trim() && it.isNotBlank() }
            val title = tocLabel ?: extractTitle(html, metadata.title)
                ?: "Chapter ${spineIndex + 1}"

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
            .replace(Regex("(?s)<script.*?</script>"), "")
            .replace(Regex("(?s)<style.*?</style>"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }.toLong()
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
                    val candidate = sb.toString().trim().replace(Regex("\\s+"), " ")
                    val candidateNorm = candidate.lowercase()
                    
                    // Skip if empty or exactly the book title
                    if (candidate.isNotEmpty() && candidateNorm != bookTitleNorm) {
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
                norm.matches(Regex(".*\\b(part|book|section)\\s+\\d+.*", RegexOption.IGNORE_CASE)) ||
                norm.matches(Regex("\\d+[.:)].*")) // Starts with number
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
        return (basePath + href).replace(Regex("/\\./"), "/").replace(Regex("([^/])/\\.\\./"), "$1/")
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
     * as-is first, then the OPF-resolved variant.
     */
    private fun findZipEntry(zipFile: ZipFile, opfPath: String, href: String): String? {
        if (href.isNotBlank() && zipFile.getEntry(href) != null) return href
        if (href.startsWith("/") || href.contains("://")) return null
        val resolved = resolveHref(opfPath, href)
        if (resolved != href && zipFile.getEntry(resolved) != null) return resolved
        return null
    }

    private fun parseDate(dateStr: String): Instant? {
        return try {
            // Try various date formats
            Instant.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}

class EpubParseException(message: String) : Exception(message)