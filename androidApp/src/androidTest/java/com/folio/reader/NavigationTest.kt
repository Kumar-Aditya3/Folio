package com.folio.reader

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.folio.reader.nav.FolioRoutes
import org.junit.Rule
import org.junit.Test

/**
 * §8.2 NavigationTest. Asserts what §3.3 promised: exactly four bar routes, the
 * bar hidden everywhere else, back walking correctly, and hub state surviving
 * the death of the composition (activity recreation — the automatable form of
 * the am-kill scenario; the full process-death walk is covered by the scripted
 * device check that ships with each release).
 */
class NavigationTest {

    @get:Rule val compose = createFolioComposeRule()

    private fun tapBar(label: String) {
        compose.onAllNodesWithText(label).onFirst().performClick()
    }

    @Test
    fun startDestinationIsLibraryWithBarVisible() {
        compose.waitForIdle()
        compose.awaitRoute(FolioRoutes.LIBRARY)
        compose.onNodeWithText("Home").assertExists()
        compose.onNodeWithText("Stats").assertExists()
        compose.onNodeWithText("More").assertExists()
    }

    @Test
    fun fourBarRoutesAreReachable() {
        compose.waitForIdle()

        tapBar("Stats")
        compose.awaitRoute(FolioRoutes.STATS)

        tapBar("Home")
        compose.awaitRoute(FolioRoutes.HOME)

        tapBar("More")
        compose.awaitRoute(FolioRoutes.MORE)

        tapBar("Library")
        compose.awaitRoute(FolioRoutes.LIBRARY)
    }

    @Test
    fun barHiddenOnPushedRouteAndBackReturns() {
        compose.waitForIdle()
        tapBar("More")
        compose.awaitRoute(FolioRoutes.MORE)

        // The tools rows sit in a LazyColumn below the fold; scroll to the Tags row
        // before tapping, exactly like the survival test scrolls to History.
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Tags"))
        tapBar("Tags")
        compose.awaitRoute(FolioRoutes.TAGS)
        compose.onNodeWithText("Home").assertDoesNotExist()

        FolioTestBase.goBack(compose)
        compose.awaitRoute(FolioRoutes.MORE)
        compose.onNodeWithText("Home").assertExists()
    }

    @Test
    fun backFromPushedReaderReturnsToLibrary() {
        val seeded = compose.firstBookOrSeed()
        try {
            compose.openReader(seeded.book.id)
            compose.onNodeWithText("Home").assertDoesNotExist()

            FolioTestBase.goBack(compose)
            compose.awaitRoute(FolioRoutes.LIBRARY)
            compose.onNodeWithText("Home").assertExists()
        } finally {
            seeded.close()
        }
    }

    @Test
    fun moreHubContentSurvivesActivityRecreation() {
        compose.waitForIdle()
        tapBar("More")
        compose.awaitRoute(FolioRoutes.MORE)

        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("History"))
        compose.onNodeWithText("History").assertExists()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.awaitRoute(FolioRoutes.MORE)
        compose.onNodeWithText("History").assertExists()
    }
}
