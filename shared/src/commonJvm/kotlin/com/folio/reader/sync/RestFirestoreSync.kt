package com.folio.reader.sync

import com.folio.reader.firebase.FsBook
import com.folio.reader.firebase.FsBookmark
import com.folio.reader.firebase.FsCollection
import com.folio.reader.firebase.FsHighlight
import com.folio.reader.firebase.FsManga
import com.folio.reader.firebase.FsMangaCategory
import com.folio.reader.firebase.FsMangaChapter
import com.folio.reader.firebase.FsMangaNote
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
    private val accountPassword: String? = null,
    internal val identityToolkitBaseUrl: String = "https://identitytoolkit.googleapis.com/v1",
    firestoreBaseUrl: String? = null
) : FirestoreSync {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val baseUrl = firestoreBaseUrl
        ?: "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

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
                    // 2. Try Email/Password sign up (first-time account creation)
                    identityToolkit("accounts:signUp", credentialsBody(email, password))
                }.getOrElse { error2 ->
                    // No anonymous fallback: silently adopting a fresh anonymous
                    // identity signed the reader into an empty account they did
                    // not ask for, making their library appear lost.
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
        append(jsonEscape(email))
        append("\",\"password\":\"")
        append(jsonEscape(password))
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
        } else if (msg.contains("INVALID_PASSWORD") || msg.contains("EMAIL_NOT_FOUND") ||
            msg.contains("EMAIL_EXISTS") || msg.contains("INVALID_EMAIL") ||
            msg.contains("INVALID_LOGIN_CREDENTIALS")
        ) {
            throw IOException("Sync account sign-in failed — check the email and password in Settings -> Advanced.")
        } else {
            throw IOException("Firebase Auth failed: ${msg.take(120)}")
        }
    }

    private fun ensureAuth() {
        if (uidOverride == null && !hasValidToken()) authenticate()
    }

    override fun upsertBook(book: FsBook) =
        putPayload(
            FirestorePaths.userBook(uid, fsId(book.id)),
            book.updatedAt,
            json.encodeToString(book)
        )

    override fun upsertPosition(position: FsReadingPosition) = putPayload(
        "${FirestorePaths.userPositions(uid, fsId(position.bookId))}/${fsId(position.deviceId)}",
        position.updatedAt,
        json.encodeToString(position)
    )

    override fun upsertHighlight(highlight: FsHighlight) = putPayload(
        "${FirestorePaths.userHighlights(uid, fsId(highlight.bookId))}/${fsId(highlight.id)}",
        highlight.updatedAt,
        json.encodeToString(highlight)
    )

    override fun upsertNote(note: FsNote) = putPayload(
        "${FirestorePaths.userNotes(uid, fsId(note.bookId))}/${fsId(note.id)}",
        note.updatedAt,
        json.encodeToString(note)
    )

    override fun upsertBookmark(bookmark: FsBookmark) = putPayload(
        "${FirestorePaths.userBookmarks(uid, fsId(bookmark.bookId))}/${fsId(bookmark.id)}",
        bookmark.updatedAt,
        json.encodeToString(bookmark)
    )

    override fun upsertSession(session: FsReadingSession) = putPayload(
        "${FirestorePaths.userSessions(uid)}/${fsId(session.id)}",
        session.startedAt,
        json.encodeToString(session)
    )

    override fun upsertSettings(settings: FsSettings) =
        putPayload(FirestorePaths.userSettingsDocument(uid), settings.updatedAt, json.encodeToString(settings))

    override fun upsertCollection(collection: FsCollection) = putPayload(
        "${FirestorePaths.userCollections(uid)}/${fsId(collection.id)}",
        collection.updatedAt,
        json.encodeToString(collection)
    )

    override fun upsertSeries(series: FsSeries) = putPayload(
        "${FirestorePaths.userSeries(uid)}/${fsId(series.id)}",
        series.updatedAt,
        json.encodeToString(series)
    )

    override fun upsertTag(tag: FsTag) = putPayload(
        "${FirestorePaths.userTags(uid)}/${fsId(tag.id)}",
        tag.updatedAt,
        json.encodeToString(tag)
    )

    override fun upsertQuote(quote: FsQuote) = putPayload(
        "${FirestorePaths.userQuotes(uid)}/${fsId(quote.id)}",
        quote.createdAt,
        json.encodeToString(quote)
    )

    override fun upsertRevisitItem(item: FsRevisitItem) = putPayload(
        "${FirestorePaths.userRevisit(uid)}/${fsId(item.id)}",
        item.resolvedAt ?: item.createdAt,
        json.encodeToString(item)
    )

    override fun fetchBooks(): List<FsBook> {
        ensureAuth()
        // Cycle boundary: preconditions must reflect only this cycle's fetches.
        docUpdateTimes.clear()
        val docs = listCollection(FirestorePaths.userBooks(uid))
        // The engine fetches books first every cycle; the annotation fan-out below
        // reuses this listing instead of re-listing the collection four more times.
        booksCache = docs
        return docs.mapNotNull { decode<FsBook>(it) }
    }

    private var booksCache: List<RemoteDoc>? = null

    private fun fetchAllUserBooks(): List<RemoteDoc> {
        ensureAuth()
        booksCache?.let { return it }
        return listCollection(FirestorePaths.userBooks(uid)).also { booksCache = it }
    }

    override fun fetchPositions(): List<FsReadingPosition> {
        ensureAuth()
        val out = mutableListOf<FsReadingPosition>()
        for (doc in fetchAllUserBooks()) {
            val book = decode<FsBook>(doc) ?: continue
            listCollection(FirestorePaths.userPositions(uid, fsId(book.id))).forEach { sub ->
                runCatching { json.decodeFromString(FsReadingPosition.serializer(), sub.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchHighlights(): List<FsHighlight> {
        ensureAuth()
        val out = mutableListOf<FsHighlight>()
        for (doc in fetchAllUserBooks()) {
            val book = decode<FsBook>(doc) ?: continue
            listCollection(FirestorePaths.userHighlights(uid, fsId(book.id))).forEach { sub ->
                runCatching { json.decodeFromString(FsHighlight.serializer(), sub.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchNotes(): List<FsNote> {
        ensureAuth()
        val out = mutableListOf<FsNote>()
        for (doc in fetchAllUserBooks()) {
            val book = decode<FsBook>(doc) ?: continue
            listCollection(FirestorePaths.userNotes(uid, fsId(book.id))).forEach { sub ->
                runCatching { json.decodeFromString(FsNote.serializer(), sub.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchBookmarks(): List<FsBookmark> {
        ensureAuth()
        val out = mutableListOf<FsBookmark>()
        for (doc in fetchAllUserBooks()) {
            val book = decode<FsBook>(doc) ?: continue
            listCollection(FirestorePaths.userBookmarks(uid, fsId(book.id))).forEach { sub ->
                runCatching { json.decodeFromString(FsBookmark.serializer(), sub.payload) }.getOrNull()
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    override fun fetchSessions(sinceStartedAtMs: Long): List<FsReadingSession> {
        ensureAuth()
        if (sinceStartedAtMs <= 0L) {
            return listCollection(FirestorePaths.userSessions(uid))
                .mapNotNull { decode<FsReadingSession>(it) }
        }
        // Sessions are append-only, so an inequality query on the updatedAt field
        // (which stores startedAt) pulls just the new history instead of the whole
        // collection every cycle — the session list is what grows without bound.
        return runStructuredQuery(
            parentPath = FirestorePaths.USERS + "/" + uid,
            collectionId = FirestorePaths.SESSIONS,
            filterField = "updatedAt",
            greaterThan = sinceStartedAtMs.toDouble()
        ).mapNotNull { decode<FsReadingSession>(it) }
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

    override fun fetchSettings(): FsSettings? {
        ensureAuth()
        val doc = getDocument(FirestorePaths.userSettingsDocument(uid)) ?: return null
        return decode<FsSettings>(doc)
    }

    override fun upsertManga(manga: FsManga) = putPayload(
        "${FirestorePaths.userManga(uid)}/${fsId(manga.id)}",
        manga.updatedAt,
        json.encodeToString(manga)
    )

    override fun upsertMangaChapter(chapter: FsMangaChapter) = putPayload(
        "${FirestorePaths.userMangaChapters(uid)}/${fsId(chapter.id)}",
        chapter.updatedAt,
        json.encodeToString(chapter)
    )

    override fun upsertMangaNote(note: FsMangaNote) = putPayload(
        "${FirestorePaths.userMangaNotes(uid)}/${fsId(note.id)}",
        note.updatedAt,
        json.encodeToString(note)
    )

    override fun fetchManga(): List<FsManga> {
        ensureAuth()
        return listCollection(FirestorePaths.userManga(uid)).mapNotNull { decode<FsManga>(it) }
    }

    override fun fetchMangaChapters(): List<FsMangaChapter> {
        ensureAuth()
        return listCollection(FirestorePaths.userMangaChapters(uid)).mapNotNull { decode<FsMangaChapter>(it) }
    }

    override fun fetchMangaNotes(): List<FsMangaNote> {
        ensureAuth()
        return listCollection(FirestorePaths.userMangaNotes(uid)).mapNotNull { decode<FsMangaNote>(it) }
    }

    override fun upsertMangaCategory(category: FsMangaCategory) = putPayload(
        "${FirestorePaths.userMangaCategories(uid)}/${fsId(category.id)}",
        category.updatedAt,
        json.encodeToString(category)
    )

    override fun fetchMangaCategories(): List<FsMangaCategory> {
        ensureAuth()
        return listCollection(FirestorePaths.userMangaCategories(uid)).mapNotNull { decode<FsMangaCategory>(it) }
    }

    // ---------- internals ----------

    /**
     * Server updateTime of every document fetched this cycle, keyed by short path
     * ("users/{uid}/books/…" etc.). Writes to a known document carry it back as a
     * currentDocument.updateTime precondition: if another device wrote in between,
     * Firestore rejects the PATCH and the engine converges from the fresher remote
     * copy on the next cycle instead of clobbering it.
     */
    private val docUpdateTimes = mutableMapOf<String, String>()

    private fun shortKey(fullResourceName: String): String =
        fullResourceName.substringAfter("/documents/", fullResourceName)

    private inline fun <reified T : Any> decode(doc: RemoteDoc): T? =
        runCatching { json.decodeFromString<T>(doc.payload) }.getOrNull()

    private fun putPayload(path: String, updatedAtMs: Long, payloadJson: String) {
        val fields = buildJsonObject {
            put("payload", buildJsonObject { put("stringValue", payloadJson) })
            put("updatedAt", buildJsonObject { put("doubleValue", updatedAtMs.toDouble()) })
        }
        val body = JsonObject(mapOf("fields" to fields)).toString()
        val precondition = docUpdateTimes[path]
        val query = precondition?.let { "?currentDocument.updateTime=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        httpJson("$baseUrl/${encodePath(path)}$query", "PATCH", body, bearer())
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
                    val updateTime = docObj["updateTime"]?.jsonPrimitive?.content
                    updateTime?.let { docUpdateTimes[shortKey(name)] = it }
                    out.add(RemoteDoc(name, payload, updated.toLong()))
                }
            }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out
    }

    /**
     * Runs a scoped structured query (inequality on a numeric field) against one
     * collection under [parentPath]. Used for incremental fetches of append-only
     * collections. Inequality filters require ordering by the same field.
     */
    private fun runStructuredQuery(
        parentPath: String,
        collectionId: String,
        filterField: String,
        greaterThan: Double
    ): List<RemoteDoc> {
        val body = buildJsonObject {
            put(
                "structuredQuery",
                buildJsonObject {
                    put(
                        "from",
                        JsonArray(listOf(buildJsonObject { put("collectionId", collectionId) }))
                    )
                    put(
                        "where",
                        buildJsonObject {
                            put(
                                "fieldFilter",
                                buildJsonObject {
                                    put("field", buildJsonObject { put("fieldPath", filterField) })
                                    put("op", "GREATER_THAN")
                                    put("value", buildJsonObject { put("doubleValue", greaterThan) })
                                }
                            )
                        }
                    )
                    put(
                        "orderBy",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("field", buildJsonObject { put("fieldPath", filterField) })
                                    put("direction", "ASCENDING")
                                }
                            )
                        )
                    )
                }
            )
        }.toString()
        val response = httpJson("$baseUrl/${encodePath(parentPath)}:runQuery", "POST", body, bearer())
        val out = mutableListOf<RemoteDoc>()
        for (element in (json.parseToJsonElement(response) as? JsonArray) ?: return out) {
            val docObj = element.jsonObject["document"]?.jsonObject ?: continue
            val name = docObj["name"]?.jsonPrimitive?.content ?: continue
            val fields = docObj["fields"]?.jsonObject ?: continue
            val payload = fields["payload"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
                ?: continue
            val updated = fields["updatedAt"]?.jsonObject?.get("doubleValue")?.jsonPrimitive?.double
                ?: 0.0
            docObj["updateTime"]?.jsonPrimitive?.content?.let { docUpdateTimes[shortKey(name)] = it }
            out.add(RemoteDoc(name, payload, updated.toLong()))
        }
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

    /**
     * Entity ids must occupy exactly one path segment: manga/chapter ids embed
     * source URLs containing '/', and [encodePath] would split a raw id into
     * several segments, making Firestore reject the path (400, "lacks a
     * collection id"). Pre-encoding here keeps the id one opaque segment; the
     * server stores exactly this encoded form, so [shortKey] returns it verbatim
     * and the docUpdateTimes keys still match. Safe ids (uuids, hashes) encode to
     * themselves, leaving existing document ids untouched.
     */
    private fun fsId(id: String): String = URLEncoder.encode(id, "UTF-8")

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
