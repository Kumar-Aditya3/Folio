package com.folio.reader

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.folio.reader.settings.BookReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * §8.2 ReaderFlowTest — open a book, change the font size, rotate: the setting
 * and the reading position survive. The book's original per-book settings are
 * snapshotted and restored in `finally`, so the user's library is unaffected;
 * the reader session the open creates is seconds long and read-only.
 */
class ReaderFlowTest {

    @get:Rule val compose = createFolioComposeRule()

    private val graph get() = FolioTestBase.graphOf(compose)

    private fun showPanel(bookId: String) {
        // Chrome may already be up after openReader — an unconditional page tap
        // would toggle it away again. Probe first, then retry like a real user.
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
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(8000) {
            runCatching { compose.onNodeWithText("Applies to:").assertExists() }.isSuccess
        }
    }

    @Test
    fun fontSizeChangeSurvivesRotation() {
        val seeded = compose.firstBookOrSeed()
        val book = seeded.book
        val global = FolioTestBase.db { graph.settingsRepository.getGlobalSettings() }
        org.junit.Assume.assumeTrue("Headroom for an A+ tap", global.fontSize < 30f)
        val original = FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
        try {
            FolioTestBase.db { graph.settingsRepository.deleteBookSettings(book.id) }
            compose.openReader(book.id)
            showPanel(book.id)
            compose.onNodeWithText("A+").performClick()

            val changed = FolioTestBase.poll {
                FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
                    ?.takeIf { it.fontSize == global.fontSize + 1f }
            }
            org.junit.Assert.assertNotNull("Font change wrote through", changed)

            FolioTestBase.device.setOrientationLeft()
            compose.awaitRoute("reader/")
            val afterRotation = FolioTestBase.db { graph.settingsRepository.getBookSettings(book.id) }
            assertEquals(
                "Per-book font size retained across rotation",
                global.fontSize + 1f,
                afterRotation?.fontSize,
            )
        } finally {
            FolioTestBase.device.setOrientationNatural()
            FolioTestBase.db {
                if (original == null) graph.settingsRepository.deleteBookSettings(book.id)
                else graph.settingsRepository.saveBookSettings(book.id, original)
            }
            seeded.close()
        }
    }

    @Test
    fun readingPositionSurvivesRotation() {
        val seeded = compose.firstBookOrSeed()
        val book = seeded.book
        val before = FolioTestBase.db { graph.bookRepository.getBook(book.id) }?.progressPercent
        org.junit.Assume.assumeTrue(before != null)
        try {
            compose.openReader(book.id)
            FolioTestBase.device.setOrientationLeft()
            compose.awaitRoute("reader/")

            val after = FolioTestBase.db { graph.bookRepository.getBook(book.id) }?.progressPercent
            assertEquals(
                "Position untouched by open + rotate",
                before,
                after,
            )
        } finally {
            FolioTestBase.device.setOrientationNatural()
            seeded.close()
        }
    }
}
