package com.folio.reader

import com.folio.reader.manga.MangaChallenge
import com.folio.reader.manga.MangaChallenges
import com.folio.reader.manga.MangaAccessReason
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bot-check handshake: the network layer records a blocked URL, the UI offers to
 * open it, and source access is verified by an explicit retry, not browser closure.
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
        assertEquals(seen, MangaChallenges.pending.value, "Opening a browser is not a successful source load")
    }

    @Test
    fun `origin refusal is not presented as a Cloudflare puzzle`() {
        MangaChallenges.record("https://site.test/", "UA", MangaAccessReason.SITE_REFUSAL)
        assertEquals(MangaAccessReason.SITE_REFUSAL, MangaChallenges.pending.value?.reason)
        var opened = 0
        MangaChallenges.solver = { opened++ }
        MangaChallenges.solve()
        assertEquals(1, opened)
        assertTrue(MangaChallenges.pending.value != null)
    }

    @Test
    fun `an older successful request cannot erase a newer same-host failure`() {
        MangaChallenges.record("https://blocked.test/first")
        val old = MangaChallenges.pending.value
        MangaChallenges.record("https://blocked.test/newer")
        val newer = MangaChallenges.pending.value
        MangaChallenges.clearedFor("blocked.test", old)
        assertEquals(newer, MangaChallenges.pending.value)
        MangaChallenges.clear(old)
        assertEquals(newer, MangaChallenges.pending.value)
        MangaChallenges.clearedFor("blocked.test", newer)
        assertNull(MangaChallenges.pending.value)
    }

    @Test
    fun `a valid request path with brackets does not hide its blocked host`() {
        MangaChallenges.record("https://blocked.test/a[b]")
        assertEquals("blocked.test", MangaChallenges.pending.value?.host)
    }

    @Test
    fun `identical repeated failures still have distinct generations`() {
        MangaChallenges.record("https://blocked.test/")
        val old = MangaChallenges.pending.value
        MangaChallenges.record("https://blocked.test/")
        val newer = MangaChallenges.pending.value
        assertTrue(old?.generation != newer?.generation)
        MangaChallenges.clearedFor("blocked.test", old)
        assertEquals(newer, MangaChallenges.pending.value)
    }

    @Test
    fun `successful source retry clears its api subdomain but not another source`() {
        MangaChallenges.record("https://api.site.test/search", sourceId = 12L)
        val pending = MangaChallenges.pending.value
        MangaChallenges.clearedForSource(13L, pending)
        assertEquals(pending, MangaChallenges.pending.value)
        MangaChallenges.clearedForSource(12L, pending)
        assertNull(MangaChallenges.pending.value)
    }
}
