package com.folio.reader.manga

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A source that has stopped answering until a human clears a bot check.
 *
 * [url] is the exact request Cloudflare (or an equivalent) blocked, so the WebView
 * that opens it lands on the challenge page itself rather than the site's front
 * door.
 */
data class MangaChallenge(
    val url: String,
    val host: String,
    /**
     * The user agent the blocked request used. The WebView must present the same
     * one: Cloudflare binds `cf_clearance` to the agent it was issued to, so a
     * clearance earned under the WebView's own UA is refused for OkHttp's.
     */
    val userAgent: String? = null,
    /** Epoch millis the block was seen; the UI uses it to age the prompt out. */
    val at: Long = System.currentTimeMillis(),
)

/**
 * The bridge between "a source is blocked" and "show the reader a browser".
 *
 * The headless bypass in `CloudflareInterceptor` can only clear challenges that
 * solve themselves — a Turnstile checkbox or an image puzzle needs a real WebView
 * the user can touch, and OkHttp interceptors cannot open one. So the interceptor
 * records the block here, the browse UI offers to open it, and the platform host
 * registers the [solver] that actually shows the WebView.
 *
 * Cookies do the rest: Android's `AndroidCookieJar` is backed by the system
 * `CookieManager`, which is the same store the WebView writes `cf_clearance` into,
 * so a solved challenge is visible to the very next OkHttp call with no handover.
 *
 * Desktop registers no solver (JCEF hosts no source browsing), so the prompt never
 * appears there.
 */
object MangaChallenges {

    /**
     * The message the network layer raises when a bot check blocked a request. The
     * browse screens show it verbatim next to the button that opens the WebView.
     */
    const val BLOCKED_MESSAGE = "Blocked by a bot check — open the browser view to solve it"

    private val _pending = MutableStateFlow<MangaChallenge?>(null)

    /** The block waiting on the reader, or null when nothing is blocked. */
    val pending: StateFlow<MangaChallenge?> = _pending

    /** Set once by the platform host; opens an in-app WebView on the challenge. */
    var solver: ((MangaChallenge) -> Unit)? = null

    /** True when this platform can actually show a challenge. */
    val canSolve: Boolean get() = solver != null

    /** Called by the network layer when a request came back as a bot check. */
    fun record(url: String, userAgent: String? = null) {
        val host = hostOf(url) ?: return
        _pending.value = MangaChallenge(url = url, host = host, userAgent = userAgent)
    }

    /** Called when a request to [host] succeeded: whatever was blocking it is gone. */
    fun clearedFor(host: String?) {
        if (host == null) return
        if (_pending.value?.host == host) _pending.value = null
    }

    fun clear() {
        _pending.value = null
    }

    /** Hands [challenge] to the platform solver. No-op where none is registered. */
    fun solve(challenge: MangaChallenge? = _pending.value) {
        val target = challenge ?: return
        solver?.invoke(target)
    }

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
