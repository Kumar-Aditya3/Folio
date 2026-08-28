package com.folio.reader.platform

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.folio.reader.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.folioDataStore by preferencesDataStore(name = "folio_settings")
private val APP_SETTINGS_KEY = stringPreferencesKey("app_settings_json")
private val DEVICE_ID_KEY = stringPreferencesKey("device_id")

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

class AndroidSettingsStore(private val context: Context) : SettingsStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAppSettings(): AppSettings {
        val prefs = context.folioDataStore.data.first()
        val stored = prefs[APP_SETTINGS_KEY]
            ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        return stored ?: AppSettings(
            deviceId = getOrCreateDeviceId(),
            deviceName = android.os.Build.MODEL ?: "Android Device",
            platform = "android"
        )
    }

    override suspend fun saveAppSettings(settings: AppSettings) {
        context.folioDataStore.edit { prefs ->
            prefs[APP_SETTINGS_KEY] = json.encodeToString(AppSettings.serializer(), settings)
        }
    }

    private suspend fun getOrCreateDeviceId(): String {
        var id: String? = null
        context.folioDataStore.edit { prefs ->
            val existing = prefs[DEVICE_ID_KEY]
            if (existing == null) {
                val generated = UUID.randomUUID().toString()
                prefs[DEVICE_ID_KEY] = generated
                id = generated
            } else {
                id = existing
            }
        }
        return id ?: UUID.randomUUID().toString()
    }
}

class AndroidPlatform(private val context: Context) : FolioPlatform {
    override val fileSystem: FolioFileSystem = AndroidFileSystem(context)
    override val hasher: FileHasher = MessageDigestFileHasher()
    override val settingsStore: SettingsStore = AndroidSettingsStore(context)
}
