package com.folio.reader.ui.manga

import java.io.File
import java.security.MessageDigest

/**
 * Persistent store for network-fetched manga covers.
 *
 * The in-memory cache in [MangaCover] dies with the process, which made every
 * cold start refetch every thumbnail — a library of covers re-downloaded before
 * the first shelf was fully painted. This store keeps the fetched bytes on disk
 * (the platform's `mangaCoversDir`, configured at app start), so a cover that
 * has been seen once is a plain file read from then on. Library entries
 * therefore stay permanently loaded, and browse results benefit too.
 *
 * The cache key includes the thumbnail URL, so a source changing its cover art
 * naturally invalidates the old bytes. Files are unbounded in v1: covers are
 * small, the directory is app-private, and eviction would fight the whole point
 * (library covers staying loaded).
 */
object MangaCoverDiskCache {
    /** Root of the store; null until the app configures it, which keeps covers memory-only. */
    @Volatile
    var directory: File? = null

    private fun fileFor(key: String): File? {
        val dir = directory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, name)
    }

    /** Cached bytes for [key], or null when nothing (or no directory) is stored. */
    fun read(key: String): ByteArray? {
        val file = fileFor(key) ?: return null
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    /** Write-through after a network fetch; failures are silent — the memory cache still holds the cover. */
    fun write(key: String, bytes: ByteArray) {
        val file = fileFor(key) ?: return
        runCatching {
            file.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            file.writeBytes(bytes)
        }
    }
}
