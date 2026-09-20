package com.folio.reader.importer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [usableTitle]'s boundary, because the whole value of the rule is in *not* firing.
 *
 * The defect it fixes is narrow — one book in the test library declares a `file:///` path as its
 * `<dc:title>` — so the risk is not that it fails to catch a path, it is that a later widening
 * ("trim it", "strip anything with a slash", "fall back whenever the title is short") starts
 * replacing real titles with filenames. Every case below is a title that must survive untouched.
 */
class UsableTitleTest {

    private val source = File("/tmp/Some Folder/Assassin's Apprentice.epub")

    @Test
    fun `a file URL is replaced by the file name`() {
        // The real value from the test library, abbreviated. `C|` is what a Windows drive colon
        // becomes once the path is written as a URL, and `%20` is its encoding of a space.
        val declared = "file:///C|/Documents%20and%20Settings/Tony/My%20Documents/Books/Robin%20Hobb/Assassin's%20Apprentice.epub"
        assertEquals("Assassin's Apprentice", usableTitle(declared, source))
    }

    @Test
    fun `an http URL is replaced too`() {
        assertEquals(
            "Assassin's Apprentice",
            usableTitle("https://example.com/books/assassins-apprentice.epub", source),
        )
    }

    @Test
    fun `a bare Windows drive path is replaced`() {
        assertEquals("Assassin's Apprentice", usableTitle("""D:\Books\assassins-apprentice.epub""", source))
        assertEquals("Assassin's Apprentice", usableTitle("C:/Books/assassins-apprentice.epub", source))
    }

    @Test
    fun `an ordinary title is untouched`() {
        // The cases a naive rule would break. A colon is not a drive path without a separator, a
        // slash is not a path without a scheme, and a percent sign is ordinary punctuation.
        val titles = listOf(
            "Assassin's Apprentice",
            "Homeland",
            "The Malazan Empire",
            "Dune: Messiah",
            "50% Off Everything",
            "A/B Testing for Fun",
            "Snowflake Arctic Embed S",
            "file",
            "http",
            "C: a memoir",
        )
        titles.forEach { title ->
            assertEquals(title, usableTitle(title, source), "'$title' must survive unchanged")
        }
    }

    @Test
    fun `a path with no file name falls back to the declared title rather than to empty`() {
        // `nameWithoutExtension` is empty when the source itself is a bare path segment, which
        // cannot happen through the importer but must not turn a title into an empty string.
        assertEquals("file:///", usableTitle("file:///", File("/")))
    }
}
