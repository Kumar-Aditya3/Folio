package eu.kanade.tachiyomi.network

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * TLS trust configuration that fixes `CertPathValidatorException: Trust anchor for certification
 * path not found` for sites that serve an INCOMPLETE certificate chain (the common cause: the
 * server omits the intermediate CA certificate).
 *
 * This does NOT disable certificate validation. It still anchors to the device's system trust
 * store and verifies the full chain with PKIX. When the server-sent chain cannot be validated
 * because an intermediate is missing, it attempts to download the missing issuer certificate from
 * the leaf's Authority Information Access (AIA) "caIssuers" URL, then re-validates the completed
 * chain against the system trust anchors. If the chain still cannot be anchored to a trusted root,
 * the connection is rejected (secure default).
 */
object TlsHelper {

    private const val TAG = "FolioTls"

    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

    /** Cache of fetched intermediate certificates, keyed by their subject principal. */
    private val issuerCache = ConcurrentHashMap<X500Principal, X509Certificate>()

    private val systemTrustManager: X509TrustManager by lazy { loadSystemTrustManager() }

    private val trustAnchors: Set<TrustAnchor> by lazy {
        systemTrustManager.acceptedIssuers.map { TrustAnchor(it, null) }.toSet()
    }

    /** Trust manager used by the OkHttp client. */
    val trustManager: X509TrustManager by lazy { ChainCompletingTrustManager() }

    /** Socket factory bound to [trustManager], for `OkHttpClient.Builder.sslSocketFactory`. */
    val sslSocketFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
        context.socketFactory
    }

    private fun loadSystemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class ChainCompletingTrustManager : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            systemTrustManager.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                // Fast path: a correctly-configured server validates with the system trust store.
                systemTrustManager.checkServerTrusted(chain, authType)
            } catch (original: CertificateException) {
                // Likely an incomplete chain. Try to complete it via AIA and re-validate.
                Log.i(TAG, "System trust failed (${original.message}); attempting AIA chain completion")
                val completed = try {
                    completeChain(chain.toList())
                } catch (e: Exception) {
                    Log.i(TAG, "AIA chain completion threw: ${e.message}")
                    null
                }
                if (completed == null || !validatePkix(completed)) {
                    // Could not anchor to a trusted root; reject (do not weaken security).
                    Log.i(TAG, "AIA chain completion did not anchor to a trusted root; rejecting")
                    throw original
                }
                Log.i(TAG, "AIA chain completion succeeded (${completed.size} certs); trusting connection")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    /**
     * Walks up from the leaf, downloading missing issuer certificates via each cert's AIA
     * caIssuers URL, until the chain reaches a certificate issued by a system trust anchor
     * (or a self-signed root), or no further issuer can be found.
     */
    private fun completeChain(serverChain: List<X509Certificate>): List<X509Certificate> {
        if (serverChain.isEmpty()) return serverChain
        val chain = ArrayList(serverChain)
        var guard = 0
        while (guard++ < 6) {
            val last = chain.last()
            // Self-signed: already at a root.
            if (last.subjectX500Principal == last.issuerX500Principal) break
            // The next certificate up is a known trust anchor; the path is complete.
            if (trustAnchors.any { it.trustedCert.subjectX500Principal == last.issuerX500Principal }) break
            val issuer = resolveIssuer(last) ?: break
            if (chain.any { it.subjectX500Principal == issuer.subjectX500Principal }) break
            chain.add(issuer)
        }
        return chain
    }

    /** Returns the issuer certificate for [cert], from cache or by downloading via AIA. */
    private fun resolveIssuer(cert: X509Certificate): X509Certificate? {
        issuerCache[cert.issuerX500Principal]?.let { return it }
        for (url in caIssuerUrls(cert)) {
            val downloaded = try {
                download(url)
            } catch (e: Exception) {
                Log.d(TAG, "AIA fetch failed for $url: ${e.message}")
                continue
            }
            val match = parseCertificates(downloaded)
                .firstOrNull { it.subjectX500Principal == cert.issuerX500Principal }
            if (match != null) {
                issuerCache[match.subjectX500Principal] = match
                return match
            }
        }
        return null
    }

    /** Validates a leaf-first chain against the system trust anchors using PKIX. */
    private fun validatePkix(chain: List<X509Certificate>): Boolean {
        return try {
            val anchorCerts = trustAnchors.map { it.trustedCert }.toSet()
            val pathCerts = chain.filter { it !in anchorCerts }
            if (pathCerts.isEmpty()) return false
            val certPath = certFactory.generateCertPath(pathCerts)
            val params = PKIXParameters(trustAnchors).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(certPath, params)
            true
        } catch (e: Exception) {
            Log.d(TAG, "PKIX validation of completed chain failed: ${e.message}")
            false
        }
    }

    private fun download(url: String, redirects: Int = 0): ByteArray {
        val parsed = URL(url)
        // The app targets SDK 34, where cleartext HTTP is disabled by default, so
        // HttpURLConnection would refuse an http:// AIA endpoint. Fetch those over a raw
        // socket to bypass the platform cleartext policy. This is safe: the downloaded issuer
        // certificate is validated cryptographically by PKIX against the system trust anchors
        // before it is ever used, so a tampered response cannot forge trust.
        if (parsed.protocol.equals("http", ignoreCase = true)) {
            return downloadCleartext(parsed, redirects)
        }
        val connection = parsed.openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return connection.getInputStream().use { it.readBytes() }
    }

    /** Minimal HTTP/1.0 GET over a raw socket, following up to 3 redirects. */
    private fun downloadCleartext(url: URL, redirects: Int): ByteArray {
        val host = url.host
        val port = if (url.port != -1) url.port else 80
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 10_000
            val request = "GET $path HTTP/1.0\r\n" +
                "Host: $host\r\n" +
                "User-Agent: Folio\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val raw = socket.getInputStream().use { it.readBytes() }
            val headerEnd = indexOfHeaderEnd(raw)
            if (headerEnd < 0) return ByteArray(0)
            val headerText = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
            val status = headerText.lineSequence().firstOrNull().orEmpty()
                .split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (status in 300..399 && redirects < 3) {
                val location = headerText.lineSequence()
                    .firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                if (!location.isNullOrEmpty()) {
                    val next = if (location.startsWith("http", ignoreCase = true)) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    return download(next, redirects + 1)
                }
            }
            return raw.copyOfRange(headerEnd + 4, raw.size)
        }
    }

    /** Index of the CRLF-CRLF that separates HTTP headers from the body, or -1. */
    private fun indexOfHeaderEnd(data: ByteArray): Int {
        val cr = '\r'.code.toByte()
        val lf = '\n'.code.toByte()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == cr && data[i + 1] == lf && data[i + 2] == cr && data[i + 3] == lf) {
                return i
            }
            i++
        }
        return -1
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCertificates(bytes: ByteArray): List<X509Certificate> {
        return try {
            // Handles DER, PEM, and PKCS#7 bundles.
            certFactory.generateCertificates(ByteArrayInputStream(bytes))
                .filterIsInstance<X509Certificate>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Extracts caIssuers URLs from the Authority Information Access extension (OID 1.3.6.1.5.5.7.1.1).
     * The access-location URIs are stored as ASCII inside the extension DER, so they can be scanned
     * out directly. OCSP responder URLs may also be returned; they are harmless because fetching one
     * yields no parseable certificate and is skipped.
     */
    private fun caIssuerUrls(cert: X509Certificate): List<String> {
        val extension = cert.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return emptyList()
        val text = String(extension, Charsets.ISO_8859_1)
        val urls = ArrayList<String>()
        var index = text.indexOf("http")
        while (index >= 0) {
            var end = index
            while (end < text.length && isUrlChar(text[end])) end++
            val candidate = text.substring(index, end)
            if (candidate.length > "http://".length && candidate !in urls) {
                urls.add(candidate)
            }
            index = text.indexOf("http", end)
        }
        return urls
    }

    private fun isUrlChar(c: Char): Boolean {
        return c > ' ' && c.code < 127 && c != '"' && c != '<' && c != '>'
    }
}
