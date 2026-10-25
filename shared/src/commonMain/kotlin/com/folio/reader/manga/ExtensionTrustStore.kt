package com.folio.reader.manga

import com.folio.reader.database.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Commits approvals before publishing them to extension loaders. */
internal class ExtensionTrustStore(private val settings: SettingsRepository) {
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var trusted = emptySet<String>()

    suspend fun preload() {
        mutex.withLock {
            if (loaded.isCompleted) {
                loaded.await()
                return
            }
            try {
                val entries = settings.getRaw(KEY_TRUSTED).orEmpty().lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val migrated = entries.map { entry ->
                    val parts = entry.split(':')
                    if (parts.size == 3 && parts[1].toLongOrNull() != null) {
                        "${parts[0]}:${parts[2]}"
                    } else {
                        entry
                    }
                }.toSet()
                if (migrated != entries) persist(migrated)
                trusted = migrated
                loaded.complete(Unit)
            } catch (error: Throwable) {
                loaded.completeExceptionally(error)
                throw error
            }
        }
    }

    suspend fun trustedExtensions(): Set<String> {
        loaded.await()
        return mutex.withLock { trusted }
    }

    suspend fun trust(entry: String, pkgName: String) {
        loaded.await()
        mutex.withLock {
            val updated = trusted.filterNot { it.startsWith("$pkgName:") }.toSet() + entry
            // Once a commit starts, cancellation must not split the disk and memory snapshots.
            withContext(NonCancellable) {
                persist(updated)
                trusted = updated
            }
        }
    }

    suspend fun revokeAll() {
        loaded.await()
        mutex.withLock {
            withContext(NonCancellable) {
                persist(emptySet())
                trusted = emptySet()
            }
        }
    }

    private suspend fun persist(snapshot: Set<String>) {
        settings.setRaw(KEY_TRUSTED, snapshot.sorted().joinToString("\n"))
    }

    companion object {
        private const val KEY_TRUSTED = "manga.trusted"
    }
}
