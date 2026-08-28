package com.folio.reader.platform

import com.folio.reader.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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
}

class DesktopSettingsStore(rootOverride: File? = null) : SettingsStore {

    private val settingsFile: File = File(
        rootOverride ?: File(System.getProperty("user.home"), ".folio"),
        "settings.json"
    )
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAppSettings(): AppSettings {
        return withContext(Dispatchers.IO) {
            if (settingsFile.exists()) {
                runCatching { json.decodeFromString(AppSettings.serializer(), settingsFile.readText()) }
                    .getOrElse { AppSettings(platform = "desktop") }
            } else {
                AppSettings(
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = System.getProperty("user.name")?.let { "$it's Computer" } ?: "Desktop",
                    platform = "desktop"
                )
            }
        }
    }

    override suspend fun saveAppSettings(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
        }
    }
}

class DesktopPlatform(rootOverride: File? = null) : FolioPlatform {
    override val fileSystem: FolioFileSystem = DesktopFileSystem(rootOverride)
    override val hasher: FileHasher = MessageDigestFileHasher()
    override val settingsStore: SettingsStore = DesktopSettingsStore(rootOverride)
}
