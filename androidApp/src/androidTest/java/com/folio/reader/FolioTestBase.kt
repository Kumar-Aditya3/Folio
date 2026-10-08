package com.folio.reader

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.nav.FolioDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The type `createFolioComposeRule()` returns, named once. */
internal typealias FolioComposeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

internal fun createFolioComposeRule(): FolioComposeRule = createAndroidComposeRule()

/**
 * §8 FOLIO_IMPLEMENTATION_SPEC. The suite runs in the app's own process, so tests
 * read and (deliberately, with restore) mutate the real database through the app
 * graph — that is what makes them deterministic on a device with a live library.
 *
 * Device-facing caveats baked into every helper: the phone may be asleep or the
 * activity may still be settling, so navigation assertions poll the navModel's
 * current route instead of assuming instant composition.
 */
internal object FolioTestBase {

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    fun graphOf(rule: FolioComposeRule) =
        (rule.activity.application as FolioApplication).graph

    /** Current NavHost route, or null before the navModel is attached. */
    fun currentRoute(rule: FolioComposeRule): String? {
        var route: String? = null
        instrumentation.runOnMainSync {
            route = rule.activity.navModel.navController?.currentDestination?.route
        }
        return route
    }

    fun navigate(rule: FolioComposeRule, route: String) {
        instrumentation.runOnMainSync {
            rule.activity.navModel.navController?.navigate(route)
        }
    }

    fun goBack(rule: FolioComposeRule) {
        instrumentation.runOnMainSync {
            rule.activity.navModel.navController?.popBackStack()
        }
    }

    /**
     * The tap gesture that toggles the reader chrome. Injected at the centre of
     * the screen, mirroring a real tap on the page.
     */
    fun tapReaderPage() {
        val d = device
        d.click(d.displayWidth / 2, d.displayHeight / 2)
    }

    /**
     * DB work from the test thread. The repositories are JDBC-backed and the
     * graph already guards its own connections, so a plain runBlocking is safe.
     */
    fun <T> db(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }

    fun <T> poll(timeoutMs: Long = 8000, intervalMs: Long = 100, check: suspend () -> T?): T =
        runBlocking {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                check()?.let { return@runBlocking it }
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("Timed out waiting for condition")
                }
                delay(intervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("unreachable")
        }

    /** Every node in the merged semantics tree, depth-first from the root. */
    fun collectNodes(root: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out.add(node)
            node.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    fun clickableNodes(root: SemanticsNode): List<SemanticsNode> =
        collectNodes(root).filter {
            it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsActions.OnLongClick)
        }

    private fun <T> SemanticsConfiguration.opt(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) get(key) else null

    fun hasVisibleText(node: SemanticsNode): Boolean =
        !node.config.opt(SemanticsProperties.Text).isNullOrEmpty() ||
            !node.config.opt(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
            node.config.opt(SemanticsProperties.EditableText) != null

    /** First text or contentDescription in the node's subtree — for failure messages. */
    fun subtreeLabel(node: SemanticsNode): String {
        if (hasVisibleText(node)) {
            return node.config.opt(SemanticsProperties.Text)?.firstOrNull()?.toString()
                ?: node.config.opt(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: "?"
        }
        return node.children.firstNotNullOfOrNull { subtreeLabel(it) } ?: "<no label>"
    }

    fun contentDescriptionOf(node: SemanticsNode): List<String>? =
        node.config.opt(SemanticsProperties.ContentDescription)

    fun isImageRole(node: SemanticsNode): Boolean =
        node.config.opt(SemanticsProperties.Role) == Role.Image

    /**
     * TalkBack reads the merged tree, where a clickable node speaks the text and
     * content descriptions of its whole subtree. Auditing the unmerged tree
     * therefore has to look at descendants too — a labeled button's Text lives on
     * a child node here.
     */
    fun hasAccessibleLabel(node: SemanticsNode): Boolean {
        if (hasVisibleText(node)) return true
        return node.children.any { hasAccessibleLabel(it) }
    }

    /**
     * Inserts a real one-chapter book — a minimal EPUB written into the app's own
     * library directory plus its rows — so reader tests are deterministic on any
     * device, even one whose library is empty. [TestBook.close] removes every trace.
     */
    fun seedBook(rule: FolioComposeRule): TestBook {
        val graph = graphOf(rule)
        val id = "folio-test-book-${System.currentTimeMillis()}"
        val title = "Folio Test Book"
        val body =
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>$title</title></head>" +
                "<body>" +
                "<h1>Chapter One</h1>" +
                "<p>The seeded fixture opens here. This paragraph exists so the reader has " +
                "real text to lay out, measure and highlight while the suite exercises it.</p>" +
                "<p>A second paragraph keeps the chapter from being a single line, which some " +
                "layout paths treat degenerately during pagination.</p>" +
                "</body></html>"
        val epub = miniEpub(title, body)

        val ctx = instrumentation.targetContext
        val bookDir = File(ctx.filesDir, "library/books/$id").apply { mkdirs() }
        File(bookDir, "original.epub").writeBytes(epub)

        val book = Book(
            id = id,
            title = title,
            authors = listOf("Folio Test Suite"),
            epubHash = "test-hash-$id",
            epubFileSize = epub.size.toLong(),
            chapterCount = 1,
            totalCharacters = body.length.toLong(),
            totalWords = body.split(Regex("\\s+")).size.toLong(),
        )
        val chapter = Chapter(
            id = "$id-ch1",
            bookId = id,
            title = "Chapter One",
            href = "chapter1.xhtml",
            spineIndex = 0,
            characterCount = body.length.toLong(),
            wordCount = body.split(Regex("\\s+")).size.toLong(),
            endOffset = body.length.toLong(),
        )
        db {
            graph.bookRepository.insertBook(book, emitSyncEvent = false)
            graph.bookRepository.insertChapters(id, listOf(chapter))
        }
        return TestBook(book) {
            db { runCatching { graph.bookRepository.deleteBook(id, emitSyncEvent = false) } }
            bookDir.deleteRecursively()
        }
    }

    /** A valid single-chapter EPUB 3 package: mimetype first and stored, container, OPF, nav, one xhtml. */
    private fun miniEpub(title: String, body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimetype = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(mimetype)
            zip.write(mime)
            zip.closeEntry()

            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" " +
                    "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>" +
                    "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
                    "</rootfiles></container>",
            )
            entry(
                "OEBPS/content.opf",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:identifier id=\"uid\">urn:uuid:$title</dc:identifier>" +
                    "<dc:title>$title</dc:title><dc:language>en</dc:language>" +
                    "<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta></metadata>" +
                    "<manifest>" +
                    "<item id=\"c1\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                    "</manifest>" +
                    "<spine><itemref idref=\"c1\"/></spine></package>",
            )
            entry(
                "OEBPS/nav.xhtml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
                    "<head><title>Contents</title></head><body><nav epub:type=\"toc\">" +
                    "<ol><li><a href=\"chapter1.xhtml\">Chapter One</a></li></ol></nav></body></html>",
            )
            entry("OEBPS/chapter1.xhtml", body)
        }
        return out.toByteArray()
    }
}

/** A library book the test may open, plus a no-op-or-remove cleanup handle. */
internal class TestBook(val book: Book, private val remove: () -> Unit) : AutoCloseable {
    override fun close() = remove()
}

/** The first book in the library, or a freshly seeded fixture book when it is empty. */
internal fun FolioComposeRule.firstBookOrSeed(): TestBook {
    val existing = FolioTestBase.db {
        FolioTestBase.graphOf(this@firstBookOrSeed).bookRepository.getAllBooks().first().firstOrNull()
    }
    if (existing != null) return TestBook(existing) { }
    return FolioTestBase.seedBook(this@firstBookOrSeed)
}

/** Waits until the NavHost lands on [routePrefix]. */
internal fun FolioComposeRule.awaitRoute(routePrefix: String, timeoutMs: Long = 8000) {
    waitUntil(timeoutMs) { FolioTestBase.currentRoute(this)?.startsWith(routePrefix) == true }
}

/** The screen reached a real state (Rule 7): it composes at least one text-bearing node. */
internal fun FolioComposeRule.assertNotBlank() {
    waitForIdle()
    val root = runCatching { onRoot(useUnmergedTree = true).fetchSemanticsNode() }
        .getOrElse { throw AssertionError("No semantics root — nothing is composed") }
    val anyText = FolioTestBase.collectNodes(root).any { node -> FolioTestBase.hasVisibleText(node) }
    assertTrue("Screen is blank — no text-bearing node anywhere", anyText)
}

internal fun FolioComposeRule.openReader(bookId: String) {
    FolioTestBase.navigate(this, FolioDestination.reader(bookId))
    awaitRoute("reader/")
    waitUntil(8000) { runCatching { onRoot().fetchSemanticsNode() }.isSuccess }
}

internal fun SemanticsNodeInteraction.exists(timeoutMs: Long = 4000): Boolean =
    runCatching { assertExists() }.isSuccess
