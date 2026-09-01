package com.folio.reader.manga

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Built-in local manga source, available on every platform. Mirrors Mihon's LocalSource
 * layout: each folder directly under [localDir] is a series, and each archive (.cbz/.zip)
 * or image folder inside a series is one chapter.
 */
class LocalMangaSource(private val localDir: File) {

    data class LocalSeries(
        val name: String,
        val dir: File,
    )

    data class LocalChapter(
        val name: String,
        /** Path relative to the series dir; used as the chapter url. */
        val relativePath: String,
        val isArchive: Boolean,
    )

    init {
        localDir.mkdirs()
    }

    fun listSeries(): List<LocalSeries> {
        return localDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedWith(naturalOrder { it.name })
            .map { LocalSeries(it.name, it) }
    }

    fun seriesByName(name: String): LocalSeries? =
        listSeries().firstOrNull { it.name == name }

    fun chaptersFor(seriesName: String): List<LocalChapter> {
        val series = seriesByName(seriesName) ?: return emptyList()
        val topLevel = series.dir.listFiles().orEmpty()
        val archives = topLevel.filter { it.isFile && isSupportedArchive(it) }
        val folders = topLevel.filter { it.isDirectory && containsImages(it) }
        val sortedArchives = archives.sortedWith(
            compareBy<java.io.File> { parseChapterNumber(it.nameWithoutExtension) ?: Double.MAX_VALUE }
                .thenComparator { a, b -> naturalCompare(a.name, b.name) }
        )
        val sortedFolders = folders.sortedWith(naturalOrder { it.name })
        return sortedArchives.map { LocalChapter(cleanDisplayName(it.nameWithoutExtension), it.name, isArchive = true) } +
            sortedFolders.map { LocalChapter(it.nameWithoutExtension, it.name, isArchive = false) }
    }

    /** Ordered entry names (or file names for folder chapters) for a chapter. */
    suspend fun pageEntryNames(seriesName: String, chapterRelativePath: String): List<String> =
        withContext(Dispatchers.IO) {
            val series = seriesByName(seriesName) ?: return@withContext emptyList()
            val chapterFile = File(series.dir, chapterRelativePath)
            if (!chapterFile.exists()) return@withContext emptyList()

            if (chapterFile.isFile) {
                ZipFile(chapterFile).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && isImageName(it.name) }
                        .map { it.name }
                        .toList()
                        .sortedWith(naturalOrder { it })
                }
            } else {
                chapterFile.listFiles().orEmpty()
                    .filter { it.isFile && isImageName(it.name) }
                    .sortedWith(naturalOrder { it.name })
                    .map { it.name }
            }
        }

    suspend fun readPage(seriesName: String, chapterRelativePath: String, entryName: String): ByteArray =
        withContext(Dispatchers.IO) {
            val series = seriesByName(seriesName) ?: throw IllegalStateException("Unknown series $seriesName")
            val chapterFile = File(series.dir, chapterRelativePath)
            if (chapterFile.isFile) {
                ZipFile(chapterFile).use { zip ->
                    val entry = zip.getEntry(entryName)
                        ?: throw IllegalStateException("Missing page $entryName in ${chapterFile.name}")
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            } else {
                File(chapterFile, entryName).readBytes()
            }
        }

    private val coverNames = listOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp")

    private fun existingCoverFile(seriesDir: File): File? =
        coverNames.firstNotNullOfOrNull { name -> File(seriesDir, name).takeIf { it.isFile } }

    /**
     * Cover file for a series. On first use it is materialized from the first page of the
     * first chapter (or copied from an image-folder chapter), so every later cover load is a
     * plain file read instead of reopening an archive.
     */
    suspend fun materializeCover(seriesName: String): File? = withContext(Dispatchers.IO) {
        val series = seriesByName(seriesName) ?: return@withContext null
        existingCoverFile(series.dir)?.let { return@withContext it }

        val first = chaptersFor(seriesName).firstOrNull() ?: return@withContext null
        val entryName = pageEntryNames(seriesName, first.relativePath).firstOrNull()
            ?: return@withContext null
        val ext = entryName.substringAfterLast('.', "jpg").lowercase().let {
            if (it in setOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }
        val cover = File(series.dir, "cover.$ext")
        runCatching {
            val bytes = readPage(seriesName, first.relativePath, entryName)
            cover.writeBytes(bytes)
        }
        cover.takeIf { it.isFile }
    }

    suspend fun coverBytes(seriesName: String): ByteArray? {
        val file = materializeCover(seriesName) ?: return null
        return withContext(Dispatchers.IO) { file.readBytes() }
    }

    /**
     * Imports a CBZ/ZIP archive (or a folder of images) into the local source. Returns the
     * series name the content ended up in.
     */
    suspend fun import(source: File, seriesNameOverride: String? = null): String =
        withContext(Dispatchers.IO) {
            val baseName = source.nameWithoutExtension
            val seriesName = (seriesNameOverride ?: baseName).sanitizeFileName()
            val seriesDir = File(localDir, seriesName).apply { mkdirs() }

            if (source.isDirectory) {
                // Folder of images becomes a single chapter named after the folder.
                val chapterDir = File(seriesDir, baseName.sanitizeFileName()).apply { mkdirs() }
                source.listFiles().orEmpty()
                    .filter { it.isFile && isImageName(it.name) }
                    .forEach { it.copyTo(File(chapterDir, it.name), overwrite = true) }
            } else {
                require(isSupportedArchive(source)) { "Unsupported manga archive: ${source.name}" }
                var target = File(seriesDir, source.name)
                var counter = 1
                while (target.exists()) {
                    target = File(seriesDir, "${baseName}_$counter.${source.extension}")
                    counter++
                }
                source.copyTo(target, overwrite = true)
            }
            materializeCover(seriesName)
            seriesName
        }

    /** An archive to import, opened lazily so callers can stream from any source. */
    class PendingArchive(val name: String, val open: () -> java.io.InputStream)

    /**
     * Imports a list of archives as one series named after [folderName], streaming each
     * archive straight into the local source (no intermediate copy). Reports per-file
     * progress and materializes the series cover. Re-importing the same archive name
     * overwrites it so the series stays stable.
     */
    suspend fun importFolder(
        folderName: String,
        archives: List<PendingArchive>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        require(archives.isNotEmpty()) { "No archives to import" }
        val seriesName = folderName.sanitizeFileName()
        val seriesDir = File(localDir, seriesName).apply { mkdirs() }
        archives.forEachIndexed { index, archive ->
            val dest = File(seriesDir, archive.name)
            archive.open().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            onProgress(index + 1, archives.size)
        }
        materializeCover(seriesName)
        seriesName
    }

    /**
     * Imports a folder as a single manga collection. Every CBZ/ZIP directly inside [folder]
     * becomes one chapter. The series name is derived from the folder name.
     */
    suspend fun importFolder(folder: File): String {
        require(folder.isDirectory) { "Expected a directory: ${folder.path}" }
        val archives = folder.listFiles().orEmpty()
            .filter { it.isFile && isSupportedArchive(it) }
            .map { PendingArchive(it.name) { it.inputStream() } }
        return importFolder(folder.name, archives)
    }

    suspend fun deleteSeries(seriesName: String): Unit = withContext(Dispatchers.IO) {
        seriesByName(seriesName)?.dir?.deleteRecursively()
    }

    private fun containsImages(dir: File): Boolean =
        dir.listFiles().orEmpty().any { it.isFile && isImageName(it.name) }

    private fun isSupportedArchive(file: File): Boolean =
        file.extension.lowercase() in setOf("cbz", "zip")

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
            lower.endsWith(".avif")
    }

    /** Numeric-aware ordering so page-2 sorts before page-10. */
    private fun <T> naturalOrder(selector: (T) -> String): Comparator<T> = Comparator { a, b ->
        naturalCompare(selector(a), selector(b))
    }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit()) endB++
                val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * Extracts a chapter number from a file name. Prefers explicit markers like "ch07",
     * "chapter 12", "c34.5"; otherwise uses the last numeric token. Returns null when no
     * number can be parsed so callers can fall back to position order.
     */
    private fun parseChapterNumber(name: String): Double? {
        val cleaned = name.replace('_', ' ').replace('-', ' ')
        val explicit = Regex("""\b(?:chapter|ch|c)\s*\.?\s*(\d+(?:[._]\d+)?)""", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
        val raw = explicit ?: cleaned.substringAfterLast(' ', cleaned).let { lastToken ->
            Regex("""(\d+(?:[._]\d+)?)""").findAll(lastToken).lastOrNull()?.value
                ?: Regex("""(\d+(?:[._]\d+)?)""").findAll(cleaned).lastOrNull()?.value
        } ?: return null
        val normalized = raw.replace('_', '.')
        return normalized.toDoubleOrNull()
    }

    private fun cleanDisplayName(baseName: String): String =
        baseName.replace('_', ' ')
            .replace(Regex("""(?<!\d)\.(?!\d)"""), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .ifEmpty { baseName }

    private fun String.sanitizeFileName(): String =
        replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "untitled" }
}
