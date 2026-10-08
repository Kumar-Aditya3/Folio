package com.folio.reader

import com.folio.reader.ui.manga.MangaReaderMode
import com.folio.reader.ui.manga.resolveMangaReaderMode
import kotlin.test.Test
import kotlin.test.assertEquals

class MangaReaderDefaultsTest {
    @Test
    fun `unsaved manga follows the global default`() {
        assertEquals(
            MangaReaderMode.PAGED_RTL,
            resolveMangaReaderMode(mangaModeName = null, defaultModeName = "PAGED_RTL")
        )
    }

    @Test
    fun `manga override wins over a changed global default`() {
        assertEquals(
            MangaReaderMode.PAGED_LTR,
            resolveMangaReaderMode(mangaModeName = "PAGED_LTR", defaultModeName = "WEBTOON")
        )
    }

    @Test
    fun `missing or invalid values retain the webtoon default`() {
        assertEquals(MangaReaderMode.WEBTOON, resolveMangaReaderMode(null, null))
        assertEquals(MangaReaderMode.WEBTOON, resolveMangaReaderMode("unknown", "also_unknown"))
    }
}
