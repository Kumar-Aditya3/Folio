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
        return (archives + folders)
            .sortedWith(naturalOrder { it.name })
            .map { LocalChapter(it.nameWithoutExtension, it.name, isArchive = it.isFile) }
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

    /** First page of the first chapter, or an explicit cover.jpg next to it. */
    suspend fun coverBytes(seriesName: String): ByteArray? = withContext(Dispatchers.IO) {
        val series = seriesByName(seriesName) ?: return@withContext null
        val explicit = File(series.dir, "cover.jpg")
        if (explicit.isFile) return@withContext explicit.readBytes()

        val first = chaptersFor(seriesName).firstOrNull() ?: return@withContext null
        val entries = pageEntryNames(seriesName, first.relativePath)
        entries.firstOrNull()?.let { readPage(seriesName, first.relativePath, it) }
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
            seriesName
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

    private fun String.sanitizeFileName(): String =
        replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "untitled" }
}
