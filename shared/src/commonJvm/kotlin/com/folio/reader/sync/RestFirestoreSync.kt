package com.folio.reader.sync

import com.folio.reader.firebase.FsBook
import com.folio.reader.firebase.FsBookmark
import com.folio.reader.firebase.FsCollection
import com.folio.reader.firebase.FsHighlight
import com.folio.reader.firebase.FsNote
import com.folio.reader.firebase.FsQuote
import com.folio.reader.firebase.FsReadingPosition
import com.folio.reader.firebase.FsReadingSession
import com.folio.reader.firebase.FsRevisitItem
import com.folio.reader.firebase.FsSeries
import com.folio.reader.firebase.FsSettings
import com.folio.reader.firebase.FsTag
import com.folio.reader.firebase.FirestorePaths
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * FirestoreSync implementation over the Firestore REST v1 API using only
 * HttpURLConnection - works on both Android and Desktop JVM with zero extra
 * dependencies and no Firebase SDK.
 *
 * Documents store the serialized Fs* DTO as a single "payload" string field
 * plus an "updatedAt" numeric field used for last-write-wins conflict checks.
 *
 * Auth uses Firebase Anonymous Auth via the Identity Toolkit REST API, which
 * only needs the Web API key (no google-services.json required). Pass a
 * [uidOverride] to skip auth entirely (e.g. against the Firestore emulator).
 */
class RestFirestoreSync(
    private val projectId: String,
    private val apiKey: String,
    private val uidOverride: String? = null,
    private val accountEmail: String? = null,
    private val accountPassword: String? = null
) : FirestoreSync {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val baseUrl = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    @Volatile
    private var idToken: String? = null

    @Volatile
    private var refreshToken: String? = null

    @Volatile
    private var idTokenExpiresAtMs: Long = 0L

    @Volatile
    var uid: String = uidOverride ?: ""
        private set

    override fun isConnected(): Boolean {
        if (apiKey.isBlank() || projectId.isBlank()) return false
        return runCatching {
            authenticate()
            uid.isNotEmpty()
        }.getOrDefault(false)
    }

    override fun authenticate() {
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
                // 1. Try Email/Password sign in
                identityToolkit("accounts:signInWithPassword", credentialsBody(email, password))
            }.getOrElse { error1 ->
                runCatching {
                    // 2. Try Email/Password sign up
                    identityToolkit("accounts:signUp", credentialsBody(email, password))
                }.getOrElse { error2 ->
                    runCatching {
                        // 3. Try Anonymous Auth fallback
                        identityToolkit("accounts:signUp", anonymousSignUpBody)
                    }.getOrElse { error3 ->
                        authFailure(error2.message ?: error1.message ?: error3.message, email)
                        return
                    }
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
        append(jsonEscape(email))
        append("\",\"password\":\"")
        append(jsonEscape(password))
        append("\",\"returnSecureToken\":true}")
    }

    private fun identityToolkit(endpoint: String, body: String): String = httpJson(
        url = "https://identitytoolkit.googleapis.com/v1/$endpoint?key=$apiKey",
        method = "POST",
        body = body,
        authHeader = null
    )

    private fun adoptAuthTokens(response: String) {
        val obj = json.parseToJsonElement(response).jsonObject
        idToken = obj["idToken"]?.jsonPrimitive?.content
        refreshToken = obj["refreshToken"]?.jsonPrimitive?.content
        uid = obj["localId"]?.jsonPrimitive?.content ?: "default_user"
        idTokenExpiresAtMs = Clock.System.now().toEpochMilliseconds() + expiresInMs(obj["expiresIn"]?.jsonPrimitive?.content)
    }

    private fun expiresInMs(raw: String?): Long = (raw?.toLongOrNull() ?: 3600L) * 1000L

    private fun authFailure(rawMsg: String?, email: String?) {
        val msg = rawMsg ?: ""
        if (msg.contains("OPERATION_NOT_ALLOWED")) {
            // Firebase Auth disabled in console — fallback to unauthenticated mode (works if Firestore rules allow read/write)
            idToken = null
            refreshToken = null
            idTokenExpiresAtMs = 0L
            uid = email ?: "default_user"
        } else if (msg.contains("API key not valid") || msg.contains("API_KEY_INVALID")) {
            throw IOException("Invalid Firebase API Key. Check Settings -> Advanced.")
        } else {
            throw IOException("Firebase Auth failed: ${msg.take(120)}")
        }
    }

    private fun ensureAuth() {
        if (uidOverride == null && !hasValidToken()) authenticate()
    }

    override fun upsertBook(book: FsBook) =
        putPayload(FirestorePaths.userBook(uid, book.id), book.updatedAt, json.encodeToString(book))

    override fun upsertPosition(position: FsReadingPosition) = putPayload(
        "${FirestorePaths.userPositions(uid, position.bookId)}/${docId(position.deviceId)}",
        position.updatedAt,
        json.encodeToString(position)
    )

    override fun upsertHighlight(highlight: FsHighlight) = putPayload(
        "${FirestorePaths.userHighlights(uid, highlight.bookId)}/${docId(highlight.id)}",
        highlight.updatedAt,
        json.encodeToString(highlight)
    )

    override fun upsertNote(note: FsNote) = putPayload(
        "${FirestorePaths.userNotes(uid, note.bookId)}/${docId(note.id)}",
        note.updatedAt,
        json.encodeToString(note)
    )

    override fun upsertBookmark(bookmark: FsBookmark) = putPayload(
        "${FirestorePaths.userBookmarks(uid, bookmark.bookId)}/${docId(bookmark.id)}",
        bookmark.updatedAt,
        json.encodeToString(bookmark)
    )

    override fun upsertSession(session: FsReadingSession) = putPayload(
        "${FirestorePaths.userSessions(uid)}/${docId(session.id)}",
        session.startedAt,
        json.encodeToString(session)
    )

    override fun upsertSettings(settings: FsSettings) =
        putPayload(FirestorePaths.userSettingsDocument(uid), settings.updatedAt, json.encodeToString(settings))

    override fun upsertCollection(collection: FsCollection) = putPayload(
        "${FirestorePaths.userCollections(uid)}/${docId(collection.id)}",
        collection.createdAt,
        json.encodeToString(collection)
    )

    override fun upsertSeries(series: FsSeries) = putPayload(
        "${FirestorePaths.userSeries(uid)}/${docId(series.id)}",
        0L,
        json.encodeToString(series)
    )

    override fun upsertTag(tag: FsTag) = putPayload(
        "${FirestorePaths.userTags(uid)}/${docId(tag.id)}",
        tag.createdAt,
        json.encodeToString(tag)
    )

    override fun upsertQuote(quote: FsQuote) = putPayload(
        "${FirestorePaths.userQuotes(uid)}/${docId(quote.id)}",
        quote.createdAt,
        json.encodeToString(quote)
    )

    override fun upsertRevisitItem(item: FsRevisitItem) = putPayload(
        "${FirestorePaths.userRevisit(uid)}/${docId(item.id)}",
        item.resolvedAt ?: item.createdAt,
        json.encodeToString(item)
    )

    override fun fetchBooks(): List<FsBook> {
        ensureAuth()
        return listCollection(FirestorePaths.userBooks(uid))
            .mapNotNull { decode<FsBook>(it) }
    }

    private fun fetchAllUserBooks(): List<FsBook> {
        ensureAuth()
        return listCollection(FirestorePaths.userBooks(uid)).mapNotNull { decode(it) }
    }

    override fun fetchPositions(): List<FsReadingPosition> {
        ensureAuth()
        val out = mutableListOf<FsReadingPosition>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userPositions(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsReadingPosition.serializer(), doc.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchHighlights(): List<FsHighlight> {
        ensureAuth()
        val out = mutableListOf<FsHighlight>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userHighlights(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsHighlight.serializer(), doc.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchNotes(): List<FsNote> {
        ensureAuth()
        val out = mutableListOf<FsNote>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userNotes(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsNote.serializer(), doc.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchBookmarks(): List<FsBookmark> {
        ensureAuth()
        val out = mutableListOf<FsBookmark>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userBookmarks(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsBookmark.serializer(), doc.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchSessions(): List<FsReadingSession> {
        ensureAuth()
        return listCollection(FirestorePaths.userSessions(uid))
            .mapNotNull { decode<FsReadingSession>(it) }
    }

    override fun fetchCollections(): List<FsCollection> {
        ensureAuth()
        return listCollection(FirestorePaths.userCollections(uid))
            .mapNotNull { decode<FsCollection>(it) }
    }

    override fun fetchSeries(): List<FsSeries> {
        ensureAuth()
        return listCollection(FirestorePaths.userSeries(uid))
            .mapNotNull { decode<FsSeries>(it) }
    }

    override fun fetchTags(): List<FsTag> {
        ensureAuth()
        return listCollection(FirestorePaths.userTags(uid))
            .mapNotNull { decode<FsTag>(it) }
    }

    override fun fetchQuotes(): List<FsQuote> {
        ensureAuth()
        return listCollection(FirestorePaths.userQuotes(uid))
            .mapNotNull { decode<FsQuote>(it) }
    }

    override fun fetchRevisitItems(): List<FsRevisitItem> {
        ensureAuth()
        return listCollection(FirestorePaths.userRevisit(uid))
            .mapNotNull { decode<FsRevisitItem>(it) }
    }

    // ---------- internals ----------

    private inline fun <reified T : Any> decode(doc: RemoteDoc): T? =
        runCatching { json.decodeFromString<T>(doc.payload) }.getOrNull()

    private fun putPayload(path: String, updatedAtMs: Long, payloadJson: String) {
        val fields = buildJsonObject {
            put("payload", buildJsonObject { put("stringValue", payloadJson) })
            put("updatedAt", buildJsonObject { put("doubleValue", updatedAtMs.toDouble()) })
        }
        val body = JsonObject(mapOf("fields" to fields)).toString()
        httpJson("$baseUrl/${encodePath(path)}", "PATCH", body, bearer())
    }

    /** Lists a collection fully (paginated). Personal-scale collections only. */
    private fun listCollection(collectionPath: String): List<RemoteDoc> {
        val out = mutableListOf<RemoteDoc>()
        var pageToken: String? = null
        do {
            var url = "$baseUrl/${encodePath(collectionPath)}?pageSize=300"
            pageToken?.let { url += "&pageToken=${URLEncoder.encode(it, "UTF-8")}" }
            val response = httpJson(url, "GET", null, bearer())
            val obj = json.parseToJsonElement(response).jsonObject
            obj["documents"]?.let { docsEl ->
                for (doc in (docsEl as JsonArray)) {
                    val docObj = doc.jsonObject
                    val name = docObj["name"]?.jsonPrimitive?.content ?: continue
                    val fields = docObj["fields"]?.jsonObject ?: continue
                    val payload = fields["payload"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
                        ?: continue
                    val updated = fields["updatedAt"]?.jsonObject?.get("doubleValue")?.jsonPrimitive?.double
                        ?: 0.0
                    out.add(RemoteDoc(name, payload, updated.toLong()))
                }
            }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out
    }

    private fun getDocument(path: String): RemoteDoc? {
        val response = runCatching {
            httpJson("$baseUrl/${encodePath(path)}", "GET", null, bearer())
        }.getOrNull() ?: return null
        val obj = json.parseToJsonElement(response).jsonObject
        val fields = obj["fields"]?.jsonObject ?: return null
        val payload = fields["payload"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: return null
        val updated = fields["updatedAt"]?.jsonObject?.get("doubleValue")?.jsonPrimitive?.double ?: 0.0
        return RemoteDoc(obj["name"]?.jsonPrimitive?.content ?: path, payload, updated.toLong())
    }

    private fun bearer(): String? = idToken?.let { "Bearer $it" }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    private data class RemoteDoc(val name: String, val payload: String, val updatedAtMs: Long)

    private fun httpJson(url: String, method: String, body: String?, authHeader: String?, contentType: String = "application/json"): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // The desktop JDK's HttpURLConnection rejects PATCH outright.
            // Firestore accepts Google's standard method-override header, while
            // Android accepts this POST form too, so use it consistently.
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
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
}

internal const val TOKEN_REFRESH_MARGIN_MS = 60_000L

private fun docId(raw: String): String = URLEncoder.encode(raw, "UTF-8")

internal fun jsonEscape(raw: String): String =
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
