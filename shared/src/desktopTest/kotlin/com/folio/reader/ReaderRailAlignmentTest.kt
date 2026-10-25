package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.folio.reader.ui.components.FolioGlassRoot
import com.folio.reader.ui.components.GlassCapabilities
import com.folio.reader.ui.reader.ReaderFloatingRail
import com.folio.reader.ui.theme.FolioTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ReaderRailAlignmentTest {
    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)

    @Test fun everyRailIconSharesThePaintedRailsCenter() = SwingUtilities.invokeAndWait {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl))
            for (scale in listOf(1f, 1.5f)) {
                val scene = ImageComposeScene((180 * scale).toInt(), (500 * scale).toInt(),
                    density = Density(scale), layoutDirection = direction) {
                    FolioTheme.MaterialTheme(darkTheme = true) {
                        FolioGlassRoot(GlassCapabilities.None) {
                            Box(Modifier.fillMaxSize().background(Color(0xff505050))) {
                                ReaderFloatingRail(Triple("chapter", 0, "Selected passage"), { _, _, _ -> }, {},
                                    false, Color.Yellow, {}, {}, {}, {}, echoesEnabled = true, onOpenEchoes = {})
                            }
                        }
                    }
                }
                try {
                    repeat(10) { Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L).close() }
                    val nodes = scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
                    val names = listOf("Highlight selection", "Find echoes of this passage", "Search", "Contents", "Annotations")
                    val center = (if (direction == LayoutDirection.Ltr) 32f else 148f) * scale
                    for (name in names) {
                        val icon = nodes.single { name in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        assertEquals(center, icon.boundsInRoot.center.x, .6f,
                            "$name leans away from rail center ($direction, density=$scale): ${icon.boundsInRoot}")
                        assertTrue(icon.boundsInRoot.width > 0f)
                    }
                    val image = scene.render(1_100_000_000L)
                    try { File("build/visual-review/reader-rail/${direction.name}-$scale.png").apply {
                        parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    } } finally { image.close() }
                } finally { scene.close() }
            }
    }

    @Test fun restingRailUsesOnlyThreeFullSizeActionTargets() = SwingUtilities.invokeAndWait {
        val scene = ImageComposeScene(180, 500) {
            FolioTheme.MaterialTheme(darkTheme = true) {
                FolioGlassRoot(GlassCapabilities.None) {
                    ReaderFloatingRail(null, null, {}, false, Color.Yellow, {}, {}, {}, {}, echoesEnabled = true)
                }
            }
        }
        try {
            repeat(6) { Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L).close() }
            val nodes = scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
            val buttons = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
            assertEquals(3, buttons.size)
            buttons.forEach { assertEquals(48f, it.boundsInRoot.width, .1f); assertEquals(48f, it.boundsInRoot.height, .1f) }
            assertTrue(buttons.maxOf { it.boundsInRoot.bottom } - buttons.minOf { it.boundsInRoot.top } <= 144.1f)
        } finally { scene.close() }
    }
}
