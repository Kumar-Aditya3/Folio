package com.folio.reader.manga

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
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

    /** How long a blocked request waits for its browser view to be closed. */
    private const val BROWSER_SOLVE_TIMEOUT_MS = 3L * 60 * 1000

    /** A host that already had a browser window this recently is not given another one. */
    private const val AUTO_SOLVE_COOLDOWN_MS = 90L * 1000

    private val _pending = MutableStateFlow<MangaChallenge?>(null)
    private val generation = AtomicLong()

    /**
     * Completes when the browser view currently on screen goes away.
     *
     * Replaced on every [record]: a close belonging to an earlier attempt must not release
     * a request that is waiting on a newer one.
     */
    @Volatile
    private var closedSignal: CompletableDeferred<Unit>? = null

    /** Hosts that already opened a window, so one wall is not re-opened on every request. */
    private val autoSolvedAt = ConcurrentHashMap<String, Long>()

    /** The block waiting on the reader, or null when nothing is blocked. */
    val pending: StateFlow<MangaChallenge?> = _pending

    /** Set once by the platform host; opens an in-app WebView on the challenge. */
    var solver: ((MangaChallenge) -> Unit)? = null

    /**
     * Set by the platform host; false while the app cannot put a window on screen.
     *
     * Android refuses activity starts from the background, and that refusal is silent, so
     * a request that waited on a window that never appeared would hang until the timeout.
     */
    var canShowWindow: (() -> Boolean)? = null

    /** True when this platform can actually show a challenge. */
    val canSolve: Boolean get() = solver != null

    /**
     * Called by the network layer when a request came back as a bot check.
     *
     * Returns the published challenge so the caller can wait for its browser view, or null
     * when the URL carries no host to key on.
     */
    fun record(
        url: String,
        userAgent: String? = null,
        reason: MangaAccessReason = MangaAccessReason.CLOUDFLARE_CHALLENGE,
        sourceId: Long? = null,
    ): MangaChallenge? {
        val host = hostOf(url) ?: return null
        val challenge = MangaChallenge(url = url, host = host, userAgent = userAgent,
            reason = reason, generation = generation.incrementAndGet(), sourceId = sourceId)
        closedSignal = CompletableDeferred()
        _pending.value = challenge
        return challenge
    }

    /** Called after a source request to [host] has loaded and parsed successfully. */
    fun clearedFor(host: String?, expected: MangaChallenge? = _pending.value) {
        if (host != null && expected?.host == host) {
            // The wall is gone, so the next block of this host may open a window again.
            autoSolvedAt.remove(host)
            _pending.compareAndSet(expected, null)
        }
    }

    fun clearedForSource(sourceId: Long, expected: MangaChallenge? = _pending.value) {
        if (expected?.sourceId == sourceId) _pending.compareAndSet(expected, null)
    }

    fun clear(expected: MangaChallenge? = _pending.value) {
        _pending.compareAndSet(expected, null)
    }

    /** Hands [challenge] to the platform solver. No-op where none is registered. */
    fun solve(challenge: MangaChallenge? = _pending.value) {
        val target = challenge ?: return
        solver?.invoke(target)
    }

    /**
     * Opens the browser view for [challenge] and suspends until it closes.
     *
     * This is the only way past a wall whose refusal is a script: the page has to run in a
     * real browser, and the cookie it earns is what the next request needs. Returns false
     * when the platform has no window to show, when this host already had one this
     * recently, or when the reader kept it open past [timeoutMs] — in each case the caller
     * reports the original failure instead of retrying into the same wall.
     */
    suspend fun awaitBrowserSolve(
        challenge: MangaChallenge,
        timeoutMs: Long = BROWSER_SOLVE_TIMEOUT_MS,
    ): Boolean {
        if (!canSolve) return false
        if (canShowWindow?.invoke() == false) return false
        val now = System.currentTimeMillis()
        val previous = autoSolvedAt[challenge.host]
        if (previous != null && now - previous < AUTO_SOLVE_COOLDOWN_MS) return false
        autoSolvedAt[challenge.host] = now
        val signal = closedSignal ?: return false
        solve(challenge)
        return withTimeoutOrNull(timeoutMs) { signal.await() } != null
    }

    /** Called by the platform host once the browser view is gone. */
    fun closed() {
        closedSignal?.complete(Unit)
    }

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(mangaBrowserUrl("POST", url)).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
