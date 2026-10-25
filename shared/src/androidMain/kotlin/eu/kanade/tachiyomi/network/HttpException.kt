package eu.kanade.tachiyomi.network

import okhttp3.Response
import okhttp3.Request
import com.folio.reader.manga.isCloudflareChallenge

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @see Response.isSuccessful
 * @since tachiyomix 1.6
 * @param code [Int] the HTTP status code
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code") {
    internal var failedRequest: Request? = null
        private set
    internal var cloudflareChallenge = false
        private set

    /** Keep the public Int constructor used by compiled extensions unchanged. */
    internal constructor(response: Response) : this(response.code) {
        failedRequest = response.request
        cloudflareChallenge = isCloudflareChallenge(
            response.code, response.header("Server"), response.header("cf-mitigated"),
        ) { response.peekBody(512L * 1024).string() }
    }
}
