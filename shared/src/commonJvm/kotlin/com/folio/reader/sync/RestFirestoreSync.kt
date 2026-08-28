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
        if (idToken != null && uid.isNotEmpty()) return
        val email = accountEmail?.takeIf { it.isNotBlank() } ?: "folio-sync-default@folio.app"
        val password = accountPassword?.takeIf { it.isNotBlank() } ?: "FolioSyncPass2026!"

        val response = runCatching {
            // 1. Try Email/Password sign in
            httpJson(
                url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$apiKey",
                method = "POST",
                body = buildString {
                    append("{\"email\":\"")
                    append(jsonEscape(email))
                    append("\",\"password\":\"")
                    append(jsonEscape(password))
                    append("\",\"returnSecureToken\":true}")
                },
                authHeader = null
            )
        }.getOrElse { error1 ->
            // 2. Try Email/Password sign up
            runCatching {
                httpJson(
                    url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey",
                    method = "POST",
                    body = buildString {
                        append("{\"email\":\"")
                        append(jsonEscape(email))
                        append("\",\"password\":\"")
                        append(jsonEscape(password))
                        append("\",\"returnSecureToken\":true}")
                    },
                    authHeader = null
                )
            }.getOrElse { error2 ->
                // 3. Try Anonymous Auth fallback
                runCatching {
                    httpJson(
                        url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey",
                        method = "POST",
                        body = "{\"returnSecureToken\":true}",
                        authHeader = null
                    )
                }.getOrElse { error3 ->
                    val rawMsg = error2.message ?: error1.message ?: error3.message ?: ""
                    if (rawMsg.contains("OPERATION_NOT_ALLOWED")) {
                        // Firebase Auth disabled in console — fallback to unauthenticated mode (works if Firestore rules allow read/write)
                        idToken = null
                        uid = accountEmail?.takeIf { it.isNotBlank() } ?: "default_user"
                        return
                    } else if (rawMsg.contains("API key not valid") || rawMsg.contains("API_KEY_INVALID")) {
                        throw IOException("Invalid Firebase API Key. Check Settings -> Advanced.")
                    } else {
                        throw IOException("Firebase Auth failed: ${rawMsg.take(120)}")
                    }
                }
            }
        }

        val obj = json.parseToJsonElement(response).jsonObject
        idToken = obj["idToken"]?.jsonPrimitive?.content
        uid = obj["localId"]?.jsonPrimitive?.content ?: "default_user"
    }

    private fun ensureAuth() {
        if (uidOverride == null && (idToken == null || uid.isEmpty())) authenticate()
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

    override fun fetchBooks(excludeDeviceId: String): List<FsBook> {
        ensureAuth()
        return listCollection(FirestorePaths.userBooks(uid))
            .mapNotNull { decode<FsBook>(it) }
            .filter { it.deviceId != excludeDeviceId }
    }

    private fun fetchAllUserBooks(): List<FsBook> {
        ensureAuth()
        return listCollection(FirestorePaths.userBooks(uid)).mapNotNull { decode(it) }
    }

    override fun fetchPositions(excludeDeviceId: String): List<FsReadingPosition> {
        ensureAuth()
        val out = mutableListOf<FsReadingPosition>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userPositions(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsReadingPosition.serializer(), doc.payload) }.getOrNull()
                    ?.takeIf { it.deviceId != excludeDeviceId }
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchHighlights(excludeDeviceId: String): List<FsHighlight> {
        ensureAuth()
        val out = mutableListOf<FsHighlight>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userHighlights(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsHighlight.serializer(), doc.payload) }.getOrNull()
                    ?.takeIf { it.deviceId != excludeDeviceId }
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchNotes(excludeDeviceId: String): List<FsNote> {
        ensureAuth()
        val out = mutableListOf<FsNote>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userNotes(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsNote.serializer(), doc.payload) }.getOrNull()
                    ?.takeIf { it.deviceId != excludeDeviceId }
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchBookmarks(excludeDeviceId: String): List<FsBookmark> {
        ensureAuth()
        val out = mutableListOf<FsBookmark>()
        for (book in fetchAllUserBooks()) {
            listCollection(FirestorePaths.userBookmarks(uid, book.id)).forEach { doc ->
                runCatching { json.decodeFromString(FsBookmark.serializer(), doc.payload) }.getOrNull()
                    ?.takeIf { it.deviceId != excludeDeviceId }
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchSessions(excludeDeviceId: String): List<FsReadingSession> {
        ensureAuth()
        return listCollection(FirestorePaths.userSessions(uid))
            .mapNotNull { decode<FsReadingSession>(it) }
            .filter { it.deviceId != excludeDeviceId }
    }

    override fun fetchSettings(excludeDeviceId: String): FsSettings? {
        ensureAuth()
        return getDocument(FirestorePaths.userSettingsDocument(uid))?.let { decode(it) }
    }

    override fun fetchCollections(excludeDeviceId: String): List<FsCollection> {
        ensureAuth()
        return listCollection(FirestorePaths.userCollections(uid))
            .mapNotNull { decode<FsCollection>(it) }
            .filter { it.deviceId != excludeDeviceId }
    }

    override fun fetchSeries(excludeDeviceId: String): List<FsSeries> {
        ensureAuth()
        return listCollection(FirestorePaths.userSeries(uid))
            .mapNotNull { decode<FsSeries>(it) }
            .filter { it.deviceId != excludeDeviceId }
    }

    override fun fetchTags(excludeDeviceId: String): List<FsTag> {
        ensureAuth()
        return listCollection(FirestorePaths.userTags(uid))
            .mapNotNull { decode<FsTag>(it) }
            .filter { it.deviceId != excludeDeviceId }
    }

    override fun fetchQuotes(excludeDeviceId: String): List<FsQuote> {
        ensureAuth()
        return listCollection(FirestorePaths.userQuotes(uid))
            .mapNotNull { decode<FsQuote>(it) }
            .filter { it.deviceId != excludeDeviceId }
    }

    override fun fetchRevisitItems(excludeDeviceId: String): List<FsRevisitItem> {
        ensureAuth()
        return listCollection(FirestorePaths.userRevisit(uid))
            .mapNotNull { decode<FsRevisitItem>(it) }
            .filter { it.deviceId != excludeDeviceId }
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

    private fun httpJson(url: String, method: String, body: String?, authHeader: String?): String {
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
                setRequestProperty("Content-Type", "application/json")
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
