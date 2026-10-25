package com.folio.reader.manga

import java.io.IOException

enum class MangaAccessReason(val message: String) {
    CLOUDFLARE_CHALLENGE("Cloudflare's site check could not be completed. Access may remain blocked in browser view."),
    SITE_REFUSAL("The site refused access (HTTP 403). A browser view may also be blocked; repeated retries will not remove a site-side restriction."),

    /**
     * The site answered 401 rather than 403. In practice this is the tell of a JS
     * gate (DLE Guard and friends): the refusal is a decoy page that only a browser
     * which runs the site's script can get past, and the cookie it earns is what the
     * source needs. Unlike a refusal this is removable, but only by a human.
     */
    SIGN_IN_REQUIRED("The site wants a browser session (HTTP 401). Open the browser view, let the page finish loading, then retry the source to verify access."),
}

class MangaSiteAccessException(
    val reason: MangaAccessReason,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    val browserUrl: String? = null,
    val userAgent: String? = null,
) : IOException(reason.message, cause)

/** Only requests already in flight may reuse a failed hidden solve. */
internal data class MangaChallengeFailure(
    val completedAtNanos: Long,
    val clearance: String?,
    val userAgent: String?,
    val reason: MangaAccessReason,
    val statusCode: Int?,
) {
    fun appliesTo(requestStartedAtNanos: Long, currentClearance: String?, currentUserAgent: String?): Boolean =
        requestStartedAtNanos - completedAtNanos <= 0L &&
            clearance == currentClearance && userAgent == currentUserAgent
}

/** A refused HTTP response is not necessarily a JavaScript challenge. */
internal fun isCloudflareChallenge(
    statusCode: Int,
    server: String?,
    mitigated: String?,
    body: () -> String = { "" },
): Boolean {
    if (mitigated.equals("challenge", ignoreCase = true)) return true
    if (statusCode != 403 && statusCode != 503) return false
    if (server?.lowercase() !in listOf("cloudflare", "cloudflare-nginx")) return false
    val content = runCatching(body).getOrDefault("")
    return CLOUDFLARE_MARKERS.any { content.contains(it, ignoreCase = true) }
}

/** A browser cannot replay an extension's POST endpoint as a GET. */
internal fun mangaBrowserUrl(method: String, url: String): String {
    if (method == "GET") return url
    // Only the authority is needed. Do not parse extension paths with URI: an
    // otherwise valid OkHttp URL can contain characters that URI(String) rejects.
    val authorityStart = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: url.length
    return url.substring(0, authorityEnd) + "/"
}

private val CLOUDFLARE_MARKERS = listOf(
    "challenge-platform", "cf_chl_opt", "window._cf_chl", "cf-chl-", "cf-browser-verification",
)
