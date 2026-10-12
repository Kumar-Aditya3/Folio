package eu.kanade.tachiyomi.network.interceptor

import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat

/**
 * Logs only *failed* source requests to logcat under the `FolioManga` tag with a `FolioNet`
 * marker.
 *
 * Release builds strip the extensions' own debug logging and the host catches network
 * failures as in-app UI states, so a 403, a TLS "trust anchor" failure, or an empty-screen
 * parse never reach logcat — which makes field diagnosis of a specific source impossible.
 * This interceptor is failure-only (non-2xx responses and thrown exceptions), so it adds no
 * noise on the happy path while making the exact URL + status + exception greppable with
 * `adb logcat | findstr FolioNet`.
 *
 * Placed near the top of the chain so it observes the final outcome after Cloudflare and
 * retry handling, but still rethrows unchanged.
 */
class FailureLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "FolioNet ${request.method} ${request.url} failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            throw e
        }
        if (!response.isSuccessful) {
            val server = response.header("Server")
            val mitigated = response.header("cf-mitigated")
            logcat(LogPriority.WARN) {
                buildString {
                    append("FolioNet ")
                    append(response.code)
                    append(' ')
                    append(request.method)
                    append(' ')
                    append(request.url)
                    if (server != null) append(" server=$server")
                    if (mitigated != null) append(" cf-mitigated=$mitigated")
                }
            }
        }
        return response
    }
}
