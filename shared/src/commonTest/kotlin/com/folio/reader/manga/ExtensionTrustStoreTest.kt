package com.folio.reader.manga

import com.folio.reader.database.SettingsRepository
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionTrustStoreTest {
    private class Settings : SettingsRepository {
        val values = mutableMapOf<String, String>()
        var beforeWrite: suspend (String, String) -> Unit = { _, _ -> }
        override suspend fun getRaw(key: String) = values[key]
        override suspend fun setRaw(key: String, value: String) {
            beforeWrite(key, value)
            values[key] = value
        }
        override suspend fun getGlobalSettings() = ReaderSettings()
        override suspend fun saveGlobalSettings(settings: ReaderSettings, emitSyncEvent: Boolean) = Unit
        override suspend fun getBookSettings(bookId: String): BookReaderSettings? = null
        override suspend fun saveBookSettings(bookId: String, settings: BookReaderSettings) = Unit
        override suspend fun deleteBookSettings(bookId: String) = Unit
    }

    @Test
    fun coldLoadWaitsForPersistedApprovalsAndMigratesOldVersions() = runTest {
        val settings = Settings().apply { values[KEY] = "reader.ext:21:signer\nother.ext:other" }
        val store = ExtensionTrustStore(settings)
        val check = async { store.trustedExtensions() }
        runCurrent()
        assertFalse(check.isCompleted)
        store.preload()
        assertEquals(setOf("reader.ext:signer", "other.ext:other"), check.await())
        assertEquals("other.ext:other\nreader.ext:signer", settings.values[KEY])
        val reopened = ExtensionTrustStore(settings)
        reopened.preload()
        assertEquals(check.await(), reopened.trustedExtensions())
    }

    @Test
    fun approvalDoesNotFinishBeforeItIsOnDisk() = runTest {
        val settings = Settings()
        val store = ExtensionTrustStore(settings)
        store.preload()
        val commit = CompletableDeferred<Unit>()
        settings.beforeWrite = { _, _ -> commit.await() }
        val approval = async { store.trust("reader.ext:signer", "reader.ext") }
        runCurrent()
        assertFalse(approval.isCompleted)
        assertEquals(null, settings.values[KEY])
        commit.complete(Unit)
        approval.await()
        val restarted = ExtensionTrustStore(settings)
        restarted.preload()
        assertEquals(setOf("reader.ext:signer"), restarted.trustedExtensions())
    }

    @Test
    fun concurrentApprovalsDoNotLoseEarlierPackagesOrReplayOldSnapshots() = runTest {
        val settings = Settings()
        val store = ExtensionTrustStore(settings)
        store.preload()
        val commit = CompletableDeferred<Unit>()
        settings.beforeWrite = { _, value -> if (value == "first.ext:signer") commit.await() }
        val first = async { store.trust("first.ext:signer", "first.ext") }
        runCurrent()
        val second = async { store.trust("second.ext:signer", "second.ext") }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        commit.complete(Unit)
        first.await()
        second.await()
        assertEquals("first.ext:signer\nsecond.ext:signer", settings.values[KEY])
        assertEquals(setOf("first.ext:signer", "second.ext:signer"), store.trustedExtensions())
    }

    @Test
    fun updatingAPackageApprovalPreservesAllOtherTrustedExtensions() = runTest {
        val settings = Settings().apply { values[KEY] = "reader.ext:old-signer\nother.ext:publisher" }
        val store = ExtensionTrustStore(settings)
        store.preload()
        store.trust("reader.ext:new-signer", "reader.ext")
        val restarted = ExtensionTrustStore(settings)
        restarted.preload()
        assertEquals(setOf("reader.ext:new-signer", "other.ext:publisher"), restarted.trustedExtensions())
    }

    @Test
    fun failedPreloadReleasesWaitingChecksWithAnErrorInsteadOfAnEmptyTrustSet() = runTest {
        val settings = Settings().apply {
            values[KEY] = "reader.ext:21:signer"
            beforeWrite = { _, _ -> error("migration commit failed") }
        }
        val store = ExtensionTrustStore(settings)
        assertFailsWith<IllegalStateException> { store.preload() }
        assertFailsWith<IllegalStateException> { store.trustedExtensions() }
        assertEquals("reader.ext:21:signer", settings.values[KEY])
    }

    @Test
    fun failedWriteDoesNotPublishAnApprovalThatWillDisappearAfterRestart() = runTest {
        val settings = Settings()
        val store = ExtensionTrustStore(settings)
        store.preload()
        settings.beforeWrite = { _, _ -> error("disk unavailable") }
        assertFailsWith<IllegalStateException> { store.trust("reader.ext:signer", "reader.ext") }
        assertTrue(store.trustedExtensions().isEmpty())
        assertEquals(null, settings.values[KEY])
    }

    @Test
    fun cancellingDuringCommitKeepsMemoryAndDiskInAgreement() = runTest {
        val settings = Settings()
        val store = ExtensionTrustStore(settings)
        store.preload()
        val commit = CompletableDeferred<Unit>()
        settings.beforeWrite = { _, _ -> commit.await() }
        val approval = launch { store.trust("reader.ext:signer", "reader.ext") }
        runCurrent()
        approval.cancel()
        commit.complete(Unit)
        approval.join()
        assertEquals(setOf("reader.ext:signer"), store.trustedExtensions())
        assertEquals("reader.ext:signer", settings.values[KEY])
    }

    @Test
    fun revocationCannotBeOverwrittenByAnOlderInFlightApproval() = runTest {
        val settings = Settings()
        val store = ExtensionTrustStore(settings)
        store.preload()
        val commit = CompletableDeferred<Unit>()
        settings.beforeWrite = { _, value -> if (value.isNotEmpty()) commit.await() }
        val approval = async { store.trust("reader.ext:signer", "reader.ext") }
        runCurrent()
        val revocation = async { store.revokeAll() }
        runCurrent()
        commit.complete(Unit)
        approval.await()
        revocation.await()
        assertTrue(store.trustedExtensions().isEmpty())
        assertEquals("", settings.values[KEY])
    }

    companion object {
        private const val KEY = "manga.trusted"
    }
}
