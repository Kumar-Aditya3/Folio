package com.folio.reader

import com.folio.reader.ui.render.ContinuousEngine
import com.folio.reader.ui.render.MulticolEngine
import com.folio.reader.ui.render.PageEngine
import com.folio.reader.ui.render.ReaderSection
import com.folio.reader.ui.render.ReaderWindowAssembler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards on the two reader-jump fixes. The behaviours they protect are LAYOUT behaviours —
 * the JVM has no layout, so these assert the structure the behaviour depends on and nothing
 * here proves a page renders correctly. That proof lives in the Chromium harness (a real
 * engine at a phone viewport), and the numbers it produced are quoted in the assertions'
 * comments so a future reader knows what each guard is worth.
 */
class ReaderJumpEngineGuardTest {

    private val pagedJs = MulticolEngine.innerJs(0f, 0f, 0f, 0f, 0f, 0, diag = false)

    /**
     * A top-level function body in these scripts always closes with `}` at column zero.
     * Whole-line comments are dropped: these guards assert on code, and measure()'s own
     * comment explains the very call they must not find.
     */
    private fun fn(js: String, signature: String): String {
        val start = js.indexOf(signature)
        assertTrue(start >= 0, "missing $signature")
        val body = js.substring(start, js.indexOf("\n}", start) + 2)
        return body.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun `paged page count is measured from painted extent, never from the width it pins`() {
        val measure = fn(pagedJs, "function measure(){")
        assertTrue(
            measure.contains("paintedRight()"),
            "the count must come from the strip's painted extent",
        )
        // The ratchet this replaces: root AND the iframe were both pinned to the previous
        // total, so reading that box back could only ever grow it — 216 pages of a real book
        // stuck at 1123 after one reflow, i.e. 907 blank pages past the chapter's end.
        assertEquals(-1, measure.indexOf("root.scrollWidth"), "measure() must not read its own pin")
        assertTrue(
            measure.indexOf("paintedRight()") < measure.indexOf("setProperty('width',expanded"),
            "the extent must be read BEFORE the width is re-pinned",
        )
        assertEquals(1, measure occurrencesOf "total*P-G", "root and iframe must be pinned from one expression")
        // The fallback belongs in paintedRight(), not in the measure path.
        assertTrue(fn(pagedJs, "function paintedRight(){").contains("root.scrollWidth"))
    }

    @Test
    fun `paged engines tolerate the host chapter scope instead of misparsing it`() {
        // `scopedTarget` prefixes windowed seeks with "c:<chapterId>|"; a paged document is
        // one chapter, so the prefix is noise — and left in place it parsed as the paragraph
        // number, landing the jump on an arbitrary paragraph of the wrong chapter.
        assertTrue(
            fn(pagedJs, "window.__folioSeekTo=function(t){").contains("indexOf('c:')===0"),
            "MulticolEngine must strip the chapter scope",
        )
        assertTrue(
            fn(PageEngine.js(0f, 1, 0f, 0), "window.__folioSeekTo=function(t){")
                .contains("indexOf('c:')===0"),
            "PageEngine must strip the chapter scope",
        )
    }

    @Test
    fun `a continuous seek that cannot resolve its chapter refuses instead of numbering the window`() {
        val js = ContinuousEngine.js(0, 0f, desktopEvents = false)
        val resolve = fn(js, "function seekResolve(t){")
        // The old fallback numbered every <p> in the whole window document: an ordinal of 792
        // inside one chapter became document paragraph 792 — three chapters behind the row
        // that was tapped, which is the report these guards exist for.
        assertEquals(-1, js.indexOf("contentRoot(scope)||document"), "the whole-window fallback must be gone")
        assertTrue(resolve.contains("if(scoped&&!scope)return null;"), "an unresolved scope must be a miss")
        assertTrue(resolve.contains("visibleSection()"), "an unscoped ordinal belongs to one visible section")
        assertTrue(js.contains("folio-seekmiss:"), "a miss must be reportable, not silent")
    }

    @Test
    fun `the window's chapter markers are exactly what a c-scope has to match`() {
        val sections = listOf(
            ReaderSection(1, "id-seven", "ch01.xhtml", "<html><body><p>Seven</p></body></html>"),
            ReaderSection(2, "id eight", "ch02.xhtml", "<html><body><p>Eight</p></body></html>"),
        )
        val doc = ReaderWindowAssembler.assemble(sections, shadow = false)
        val surfaceScope = { id: String -> "c:$id|" }
        for (section in sections) {
            val scope = surfaceScope(section.chapterId)
            val id = scope.substring(2, scope.indexOf('|'))
            assertTrue(
                doc.contains("data-folio-chapter=\"$id\""),
                "secByChapter() matches this attribute; ${section.chapterId} must appear verbatim",
            )
        }
    }

    private infix fun String.occurrencesOf(needle: String): Int =
        Regex(Regex.escape(needle)).findAll(this).count()
}
