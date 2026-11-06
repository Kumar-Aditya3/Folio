package com.folio.reader.tumble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class TumbleRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun pocketGamesAndAnimatedCompanionRenderAcrossThemes() {
        val fonts=requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
        UiFonts.install(File(fonts.toURI()).parentFile)
        val output=File("build/visual-review/sable").apply {mkdirs()}
        val cover = File(output, "preview-cover.png")
        val coverImage = BufferedImage(168, 252, BufferedImage.TYPE_INT_RGB)
        coverImage.createGraphics().let { graphics ->
            try {
                graphics.color = java.awt.Color(38, 57, 63); graphics.fillRect(0, 0, 168, 252)
                graphics.color = java.awt.Color(223, 197, 146); graphics.drawRect(12, 12, 143, 227)
                graphics.font = java.awt.Font("Serif", java.awt.Font.BOLD, 24)
                graphics.drawString("SMALL", 27, 61); graphics.drawString("WORLDS", 19, 88)
                graphics.drawOval(40, 112, 88, 88); graphics.drawOval(55, 112, 58, 88)
                graphics.drawLine(40, 156, 128, 156)
                graphics.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12)
                graphics.drawString("A. READER", 52, 223)
            } finally { graphics.dispose() }
        }
        check(ImageIO.write(coverImage, "png", cover))
        runBlocking { checkNotNull(loadCoverBitmap(cover.absolutePath)) }
        val now=System.currentTimeMillis()
        val runs=(1..12).map {n->ArcadeRun(ArcadeState("run-$n",PocketGame.SHELVES,n,now,score=n*520,moves=25+n,bestCombo=n%5,finished=true),now-n*3600000)}
        val data=TumbleLibrary(readingMinutes=300,owned=setOf("cushion","lamp"),decoration="lamp",arcadeRuns=runs,
            bond=PicaBond(affection=8,curiosity=4,calm=2,playfulness=5,discoveries=1,discoveryBaseline=20,memories=listOf(PicaMemory("We made a map of all the places our books might take us.",now))))
        val pica=PicaSession().apply {ready=true;hour=14;todayMinutes=25;bookTitle="Small Worlds";bookAuthor="A. Reader";bookCover=cover.absolutePath;bookId="preview";onContinue={};finishedTitles=listOf("Small Worlds","The Creative Act");quote="To pay attention, this is our endless and proper work.";sessionLine="25 min with Small Worlds · your place is kept"}
        val fallback = PicaSession().apply { ready = true; hour = 14; bookTitle = pica.bookTitle; bookAuthor = pica.bookAuthor; bookId = "preview"; onContinue = {} }
        val shelf=ArcadeEngine(ArcadeState("preview",PocketGame.SHELVES,42,now))
        repeat(18) {if(shelf.state.draft.isNotEmpty()) shelf.chooseBookmark(shelf.state.draft.first());shelf.slide(Slide.entries[it%4])}
        val lanterns=ArcadeEngine(ArcadeState("lanterns",PocketGame.LANTERNS,42,now))
        val balance=ArcadeState("balance",PocketGame.BALANCE,42,now,movingX=.4f,score=750,moves=5,combo=2,
            books=listOf(BalanceBook(.23f,.54f),BalanceBook(.24f,.51f),BalanceBook(.24f,.51f,true),BalanceBook(.28f,.45f),BalanceBook(.28f,.45f,true)))
        var failure:Throwable?=null
        SwingUtilities.invokeAndWait {
            try {
                for(dark in listOf(false,true)) {
                    val palette=AppPalette.entries.first {it.isDark==dark}
                    val pages:List<Pair<String,@Composable ()->Unit>> = listOf(
                        "home" to {Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            FolioSectionHead("Folio",eyebrow="YOUR READING ROOM")
                            FolioPaperSheet {
                                Text("Continue reading",style=FolioTheme.typography.labelMedium);Text("Small Worlds",style=FolioTheme.typography.headlineSmall);Text("Chapter 4 · 38%",style=FolioTheme.typography.bodySmall)
                                PicaHomeCard(data,pica,onPet={},onVisit={})
                            }
                            FolioSectionHead("This week",eyebrow="A LITTLE EVERY DAY")
                        }},
                        "desk" to {Column(Modifier.widthIn(max=680.dp).fillMaxSize().padding(20.dp)) {FolioSectionHead("Between chapters",eyebrow="FOLIO");SableNook(data,pica,{},{},{},{},{})}},
                        "desk-fallback" to {Column(Modifier.widthIn(max=680.dp).fillMaxSize().padding(20.dp)) {FolioSectionHead("Between chapters");SableNook(data,fallback,{},{},{},{},{})}},
                        "night" to {androidx.compose.runtime.CompositionLocalProvider(LocalFolioDaylight provides daylightAt(23)) {Column(Modifier.widthIn(max=680.dp).fillMaxSize().padding(20.dp)) {FolioSectionHead("Between chapters");SableNook(data,PicaSession().apply {hour=23;bookTitle="Small Worlds"},{},{},{},{},{})}}},
                        "object" to {Column(Modifier.widthIn(max=680.dp).fillMaxSize().padding(20.dp)) {FolioSectionHead("Shelf Shift");SableGameObject(PocketGame.SHELVES,data,pica,false,{_,_->},{},{},{})}},
                        "object-lanterns" to {Column(Modifier.widthIn(max=680.dp).fillMaxSize().padding(20.dp)) {FolioSectionHead("Lantern Lines");SableGameObject(PocketGame.LANTERNS,data,pica,false,{_,_->},{},{},{})}},
                        "object-balance" to {Column(Modifier.widthIn(max=680.dp).fillMaxSize().padding(20.dp)) {FolioSectionHead("Book Balance");SableGameObject(PocketGame.BALANCE,data,pica,false,{_,_->},{},{},{})}},
                        "shelves" to {PocketGameContent(shelf.state,pica,{},{},{},{},{},false,false,{},null,{})},
                        "bookmarks" to {PocketGameContent(shelf.state.copy(draft=RunBookmark.entries.filterNot {it in shelf.state.bookmarks}.take(2)),pica,{},{},{},{},{},false,false,{},null,{})},
                        "lanterns" to {PocketGameContent(lanterns.state,pica,{},{},{},{},{},false,false,{},null,{})},
                        "balance" to {PocketGameContent(balance,pica,{},{},{},{},{},false,false,{},null,{})},
                        "history" to {Column(Modifier.fillMaxSize().padding(20.dp)) {FolioSectionHead("Your arcade journal");PocketHistory(data){}}},
                        "result" to {Column(Modifier.fillMaxSize().padding(20.dp)) {PocketResult(runs.last(),runs,true,{},{},{})}},
                        "journal" to {Column(Modifier.fillMaxSize().padding(20.dp)) {SableJournal(data,pica,{},{})}},
                        "discovery" to {Column(Modifier.fillMaxSize().padding(20.dp)) {SableNook(data.copy(readingMinutes=315),pica,{},{},{},{},{})}},
                    )
                    for((name,content) in pages) for((width,height) in listOf(393 to 852,320 to 640,1000 to 800)) {
                        val scene=ImageComposeScene(width,height) {
                            FolioTheme.AppTheme(palette,daylight=FolioDaylight.Neutral) {
                                FolioGlassRoot(GlassCapabilities.None) {
                                    Box(Modifier.fillMaxSize().folioField(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {content()}
                                }
                            }
                        }
                        try {
                            listOf(0L,1_000_000_000L,2_000_000_000L,4_500_000_000L,5_000_000_000L).forEach {scene.render(it).close()}
                            scene.render(5_100_000_000L).use {image->File(output,"$name-$width-${if(dark) "dark" else "light"}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
                        } finally {scene.close()}
                    }
                }
                for(palette in AppPalette.entries) {
                    val preview=ImageComposeScene(192,192) {
                        FolioTheme.AppTheme(palette,daylight=FolioDaylight.Neutral) {
                            Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {
                                SableAvatar(Modifier.fillMaxSize(),decoration="plant")
                            }
                        }
                    }
                    try {preview.render(0L).use {image->File(output,"bookmark-${palette.name.lowercase()}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
                    finally {preview.close()}
                }
                val scene=ImageComposeScene(360,260) {
                    FolioTheme.AppTheme(AppPalette.entries.first { !it.isDark },daylight=FolioDaylight.Neutral) {
                        Box(Modifier.fillMaxSize().background(FolioTheme.colors.background)) {PicaAvatar(Modifier.fillMaxSize(),"watch","stretch",1,decoration="lamp")}
                    }
                }
                val hashes=mutableSetOf<Int>()
                val animation=File(output,"stretch").apply {mkdirs()}
                try {
                    for(index in 0..75) {
                        scene.render(index*20_000_000L).use {image->val bytes=image.encodeToData(EncodedImageFormat.PNG)!!.bytes;hashes+=bytes.contentHashCode();File(animation,"frame-${index.toString().padStart(3,'0')}.png").writeBytes(bytes)}
                    }
                } finally {scene.close()}
                assertTrue(hashes.size>=4,"The bookmark must respond with visible paper movement")
            } catch(error:Throwable) {failure=error}
        }
        failure?.let {throw it}
    }
}
