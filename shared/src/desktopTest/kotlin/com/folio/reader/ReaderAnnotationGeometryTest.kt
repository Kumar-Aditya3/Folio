package com.folio.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.components.folioContextDialogPosition
import com.folio.reader.ui.reader.readerSelectionRect
import com.folio.reader.ui.reader.readerSelectionToolbarPosition
import com.folio.reader.ui.render.ReaderSelectionBounds
import com.folio.reader.ui.render.parseReaderSelectionMessage
import kotlin.test.*

class ReaderAnnotationGeometryTest {
    @Test fun selectionProtocolKeepsSourceGeometryAndEncodedTextTogether() {
        val message = assertNotNull(parseReaderSelectionMessage("folio-sel3:4:18:0.1:0.2:0.7:0.3:A%3AB%2BC:15"))
        assertEquals(4, message.spine)
        assertEquals(18, message.paragraph)
        assertEquals("A:B+C", java.net.URLDecoder.decode(message.encodedText, "UTF-8"))
        assertEquals(ReaderSelectionBounds(.1f, .2f, .7f, .3f), message.bounds)
        assertNull(parseReaderSelectionMessage("folio-sel3:0:1:NaN:0:1:1:Text:1"))
        assertNull(parseReaderSelectionMessage("folio-sel3:0:1:1:0:0:1:Text:1"))
        assertNull(parseReaderSelectionMessage("folio-sel3:0:-1:0:0:1:1:Text:1"))
    }

    @Test fun browserGeometryProjectsIntoInsetAndDockedPageCoordinates() {
        val rect = assertNotNull(readerSelectionRect(ReaderSelectionBounds(.25f, .5f, .75f, .6f), Rect(20f, 60f, 620f, 860f)))
        assertEquals(Rect(170f, 460f, 470f, 540f), rect)
        assertNull(readerSelectionRect(ReaderSelectionBounds(0f, -1f, 1f, -.5f), Rect(0f, 0f, 400f, 800f)))
    }

    @Test fun selectionToolsPreferBelowTheHandlesAndStayInsideThePage() {
        val page = Rect(0f, 0f, 393f, 852f)
        val position = readerSelectionToolbarPosition(Rect(80f, 300f, 310f, 330f), page, IntSize(280, 48), 24f)
        assertEquals(354, position.y)
        val nearBottom = readerSelectionToolbarPosition(Rect(300f, 800f, 380f, 830f), page, IntSize(280, 48), 24f)
        assertTrue(nearBottom.x in 0..113)
        assertTrue(nearBottom.y + 48 < 800 - 48)
    }

    @Test fun passageComposerAvoidsKeyboardAndClampsAfterWindowResizing() {
        val point = folioContextDialogPosition(Rect(280f, 580f, 380f, 610f), 360, 300, 393, 500, 12)
        assertTrue(point.x in 12..21)
        assertTrue(point.y in 12..188)
        val short = folioContextDialogPosition(Rect(0f, 500f, 100f, 540f), 300, 240, 300, 240, 12)
        assertEquals(0, short.x)
        assertEquals(0, short.y)
    }
}
