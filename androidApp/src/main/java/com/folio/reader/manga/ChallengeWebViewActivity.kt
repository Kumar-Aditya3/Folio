package com.folio.reader.manga

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
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
 * lands where OkHttp already looks. Closing the screen clears the pending
 * challenge, which is the signal the browse screen retries on.
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
                Box(modifier = Modifier.fillMaxSize().folioField()) {
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
        MangaChallenges.clear()
        finish()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
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
    onWebViewCreated: (WebView) -> Unit,
    onClose: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var solved by remember { mutableStateOf(false) }

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
            text = if (solved) {
                "Check cleared — close this to carry on."
            } else {
                "Complete the site's check. Closing this screen retries the source."
            },
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
        )
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                        if (!userAgent.isNullOrBlank()) userAgentString = userAgent
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            loading = false
                            if (hasClearance(finishedUrl)) {
                                solved = true
                                // The interceptor's success path clears this too, but
                                // doing it here means the browse screen reloads the
                                // moment the site relents.
                                CookieManager.getInstance().flush()
                                MangaChallenges.clear()
                            }
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
 * True once the site has issued a Cloudflare clearance cookie for [url] — the one
 * thing the blocked request was missing.
 */
private fun hasClearance(url: String): Boolean {
    val raw = CookieManager.getInstance().getCookie(url) ?: return false
    return raw.split(';').any { it.trim().startsWith("cf_clearance=") }
}
