package com.folio.reader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.folio.reader.nav.FolioRoutes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * §8.3 AccessibilityAuditTest. Walks the four top-level screens and asserts,
 * per the spec: zero clickable nodes without text or a content description,
 * zero clickable nodes smaller than 48 dp in either dimension, and every
 * image-role node carrying a description. The reader is out of scope (its page
 * is a WebView with its own tree); its chrome is covered by the same rules in
 * the reader-flow tests.
 */
class AccessibilityAuditTest {

    @get:Rule val compose = createFolioComposeRule()

    private fun density(): Float = FolioTestBase.instrumentation.context.resources.displayMetrics.density

    private fun auditCurrentScreen(screenName: String) {
        compose.waitForIdle()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val densityPx = density()
        val px = 48f * densityPx

        val clickables = FolioTestBase.clickableNodes(root)
        val unlabeled = clickables.filter { node -> !FolioTestBase.hasAccessibleLabel(node) }
        assertTrue(
            "$screenName: clickable nodes without text or contentDescription: " +
                unlabeled.joinToString { it.config.toString() },
            unlabeled.isEmpty(),
        )

        // Rule 5 is about the touch target, not the visual bounds: Material3
        // expands small controls to 48dp via touchBoundsExpansion while their
        // layout bounds stay small, so measure what TalkBack and fingers hit.
        // LazyColumn pre-composes items below the fold with unplaced (zero)
        // bounds — untappable and a11y-invisible, so not a 48dp violation.
        val tooSmall = clickables.filter { node ->
            val touch = node.touchBoundsInRoot
            touch.width > 0f && touch.height > 0f &&
                (touch.width < px * 0.98f || touch.height < px * 0.98f)
        }
        assertTrue(
            "$screenName: clickable nodes under 48dp touch target: " +
                tooSmall.joinToString {
                    "'${FolioTestBase.subtreeLabel(it)}' " +
                        "${it.touchBoundsInRoot.width}/${it.touchBoundsInRoot.height} vs $px"
                },
            tooSmall.isEmpty(),
        )

        val undescribedImages = FolioTestBase.collectNodes(root).filter { node ->
            FolioTestBase.isImageRole(node) &&
                FolioTestBase.contentDescriptionOf(node).isNullOrEmpty()
        }
        assertTrue(
            "$screenName: image-role nodes without a description: " +
                undescribedImages.size,
            undescribedImages.isEmpty(),
        )
    }

    @Test
    fun libraryScreenPassesTheAudit() = auditCurrentScreen("Library")

    @Test
    fun homeScreenPassesTheAudit() {
        compose.waitForIdle()
        compose.onNodeWithText("Home").performClick()
        compose.awaitRoute(FolioRoutes.HOME)
        auditCurrentScreen("Home")
    }

    @Test
    fun statsScreenPassesTheAudit() {
        compose.waitForIdle()
        compose.onNodeWithText("Stats").performClick()
        compose.awaitRoute(FolioRoutes.STATS)
        auditCurrentScreen("Stats")
    }

    @Test
    fun moreScreenPassesTheAudit() {
        compose.waitForIdle()
        compose.onNodeWithText("More").performClick()
        compose.awaitRoute(FolioRoutes.MORE)
        auditCurrentScreen("More")
    }
}
