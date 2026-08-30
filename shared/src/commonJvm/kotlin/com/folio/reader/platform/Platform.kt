package com.folio.reader.platform

import java.io.File
import java.security.MessageDigest

interface FileHasher {
    suspend fun sha256File(filePath: String): String
    suspend fun sha256Bytes(bytes: ByteArray): String
}

class MessageDigestFileHasher : FileHasher {
    override suspend fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    override suspend fun sha256File(filePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(filePath).inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

interface FolioFileSystem {
    val libraryBooksDir: File

    /** Root for the built-in local manga source (CBZ/ZIP/folder series). */
    val mangaLocalDir: File

    /** Cache directory for downloaded manga covers. */
    val mangaCoversDir: File

    /** Directory where downloaded manga chapters are stored. */
    val mangaDownloadsDir: File

    fun getBookDir(bookId: String): File
    fun getBookEpubPath(bookId: String): String
    fun getBookCoverPath(bookId: String): String
    fun getBookMetadataPath(bookId: String): String
    fun getCacheDir(bookId: String): File
    fun getDatabasePath(): String
    fun getThumbnailPath(bookId: String): String
    fun getFontsDir(): File
    fun getFontPath(fontId: String): String
    suspend fun copyToLibrary(sourceFile: File, bookId: String): String
    suspend fun copyToLibrary(sourceBytes: ByteArray, bookId: String): String
    suspend fun deleteBookFiles(bookId: String)
    fun getLibrarySize(): Long
    fun getBookSize(bookId: String): Long

    fun dirSize(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

interface FolioPlatform {
    val fileSystem: FolioFileSystem
    val hasher: FileHasher
}
