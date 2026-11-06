package com.folio.reader.ui.render

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopReaderDocumentGateTest {
    @Test fun toolbarActionsAcceptBothCounterAndTimestampNonces() {
        assertEquals("reader:toggle-controls", desktopReaderOverlayAction("folio-ovl:reader:toggle-controls:12"))
        for (action in listOf("keep", "note", "copy", "export")) {
            assertEquals("sel:$action", desktopReaderOverlayAction("folio-ovl:sel:$action:1793423000000"))
        }
        assertEquals("reader:search", desktopReaderOverlayAction("folio-ovl:reader:search"))
    }

    @Test fun staleDocumentsAndDelayedMessagesCannotOwnTheIncomingPosition() {
        val gate = DesktopReaderDocumentGate()
        val first = gate.begin("file:///reader/first.html")
        assertTrue(gate.acknowledge(first, "file:///reader/first.html#page"))
        assertEquals("folio-progress:0.5", gate.message("folio-generation:$first|folio-progress:0.5", gate.url))
        gate.invalidate()
        assertFalse(gate.ready)
        assertFalse(gate.acknowledge(first, "file:///reader/first.html"))
        assertNull(gate.message("folio-generation:$first|folio-ready:$first|", "file:///reader/first.html"))
        val second = gate.begin("file:///reader/second.html")
        assertFalse(gate.matches(first, "file:///reader/first.html"))
        assertNull(gate.message("folio-generation:$first|folio-progress:1", gate.url))
        assertNull(gate.message("folio-progress:1", gate.url))
        assertFalse(gate.acknowledge(second, "file:///reader/first.html"))
        assertTrue(gate.acknowledge(second, gate.url))
        gate.dispose()
        assertFalse(gate.matches(second, "file:///reader/second.html"))
        assertNull(gate.message("folio-generation:$second|folio-progress:1", "file:///reader/second.html"))
    }

    @Test fun bothBootstrapsAreValidJavaScriptAndWaitForLayoutBeforeReportingPaint() {
        for (engine in listOf(PageEngine.js(.5f, 1, 24f, 720), ContinuousEngine.js(1, .5f, true))) {
            val script = desktopReaderBootstrap(17, true, false, engine, "file:///reader/test.html")
            val process = ProcessBuilder("node", "--check").redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            assertTrue(script.contains("window.__folioLayoutReady===false"))
            assertTrue(script.contains("folio-generation:17|"))
            assertTrue(script.contains("requestAnimationFrame(function(){requestAnimationFrame"))
        }
    }
}
