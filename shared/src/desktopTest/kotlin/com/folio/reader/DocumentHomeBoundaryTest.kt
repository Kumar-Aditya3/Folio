package com.folio.reader

import com.folio.reader.ui.home.HomeItemKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentHomeBoundaryTest {
    @Test fun readingDeskIsRestrictedToBooksAndManga() {
        assertEquals(setOf("BOOK", "MANGA"), HomeItemKind.entries.map { it.name }.toSet())
    }
}
