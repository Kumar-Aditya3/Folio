package com.folio.reader

import com.folio.reader.firebase.FsBook
import com.folio.reader.sync.RestFirestoreSync
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transport-level proof of the conditional-write guard: a document fetched this
 * cycle is written back with a currentDocument.updateTime precondition, so a
 * concurrent write from another device makes Firestore reject the PATCH (412)
 * instead of being silently overwritten. Documents never seen carry none.
 */
class RestFirestorePreconditionTest {

    private lateinit var server: HttpServer
    private val patchRequests = CopyOnWriteArrayList<String>() // full request URI

    private val bookJson = kotlinx.serialization.json.Json.encodeToString(
        FsBook.serializer(),
        FsBook(
            id = "b1",
            title = "Existing",
            epubHash = "hash-b1",
            epubFileSize = 10,
            addedAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            deviceId = "self-device"
        )
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val uri = exchange.requestURI
            val method = exchange.requestHeaders.getFirst("X-HTTP-Method-Override") ?: exchange.requestMethod
            if (method == "PATCH") {
                patchRequests.add(uri.toString())
                val ok = "{}".toByteArray()
                exchange.sendResponseHeaders(200, ok.size.toLong())
                exchange.responseBody.use { it.write(ok) }
                return@createContext
            }
            // GET listing of the books collection
            val docFields = buildJsonObject {
                put("payload", buildJsonObject { put("stringValue", bookJson) })
                put("updatedAt", buildJsonObject { put("doubleValue", 1_700_000_000_000.0) })
            }
            val bodyObj = buildJsonObject {
                put(
                    "documents",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("name", "projects/p/databases/(default)/documents/users/test-uid/books/b1")
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

    @Test
    fun `known documents are written with an updateTime precondition and new ones are not`() {
        val sync = RestFirestoreSync(
            projectId = "p",
            apiKey = "k",
            uidOverride = "test-uid",
            firestoreBaseUrl = "http://127.0.0.1:${server.address.port}/v1/projects/p/databases/(default)/documents"
        )

        sync.fetchBooks()

        // Writing back the fetched document must carry the observed updateTime.
        sync.upsertBook(
            FsBook(
                id = "b1",
                title = "Changed",
                epubHash = "hash-b1",
                epubFileSize = 10,
                addedAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_060_000L,
                deviceId = "self-device"
            )
        )
        // A document never fetched this cycle gets no precondition.
        sync.upsertBook(
            FsBook(
                id = "brand-new",
                title = "New",
                epubHash = "hash-new",
                epubFileSize = 10,
                addedAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_060_000L,
                deviceId = "self-device"
            )
        )

        val known = patchRequests.single { it.contains("/books/b1") }
        assertTrue(
            known.contains("currentDocument.updateTime="),
            "write to a fetched document must be preconditioned, got: $known"
        )
        assertTrue(known.contains("2026-01-01T00"), "precondition must carry the observed updateTime: $known")

        val fresh = patchRequests.single { it.contains("/books/brand-new") }
        assertFalse(
            fresh.contains("currentDocument.updateTime="),
            "a never-fetched document must be created unconditionally, got: $fresh"
        )
    }
}
