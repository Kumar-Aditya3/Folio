package com.folio.reader

import com.folio.reader.manga.MangaChallenge
import com.folio.reader.manga.MangaChallenges
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bot-check handshake: the network layer records a blocked URL, the UI offers to
 * open it, the platform solver shows a WebView, and clearing the record is what tells
 * the screen underneath to retry.
 */
class MangaChallengeTest {

    @AfterTest
    fun reset() {
        MangaChallenges.solver = null
        MangaChallenges.clear()
    }

    @Test
    fun `a recorded block carries its host and user agent`() {
        MangaChallenges.record("https://comick.example/api/manga?page=1", userAgent = "Folio/UA")

        val pending = MangaChallenges.pending.value
        assertEquals("comick.example", pending?.host)
        assertEquals("Folio/UA", pending?.userAgent)
        // The exact blocked URL, not the site root: the WebView has to land on the
        // challenge page itself.
        assertEquals("https://comick.example/api/manga?page=1", pending?.url)
    }

    @Test
    fun `a URL with no host records nothing`() {
        MangaChallenges.record("not a url")

        assertNull(MangaChallenges.pending.value)
    }

    @Test
    fun `only the blocked host clears the prompt`() {
        MangaChallenges.record("https://comick.example/manga")

        MangaChallenges.clearedFor("other.example")
        assertTrue(MangaChallenges.pending.value != null, "an unrelated host must not clear the prompt")

        MangaChallenges.clearedFor("comick.example")
        assertNull(MangaChallenges.pending.value)
    }

    @Test
    fun `without a platform solver there is nothing to offer`() {
        assertFalse(MangaChallenges.canSolve)

        // solve() must be a no-op rather than a crash on a platform with no WebView.
        MangaChallenges.record("https://comick.example/manga")
        MangaChallenges.solve()
    }

    @Test
    fun `the solver receives the pending challenge`() {
        var seen: MangaChallenge? = null
        MangaChallenges.solver = { seen = it }

        MangaChallenges.record("https://comick.example/manga", userAgent = "Folio/UA")
        MangaChallenges.solve()

        assertTrue(MangaChallenges.canSolve)
        assertEquals("comick.example", seen?.host)
        assertEquals("Folio/UA", seen?.userAgent)
    }
}
