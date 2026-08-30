package com.folio.reader.epub

import com.folio.reader.platform.FolioPlatform
import java.io.File
import java.util.LinkedHashMap
import java.util.zip.ZipFile

/**
 * Provides raw chapter HTML for a persisted book by re-reading the stored EPUB.
 * A chapter is served lazily straight from its zip entry when possible; only a
 * miss falls back to the full parse, whose results are cached per book (LRU)
 * so chapter switches don't re-open the zip file.
 * Also resolves chapter-relative image paths to on-disk cache files so the UI
 * can render inline EPUB images (covers, illustrations, maps…).
 */
class JvmChapterContentProvider(
    private val platform: FolioPlatform,
    private val parser: EpubParser
) {
    /** Access-order LRU of fully-parsed books; evicts the eldest past [MAX_CACHED_BOOKS]. */
    private val cache = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>): Boolean =
            size > MAX_CACHED_BOOKS
    }

    suspend fun getHtml(bookId: String, chapterHref: String): String {
        // Fast path: read just this chapter's zip entry (milliseconds) instead
        // of parsing container.xml/OPF/TOC/cover/every chapter up front.
        readSingleChapter(bookId, chapterHref)?.let { return it }

        // Slow path: full parse (kept intact; also enables suffix href matching).
        val htmlByHref = synchronized(cache) { cache[bookId] } ?: run {
            val epubPath = platform.fileSystem.getBookEpubPath(bookId)
            val file = File(epubPath)
            if (!file.exists()) throw java.io.FileNotFoundException("EPUB missing at $epubPath — please re-import the book")
            val parsed = try {
                parser.parseEpub(epubPath)
            } catch (e: Exception) {
                throw RuntimeException("Parse failed for $epubPath: ${e.message}", e)
            }
            val map = parsed.rawHtmlByHref
            if (map.isNotEmpty()) {
                synchronized(cache) { cache[bookId] = map }
            } else if (parsed.chapters.isEmpty()) {
                throw RuntimeException("No chapters parsed from $epubPath")
            }
            map
        }
        return htmlByHref[chapterHref]
            ?: htmlByHref.entries.firstOrNull { entry ->
                val k = entry.key.substringBefore("#")
                val c = chapterHref.substringBefore("#")
                k.endsWith(c) || c.endsWith(k)
            }?.value
            ?: throw RuntimeException("Chapter not found: href=$chapterHref (keys=${htmlByHref.keys.take(3)})")
    }

    /**
     * Lazily extracts a single chapter's text from the book's EPUB without a
     * full parse. Returns null when the entry cannot be located so the caller
     * falls back to the full parser (which also builds the href suffix map).
     */
    private suspend fun readSingleChapter(bookId: String, chapterHref: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val epubPath = platform.fileSystem.getBookEpubPath(bookId)
                val file = File(epubPath)
                if (!file.exists()) return@runCatching null
                val path = chapterHref.substringBefore("#").substringBefore("?")
                if (path.isBlank()) return@runCatching null
                ZipFile(file).use { zip ->
                    val entry = findEntry(zip, path) ?: return@runCatching null
                    val bytes = zip.getInputStream(entry).use { input -> input.readBytes() }
                    withStylesheets(zip, path, com.folio.reader.epub.decodeEpubBytes(bytes))
                }
            }.getOrNull()
        }

    }

    /** Inline chapter-relative stylesheets so the Compose renderer can apply EPUB CSS. */
    private fun withStylesheets(zip: ZipFile, chapterHref: String, html: String): String {
        val links = Regex("(?is)<link\\b[^>]*rel\\s*=\\s*[\"'][^\"']*stylesheet[^\"']*[\"'][^>]*>")
            .findAll(html)
            .mapNotNull { tag ->
                Regex("(?i)\\bhref\\s*=\\s*[\"']([^\"']+)[\"']").find(tag.value)?.groupValues?.get(1)
            }
            .toList()
        if (links.isEmpty()) return html
        val css = links.mapNotNull { href ->
            val entry = zip.getEntry(normalizeSrc(chapterHref, href)) ?: return@mapNotNull null
            runCatching {
                val stylesheetPath = normalizeSrc(chapterHref, href)
                val source = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                // Once inlined, CSS loses its own base URL. Rebase url() references
                // to the chapter so the desktop resource resolver can serve them.
                Regex("""url\(\s*(['"]?)([^)'"]+)\1\s*\)""", RegexOption.IGNORE_CASE)
                    .replace(source) { match ->
                        val resource = match.groupValues[2]
                        if (resource.startsWith("#") || resource.startsWith("data:") ||
                            resource.startsWith("http:") || resource.startsWith("https:") ||
                            resource.startsWith("//")
                        ) match.value
                        else "url('${normalizeSrc(chapterHref, normalizeSrc(stylesheetPath, resource))}')"
                    }
            }.getOrNull()
        }
        if (css.isEmpty()) return html
        return html.replaceFirst(Regex("(?is)</head>"), css.joinToString("\n") { "<style>$it</style>" } + "</head>")
    }

    /**
     * Resolves an <img src> relative to [chapterHref] inside the book's EPUB,
     * extracts the bytes to the book cache dir and returns the file path.
     * Returns null when the entry does not exist. Results are cached on disk so
     * repeated renders don't touch the zip.
     */
    suspend fun resolveImage(bookId: String, chapterHref: String, src: String): String? {
        return resolveResource(bookId, chapterHref, src)
    }

    /** Resolves any EPUB resource (CSS, fonts, images, SVG, media) to a cache file. */
    suspend fun resolveResource(bookId: String, chapterHref: String, src: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val epubPath = platform.fileSystem.getBookEpubPath(bookId)
                val file = File(epubPath)
                if (!file.exists()) return@runCatching null

                val resolved = normalizeSrc(chapterHref, src)
                val cacheDir = File(platform.fileSystem.getCacheDir(bookId), "resources").apply { mkdirs() }
                val outName = resolved.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val outFile = File(cacheDir, outName)
                if (outFile.exists() && outFile.length() > 0) return@runCatching outFile.absolutePath

                ZipFile(file).use { zip ->
                    val entry = findEntry(zip, resolved) ?: return@runCatching null
                    zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
                    outFile.absolutePath
                }
            }.getOrNull()
        }
    }

    /** Strip fragments/queries and resolve ../ and ./ segments against the chapter. */
    private fun normalizeSrc(chapterHref: String, src: String): String {
        val clean = src.substringBefore("#").substringBefore("?")
        val root = chapterHref.substringBefore('/').takeIf { it.isNotBlank() }
        if (root != null && clean.startsWith("$root/")) return clean
        val baseDir = chapterHref.substringBeforeLast('/', "")
        val segments = mutableListOf<String>()
        val start = if (clean.startsWith("/")) "" else baseDir
        for (part in "$start/$clean".split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun findEntry(zip: ZipFile, path: String): java.util.zip.ZipEntry? {
        zip.getEntry(path)?.let { return it }
        // Case-insensitive / suffix fallback (some EPUBs use differing casing)
        val lower = path.lowercase()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.name.lowercase() == lower || e.name.lowercase().endsWith("/$lower")) return e
        }
        return null
    }

    private companion object {
        const val MAX_CACHED_BOOKS = 8
    }
}
