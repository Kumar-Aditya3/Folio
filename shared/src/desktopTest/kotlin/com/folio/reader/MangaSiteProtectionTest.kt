package com.folio.reader

import com.folio.reader.manga.MangaAccessReason
import com.folio.reader.manga.MangaSiteAccessException
import com.folio.reader.manga.MangaChallengeFailure
import com.folio.reader.manga.isCloudflareChallenge
import com.folio.reader.manga.mangaBrowserUrl
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MangaSiteProtectionTest {
    @Test
    fun mitigationHeaderIsAuthoritativeWithoutAServerHeaderOrBody() {
        assertTrue(isCloudflareChallenge(403, null, "challenge") { error("body must not be read") })
        assertTrue(isCloudflareChallenge(503, "nginx", "Challenge"))
    }

    @Test
    fun ordinaryRefusalsNeverTriggerAHeadlessSolve() {
        val nginx = "<h1>403 Forbidden</h1>nginx<script type=\"ignore\"><a href=\"/1-anchor-decoy.html\">.</a></script>"
        assertFalse(isCloudflareChallenge(403, "nginx", null) { nginx })
        assertFalse(isCloudflareChallenge(403, "cloudflare", null) { nginx })
        assertFalse(isCloudflareChallenge(403, "cloudflare", null) { "Sorry, you have been blocked" })
        assertFalse(isCloudflareChallenge(503, "cloudflare", null) { "Service unavailable" })
        assertFalse(isCloudflareChallenge(200, "cloudflare", null) { "turnstile widget" })
    }

    @Test
    fun legacyCloudflarePagesRequireActualChallengeMarkers() {
        assertTrue(isCloudflareChallenge(403, "Cloudflare", null) { "window._cf_chl_opt = {}" })
        assertTrue(isCloudflareChallenge(503, "cloudflare-nginx", null) { "/cdn-cgi/challenge-platform/" })
        assertFalse(isCloudflareChallenge(403, "nginx", null) { "window._cf_chl_opt = {}" })
        assertFalse(isCloudflareChallenge(403, "cloudflare", null) { error("body unavailable") })
    }

    @Test
    fun postChallengeOpensHostRootWithoutReplayingTheControllerAsGet() {
        val controller = "https://batcave.biz/engine/ajax/controller.php?mod=load_comix#test"
        assertEquals("https://batcave.biz/", mangaBrowserUrl("POST", controller))
        assertEquals(controller, mangaBrowserUrl("GET", controller))
        assertEquals("https://example.test:8443/", mangaBrowserUrl("POST", "https://example.test:8443/api?q=secret"))
        assertEquals("https://example.test/", mangaBrowserUrl("POST", "https://example.test/a[b]?q=test"))
    }

    @Test
    fun challengeAndOriginRefusalKeepDifferentMessagesAndHttpStatus() {
        val blocked = MangaSiteAccessException(MangaAccessReason.SITE_REFUSAL, 403)
        assertIs<IOException>(blocked)
        assertEquals(403, blocked.statusCode)
        assertTrue(blocked.message.orEmpty().contains("site refused access"))
        val challenge = MangaSiteAccessException(MangaAccessReason.CLOUDFLARE_CHALLENGE, 503)
        assertTrue(challenge.message.orEmpty().contains("Cloudflare"))
        assertEquals(503, challenge.statusCode)
    }

    @Test
    fun failedSolveIsSharedByConcurrentRequestsButNeverLocksOutLaterRetries() {
        val failure = MangaChallengeFailure(100L, null, "UA", MangaAccessReason.CLOUDFLARE_CHALLENGE, 503)
        assertTrue(failure.appliesTo(50L, null, "UA"))
        assertTrue(failure.appliesTo(99L, null, "UA"))
        assertFalse(failure.appliesTo(101L, null, "UA"), "A fresh Retry must reattempt even with no cookie")
        assertFalse(failure.appliesTo(50L, "new-clearance", "UA"))
        assertFalse(failure.appliesTo(50L, null, "Other UA"))
        assertEquals(100L, failure.completedAtNanos, "Sharing must not extend a rejected batch")
    }
}
