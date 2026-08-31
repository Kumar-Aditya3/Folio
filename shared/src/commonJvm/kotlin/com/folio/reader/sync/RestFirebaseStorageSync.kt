package com.folio.reader.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.min

class RestFirebaseStorageSync(
    private val projectId: String,
    private val apiKey: String,
    private val bucket: String,
    private val uidOverride: String? = null,
    private val accountEmail: String? = null,
    private val accountPassword: String? = null,
    private val maxRetries: Int = 5,
    internal val identityToolkitBaseUrl: String = "https://identitytoolkit.googleapis.com/v1"
) : StorageSync {

    private val json = Json { ignoreUnknownKeys = true }
    private val storageBaseUrl = "https://firebasestorage.googleapis.com/v0/b/$bucket/o"

    @Volatile
    private var idToken: String? = null

    @Volatile
    private var refreshToken: String? = null

    @Volatile
    private var idTokenExpiresAtMs: Long = 0L

    @Volatile
    override var uid: String = uidOverride ?: ""
        private set

    fun authenticate() {
        if (uidOverride != null) {
            uid = uidOverride
            return
        }
        if (hasValidToken()) return
        // Id tokens expire after ~1h; long-running sessions must refresh or every
        // request starts failing with 401.
        if (refreshToken != null && tryRefreshToken()) return

        val email = accountEmail?.takeIf { it.isNotBlank() }
        val password = accountPassword?.takeIf { it.isNotBlank() }

        val response = if (email != null && password != null) {
            runCatching {
                identityToolkit("accounts:signInWithPassword", credentialsBody(email, password))
            }.getOrElse { error1 ->
                runCatching {
                    identityToolkit("accounts:signUp", credentialsBody(email, password))
                }.getOrElse { error2 ->
                    // No anonymous fallback: silently adopting a fresh anonymous
                    // identity signed the reader into an empty account they did
                    // not ask for, making uploads land in the wrong place.
                    authFailure(error1.message ?: error2.message, email)
                    return
                }
            }
        } else {
            // No shared fallback account compiled into the binary: an install
            // without credentials gets its own anonymous identity. Cross-device
            // sync requires entering the same account in Settings -> Advanced.
            runCatching {
                identityToolkit("accounts:signUp", anonymousSignUpBody)
            }.getOrElse { error ->
                authFailure(error.message, null)
                return
            }
        }

        adoptAuthTokens(response)
    }

    private val anonymousSignUpBody = "{\"returnSecureToken\":true}"

    private fun hasValidToken(): Boolean =
        idToken != null && uid.isNotEmpty() &&
                Clock.System.now().toEpochMilliseconds() < idTokenExpiresAtMs - TOKEN_REFRESH_MARGIN_MS

    private fun tryRefreshToken(): Boolean {
        val current = refreshToken ?: return false
        val response = runCatching {
            httpJson(
                url = "https://securetoken.googleapis.com/v1/token?key=$apiKey",
                method = "POST",
                body = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(current, "UTF-8")}",
                authHeader = null,
                contentType = "application/x-www-form-urlencoded"
            )
        }.getOrNull() ?: return false
        val obj = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return false
        val newIdToken = obj["id_token"]?.jsonPrimitive?.content ?: return false
        idToken = newIdToken
        obj["refresh_token"]?.jsonPrimitive?.content?.let { refreshToken = it }
        obj["user_id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let { uid = it }
        idTokenExpiresAtMs = Clock.System.now().toEpochMilliseconds() + expiresInMs(obj["expires_in"]?.jsonPrimitive?.content)
        return true
    }

    private fun credentialsBody(email: String, password: String): String = buildString {
        append("{\"email\":\"")
        append(storageJsonEscape(email))
        append("\",\"password\":\"")
        append(storageJsonEscape(password))
        append("\",\"returnSecureToken\":true}")
    }

    private fun identityToolkit(endpoint: String, body: String): String = httpJson(
        url = "$identityToolkitBaseUrl/$endpoint?key=$apiKey",
        method = "POST",
        body = body,
        authHeader = null
    )

    private fun adoptAuthTokens(response: String) {
        val obj = json.parseToJsonElement(response).jsonObject
        idToken = obj["idToken"]?.jsonPrimitive?.content
        refreshToken = obj["refreshToken"]?.jsonPrimitive?.content
        uid = obj["localId"]?.jsonPrimitive?.content ?: ""
        if (uid.isEmpty()) {
            uid = "default_user"
        }
        idTokenExpiresAtMs = Clock.System.now().toEpochMilliseconds() + expiresInMs(obj["expiresIn"]?.jsonPrimitive?.content)
    }

    private fun expiresInMs(raw: String?): Long = (raw?.toLongOrNull() ?: 3600L) * 1000L

    private fun authFailure(rawMsg: String?, email: String?) {
        val msg = rawMsg ?: ""
        if (msg.contains("OPERATION_NOT_ALLOWED")) {
            idToken = null
            refreshToken = null
            idTokenExpiresAtMs = 0L
            uid = email ?: "default_user"
        } else if (msg.contains("INVALID_PASSWORD") || msg.contains("EMAIL_NOT_FOUND") ||
            msg.contains("EMAIL_EXISTS") || msg.contains("INVALID_EMAIL") ||
            msg.contains("INVALID_LOGIN_CREDENTIALS")
        ) {
            throw IOException("Cloud storage sign-in failed — check the email and password in Settings -> Advanced.")
        } else {
            throw IOException("Firebase Storage Auth failed: ${msg.take(120)}")
        }
    }

    private fun ensureAuth() {
        if (uidOverride == null && !hasValidToken()) authenticate()
    }

    private fun bearer(): String? = idToken?.let { "Bearer $it" }

    private fun bookPath(uid: String, bookId: String): String =
        "users/$uid/books/$bookId/book.epub"

    private fun coverPath(uid: String, bookId: String): String =
        "users/$uid/books/$bookId/cover.jpg"

    override suspend fun downloadBook(
        uid: String,
        bookId: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)?
    ) = withRetry("downloadBook($bookId)") {
        downloadFile(bookPath(uid, bookId), destinationPath, onProgress, verifySha256 = true)
    }

    override suspend fun deleteBook(uid: String, bookId: String) {
        ensureAuth()
        val paths = listOf(bookPath(uid, bookId), coverPath(uid, bookId))
        for (path in paths) {
            runCatching {
                val encoded = URLEncoder.encode(path, "UTF-8")
                httpRequest("$storageBaseUrl/$encoded", "DELETE", null, bearer(), readTimeoutMs = 30_000)
            }
        }
    }

    private suspend fun downloadFile(
        objectPath: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)?,
        verifySha256: Boolean
    ) = withContext(Dispatchers.IO) {
        ensureAuth()
        val encoded = URLEncoder.encode(objectPath, "UTF-8")
        val alt = URLEncoder.encode("media", "UTF-8")
        val tokenParam = idToken?.let { "&access_token=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val url = "$storageBaseUrl/$encoded?alt=$alt$tokenParam"

        val destFile = File(destinationPath)
        destFile.parentFile?.mkdirs()

        // GCS object metadata carries a base64 MD5; verify against that exact
        // value. (An earlier revision compared a SHA-256 hex digest to the MD5,
        // which could never match and deleted every finished download.)
        val md5Digest = if (verifySha256) MessageDigest.getInstance("MD5") else null
        var downloadedBytes = 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            doInput = true
            bearer()?.let { setRequestProperty("Authorization", it) }
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
                throw IOException("HTTP $code downloading $objectPath: ${err.take(500)}")
            }
            val contentLength = conn.contentLengthLong.takeIf { it > 0 }
            val buffer = ByteArray(256 * 1024)
            onProgress?.invoke(0f)

            FileOutputStream(destFile).use { fos ->
                conn.inputStream.use { input ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead <= 0) break
                        fos.write(buffer, 0, bytesRead)
                        md5Digest?.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (contentLength != null && contentLength > 0) {
                            val progress = downloadedBytes.toFloat() / contentLength.toFloat()
                            onProgress?.invoke(progress.coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress?.invoke(1f)

            if (verifySha256 && md5Digest != null) {
                val computedMd5 = md5Digest.digest()
                val metadataMd5 = runCatching { getObjectMd5Base64(objectPath) }.getOrNull()
                if (metadataMd5 != null && !md5Matches(computedMd5, metadataMd5)) {
                    destFile.delete()
                    throw IOException("Checksum mismatch for $objectPath")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getObjectMd5Base64(objectPath: String): String? {
        val encoded = URLEncoder.encode(objectPath, "UTF-8")
        val response = runCatching {
            httpRequest("$storageBaseUrl/$encoded", "GET", null, bearer(), readTimeoutMs = 15_000)
        }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return null
        return obj["md5Hash"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun <T> withRetry(opName: String, block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt >= maxRetries) break
                val backoffMs = (1000L * (1L shl min(attempt, 6))).coerceAtMost(30_000L)
                delay(backoffMs)
            }
        }
        throw lastException ?: IOException("$opName failed after retries")
    }

    private fun httpJson(url: String, method: String, body: String?, authHeader: String?, contentType: String = "application/json"): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            doInput = true
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
            authHeader?.let { setRequestProperty("Authorization", it) }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code from $method ${url.substringBefore('?')}: ${text.take(500)}")
            }
            return text.ifEmpty { "{}" }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpRequest(
        url: String,
        method: String,
        body: ByteArray?,
        authHeader: String?,
        readTimeoutMs: Int = 30_000
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            doInput = true
            if (body != null) {
                doOutput = true
            }
            authHeader?.let { setRequestProperty("Authorization", it) }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code from $method ${url.substringBefore('?')}: ${text.take(500)}")
            }
            return text.ifEmpty { "{}" }
        } finally {
            conn.disconnect()
        }
    }

}

/** True when the streamed MD5 equals the object metadata's base64 md5Hash. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun md5Matches(computedMd5: ByteArray, metadataMd5Base64: String): Boolean =
    kotlin.io.encoding.Base64.encode(computedMd5) == metadataMd5Base64.trim()

private fun storageJsonEscape(raw: String): String =
    buildString {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
