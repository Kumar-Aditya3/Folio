package com.folio.reader.ui.render

import com.folio.reader.ui.reader.DesktopReaderEscapeHandler
import java.awt.Canvas
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopReaderEscapeTest {
    @Test fun closingToolsConsumesTheWholeEscapeGestureWithoutNavigatingBack() {
        var open = true
        var active = false
        var dismissals = 0
        val handler = DesktopReaderEscapeHandler({ open }, { active }) { open = false; dismissals++ }
        val canvas = Canvas()
        fun key(id: Int) = KeyEvent(canvas, id, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
        assertFalse(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)), "Other apps keep their Escape")
        active = true
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertFalse(open)
        assertEquals(1, dismissals)
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)), "Repeat cannot navigate away")
        assertTrue(handler.dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)), "Release must not reach the main window's Back handler")
        assertEquals(1, dismissals)
        assertFalse(handler.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)), "The next gesture can navigate normally")
    }
}
