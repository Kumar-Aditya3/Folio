package com.folio.reader

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.folio.reader.nav.FolioRoutes
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * §8.2 StateCoverageTest. Every audited screen must reach a real state of the
 * Rule-7 four (loading/empty/error/content) — operationalised here as: the
 * screen composes without crashing, is not blank, and, whenever the library
 * actually holds data, shows that data instead of an empty shell.
 */
class StateCoverageTest {

    @get:Rule val compose = createFolioComposeRule()

    @Test
    fun homeRendersAValidState() {
        compose.waitForIdle()
        compose.onNodeWithText("Home").performClick()
        compose.awaitRoute(FolioRoutes.HOME)
        compose.assertNotBlank()
    }

    @Test
    fun libraryRendersAValidState() {
        compose.waitForIdle()
        compose.assertNotBlank()
        val book = FolioTestBase.db {
            FolioTestBase.graphOf(compose).bookRepository.getAllBooks().first().firstOrNull()
        }
        if (book != null) {
            compose.waitUntil(8000) {
                runCatching { compose.onNodeWithText(book.title).assertExists() }.isSuccess
            }
        }
    }

    @Test
    fun statsRendersAValidState() {
        compose.waitForIdle()
        compose.onNodeWithText("Stats").performClick()
        compose.awaitRoute(FolioRoutes.STATS)
        compose.waitUntil(15000) {
            runCatching { compose.onNodeWithText("This week", ignoreCase = true).assertExists() }.isSuccess
        }
        compose.onNodeWithText("This week", ignoreCase = true).assertExists()
    }

    @Test
    fun bookDetailRendersAValidState() {
        val seeded = compose.firstBookOrSeed()
        val book = seeded.book
        try {
            compose.waitForIdle()
            compose.waitUntil(8000) {
                runCatching { compose.onAllNodesWithText(book.title)[0].performClick() }.isSuccess
            }
            compose.awaitRoute("book/")
            compose.waitUntil(8000) {
                runCatching { compose.onNodeWithContentDescription("Share EPUB").assertExists() }.isSuccess
            }
            compose.onNodeWithContentDescription("Share EPUB").assertExists()
            compose.assertNotBlank()
        } finally {
            seeded.close()
        }
    }
}
