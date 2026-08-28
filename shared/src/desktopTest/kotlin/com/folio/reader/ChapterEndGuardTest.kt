package com.folio.reader

import com.folio.reader.ui.reader.ChapterEndGuard
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterEndGuardTest {
    @Test
    fun `repeated end events are accepted once per chapter`() {
        val guard = ChapterEndGuard()
        assertTrue(guard.accept("one"))
        assertFalse(guard.accept("one"))
        guard.reset()
        assertTrue(guard.accept("one"))
        assertTrue(guard.accept("two"))
        assertFalse(guard.accept("two"))
    }
}
