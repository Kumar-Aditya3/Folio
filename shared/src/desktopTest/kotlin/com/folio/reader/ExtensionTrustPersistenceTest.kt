package com.folio.reader

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcSettingsRepository
import com.folio.reader.manga.ExtensionTrustStore
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionTrustPersistenceTest {
    @Test
    fun approvalSurvivesImmediateDatabaseCloseAndReload() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("folio-extension-trust-").toFile()
        val path = File(root, "folio.db").absolutePath
        try {
            val first = Database(path)
            try {
                val store = ExtensionTrustStore(JdbcSettingsRepository(first))
                store.preload()
                store.trust("reader.ext:publisher", "reader.ext")
            } finally {
                first.close()
            }
            val reopened = Database(path)
            try {
                val store = ExtensionTrustStore(JdbcSettingsRepository(reopened))
                store.preload()
                assertEquals(setOf("reader.ext:publisher"), store.trustedExtensions())
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
