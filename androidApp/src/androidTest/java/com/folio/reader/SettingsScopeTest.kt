package com.folio.reader

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.folio.reader.nav.FolioRoutes
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.FolioSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * §8.2 SettingsScopeTest — the §4 scope contract, exercised against the real
 * database. Each test snapshots whatever per-book overrides the book already
 * carries, guarantees the slate it needs, and restores the snapshot, so a run
 * leaves the user's library settings untouched.
 */
class SettingsScopeTest {

    @get:Rule val compose = createFolioComposeRule()

    private val graph get() = FolioTestBase.graphOf(compose)

    /** Opens the reader, toggles the chrome until the gear is visible, opens the panel. */
    private fun openReaderPanel(bookId: String) {
        compose.openReader(bookId)
        var gearVisible = runCatching {
            compose.onNodeWithContentDescription("Settings").assertExists()
        }.isSuccess
        var attempts = 0
        while (!gearVisible && attempts < 3) {
            FolioTestBase.tapReaderPage()
            Thread.sleep(400)
            gearVisible = runCatching {
                compose.onNodeWithContentDescription("Settings").assertExists()
            }.isSuccess
            attempts++
        }
        check(gearVisible) { "Reader chrome never showed the settings gear" }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(8000) {
            runCatching { compose.onNodeWithText("Applies to:").assertExists() }.isSuccess
        }
    }

    @Test
    fun readerPanelShowsScopeControlAndDefaultsToThisBook() {
        val seeded = compose.firstBookOrSeed()
        val book = seeded.book
        val original = FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
        try {
            FolioTestBase.db { graph.settingsRepository.deleteBookSettings(book.id) }
            openReaderPanel(book.id)

            compose.onNodeWithText("Applies to:").assertExists()
            compose.onNodeWithText("This book").assertExists()
            compose.onNodeWithText("All books").assertExists()
            compose.onNode(hasText("This book")).assertIsSelected()
        } finally {
            restoreBookSettings(book.id, original)
            seeded.close()
        }
    }

    @Test
    fun resetDisabledWithZeroOverridesAndEnabledAfterOne() {
        val seeded = compose.firstBookOrSeed()
        val book = seeded.book
        val global = FolioTestBase.db { graph.settingsRepository.getGlobalSettings() }
        org.junit.Assume.assumeTrue(
            "Global font size leaves headroom for an A+ tap",
            global.fontSize < 30f,
        )
        val original = FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
        try {
            FolioTestBase.db { graph.settingsRepository.deleteBookSettings(book.id) }
            openReaderPanel(book.id)

            compose.onNodeWithText("Reset this book to defaults").assertIsNotEnabled()

            compose.onNodeWithText("A+").performClick()
            compose.waitUntil(8000) {
                runCatching {
                    compose.onNodeWithContentDescription("Overridden for this book").assertExists()
                }.isSuccess
            }
            compose.onNodeWithText("Reset this book to defaults").assertIsEnabled()

            val written = FolioTestBase.poll {
                FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
            }!!
            assertEquals(
                "This-book write lands in the book snapshot only",
                global.fontSize + 1f,
                written.fontSize,
            )
        } finally {
            restoreBookSettings(book.id, original)
            seeded.close()
        }
    }

    @Test
    fun allBooksWriteReachesGlobalDefaults() {
        val seeded = compose.firstBookOrSeed()
        val book = seeded.book
        val originalBook = FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
        val originalGlobal = FolioTestBase.db { graph.settingsRepository.getGlobalSettings() }
        org.junit.Assume.assumeTrue(
            "Global font size leaves headroom for an A+ tap",
            originalGlobal.fontSize < 30f,
        )
        try {
            FolioTestBase.db { graph.settingsRepository.deleteBookSettings(book.id) }
            openReaderPanel(book.id)

            compose.onNodeWithText("All books").performClick()
            compose.onNodeWithText("A+").performClick()

            val updatedGlobal = FolioTestBase.poll {
                FolioTestBase.db { graph.settingsRepository.getGlobalSettings() }
                    .takeIf { it.fontSize == originalGlobal.fontSize + 1f }
            }!!
            assertEquals(
                "All-books write stores the new global default",
                originalGlobal.fontSize + 1f,
                updatedGlobal.fontSize,
            )
            assertNull(
                "and clears this book's override for the changed field",
                FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }?.fontSize,
            )
        } finally {
            restoreBookSettings(book.id, originalBook)
            FolioTestBase.db { graph.settingsRepository.saveGlobalSettings(originalGlobal) }
            seeded.close()
        }
    }

    @Test
    fun formattingSettingsScreenIsReachable() {
        compose.waitForIdle()
        compose.onNodeWithText("More").performClick()
        compose.awaitRoute(FolioRoutes.MORE)
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Formatting"))
        compose.onAllNodesWithText("Formatting")[0].performClick()
        compose.awaitRoute("settings/")
        compose.waitUntil(15000) {
            runCatching { compose.onNodeWithText("Hyphenation").assertExists() }.isSuccess
        }
        compose.onNodeWithText("Hyphenation").assertExists()
    }

    private fun restoreBookSettings(bookId: String, original: BookReaderSettings?) {
        FolioTestBase.db {
            if (original == null) graph.settingsRepository.deleteBookSettings(bookId)
            else graph.settingsRepository.saveBookSettings(bookId, original)
        }
    }
}
