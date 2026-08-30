package com.folio.reader.platform

import android.content.Context
import java.io.File

class AndroidFileSystem(private val context: Context) : FolioFileSystem {

    private val libraryDir: File by lazy {
        File(context.filesDir, "library").apply { mkdirs() }
    }

    private val databaseDir: File by lazy {
        File(context.filesDir, "database").apply { mkdirs() }
    }

    private val thumbnailsDir: File by lazy {
        File(context.filesDir, "thumbnails").apply { mkdirs() }
    }

    private val fontsDirectory: File by lazy {
        File(context.filesDir, "fonts").apply { mkdirs() }
    }

    override val libraryBooksDir: File
        get() = File(libraryDir, "books").apply { mkdirs() }

    private val mangaDir: File by lazy {
        File(context.filesDir, "manga").apply { mkdirs() }
    }

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

    override fun getDatabasePath(): String =
        File(databaseDir, "folio.db").absolutePath

    override fun getThumbnailPath(bookId: String): String =
        File(thumbnailsDir, "$bookId.jpg").absolutePath

    override fun getFontsDir(): File = fontsDirectory

    override fun getFontPath(fontId: String): String = File(fontsDirectory, fontId).absolutePath

    override suspend fun copyToLibrary(sourceFile: File, bookId: String): String {
        val destFile = File(getBookDir(bookId), "original.epub")
        sourceFile.copyTo(destFile, overwrite = true)
        return destFile.absolutePath
    }

    override suspend fun copyToLibrary(sourceBytes: ByteArray, bookId: String): String {
        val destFile = File(getBookDir(bookId), "original.epub")
        destFile.writeBytes(sourceBytes)
        return destFile.absolutePath
    }

    override suspend fun deleteBookFiles(bookId: String) {
        getBookDir(bookId).deleteRecursively()
    }

    override fun getLibrarySize(): Long = dirSize(libraryDir)

    override fun getBookSize(bookId: String): Long = dirSize(getBookDir(bookId))
}


class AndroidPlatform(private val context: Context) : FolioPlatform {
    override val fileSystem: FolioFileSystem = AndroidFileSystem(context)
    override val hasher: FileHasher = MessageDigestFileHasher()
}
