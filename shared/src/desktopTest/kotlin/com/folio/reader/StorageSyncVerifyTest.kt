package com.folio.reader

import com.folio.reader.sync.md5Matches
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Download verification must compare like with like: the streamed MD5 against the
 * object metadata's base64 md5Hash. The previous SHA-256-vs-md5Hash comparison
 * could never match, so every verified download failed and deleted its file.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class StorageSyncVerifyTest {

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun md5Base64(bytes: ByteArray): String = kotlin.io.encoding.Base64.encode(md5(bytes))

    @Test
    fun `intact download passes verification`() {
        val bytes = "the quick brown fox jumps over the lazy dog".toByteArray()
        assertTrue(md5Matches(md5(bytes), md5Base64(bytes)))
    }

    @Test
    fun `corrupted download fails verification`() {
        val original = "chapter one contents".toByteArray()
        val corrupted = "chapter one contentX".toByteArray()
        assertFalse(md5Matches(md5(corrupted), md5Base64(original)))
    }

    @Test
    fun `metadata hash with surrounding whitespace still matches`() {
        val bytes = "epub-bytes".toByteArray()
        assertTrue(md5Matches(md5(bytes), "  ${md5Base64(bytes)}  "))
    }

    @Test
    fun `empty file verifies against empty md5`() {
        val empty = ByteArray(0)
        assertTrue(md5Matches(md5(empty), md5Base64(empty)))
    }
}
