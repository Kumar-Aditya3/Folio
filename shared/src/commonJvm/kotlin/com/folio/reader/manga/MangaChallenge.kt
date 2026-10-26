package com.folio.reader.manga

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * A source whose access check or HTTP refusal can be inspected in a browser.
 *
 * [url] is a browser-safe GET URL: the request's URL for GET, the host root for POST.
 * Opening it does not establish that the source can load.
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
    val reason: MangaAccessReason = MangaAccessReason.CLOUDFLARE_CHALLENGE,
    val generation: Long = 0L,
    val sourceId: Long? = null,
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
 * Android's `AndroidCookieJar` is backed by the system
 * `CookieManager`, which is the same store the WebView writes `cf_clearance` into,
 * so a site-issued session is available to the next request without handover.
 * A cookie is not proof that the origin will accept that request.
 *
 * Desktop registers no solver (JCEF hosts no source browsing), so the prompt never
 * appears there.
 */
object MangaChallenges {

    /**
     * The message the network layer raises when a bot check blocked a request. The
     * browse screens show it verbatim next to the button that opens the WebView.
     */
    const val BLOCKED_MESSAGE = "Cloudflare's site check could not be completed. Access may remain blocked in browser view."

    private val _pending = MutableStateFlow<MangaChallenge?>(null)
    private val _bySource = MutableStateFlow<Map<Long, MangaChallenge>>(emptyMap())
    /** All blocked sources survive a global search; one source cannot overwrite another. */
    val bySource: StateFlow<Map<Long, MangaChallenge>> = _bySource
    fun forSource(sourceId: Long): MangaChallenge? = _bySource.value[sourceId]
    private val generation = AtomicLong()

    /** The block waiting on the reader, or null when nothing is blocked. */
    val pending: StateFlow<MangaChallenge?> = _pending

    /** Set once by the platform host; opens an in-app WebView on the challenge. */
    var solver: ((MangaChallenge) -> Unit)? = null

    /**
     * Set by the platform host; false while the app cannot put a window on screen.
     *
     * Prevents manual browser actions from trying to launch while Android is backgrounded.
     */
    var canShowWindow: (() -> Boolean)? = null

    /** True when this platform can actually show a challenge. */
    val canSolve: Boolean get() = solver != null

    /**
     * Called by the network layer when a request came back as a bot check.
     *
     * Records the notice without opening a window or waiting for a user action.
     */
    @Synchronized
    fun record(
        url: String,
        userAgent: String? = null,
        reason: MangaAccessReason = MangaAccessReason.CLOUDFLARE_CHALLENGE,
        sourceId: Long? = null,
    ): MangaChallenge? {
        val host = hostOf(url) ?: return null
        val challenge = MangaChallenge(url = url, host = host, userAgent = userAgent,
            reason = reason, generation = generation.incrementAndGet(), sourceId = sourceId)
        if (sourceId != null) _bySource.value = _bySource.value + (sourceId to challenge)
        _pending.value = challenge
        return challenge
    }

    /** Called after a source request to [host] has loaded and parsed successfully. */
    @Synchronized
    fun clearedFor(host: String?, expected: MangaChallenge? = _pending.value) {
        if (host != null && expected?.host == host) {
            clear(expected)
        }
    }

    @Synchronized
    fun clearedForSource(sourceId: Long, expected: MangaChallenge? = forSource(sourceId)) {
        if (expected?.sourceId == sourceId) clear(expected)
    }

    @Synchronized
    fun clear() {
        _bySource.value = emptyMap()
        _pending.value = null
    }

    @Synchronized
    fun clear(expected: MangaChallenge?) {
        val sourceId = expected?.sourceId
        if (sourceId != null && _bySource.value[sourceId] == expected) {
            _bySource.value = _bySource.value - sourceId
        }
        _pending.compareAndSet(expected, _bySource.value.values.lastOrNull())
    }

    /** Hands [challenge] to the platform solver. No-op where none is registered. */
    fun solve(challenge: MangaChallenge? = _pending.value) {
        val target = challenge ?: return
        if (canShowWindow?.invoke() == false) return
        solver?.invoke(target)
    }

    /** Browser closure is not proof of access; only an explicit source retry clears a check. */
    fun closed() = Unit

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(mangaBrowserUrl("POST", url)).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
