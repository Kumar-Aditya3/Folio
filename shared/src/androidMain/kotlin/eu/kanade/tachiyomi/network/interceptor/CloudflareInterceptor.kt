package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.folio.reader.manga.MangaAccessReason
import com.folio.reader.manga.MangaSiteAccessException
import com.folio.reader.manga.MangaChallengeFailure
import com.folio.reader.manga.isCloudflareChallenge
import com.folio.reader.manga.mangaBrowserUrl
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    /**
     * One lock per host, held while a challenge is being solved.
     *
     * Every request that arrives with a challenge response used to open its own WebView and
     * clear `cf_clearance` first. A source load is rarely one request — a details page, a cover
     * and a page list all leave at once — so the requests raced: each wiped the clearance the
     * previous one had just earned and started another solve. Under a site that only tolerates
     * a handful of solves, most of those lose, and the source fails with 403 even though the
     * challenge was solvable. Serialising per host means one WebView solves it and the others
     * ride the resulting cookie.
     */
    private class HostChallengeState {
        val lock = ReentrantReadWriteLock()
        var failure: MangaChallengeFailure? = null
    }

    private class RequestStart(val atNanos: Long, val clearance: String?)

    private val hosts = ConcurrentHashMap<String, HostChallengeState>()

    override fun prepareRequest(request: Request): Request = request.newBuilder()
        .tag(RequestStart::class.java, RequestStart(System.nanoTime(), clearance(request.url))).build()

    override fun shouldIntercept(response: Response): Boolean = isCloudflareChallenge(
        response.code, response.header("Server"), response.header("cf-mitigated"),
    ) { response.peekBody(512L * 1024).string() }

    /**
     * Field trace that survives R8.
     *
     * `logcat` is the app's diagnostic channel everywhere else, but in a minified release the
     * optimiser is free to drop those calls, and it did: a 403 on this exact path produced no
     * `FolioManga` line at all, which left "the source reports 403" with nothing behind it.
     * Appending to a file in `cacheDir` is a side effect the optimiser cannot elide, and
     * `adb exec-out run-as` or a debug pull can read it back.
     */
    private fun trace(message: String) {
        runCatching {
            val file = java.io.File(context.cacheDir, "net-trace.log")
            if (file.length() > 256L * 1024) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        // Read the clearance *before* anything clears it, so "did the WebView earn a new one"
        // is a real comparison. The old order removed the cookie first and then looked it up,
        // which could only ever answer null — any cookie at all then counted as success,
        // including one the site had already rejected.
        val requestStart = request.tag(RequestStart::class.java)
        val previousClearance = requestStart?.clearance
        val state = hosts.computeIfAbsent(request.url.host) { HostChallengeState() }
        val userAgent = request.header("User-Agent")
        val startedAt = requestStart?.atNanos ?: System.nanoTime()
        trace("engage ${request.method} ${request.url.host} code=${response.code} hasClearance=${previousClearance != null}")

        return state.lock.write {
            response.close()
            state.failure?.takeIf { it.appliesTo(startedAt, clearance(request.url), userAgent) }?.let { failure ->
                // Do not extend the original failure window as queued requests drain.
                // An explicit later Retry always gets a new attempt, even without cookies.
                throw MangaSiteAccessException(failure.reason, failure.statusCode,
                    browserUrl = mangaBrowserUrl(request.method, request.url.toString()), userAgent = userAgent)
            }
            try {
                if (isCleared(request.url, previousClearance)) {
                    // Another request to this host finished a solve while this one waited on
                    // the lock. Its clearance covers us; drop the stale challenge response.
                    trace("reuse clearance ${request.url.host}")
                } else {
                    cookieManager.remove(request.url, COOKIE_NAMES, 0)
                    solveInWebView(request)
                    trace("webview solve returned hasClearance=${clearance(request.url) != null}")
                }

                val retried = chain.proceed(request)
                trace("retry ${request.url.host} code=${retried.code}")

                if (shouldIntercept(retried)) {
                    val code = retried.code
                    retried.close()
                    throw CloudflareBypassException(MangaAccessReason.CLOUDFLARE_CHALLENGE, code)
                }

                // An origin refusal does not invalidate Cloudflare's cookie. Return
                // it to extension interceptors and do not call it a recovered source.
                retried
            }
            // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
            // we don't crash the entire app
            catch (e: CloudflareBypassException) {
                // A headless WebView can only clear challenges that clear themselves. A
                // Turnstile checkbox or an image puzzle needs a human, and an OkHttp
                // interceptor has no window to show one in — so publish the blocked URL
                // and let the browse UI open a WebView the reader can actually touch.
                trace("bypass failed ${request.url.host} reason=${e.reason}")
                state.failure = MangaChallengeFailure(System.nanoTime(), clearance(request.url), userAgent, e.reason, e.statusCode)
                throw MangaSiteAccessException(e.reason, e.statusCode, e,
                    browserUrl = mangaBrowserUrl(request.method, request.url.toString()), userAgent = userAgent)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                trace("intercept error ${request.url.host}: ${e.javaClass.simpleName}")
                throw IOException(e)
            }
        }
    }

    private fun clearance(url: HttpUrl): String? {
        return cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }?.value
    }

    private fun isCleared(url: HttpUrl, previousClearance: String?): Boolean {
        val current = clearance(url)
        return !current.isNullOrBlank() && current != previousClearance
    }

    private fun solveInWebView(request: Request) {
        resolveWithWebView(request, cookieManager.get(request.url).firstOrNull { it.name == "cf_clearance" })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        val challengeFound = AtomicBoolean(false)
        val cloudflareBypassed = AtomicBoolean(false)
        // Set from the JS bridge, which runs on a WebView thread rather than the main one.
        val challengeFailed = AtomicBoolean(false)
        val isRenderProcessGone = AtomicBoolean(false)
        val mainFrameStatus = AtomicInteger(0)
        val browserFailure = AtomicReference<IOException?>(null)
        val finished = AtomicBoolean(false)

        val origRequestUrl = originalRequest.url.toString()
        // A WebView can only load a page with GET. Replaying a POST URL as a GET lands on an
        // endpoint that answers the challenge with an error instead of the interstitial, so
        // `challengeFound` never flips and the bypass gives up. batcave.biz fetches its popular
        // list, search and page list over POST, so this is not a corner case. The clearance
        // covers the whole host, so earn it on the site's root instead.
        val challengeUrl = mangaBrowserUrl(originalRequest.method, originalRequest.url.toString())
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            if (finished.get()) return@execute
            try {
            webview = createWebView(originalRequest)

            webview.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun interactiveDetected() {
                        // The challenge cannot be solved non-interactively, abort.
                        latch.countDown()
                    }

                    @Suppress("unused")
                    @JavascriptInterface
                    fun challengeFailed() {
                        // The challenge ran and refused us. Waiting out the remaining 30 s
                        // would only delay the prompt the reader needs.
                        challengeFailed.set(true)
                        latch.countDown()
                    }
                },
                "mihon",
            )

            webview.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    mainFrameStatus.set(0)
                    challengeFound.set(false)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        if (finished.get() || challengeFailed.get() || mainFrameStatus.get() >= 400 || browserFailure.get() != null) {
                            return false
                        }
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed.set(true)
                        latch.countDown()
                    }

                    if (url == challengeUrl) {
                        if (!challengeFound.get()) {
                            // The first request didn't return the challenge, abort.
                            latch.countDown()
                        } else {
                            // Listen for an interactiveBegin event
                            view.evaluateJavascript(
                                """
                                    addEventListener("message", ({data}) => {
                                        if (data?.source === "cloudflare-challenge") {
                                            if (data?.event === "interactiveBegin") {
                                                mihon.interactiveDetected();
                                            } else if (data?.event === "fail") {
                                                mihon.challengeFailed();
                                            }
                                        }
                                    })
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        mainFrameStatus.set(errorResponse?.statusCode ?: 0)
                        if (errorResponse?.responseHeaders?.entries?.any {
                            it.key.equals("cf-mitigated", true) && it.value.equals("challenge", true)
                        } == true) {
                            // Found the Cloudflare challenge page.
                            challengeFound.set(true)
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            latch.countDown()
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        browserFailure.set(IOException("Browser network failure (${error.errorCode})"))
                        latch.countDown()
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    browserFailure.set(IOException("Browser TLS certificate validation failed"))
                    latch.countDown()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Not handling this kills the app; failing the bypass is enough. It also
                    // has to release the latch, or the caller sits out the full 30 s timeout
                    // and then touches a WebView whose renderer no longer exists.
                    logcat(LogPriority.ERROR) {
                        "WebView renderer gone during Cloudflare bypass: crashed=${detail.didCrash()}, " +
                            "url=$origRequestUrl"
                    }
                    isRenderProcessGone.set(true)
                    browserFailure.set(IOException("Browser renderer stopped"))
                    latch.countDown()
                    return true
                }
            }

            webview.loadUrl(challengeUrl, headers)
            } catch (error: Exception) {
                browserFailure.set(IOException("Browser view could not be started", error))
                latch.countDown()
            }
        }

        try {
            latch.awaitFor30Seconds()
        } finally {
            finished.set(true)

            executor.execute {
                // Only destroy can be called on a WebView whose renderer is gone
                if (!cloudflareBypassed.get() && !isRenderProcessGone.get() && webview?.isOutdated() == true) {
                    context.toast("WebView is outdated. Please update Android System WebView.", Toast.LENGTH_LONG)
                }

                webview?.run {
                    if (!isRenderProcessGone.get()) {
                        stopLoading()
                    }
                    destroy()
                }
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        browserFailure.get()?.let { throw it }
        if (!cloudflareBypassed.get()) {
            val reason = if (mainFrameStatus.get() == 403 && !challengeFound.get()) MangaAccessReason.SITE_REFUSAL
            else MangaAccessReason.CLOUDFLARE_CHALLENGE
            throw CloudflareBypassException(reason, mainFrameStatus.get().takeIf { it > 0 })
        }
    }
}

private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException(
    val reason: MangaAccessReason = MangaAccessReason.CLOUDFLARE_CHALLENGE,
    val statusCode: Int? = null,
) : Exception()
