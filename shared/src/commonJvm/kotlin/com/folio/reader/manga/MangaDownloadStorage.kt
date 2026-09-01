package com.folio.reader.manga

import java.io.File

/**
 * Pluggable home for downloaded chapter pages. Pages live at
 * "<mangaId>/<chapterId>/NNN.ext" relative to the storage root. Desktop and the
 * Android default use plain files ([FileDownloadStorage]); Android can switch to a
 * user-picked SAF tree at runtime.
 */
interface MangaDownloadStorage {
    /** True when the directory exists or was created. */
    fun ensureDir(relativePath: String): Boolean
    /** True when the bytes are on disk afterwards. */
    fun write(relativePath: String, fileName: String, bytes: ByteArray): Boolean
    /** Reads the first file whose name starts with [fileNamePrefix], or null. */
    fun readFirst(relativePath: String, fileNamePrefix: String): ByteArray?
    fun listFiles(relativePath: String): List<String>
    fun listSubDirs(relativePath: String): List<String>
    fun deleteDir(relativePath: String)
    /** Human-readable location for the settings UI. */
    fun describe(): String
}

class FileDownloadStorage(val root: File) : MangaDownloadStorage {

    override fun ensureDir(relativePath: String): Boolean {
        val dir = File(root, relativePath)
        return dir.isDirectory || dir.mkdirs()
    }

    override fun write(relativePath: String, fileName: String, bytes: ByteArray): Boolean = try {
        val dir = File(root, relativePath).apply { mkdirs() }
        val target = File(dir, fileName)
        target.writeBytes(bytes)
        target.length() == bytes.size.toLong()
    } catch (e: Exception) {
        false
    }

    override fun readFirst(relativePath: String, fileNamePrefix: String): ByteArray? {
        val dir = File(root, relativePath)
        if (!dir.isDirectory) return null
        val file = dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.startsWith(fileNamePrefix) }
            ?: return null
        return file.readBytes()
    }

    override fun listFiles(relativePath: String): List<String> =
        File(root, relativePath).listFiles().orEmpty().filter { it.isFile }.map { it.name }

    override fun listSubDirs(relativePath: String): List<String> =
        File(root, relativePath).listFiles().orEmpty().filter { it.isDirectory }.map { it.name }

    override fun deleteDir(relativePath: String) {
        File(root, relativePath).deleteRecursively()
    }

    override fun describe(): String = root.absolutePath
}
