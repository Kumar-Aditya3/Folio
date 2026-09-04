package com.folio.reader

import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.ui.manga.defaultSourceLanguages
import com.folio.reader.ui.manga.filterSourcesByLanguage
import com.folio.reader.ui.manga.languageLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Browse's "duplicate sources" problem.
 *
 * One extension publishes one source per language it supports, so an unfiltered
 * browse list shows "Webtoons.com" ten times over and the reader cannot tell the
 * rows apart. The fix is a language filter with a sane default, plus rows that name
 * their language instead of printing a two-letter code.
 */
class SourceLanguageFilterTest {

    private fun source(id: Long, name: String, lang: String, isLocal: Boolean = false) =
        MangaSourceInfo(id = id, name = name, lang = lang, isLocal = isLocal)

    private val local = source(0, "Local manga", "", isLocal = true)
    private val webtoonsEn = source(1, "Webtoons.com", "en")
    private val webtoonsTh = source(2, "Webtoons.com", "th")
    private val webtoonsEs = source(3, "Webtoons.com", "es")
    private val comickFr = source(4, "Comick", "fr")
    private val multiLang = source(5, "MangaDex", "all")

    private val all = listOf(local, webtoonsEn, webtoonsTh, webtoonsEs, comickFr, multiLang)

    @Test
    fun `the filter leaves one row per site`() {
        val kept = filterSourcesByLanguage(all, setOf("en"))

        // The local source has no language and the multi-language catalogue serves
        // every reader, so both survive; the three extra Webtoons editions do not.
        assertEquals(listOf(0L, 1L, 5L), kept.map { it.id })
    }

    @Test
    fun `no recorded preference filters nothing`() {
        assertEquals(all, filterSourcesByLanguage(all, emptySet()))
    }

    @Test
    fun `a filter that would hide every source is ignored`() {
        // A reader whose only extension is French-only must still see it: an empty
        // browse list is a bug report, not a preference.
        val french = listOf(local, comickFr)

        assertEquals(french, filterSourcesByLanguage(french, setOf("de")))
    }

    @Test
    fun `several languages can be enabled at once`() {
        val kept = filterSourcesByLanguage(all, setOf("en", "fr"))

        assertEquals(listOf(0L, 1L, 4L, 5L), kept.map { it.id })
    }

    @Test
    fun `English is always in the default set`() {
        val defaults = defaultSourceLanguages()

        assertTrue("en" in defaults, "English is the fallback catalogue language")
        assertTrue(defaults.none { it.isBlank() }, "a blank language code filters everything out")
    }

    @Test
    fun `rows name their language`() {
        assertEquals("English", languageLabel("en"))
        assertEquals("Thai", languageLabel("th"))
        assertEquals("All languages", languageLabel("all"))
        assertEquals("", languageLabel(""))
    }
}
