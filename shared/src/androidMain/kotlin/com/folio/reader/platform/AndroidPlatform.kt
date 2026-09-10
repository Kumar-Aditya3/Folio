package com.folio.reader.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

    override val libraryDocumentsDir: File
        get() = File(libraryDir, "documents").apply { mkdirs() }

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
        if (target.exists()) throw IOException("Document storage already exists")
        if (!stage.renameTo(target)) {
            try {
                stage.copyRecursively(target, overwrite = false)
                stage.deleteRecursively()
            } catch (error: Throwable) {
                target.deleteRecursively()
                throw error
            }
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
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val taken = mutableSetOf<String>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/"),
                null,
            )?.use { cursor -> while (cursor.moveToNext()) taken += cursor.getString(0) }
            val unique = uniqueFileName(fileName) { it in taken }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, unique)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused to create $unique in Downloads")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Could not open an output stream for $unique")
            "Downloads/$unique"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val unique = uniqueFileName(fileName) { File(dir, it).exists() }
            File(dir, unique).writeBytes(bytes)
            "${dir.name}/$unique"
        }
    }
}


class AndroidPlatform(private val context: Context) : FolioPlatform {
    override val fileSystem: FolioFileSystem = AndroidFileSystem(context)
    override val hasher: FileHasher = MessageDigestFileHasher()
}
