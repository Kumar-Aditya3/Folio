package com.folio.reader.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DesktopFileSystem(private val rootOverride: File? = null) : FolioFileSystem {

    private val appDir: File by lazy {
        (rootOverride ?: File(System.getProperty("user.home"), ".folio")).apply { mkdirs() }
    }

    private val libraryDir: File by lazy { File(appDir, "library").apply { mkdirs() } }
    private val databaseDir: File by lazy { File(appDir, "database").apply { mkdirs() } }
    private val thumbnailsDir: File by lazy { File(appDir, "thumbnails").apply { mkdirs() } }
    private val fontsDirectory: File by lazy { File(appDir, "fonts").apply { mkdirs() } }

    override val libraryBooksDir: File
        get() = File(libraryDir, "books").apply { mkdirs() }

    private val mangaDir: File by lazy { File(appDir, "manga").apply { mkdirs() } }

    override val mangaLocalDir: File
        get() = File(mangaDir, "local").apply { mkdirs() }

    override val mangaCoversDir: File
        get() = File(mangaDir, "covers").apply { mkdirs() }

    override val mangaDownloadsDir: File
        get() = File(mangaDir, "downloads").apply { mkdirs() }

    override fun getBookDir(bookId: String): File = File(libraryBooksDir, bookId).apply { mkdirs() }

    override fun getBookEpubPath(bookId: String): String =
        File(getBookDir(bookId), "original.epub").absolutePath

    override fun getBookCoverPath(bookId: String): String =
        File(getBookDir(bookId), "cover.jpg").absolutePath

    override fun getBookMetadataPath(bookId: String): String =
        File(getBookDir(bookId), "metadata.json").absolutePath

    override fun getCacheDir(bookId: String): File =
        File(getBookDir(bookId), "cache").apply { mkdirs() }

    override fun getDatabasePath(): String = File(databaseDir, "folio.db").absolutePath

    override fun getThumbnailPath(bookId: String): String =
        File(thumbnailsDir, "$bookId.jpg").absolutePath

    override fun getFontsDir(): File = fontsDirectory

    override fun getFontPath(fontId: String): String = File(fontsDirectory, fontId).absolutePath

    override suspend fun copyToLibrary(sourceFile: File, bookId: String): String {
        return withContext(Dispatchers.IO) {
            val destFile = File(getBookDir(bookId), "original.epub")
            sourceFile.copyTo(destFile, overwrite = true)
            destFile.absolutePath
        }
    }

    override suspend fun copyToLibrary(sourceBytes: ByteArray, bookId: String): String {
        return withContext(Dispatchers.IO) {
            val destFile = File(getBookDir(bookId), "original.epub")
            destFile.writeBytes(sourceBytes)
            destFile.absolutePath
        }
    }

    override suspend fun deleteBookFiles(bookId: String) {
        withContext(Dispatchers.IO) {
            getBookDir(bookId).deleteRecursively()
        }
    }

    override fun getLibrarySize(): Long = dirSize(libraryDir)

    override fun getBookSize(bookId: String): Long = dirSize(getBookDir(bookId))

    override suspend fun exportToDownloads(fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
        val unique = uniqueFileName(fileName) { File(dir, it).exists() }
        File(dir, unique).writeBytes(bytes)
        File(dir, unique).absolutePath
    }
}


class DesktopPlatform(rootOverride: File? = null) : FolioPlatform {
    override val fileSystem: FolioFileSystem = DesktopFileSystem(rootOverride)
    override val hasher: FileHasher = MessageDigestFileHasher()
}
