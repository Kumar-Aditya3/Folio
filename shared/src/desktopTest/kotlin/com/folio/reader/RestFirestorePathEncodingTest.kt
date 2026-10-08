package com.folio.reader

import com.folio.reader.firebase.FsManga
import com.folio.reader.firebase.FsMangaChapter
import com.folio.reader.sync.RestFirestoreSync
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transport-level proof that entity ids containing '/' (manga/chapter ids embed
 * source URLs) occupy exactly one path segment in Firestore REST calls instead
 * of splitting the document path (which the API rejects with 400 "lacks a
 * collection id"), and that the server-stored id form round-trips so the
 * conditional-write precondition map still matches.
 */
class RestFirestorePathEncodingTest {

    private lateinit var server: HttpServer
    private val patchRequests = CopyOnWriteArrayList<String>()

    private val chapterId =
        "4841602236575491202:/webtoon/secret-class-01:/https://manhwa18.cc/webtoon/secret-class-01/chapter-2?style=list"
    private val mangaId = "4841602236575491202:/webtoon/secret-class-01"

    // The form the Firestore server stores after decoding the request URL once:
    // fsId() at path construction, then encodePath() for transport.
    private val storedChapterId = URLEncoder.encode(chapterId, "UTF-8")

    private val chapterJson = kotlinx.serialization.json.Json.encodeToString(
        FsMangaChapter.serializer(),
        FsMangaChapter(
            id = chapterId,
            mangaId = mangaId,
            url = "https://manhwa18.cc/webtoon/secret-class-01/chapter-2?style=list",
            name = "Chapter 2",
            updatedAt = 1_700_000_000_000L,
            deviceId = "self-device"
        )
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val method = exchange.requestHeaders.getFirst("X-HTTP-Method-Override") ?: exchange.requestMethod
            if (method == "PATCH") {
                patchRequests.add(exchange.requestURI.toString())
                val ok = "{}".toByteArray()
                exchange.sendResponseHeaders(200, ok.size.toLong())
                exchange.responseBody.use { it.write(ok) }
                return@createContext
            }
            // GET listing of the mangaChapters collection, as the server would
            // return it: the resource name carries the stored (single-decoded) id.
            val docFields = buildJsonObject {
                put("payload", buildJsonObject { put("stringValue", chapterJson) })
                put("updatedAt", buildJsonObject { put("doubleValue", 1_700_000_000_000.0) })
            }
            val bodyObj = buildJsonObject {
                put(
                    "documents",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put(
                                    "name",
                                    "projects/p/databases/(default)/documents/users/test-uid/mangaChapters/$storedChapterId"
                                )
                                put("fields", docFields)
                                put("updateTime", "2026-01-01T00:00:00.000000Z")
                            }
                        )
                    )
                )
            }
            val body = bodyObj.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun newSync() = RestFirestoreSync(
        projectId = "p",
        apiKey = "k",
        uidOverride = "test-uid",
        firestoreBaseUrl = "http://127.0.0.1:${server.address.port}/v1/projects/p/databases/(default)/documents"
    )

    private fun chapterSegment(uri: String, collection: String): String {
        val marker = "/$collection/"
        val start = uri.indexOf(marker) + marker.length
        return uri.substring(start).substringBefore('?')
    }

    @Test
    fun `chapter id with slashes is written as a single path segment`() {
        val sync = newSync()
        sync.upsertMangaChapter(
            FsMangaChapter(
                id = chapterId,
                mangaId = mangaId,
                url = "https://manhwa18.cc/webtoon/secret-class-01/chapter-2?style=list",
                name = "Chapter 2",
                updatedAt = 1_700_000_000_000L,
                deviceId = "self-device"
            )
        )

        val uri = patchRequests.single()
        val segment = chapterSegment(uri, "mangaChapters")
        assertFalse(segment.contains("/"), "id must not split into extra segments: $uri")
        // The '/' of the embedded URL must travel as (double-)encoded data, not
        // as a path separator.
        assertTrue(segment.contains("%252F"), "embedded slashes must stay encoded: $uri")
        // One transport-decode of the segment must yield exactly the stored form.
        assertEquals(storedChapterId, URLDecoder.decode(segment, "UTF-8"))
    }

    @Test
    fun `manga id with slashes is written as a single path segment`() {
        val sync = newSync()
        sync.upsertManga(
            FsManga(
                id = mangaId,
                sourceId = 4841602236575491202L,
                sourceName = "manhwa18",
                url = "/webtoon/secret-class-01",
                title = "Secret Class",
                addedAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L,
                deviceId = "self-device"
            )
        )

        val uri = patchRequests.single()
        val segment = chapterSegment(uri, "manga")
        assertFalse(segment.contains("/"), "id must not split into extra segments: $uri")
    }

    @Test
    fun `fetched chapter with slash id keeps its conditional-write precondition`() {
        val sync = newSync()
        val fetched = sync.fetchMangaChapters()
        assertEquals(1, fetched.size)
        assertEquals(chapterId, fetched.single().id)

        sync.upsertMangaChapter(fetched.single().copy(read = true, updatedAt = 1_700_000_060_000L))

        val uri = patchRequests.single()
        assertTrue(
            uri.contains("currentDocument.updateTime="),
            "write to a fetched slash-id document must be preconditioned, got: $uri"
        )
    }
}
