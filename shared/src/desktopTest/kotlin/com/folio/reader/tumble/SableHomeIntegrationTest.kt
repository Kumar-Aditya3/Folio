package com.folio.reader.tumble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.folio.reader.database.*
import com.folio.reader.experience.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class SableHomeIntegrationTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun actualHomeBookmarkOpensBetweenChaptersWithoutOpeningTheBook() = SwingUtilities.invokeAndWait {
        val fonts=requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        val output=File("build/visual-review/sable").apply {mkdirs()}
        val file=kotlin.io.path.createTempFile("sable-home-",".db").toFile()
        val db=Database(file.absolutePath)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val environment=ReadingEnvironment(JdbcSettingsRepository(db),JdbcBookRepository(db),JdbcHighlightRepository(db),
            JdbcNoteRepository(db),JdbcReadingSessionRepository(db),scope)
        runBlocking {environment.tumble.load()}
        environment.pica.apply {ready=true;hour=14;bookTitle="Small Worlds";bookId="preview"}
        try {
            for(desk in listOf(false,true)) for(width in listOf(320,393)) for(dark in listOf(false,true)) {
                environment.pica.open=false
                var openedBook=false
                val scene=ImageComposeScene(width,1000) {
                    FolioTheme.AppTheme(if(dark) AppPalette.DARK else AppPalette.LIGHT,daylight=FolioDaylight.Neutral) {
                        CompositionLocalProvider(LocalReadingEnvironment provides environment, LocalFolioMotionAllowed provides false) {
                            FolioGlassRoot(GlassCapabilities.None) {
                                Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                                HomeScreen(HomeUiState(loaded=true,hasBooks=true,readingDesk=desk,readingNow=listOf(
                                    ReadingNowItem(HomeItemKind.BOOK,"preview","Small Worlds","A. Reader",.38f),
                                    ReadingNowItem(HomeItemKind.BOOK,"second","The Next Chapter","B. Writer",.22f),
                                    ReadingNowItem(HomeItemKind.BOOK,"third","Third Book","C. Author",.64f))),
                                    onOpenBook={openedBook=true},onOpenBookDetail={openedBook=true},onImportClick={},topInset=20.dp)
                                }
                            }
                        }
                    }
                }
                var frame=0L
                fun render() {repeat(10) {Snapshot.sendApplyNotifications();scene.render(frame++*100_000_000L).close()}}
                fun nodes()=scene.semanticsOwners.flatMap {it.getAllSemanticsNodes(mergingEnabled=false)}
                try {
                    render()
                    val avatar=nodes().single {it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Folio bookmark")}
                    assertTrue(avatar.boundsInRoot.width>=44,"The bookmark needs a usable touch area")
                    val cover=nodes().single {it.config.getOrNull(SemanticsProperties.TestTag)=="home-reading-cover"}.boundsInRoot
                    scene.render(frame*100_000_000L).use {image->File(output,"placement-${if(desk) "desk" else "classic"}-$width.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
                    assertTrue(avatar.boundsInRoot.center.x<=cover.left+2f,"Left placement: desk=$desk width=$width avatar=${avatar.boundsInRoot} cover=$cover")
                    if(desk) {
                        val shelf=nodes().single {it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Revolving reading shelf")}
                        val next=shelf.config[SemanticsActions.CustomActions].single {it.label=="Next cover"}
                        assertTrue(next.action());render()
                        val movedCover=nodes().single {it.config.getOrNull(SemanticsProperties.TestTag)=="home-reading-cover"}.boundsInRoot
                        val movedAvatar=nodes().single {it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Folio bookmark")}.boundsInRoot
                        assertTrue(movedAvatar.center.x<=movedCover.left+2f)
                        assertTrue(shelf.config[SemanticsActions.CustomActions].single {it.label=="Previous cover"}.action());render()
                    }
                    scene.sendPointerEvent(PointerEventType.Press,avatar.boundsInRoot.center)
                    scene.sendPointerEvent(PointerEventType.Release,avatar.boundsInRoot.center);render()
                    assertTrue(environment.pica.open)
                    assertFalse(openedBook)
                    environment.pica.open=false
                    val nook=scene.semanticsOwners.flatMap {it.getAllSemanticsNodes(mergingEnabled=true)}.first {
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any {text->text.text.contains("Between chapters")} &&
                            it.config.getOrNull(SemanticsActions.OnClick)!=null
                    }
                    assertTrue(nook.config[SemanticsActions.OnClick].action!!.invoke())
                    assertTrue(environment.pica.open)
                    scene.render(frame*100_000_000L).use {image ->
                        File(output,"actual-home-${if(desk) "desk" else "classic"}-$width-${if(dark) "dark" else "light"}.png")
                            .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    }
                    runBlocking {environment.tumble.visible(false)};render()
                    assertTrue(nodes().none {it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Folio bookmark")})
                    runBlocking {environment.tumble.visible(true)}
                } finally {scene.close()}
            }
        } finally {scope.cancel();db.close();file.delete()}
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun reducedMotionAndHiddenWindowsKeepTheCharacterStill() = SwingUtilities.invokeAndWait {
        for(mode in 0..3) {
            val scene=ImageComposeScene(240,200) {
                FolioTheme.AppTheme(AppPalette.LIGHT,daylight=FolioDaylight.Neutral) {
                    CompositionLocalProvider(LocalFolioMotionAllowed provides (mode!=0),LocalFolioWindowVisible provides (mode!=1),
                        LocalPicaMotionActive provides (mode!=2),LocalMotion provides
                            resolveMotion(com.folio.reader.settings.MotionPreferences(),MotionTier.STANDARD,systemReduced=mode==3)) {
                        SableAvatar(Modifier.fillMaxSize(),reaction="greet",cue=1)
                    }
                }
            }
            try {
                repeat(5) {scene.render(it*100_000_000L).close()}
                fun hash(time:Long)=scene.render(time).use {it.encodeToData(EncodedImageFormat.PNG)!!.bytes.contentHashCode()}
                assertEquals(hash(1_000_000_000L),hash(5_000_000_000L))
            } finally {scene.close()}
        }
    }
}
