package com.folio.reader.security

import com.folio.reader.database.SettingsRepository
import com.folio.reader.platform.FolioFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The four cloud-sync credential fields, held apart from [com.folio.reader.settings.ReaderSettings]
 * so they can live in a store the platform keeps OUT of backups.
 *
 * [firebaseApiKey]/[firebaseProjectId] are low sensitivity; [syncAccountEmail]/[syncAccountPassword]
 * are the sensitive pair (a plaintext account password) that must never ship to cloud backup.
 */
@Serializable
data class SyncCredentials(
    val firebaseApiKey: String = "",
    val firebaseProjectId: String = "",
    val syncAccountEmail: String = "",
    val syncAccountPassword: String = "",
) {
    /** True when no field carries a value, i.e. the user has configured no sync credentials. */
    val isEmpty: Boolean
        get() = firebaseApiKey.isBlank() && firebaseProjectId.isBlank() &&
            syncAccountEmail.isBlank() && syncAccountPassword.isBlank()
}

/**
 * Secure, NON-BACKED-UP store for the cloud-sync credentials.
 *
 * The credentials used to be fields inside the `@Serializable ReaderSettings` blob, persisted as
 * JSON in the SQLite `settings` table inside `folio.db`. That database lives in the `database`
 * directory, which Android auto-backup / device transfer INCLUDES, so the plaintext sync password
 * shipped to Google cloud backup. This store closes that exposure by writing the credentials to a
 * file the platform excludes from backup:
 *
 *  - Android: [FolioFileSystem.getNoBackupFilesDir] resolves to `Context.getNoBackupFilesDir()`,
 *    which Android automatically excludes from auto-backup and device transfer.
 *  - Desktop: a local file under the app config dir. Desktop has no cloud auto-backup, so a local
 *    file is acceptable.
 *
 * Future hardening (deliberately NOT done here, to avoid a new dependency): on Android the file
 * could be wrapped with `androidx.security.crypto` `EncryptedFile` for at-rest encryption. The
 * backup exposure this class exists to close does not require it — moving the file out of the
 * backup set is what removes the exposure.
 */
class SecureCredentialStore(fileSystem: FolioFileSystem) {

    private val file: File = File(fileSystem.getNoBackupFilesDir(), FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    /** Current credentials, or an empty [SyncCredentials] when none are stored or the file is unreadable. */
    fun load(): SyncCredentials = synchronized(lock) {
        runCatching {
            if (!file.exists()) return@runCatching SyncCredentials()
            val text = file.readText()
            if (text.isBlank()) SyncCredentials()
            else json.decodeFromString(SyncCredentials.serializer(), text)
        }.getOrDefault(SyncCredentials())
    }

    /** Persists [credentials], replacing any previous value. Writes atomically via a temp file. */
    fun save(credentials: SyncCredentials) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(SyncCredentials.serializer(), credentials))
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    /** Clears the stored credentials (used by "Disconnect"). */
    fun clear() {
        save(SyncCredentials())
    }

    /**
     * One-time, idempotent migration that moves any credentials still living in the backed-up
     * [com.folio.reader.settings.ReaderSettings] blob into this no-backup store, then blanks those
     * fields in the persisted settings blob so the backed-up `folio.db` no longer carries them.
     *
     * Safe to call on every startup:
     *  - Fresh installs (no credentials anywhere) are a no-op.
     *  - The store is seeded BEFORE the settings blob is blanked, so a crash between the two steps
     *    can never lose the credentials; a re-run simply re-blanks the settings blob.
     *  - Once seeded, the store is authoritative and is never clobbered by a later migration pass.
     *
     * The settings blank is written with `emitSyncEvent = false`: the cloud settings document never
     * carried these fields anyway (see `SyncEngine.pushSettings`, which scrubs them), so this is a
     * purely local scrub and must not trigger a sync push.
     */
    suspend fun migrateFromSettings(settingsRepository: SettingsRepository) {
        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull() ?: return
        val fromSettings = SyncCredentials(
            firebaseApiKey = settings.firebaseApiKey,
            firebaseProjectId = settings.firebaseProjectId,
            syncAccountEmail = settings.syncAccountEmail,
            syncAccountPassword = settings.syncAccountPassword,
        )
        // Fast path and fresh-install path: nothing in the settings blob to migrate or scrub.
        if (fromSettings.isEmpty) return
        // 1) Seed the no-backup store first, but never overwrite credentials already migrated there.
        if (load().isEmpty) save(fromSettings)
        // 2) Blank the credential fields in the backed-up settings blob.
        settingsRepository.mergeGlobalSettings(emitSyncEvent = false) { current ->
            current.copy(
                firebaseApiKey = "",
                firebaseProjectId = "",
                syncAccountEmail = "",
                syncAccountPassword = "",
            )
        }
    }

    private companion object {
        const val FILE_NAME = "sync_credentials.json"
    }
}
