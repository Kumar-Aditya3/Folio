package com.folio.reader.tumble

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.*
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PocketGestureTest {
    @Test fun horizontalAndVerticalSwipesReachTheGameInsideItsScrollingScreen() = SwingUtilities.invokeAndWait {
        for(direction in listOf(Slide.LEFT,Slide.UP)) {
            val cells=if(direction==Slide.LEFT) listOf(2,2,0,0)+List(12){0} else listOf(2,0,0,0,2)+List(11){0}
            Fixture(ArcadeState("gesture",PocketGame.SHELVES,42,0,cells=cells)).use {f->
                val board=f.node("Shelf Shift,").boundsInRoot
                val from=if(direction==Slide.LEFT) Offset(board.left+board.width*.8f,board.center.y) else Offset(board.center.x,board.top+board.height*.8f)
                val to=if(direction==Slide.LEFT) Offset(board.left+board.width*.2f,board.center.y) else Offset(board.center.x,board.top+board.height*.2f)
                f.drag(listOf(from,(from+to)*.5f,to))
                assertEquals(1,f.engine.state.moves,"The parent scroll must not steal $direction")
                assertEquals(4,f.engine.state.cells[0])
                assertEquals(4,f.engine.state.score)
            }
        }
    }
    @Test fun tracingALoopClearsLanternsOnReleaseAndTapPlacesABook() = SwingUtilities.invokeAndWait {
        Fixture(ArcadeState("lanterns",PocketGame.LANTERNS,42,0,cells=List(36){if(it==35)1 else 0})).use {f->
            val board=f.node("Lantern Lines.").boundsInRoot
            val positions=listOf(0,1,7,6,0).map {i->Offset(board.left+(i%6+.5f)*board.width/6,board.top+(i/6+.5f)*board.height/6)}
            f.drag(positions)
            assertEquals(1,f.engine.state.moves)
            assertEquals(35*35*10+200,f.engine.state.score)
        }
        Fixture(ArcadeState("balance",PocketGame.BALANCE,42,0,movingX=.3f)).use {f->
            val point=f.node("Book Balance.").boundsInRoot.center
            f.scene.sendPointerEvent(PointerEventType.Press,point);f.scene.sendPointerEvent(PointerEventType.Release,point);f.render()
            assertEquals(1,f.engine.state.moves)
            assertEquals(100,f.engine.state.score)
            assertEquals(.47f,f.engine.state.books.last().width,.0001f)
        }
    }
    @Test fun bookmarkOpensItsPassageAndSupportsHoldAndAccessibleUnfold() = SwingUtilities.invokeAndWait {
        Fixture(ArcadeState("pet",PocketGame.SHELVES,42,0),corner=true).use {f->
            val bounds=f.node("Folio bookmark").boundsInRoot
            val point=bounds.center
            f.scene.sendPointerEvent(PointerEventType.Press,point);f.scene.sendPointerEvent(PointerEventType.Release,point);f.render()
            assertEquals(0,f.pets)
            assertTrue(f.scene.semanticsOwners.flatMap {it.getAllSemanticsNodes(mergingEnabled=false)}.any {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any {text->text.text.contains("Highlight a passage")}
            })
            f.scene.sendPointerEvent(PointerEventType.Press,point);f.render();Thread.sleep(80);f.render()
            f.scene.sendPointerEvent(PointerEventType.Release,point);f.render()
            assertEquals(1,f.rolls)
            val unfold=f.node("Folio bookmark").config[SemanticsActions.CustomActions].single()
            assertTrue(unfold.action())
            assertEquals(1,f.pets)
        }
    }
    private class Fixture(initial:ArcadeState,corner:Boolean=false):AutoCloseable {
        init {val fonts=requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"));UiFonts.install(File(fonts.toURI()).parentFile)}
        val engine=ArcadeEngine(initial)
        val snapshot=mutableStateOf(engine.state)
        val pica=PicaSession().apply {ready=true}
        var pets=0
        var rolls=0
        val scene=ImageComposeScene(393,852) {
            val config=LocalViewConfiguration.current
            val quick=remember(config) {object:ViewConfiguration by config {override val longPressTimeoutMillis=30L}}
            FolioTheme.AppTheme(AppPalette.entries.first {!it.isDark},daylight=FolioDaylight.Neutral) {
                FolioGlassRoot(GlassCapabilities.None) {
                    CompositionLocalProvider(LocalViewConfiguration provides quick) {
                        Box(Modifier.fillMaxSize().folioField()) {
                            if(corner) SableNook(TumbleLibrary(),pica,onPet={pets++;pica.react("pet")},onStretch={rolls++;pica.react("stretch")},onChoice={},onGame={},onJournal={})
                            else PocketGameContent(snapshot.value,pica,
                                onSlide={engine.slide(it);snapshot.value=engine.state},onConnect={engine.connect(it);snapshot.value=engine.state},
                                onDrop={engine.drop();snapshot.value=engine.state},onPause={},onHelp={},help=false,accessible=false,onAccessible={},offcut=null,onSelect={})
                        }
                    }
                }
            }
        }
        var frame=0L
        init {render()}
        fun render() {repeat(8) {Snapshot.sendApplyNotifications();scene.render(frame++*16_666_667L).close()}}
        fun node(prefix:String)=scene.semanticsOwners.flatMap {it.getAllSemanticsNodes(mergingEnabled=false)}.first {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {text->text.startsWith(prefix)}
        }
        fun drag(points:List<Offset>) {
            scene.sendPointerEvent(PointerEventType.Press,points.first());render()
            points.drop(1).forEach {point->scene.sendPointerEvent(PointerEventType.Move,point);render()}
            scene.sendPointerEvent(PointerEventType.Release,points.last());render()
        }
        override fun close()=scene.close()
    }
}
