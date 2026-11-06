package com.folio.reader.tumble

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import com.folio.reader.database.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class SableDeskTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun distinctGameIconsOpenTheirGamesAtPhoneSizes() = SwingUtilities.invokeAndWait {
        val fonts=requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        for(width in listOf(320,393)) {
            var selected:PocketGame?=null
            var bookmark=false
            var journal=false
            val scene=ImageComposeScene(width,640) {
                FolioTheme.AppTheme(AppPalette.entries.first {!it.isDark},daylight=FolioDaylight.Neutral) {
                    SableDeskScene(TumbleLibrary(),remember {PicaSession()},onPet={},onStretch={},onGame={selected=it},onBookmark={bookmark=true},onJournal={journal=true})
                }
            }
            var frame=0L
            fun render() {repeat(6) {Snapshot.sendApplyNotifications();scene.render(frame++*16_666_667L).close()}}
            fun tap(prefix:String) {
                render()
                val bounds=scene.semanticsOwners.flatMap {it.getAllSemanticsNodes(mergingEnabled=false)}.first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {text->text.startsWith(prefix)}
                }.boundsInRoot
                assertTrue(bounds.width>=44 && bounds.height>=44,"$prefix needs a usable touch area")
                scene.sendPointerEvent(PointerEventType.Press,bounds.center)
                scene.sendPointerEvent(PointerEventType.Release,bounds.center)
                render()
            }
            try {
                render()
                val icons = PocketGame.entries.map { game ->
                    scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }.single {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "sable-game-icon-${game.name.lowercase()}"
                    }.boundsInRoot
                }
                icons.forEach { assertEquals(64f, it.width, .5f); assertEquals(64f, it.height, .5f) }
                assertFalse(icons[0].overlaps(icons[1])); assertFalse(icons[1].overlaps(icons[2]))
                for(game in PocketGame.entries) {tap("Open ${game.title}");assertEquals(game,selected)}
                tap("Your bookmark.");assertTrue(bookmark)
                tap("Open reading journal");assertTrue(journal)
            } finally {scene.close()}
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun lastBookIsUprightAndReturnsToReadingInsteadOfOpeningMemories() = SwingUtilities.invokeAndWait {
        val fonts = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        for (width in listOf(320, 393, 1000)) for (dark in listOf(false, true)) {
            var opened: String? = null
            var bookmark = false
            val pica = PicaSession().apply {
                open = true; bookId = "last-book"; bookTitle = "The Book of Long and Unhurried Afternoons"
                bookAuthor = "A. Reader"; onContinue = { opened = it }
            }
            val scene = ImageComposeScene(width, 1000) {
                FolioTheme.AppTheme(if (dark) AppPalette.DARK else AppPalette.LIGHT, daylight = FolioDaylight.Neutral) {
                    CompositionLocalProvider(LocalFolioMotionAllowed provides false) {
                        SableDeskScene(TumbleLibrary(), pica, onPet = {}, onStretch = {}, onGame = {}, onBookmark = { bookmark = true })
                    }
                }
            }
            var frame = 0L
            fun render() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render(frame++ * 16_666_667L).close() } }
            fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
            try {
                render()
                val cover = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "sable-book-cover" }.boundsInRoot
                val bookmarkBounds=nodes().single {it.config.getOrNull(SemanticsProperties.TestTag)=="book-attached-bookmark"}.boundsInRoot
                assertTrue(cover.overlaps(bookmarkBounds),"The bookmark stays attached to the cover edge")
                assertTrue(bookmarkBounds.center.x<cover.left,"The mascot belongs to the left of the book")
                assertEquals(84f, cover.width, .5f, "The book needs a full-size upright cover")
                assertEquals(126f, cover.height, .5f, "The cover must keep its 2:3 book proportions")
                assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == pica.bookAuthor } })
                val book = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Continue reading ${pica.bookTitle}") }
                assertTrue(book.boundsInRoot.contains(cover.topLeft) && book.boundsInRoot.contains(cover.center))
                scene.sendPointerEvent(PointerEventType.Press, cover.center)
                scene.sendPointerEvent(PointerEventType.Release, cover.center); render()
                assertEquals("last-book", opened); assertFalse(pica.open); assertFalse(bookmark)
                val memories = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Your bookmark. Open reading memories") }
                scene.sendPointerEvent(PointerEventType.Press, memories.boundsInRoot.center)
                scene.sendPointerEvent(PointerEventType.Release, memories.boundsInRoot.center); render()
                assertTrue(bookmark); assertEquals("last-book", opened)
            } finally { scene.close() }
        }
    }

    @Test fun openingFromASelectedHomeBookCarriesItsCoverAndClearsStalePassages() {
        val bookmark=PicaSession().apply {bookId="old";bookTitle="Old book";quote="An old passage";quoteCount=1;sessionLine="Yesterday's session";selectedGame=PocketGame.SHELVES}
        bookmark.openFromHome("BOOK:selected","Selected book","/covers/selected.png","A. Writer")
        assertTrue(bookmark.open);assertNull(bookmark.selectedGame)
        assertEquals("selected",bookmark.bookId);assertEquals("Selected book",bookmark.bookTitle)
        assertEquals("/covers/selected.png",bookmark.bookCover);assertEquals("A. Writer",bookmark.bookAuthor)
        assertEquals("",bookmark.quote);assertEquals("",bookmark.sessionLine)
        bookmark.quote="A passage from this book"
        bookmark.openFromHome("BOOK:selected","Selected book","/covers/selected.png","A. Writer")
        assertEquals("A passage from this book",bookmark.quote)
    }

    @Test fun readingContextUsesPlainLabelsAndKeepsNightMotionQuiet() {
        val bookmark=PicaSession().apply {hour=14}
        assertEquals("Between chapters",bookmark.personality(TumbleLibrary()).mood)
        bookmark.quoteCount=3
        assertEquals("Saved passages",bookmark.personality(TumbleLibrary()).mood)
        bookmark.quoteCount=0;bookmark.readingHabit="return"
        assertEquals("Continue where you left off.",bookmark.personality(TumbleLibrary()).line)
        bookmark.hour=23
        assertEquals("sleep",bookmark.personality(TumbleLibrary()).pose)
    }

    @Test fun bestRunMemoriesRetainReadingContextAndPracticeNeverEarnsAKeepsake() = runBlocking {
        val root=kotlin.io.path.createTempDirectory("folio-sable-").toFile()
        val db=Database(File(root,"test.db").absolutePath)
        try {
            val settings=JdbcSettingsRepository(db)
            val repo=TumbleRepository(settings);repo.load()
            val run=ArcadeRun(ArcadeState("best",PocketGame.LANTERNS,42,1,readingTitle="Small Worlds",score=3000,finished=true),2)
            repo.finishArcade(run);repo.finishArcade(run)
            repo.finishArcade(run.copy(state=run.state.copy(id="practice",practice=true,score=6000)))
            repo.finishArcade(run.copy(state=run.state.copy(id="abandoned",score=9000),abandoned=true))
            val reload=TumbleRepository(settings);reload.load()
            assertEquals(3,reload.library.value.arcadeRuns.size)
            assertEquals(1,reload.library.value.bond.memories.size)
            assertTrue(reload.library.value.bond.memories.single().text.contains("Small Worlds"))
            assertEquals(1,reload.library.value.arcadeRuns.count {it.keepsakeEligible()})
            assertEquals("Small Worlds",reload.library.value.arcadeRuns.first().state.readingTitle)
        } finally {
            db.close()
            check(root.toPath().toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.deleteRecursively()
        }
    }
}
