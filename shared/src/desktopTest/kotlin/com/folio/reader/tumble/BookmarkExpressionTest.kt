package com.folio.reader.tumble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class BookmarkExpressionTest {
    private val actions=listOf("idle","greet","inspect","bookmark","pet","celebrate","surprised","stretch","look","settle")

    @Test fun reactionsHaveDifferentFacesAndPaperPoses() {
        val calm=bookmarkFrame("idle",.4f)
        val curious=bookmarkFrame("inspect",.4f)
        val happy=bookmarkFrame("celebrate",.4f)
        val surprised=bookmarkFrame("surprised",.4f)
        assertTrue(curious.leftBrow>curious.rightBrow+.025f)
        assertTrue(curious.gazeX>.5f)
        assertTrue(happy.happyEyes>.5f && happy.smile>calm.smile+.2f && happy.mouthOpen<.15f)
        assertTrue(surprised.eyes>calm.eyes && surprised.mouthOpen>.05f && surprised.mouthOpen<.2f)
        assertTrue(bookmarkFrame("bookmark",.4f).gazeY>.5f)
        assertTrue(bookmarkFrame("idle",.4f,pose="sleep").eyes<.18f)
        assertTrue(bookmarkFrame("stretch",.4f).height>1.025f)
        assertTrue(actions.map {bookmarkFrame(it,.4f)}.distinct().size>=9)
        for(action in actions) for(step in 0..100) {
            val pose=bookmarkFrame(action,step/100f)
            assertTrue(kotlin.math.abs(pose.lean)<=4.01f,"Reactions stay controlled")
            assertTrue(pose.width in .95f..1.03f && pose.height in .98f..1.05f,"Paper retains its proportions")
            assertTrue(pose.mouthOpen<=.16f,"Expressions remain understated")
        }
    }

    @Test fun reactionsSettleAndReducedMotionKeepsExpressionsWithoutTimeBasedMovement() {
        for(action in actions) {
            val rest=bookmarkFrame("idle",1f)
            val end=bookmarkFrame(action,1f)
            fun BookmarkFrame.values()=listOf(bend,lean,lift,width,height,gazeX,gazeY,eyes,happyEyes,leftBrow,rightBrow,smile,mouthOpen,curl)
            rest.values().zip(end.values()).forEach {(a,b)->assertEquals(a,b,.0001f,"$action must settle back to the same rest pose")}
            assertEquals(bookmarkFrame(action,.4f,seconds=0f,motion=false),bookmarkFrame(action,.4f,seconds=100f,motion=false))
        }
        assertTrue(bookmarkFrame("celebrate",.4f,motion=false).happyEyes>.5f)
        assertTrue(bookmarkFrame("idle",.5f,seconds=4.25f).eyes<.05f)
        assertTrue(bookmarkFrame("idle",.5f,seconds=4.5f).eyes>.9f)
        val looking=bookmarkFrame("idle",.4f,gazeX=5f,gazeY=-5f)
        assertEquals(1f,looking.gazeX);assertEquals(-1f,looking.gazeY)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun expressionsRemainDistinctAtTheActualHomeSizeAndRenderAnimationPreviews() = SwingUtilities.invokeAndWait {
        val output=File("build/visual-review/bookmark-expressions").apply {mkdirs()}
        for(dark in listOf(false,true)) for(size in listOf(68 to 94,180 to 220)) {
            val state=mutableStateOf(BookmarkFrame())
            val scene=ImageComposeScene(size.first,size.second) {
                FolioTheme.AppTheme(if(dark) AppPalette.DARK else AppPalette.LIGHT,daylight=FolioDaylight.Neutral) {
                    BookmarkPortrait(state.value,Modifier.fillMaxSize().background(FolioTheme.colors.background),"plant")
                }
            }
            val hashes=mutableSetOf<Int>()
            try {
                for((index,action) in actions.withIndex()) {
                    state.value=bookmarkFrame(action,.4f,pose=if(action=="settle") "sleep" else "watch")
                    Snapshot.sendApplyNotifications()
                    scene.render(index*100_000_000L).use {image ->
                        val bytes=image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                        hashes+=bytes.contentHashCode()
                        File(output,"$action-${size.first}-${if(dark) "dark" else "light"}.png").writeBytes(bytes)
                    }
                }
                assertEquals(actions.size,hashes.size,"Every expression must remain visibly distinct at ${size.first}px")
            } finally {scene.close()}
        }
        val frame=mutableStateOf(BookmarkFrame())
        val scene=ImageComposeScene(240,280) {
            FolioTheme.AppTheme(AppPalette.LIGHT,daylight=FolioDaylight.Neutral) {
                BookmarkPortrait(frame.value,Modifier.fillMaxSize().background(FolioTheme.colors.background),"plant")
            }
        }
        val animation=File(output,"animation").apply {mkdirs()}
        try {
            val sequence=listOf("greet","inspect","bookmark","celebrate","surprised","stretch")
            var index=0
            for(action in sequence) for(step in 0..23) {
                frame.value=bookmarkFrame(action,step/23f,seconds=index/12f)
                Snapshot.sendApplyNotifications()
                scene.render(index*83_333_333L).use {image ->
                    File(animation,"frame-${index.toString().padStart(3,'0')}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
                index++
            }
        } finally {scene.close()}
    }
}
