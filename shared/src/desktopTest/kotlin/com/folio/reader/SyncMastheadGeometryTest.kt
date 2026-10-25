package com.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.components.SyncStatusBadge
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SyncMastheadGeometryTest {
    @BeforeTest
    fun fonts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(resource.toURI()).parentFile)
    }

    @Test
    fun syncStatesStayInsideTheirOwnActionAndDoNotCoverTheNeighbor() = SwingUtilities.invokeAndWait {
        for (direction in LayoutDirection.entries) for (width in listOf(320, 393)) {
            for (scale in listOf(1f, 1.5f)) {
                val state = mutableStateOf(SyncState(lastError = "Sync failed", pendingUploadCount = 3))
                var syncClicks = 0
                var redoClicks = 0
                val scene = ImageComposeScene((width * scale).toInt(), (100 * scale).toInt(),
                    density = Density(scale, fontScale = 1.5f), layoutDirection = direction) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            Box(Modifier.fillMaxSize()) {
                                FolioTopBar("Library", actions = {
                                    SyncStatusBadge(state.value, { syncClicks++ }, Modifier.testTag("sync-action"))
                                    IconButton(onClick = { redoClicks++ }, modifier = Modifier.testTag("redo-action")) {
                                        Icon(Icons.Filled.Refresh, "Redo")
                                    }
                                })
                            }
                        }
                    }
                }
                var frame = 0L
                fun settle() {
                    repeat(8) {
                        Snapshot.sendApplyNotifications()
                        scene.render(frame++ * 100_000_000L).close()
                    }
                }
                try {
                    for ((value, label) in listOf(
                        SyncState(lastError = "Sync failed", pendingUploadCount = 3) to "Sync error",
                        SyncState(isSyncing = true, pendingDownloadCount = 2) to "Syncing",
                        SyncState(pendingUploadCount = 1) to "Synced",
                        SyncState(quotaLimited = true, pendingDownloadCount = 2) to
                            "Sync resting — Google's free quota reached, it resumes automatically",
                    )) {
                        state.value = value
                        settle()
                        val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                        val action = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "sync-action" }.boundsInRoot
                        val next = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "redo-action" }.boundsInRoot
                        val glyph = nodes.single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInRoot
                        assertEquals(48f * scale, action.width, .1f)
                        assertEquals(48f * scale, action.height, .1f)
                        assertTrue(action.contains(glyph.topLeft) && action.contains(glyph.bottomRight), "$label escaped its slot")
                        assertTrue(!action.overlaps(next), "Sync covers redo at $width/$scale/$direction: $action / $next")
                        assertEquals(action.center.x, glyph.center.x, .5f)
                        assertEquals(action.center.y, glyph.center.y, .5f)
                    }
                    val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    val sync = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "sync-action" }.boundsInRoot.center
                    val redo = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "redo-action" }.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, sync)
                    scene.sendPointerEvent(PointerEventType.Release, sync)
                    settle()
                    scene.sendPointerEvent(PointerEventType.Press, redo)
                    scene.sendPointerEvent(PointerEventType.Release, redo)
                    settle()
                    assertEquals(1, syncClicks)
                    assertEquals(1, redoClicks)
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun aPendingQueueNeverAddsAnotherMarkOverErrorOrRefresh() = SwingUtilities.invokeAndWait {
        val state = mutableStateOf(SyncState(lastError = "Failed"))
        val scene = ImageComposeScene(64, 64) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                SyncStatusBadge(state.value, {})
            }
        }
        var frame = 0L
        fun pixels(): ByteArray {
            repeat(6) {
                Snapshot.sendApplyNotifications()
                scene.render(frame++ * 100_000_000L).close()
            }
            val image = scene.render(frame++ * 100_000_000L)
            try {
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try { return data.bytes } finally { data.close() }
            } finally { image.close() }
        }
        try {
            for (value in listOf(SyncState(lastError = "Failed"), SyncState(isSyncing = true))) {
                state.value = value
                val plain = pixels()
                state.value = value.copy(pendingUploadCount = 3, pendingDownloadCount = 2)
                assertContentEquals(plain, pixels(), "Pending queue painted a second mark over the status glyph")
            }
        } finally {
            scene.close()
        }
    }
}
