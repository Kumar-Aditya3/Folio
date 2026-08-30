package com.folio.reader

import com.folio.reader.epub.ChapterSanitizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sanitizer is the security gate in front of both reader surfaces: anything it
 * lets through runs with file access in the page.
 */
class ChapterSanitizerTest {

    @Test
    fun `script elements are removed`() {
        val html = "<p>Before</p><script>alert('x')</script><p>After</p>"
        val out = ChapterSanitizer.sanitize(html)
        assertFalse(out.contains("<script", ignoreCase = true))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("<p>Before</p>"))
        assertTrue(out.contains("<p>After</p>"))
    }

    @Test
    fun `script with src and self-closing script are removed`() {
        val html = "<script src=\"evil.js\"></script><p>x</p><script src='a.js'/>"
        val out = ChapterSanitizer.sanitize(html)
        assertFalse(out.contains("script", ignoreCase = true))
        assertTrue(out.contains("<p>x</p>"))
    }

    @Test
    fun `inline event handlers are removed even when several share a tag`() {
        val html = "<a href=\"chapter1.xhtml\" onclick=\"steal()\" onmouseover=\"leak()\">go</a>"
        val out = ChapterSanitizer.sanitize(html)
        assertFalse(out.contains("onclick", ignoreCase = true))
        assertFalse(out.contains("onmouseover", ignoreCase = true))
        assertFalse(out.contains("steal"))
        assertTrue(out.contains("href=\"chapter1.xhtml\""), "real attributes must survive")
        assertTrue(out.contains(">go</a>"))
    }

    @Test
    fun `javascript urls are removed from href and src`() {
        val html = "<a href=\"javascript:fetch('file:///x')\">a</a><img src='javascript:x'/>"
        val out = ChapterSanitizer.sanitize(html)
        assertFalse(out.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `iframe object and embed are removed`() {
        val html = "<iframe src=\"http://evil\"></iframe><object data=\"x\"></object><embed src=\"y\"><p>kept</p>"
        val out = ChapterSanitizer.sanitize(html)
        assertFalse(out.contains("<iframe", ignoreCase = true))
        assertFalse(out.contains("<object", ignoreCase = true))
        assertFalse(out.contains("<embed", ignoreCase = true))
        assertTrue(out.contains("<p>kept</p>"))
    }

    @Test
    fun `ordinary chapter content survives untouched`() {
        val html = """
            <html><head><title>Ch 1</title></head>
            <body><h1>Chapter One</h1><p>It was a dark and stormy night.</p>
            <img src="images/map.png"/><blockquote>"Quote"</blockquote></body></html>
        """.trimIndent()
        assertEquals(html, ChapterSanitizer.sanitize(html))
    }
}
