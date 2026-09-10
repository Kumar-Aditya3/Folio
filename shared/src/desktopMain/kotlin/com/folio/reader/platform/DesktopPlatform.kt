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

    override val libraryDocumentsDir: File
        get() = File(libraryDir, "documents").apply { mkdirs() }

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

    override fun getDocumentDir(documentId: String) = File(libraryDocumentsDir, requireSafeDocumentId(documentId))
    override fun getDocumentOriginalPath(documentId: String, canonicalExtension: String) =
        File(getDocumentDir(documentId), "original.${requireCanonicalExtension(canonicalExtension)}").absolutePath
    override fun getDocumentGeneratedDir(documentId: String) = File(getDocumentDir(documentId), "generated").apply { mkdirs() }
    override fun getDocumentGeneratedIndexPath(documentId: String) = File(getDocumentGeneratedDir(documentId), "index.html").absolutePath
    override fun getDocumentAssetsDir(documentId: String) = File(getDocumentGeneratedDir(documentId), "assets").apply { mkdirs() }
    override fun getDocumentCacheDir(documentId: String) = File(getDocumentDir(documentId), "cache").apply { mkdirs() }
    override fun getDocumentPagesDir(documentId: String) = File(getDocumentCacheDir(documentId), "pages").apply { mkdirs() }
    override fun getDocumentThumbnailsDir(documentId: String) = File(getDocumentCacheDir(documentId), "thumbnails").apply { mkdirs() }

    override suspend fun stageDocumentCopy(
        sourceFile: File,
        stagingId: String,
        canonicalExtension: String,
        maxBytes: Long
    ): StagedDocumentCopy = withContext(Dispatchers.IO) {
        val safeId = requireSafeDocumentId(stagingId)
        val extension = requireCanonicalExtension(canonicalExtension)
        val stage = File(libraryDocumentsDir, ".$safeId.staging")
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            val staged = File(stage, "original.$extension")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var size = 0L
            sourceFile.inputStream().use { input -> staged.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > maxBytes) throw StagedCopyTooLargeException()
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            } }
            StagedDocumentCopy(staged.absolutePath, digest.digest().joinToString("") { "%02x".format(it) }, size)
        } catch (error: Throwable) {
            stage.deleteRecursively()
            throw error
        }
    }

    override suspend fun commitStagedDocument(stagingId: String, documentId: String, canonicalExtension: String): String = withContext(Dispatchers.IO) {
        val stage = File(libraryDocumentsDir, ".${requireSafeDocumentId(stagingId)}.staging")
        val target = getDocumentDir(documentId)
        val extension = requireCanonicalExtension(canonicalExtension)
        if (target.exists()) throw java.io.IOException("Document storage already exists")
        try {
            java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(stage.toPath(), target.toPath())
        }
        val original = File(target, "original.$extension")
        if (!original.isFile) error("Staged document is missing")
        original.absolutePath
    }

    override suspend fun deleteDocumentFiles(documentId: String): Boolean = withContext(Dispatchers.IO) {
        val documentDeleted = !getDocumentDir(documentId).exists() || getDocumentDir(documentId).deleteRecursively()
        val stage = File(libraryDocumentsDir, ".$documentId.staging")
        val stageDeleted = !stage.exists() || stage.deleteRecursively()
        documentDeleted && stageDeleted
    }

    override fun getDocumentSize(documentId: String): Long = dirSize(getDocumentDir(documentId))

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
