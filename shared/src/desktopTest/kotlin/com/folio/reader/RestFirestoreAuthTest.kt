package com.folio.reader

import com.folio.reader.sync.RestFirestoreSync
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Auth behavior against a stubbed Identity Toolkit:
 *  - explicit credentials that fail sign-in/sign-up must error out, never silently
 *    adopt a fresh anonymous identity (the bug that synced users into an empty
 *    throwaway account);
 *  - installs without credentials still get an anonymous identity.
 */
class RestFirestoreAuthTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // endpoint -> body
    var signInResponseCode = 400
    var signInResponseBody = """{"error":{"message":"INVALID_PASSWORD"}}"""
    var signUpResponseCode = 400
    var signUpResponseBody = """{"error":{"message":"EMAIL_EXISTS"}}"""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = exchange.requestURI.path
            requests.add(path to body)
            val (code, response) = when {
                path.contains("signInWithPassword") -> signInResponseCode to signInResponseBody
                else -> signUpResponseCode to signUpResponseBody
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun stubUrl(): String = "http://127.0.0.1:${server.address.port}/v1"

    @Test
    fun `failed sign-in with configured credentials does not fall back to anonymous`() {
        val sync = RestFirestoreSync(
            projectId = "p",
            apiKey = "k",
            accountEmail = "reader@example.com",
            accountPassword = "wrong-password",
            identityToolkitBaseUrl = stubUrl()
        )

        val error = assertFailsWith<IOException> { sync.authenticate() }
        assertTrue(
            error.message!!.contains("Settings -> Advanced"),
            "failure must point at the credential settings, got: ${error.message}"
        )
        assertEquals("", sync.uid, "no identity may be adopted after a failed sign-in")
        assertTrue(
            requests.none { (_, body) -> body == "{\"returnSecureToken\":true}" },
            "no anonymous sign-up may be attempted when credentials are configured"
        )
    }

    @Test
    fun `wrong-password message is surfaced`() {
        val sync = RestFirestoreSync(
            projectId = "p",
            apiKey = "k",
            accountEmail = "reader@example.com",
            accountPassword = "wrong-password",
            identityToolkitBaseUrl = stubUrl()
        )
        val error = assertFailsWith<IOException> { sync.authenticate() }
        assertTrue(error.message!!.contains("sign-in failed"), "got: ${error.message}")
    }

    @Test
    fun `first-time sign up with new credentials succeeds`() {
        signInResponseCode = 400
        signInResponseBody = """{"error":{"message":"EMAIL_NOT_FOUND"}}"""
        signUpResponseCode = 200
        signUpResponseBody = """{"localId":"user-1","idToken":"tok","refreshToken":"ref","expiresIn":"3600"}"""

        val sync = RestFirestoreSync(
            projectId = "p",
            apiKey = "k",
            accountEmail = "new@example.com",
            accountPassword = "password123",
            identityToolkitBaseUrl = stubUrl()
        )
        sync.authenticate()
        assertEquals("user-1", sync.uid)
    }

    @Test
    fun `installs without credentials still get an anonymous identity`() {
        signUpResponseCode = 200
        signUpResponseBody = """{"localId":"anon-1","idToken":"tok","refreshToken":"ref","expiresIn":"3600"}"""

        val sync = RestFirestoreSync(projectId = "p", apiKey = "k", identityToolkitBaseUrl = stubUrl())
        sync.authenticate()
        assertEquals("anon-1", sync.uid)
    }
}
