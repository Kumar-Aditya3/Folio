package com.folio.reader.manga

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioField
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.FontTheme
import com.folio.reader.ui.theme.folioRoomScrim
import eu.kanade.tachiyomi.util.system.setUserAgent

/**
 * The browser the reader can touch.
 *
 * Cloudflare's interactive challenges — a Turnstile checkbox, a slider, an image
 * puzzle — cannot be cleared by the headless WebView inside `CloudflareInterceptor`;
 * they need a real window and a real tap. This is that window: it loads the exact
 * URL that was blocked, with the *same* user agent the blocked request used (a
 * clearance cookie issued to a different UA is refused), and lets the site do its
 * thing.
 *
 * Nothing has to be handed back. `AndroidCookieJar` reads the system
 * `CookieManager`, which is the store this WebView writes to, so `cf_clearance`
 * lands where OkHttp already looks. The source's explicit Retry action verifies
 * access; neither cookies nor closing this screen announce a successful load.
 */
class ChallengeWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val palette = AppPalette.byId(intent.getStringExtra(EXTRA_PALETTE).orEmpty())
        val fontTheme = FontTheme.byId(intent.getStringExtra(EXTRA_FONT_THEME).orEmpty())
        val host = MangaChallenges.hostOf(url) ?: url

        // Back walks the site first — a challenge often redirects a step or two — and
        // only leaves once there is nothing left to go back to.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = webView
                    if (view != null && view.canGoBack()) view.goBack() else close()
                }
            },
        )

        setContent {
            FolioTheme.AppTheme(palette = palette, fontTheme = fontTheme) {
                // The room scrim, after the field and before the content, exactly as at the app
                // root — this activity is its own composition root, so it would otherwise be the
                // one Android surface in the app without the shade. It carries bare status type
                // (the browser's access status) straight on the field, which is
                // precisely the text the scrim exists for.
                Box(modifier = Modifier.fillMaxSize().folioField().folioRoomScrim()) {
                    ChallengeContent(
                        url = url,
                        host = host,
                        userAgent = userAgent,
                        onWebViewCreated = { webView = it },
                        onClose = { close() },
                    )
                }
            }
        }
    }

    /**
     * Leaves the challenge and lets the caller try again. The cookie flush is the
     * important part: without it a clearance cookie can still be sitting in the
     * WebView's write-behind buffer when OkHttp asks for it.
     */
    private fun close() {
        CookieManager.getInstance().flush()
        // Releases a source request that opened this window and is waiting to retry.
        MangaChallenges.closed()
        finish()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            CookieManager.getInstance().flush()
            destroy()
        }
        webView = null
        // Covers the paths that never reach close(): a back gesture finishing the activity,
        // or the task being removed. A configuration change is not a close — the window is
        // coming straight back, and releasing the waiter would retry into an open one.
        if (!isChangingConfigurations) MangaChallenges.closed()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "challenge.url"
        private const val EXTRA_USER_AGENT = "challenge.ua"
        private const val EXTRA_PALETTE = "challenge.palette"
        private const val EXTRA_FONT_THEME = "challenge.font"

        fun intent(
            context: Context,
            challenge: MangaChallenge,
            paletteId: String,
            fontThemeId: String,
        ): Intent = Intent(context, ChallengeWebViewActivity::class.java)
            .putExtra(EXTRA_URL, challenge.url)
            .putExtra(EXTRA_USER_AGENT, challenge.userAgent)
            .putExtra(EXTRA_PALETTE, paletteId)
            .putExtra(EXTRA_FONT_THEME, fontThemeId)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChallengeContent(
    url: String,
    host: String,
    userAgent: String?,
    onWebViewCreated: (WebView?) -> Unit,
    onClose: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var browserMessage by remember {
        mutableStateOf("Access may remain blocked here. Close this view, then retry the source to verify access.")
    }
    var navigation by remember { mutableStateOf(0) }
    var webViewFailed by remember { mutableStateOf(false) }
    // Whether the page the WebView is currently on refused at the HTTP level. Reset on every
    // navigation; see the WebViewClient below for why this and not a cookie is the verdict.
    var mainFrameFailed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Exempt from the overlay masthead every other screen uses: the body is an Android
        // WebView, which dispatches no Compose nested-scroll events (that would need the
        // AndroidX-only nestedScrollInterop), so `collapse` could never advance and the
        // glass would be permanently off. Stacked chrome is the honest layout here.
        FolioTopBar(
            title = host,
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            },
        )
        Text(
            text = browserMessage,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
        )
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (webViewFailed) {
            return@Column
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(this, true)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    // Sets the matching client-hint metadata as well as the string. A WebView
                    // that claims one browser in `navigator.userAgent` while `Sec-CH-UA` still
                    // advertises the real one is exactly the mismatch Cloudflare refuses, so
                    // the human would solve the challenge and be turned away anyway.
                    if (!userAgent.isNullOrBlank()) setUserAgent(userAgent)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            // A new navigation starts with no verdict; the previous page's
                            // error must not vouch for this one.
                            mainFrameFailed = false
                            loading = true
                            navigation++
                            browserMessage = "Loading browser view. Source access has not been verified."
                        }

                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            loading = false
                            if (mainFrameFailed) {
                                return
                            }
                            val finishedNavigation = navigation
                            browserMessage = "Browser page loaded. Close this view and retry the source to verify access."
                            view.evaluateJavascript(
                                "(document.title||'')+' '+((document.body&&document.body.innerText)||'')",
                            ) { raw ->
                                if (finishedNavigation != navigation || mainFrameFailed) return@evaluateJavascript
                                val text = raw.orEmpty().lowercase()
                                if (BLOCK_MARKERS.any { text.contains(it) }) {
                                    browserMessage = "The site is still refusing access in this browser. This may be a site-side or network restriction."
                                }
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            // Subresource failures are noise — a blocked tracker, a slow image.
                            // Only the page itself failing means the site is still refusing.
                            if (request.isForMainFrame) {
                                mainFrameFailed = true
                                loading = false
                                browserMessage = "The site refused this browser request (HTTP ${errorResponse.statusCode}). Access has not been cleared."
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                mainFrameFailed = true
                                loading = false
                                browserMessage = "Browser connection failed (${error.errorCode}). Source access has not been verified."
                            }
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            mainFrameFailed = true
                            loading = false
                            browserMessage = "The site's TLS certificate could not be validated. The connection was stopped."
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            mainFrameFailed = true
                            loading = false
                            webViewFailed = true
                            browserMessage = "Browser renderer stopped. Close this view; source access has not been verified."
                            onWebViewCreated(null)
                            view.destroy()
                            return true
                        }
                    }
                    onWebViewCreated(this)
                    loadUrl(url)
                }
            },
        )
    }
}

/**
 * Phrases that mean the document on screen is a refusal, not the site.
 *
 * These are the interstitials the walls actually serve: Cloudflare's "Just a moment…" and
 * "Attention Required!", the origin's bare nginx page, and the usual WAF wording. Matching on
 * rendered text is deliberate — the WebView does not report every one of these through
 * `onReceivedHttpError`, so the status code alone cannot be trusted.
 */
private val BLOCK_MARKERS = listOf(
    "just a moment",
    "attention required",
    "403 forbidden",
    "access denied",
    "you have been blocked",
    "enable javascript and cookies",
    "checking your browser",
)
