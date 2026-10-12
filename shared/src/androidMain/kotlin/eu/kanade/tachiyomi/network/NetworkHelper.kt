package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebSettings
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.FailureLoggingInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Vendored from Mihon (core/common). Metro DI and preferences are replaced with plain
 * state; the FQCN and public surface used by extensions (`client`, `cloudflareClient`,
 * `defaultUserAgentProvider`) are unchanged.
 */
class NetworkHelper(private val context: Context) {

    val cookieJar = AndroidCookieJar()

    /**
     * The UA that OkHttp sends and that the Cloudflare challenge WebView is forced to adopt.
     *
     * Cloudflare fingerprints the UA against the client that actually solved the JS challenge:
     * a `cf_clearance` cookie minted for one UA is rejected (403) when later requests claim a
     * different one. A hardcoded, invented Chrome version (e.g. one ahead of what any real
     * WebView on the device reports) is the classic cause of "solve the challenge, then still
     * get 403" — which is exactly how Tachiyomi/Mihon behaves differently: it derives its
     * default UA from the device's real WebView so the OkHttp request and the solver WebView
     * present the *same, real* browser identity.
     *
     * This stays a static string at construction. `NetworkHelper` is built synchronously on the
     * main thread during startup (MainActivity.onCreate → warmTabState → MangaBackend), and
     * `WebSettings.getDefaultUserAgent()` forces WebView provider init, which blocks/ANRs there
     * (observed: main thread BLOCKED in `getDefaultUserAgent`, app stuck on the loading screen).
     * The real device UA is resolved lazily in [webViewUserAgent] off that path instead.
     */
    var defaultUserAgent: String = DEFAULT_USER_AGENT

    /**
     * The device's real WebView UA, cleaned of in-app WebView tokens so it reads as plain mobile
     * Chrome. Resolved lazily on first *use* (i.e. the first source request, on an OkHttp
     * dispatcher thread) — never during construction — so WebView provider init cannot block app
     * startup. Falls back to the static default if the platform refuses or returns no Chrome UA.
     */
    private val webViewUserAgent: String by lazy {
        runCatching {
            WebSettings.getDefaultUserAgent(context)
                // `; wv` and `Version/4.0` mark the string as an in-app WebView; strip them so it
                // looks like standalone Chrome, which is what sites (and Cloudflare) expect.
                .replace("; wv", "")
                .replace(Regex("Version/[0-9.]+ "), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.contains("Chrome/") }
        }.getOrNull() ?: DEFAULT_USER_AGENT
    }

    private val clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        // Fixes "Trust anchor for certification path not found" for sites that serve an
        // incomplete chain, by completing it via AIA while still anchoring to system roots.
        .sslSocketFactory(TlsHelper.sslSocketFactory, TlsHelper.trustManager)
        .connectTimeout(30.seconds)
        .readTimeout(30.seconds)
        .callTimeout(2.minutes)
        .cache(
            Cache(
                directory = File(context.cacheDir, "network_cache"),
                maxSize = 5L * 1024 * 1024, // 5 MiB
            ),
        )
        .addInterceptor(FailureLoggingInterceptor())
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

    val client: OkHttpClient = clientBuilder
        .addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
        )
        .build()

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    /**
     * Prefers an explicit override of [defaultUserAgent]; otherwise the lazily-resolved real
     * device WebView UA. Reading [webViewUserAgent] here (not at construction) is what keeps
     * WebView init off the startup main thread.
     */
    fun defaultUserAgentProvider(): String {
        val override = defaultUserAgent.trim()
        return if (override.isNotEmpty() && override != DEFAULT_USER_AGENT) override else webViewUserAgent
    }

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
