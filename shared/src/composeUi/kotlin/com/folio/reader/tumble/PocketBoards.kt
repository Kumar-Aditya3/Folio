package com.folio.reader.tumble

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioPaperSheet
import com.folio.reader.ui.theme.*
import kotlin.math.*

private val lanternSymbols = listOf("●", "◆", "✦", "▲")

@Composable internal fun ShelfShiftBoard(state: ArcadeState, onSlide: (Slide) -> Unit, modifier: Modifier = Modifier) {
    val action by rememberUpdatedState(onSlide)
    FolioPaperSheet(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
            var drag = Offset.Zero
            detectDragGestures(onDragStart = { drag = Offset.Zero }, onDrag = { change, delta -> change.consume(); drag += delta }, onDragEnd = {
                if (drag.getDistance() > 22.dp.toPx()) action(if (abs(drag.x) > abs(drag.y)) { if (drag.x > 0) Slide.RIGHT else Slide.LEFT } else if (drag.y > 0) Slide.DOWN else Slide.UP)
            })
        }.semantics { contentDescription = "Shelf Shift, four by four board. Swipe to merge matching books. Arrow controls are below." }) {
            TileBoard(state, 4, maxWidth, false)
        }
    }
}

@Composable private fun BoxScope.TileBoard(state: ArcadeState, columns: Int, width: androidx.compose.ui.unit.Dp, lanterns: Boolean) {
    val motion = rememberMotionEnabled()
    val progress = remember { Animatable(1f) }
    LaunchedEffect(state.moves) {
        if (state.moves > 0 && motion) { progress.snapTo(0f); progress.animateTo(1f, tween(if (lanterns) 230 else 145, easing = FastOutSlowInEasing)) }
    }
    val colors = FolioTheme.colors
    val palette = listOf(colors.accentProgress, colors.accentDiscovery, colors.accentAnnotation, colors.accentStreak)
    val cell = width / columns
    val inset = 3.dp
    for (index in state.cells.indices) Box(Modifier.offset(cell*(index%columns)+inset,cell*(index/columns)+inset).size(cell-inset*2).background(colors.textPrimary.copy(alpha=.035f),RoundedCornerShape(10.dp)))
    val moving = progress.value < 1 && state.travel.isNotEmpty()
    val travels = if (moving) state.travel else state.cells.mapIndexedNotNull { index, value -> if (!lanterns && value == 0) null else TileTravel(index,index,value) }
    for (tile in travels) {
        val t = if (moving) progress.value else 1f
        val fromX = ((tile.from % columns) + columns) % columns
        val fromY = floor(tile.from.toFloat()/columns)
        val x = fromX + (tile.to % columns - fromX) * t
        val y = fromY + (tile.to / columns - fromY) * t
        val level = if (lanterns) tile.value else (ln(tile.value.toFloat())/ln(2f)).roundToInt()-1
        val accent = palette[level.mod(palette.size)]
        Box(Modifier.offset(cell*x+inset,cell*y+inset).size(cell-inset*2)
            .background(accent.copy(alpha=if(lanterns) .12f else (.10f+level*.018f).coerceAtMost(.3f)),RoundedCornerShape(if(lanterns) 50.dp else 10.dp))
            .border(.6.dp,accent.copy(alpha=.3f),RoundedCornerShape(if(lanterns) 50.dp else 10.dp)), contentAlignment=Alignment.Center) {
            if(!lanterns) Canvas(Modifier.matchParentSize()) {
                drawLine(accent.copy(alpha=.4f),Offset(6.dp.toPx(),5.dp.toPx()),Offset(6.dp.toPx(),size.height-5.dp.toPx()),.7.dp.toPx())
                drawLine(accent.copy(alpha=.2f),Offset(9.dp.toPx(),size.height-5.dp.toPx()),Offset(size.width-6.dp.toPx(),size.height-5.dp.toPx()),.5.dp.toPx())
            }
            Text(if(lanterns) lanternSymbols[tile.value] else "${tile.value}",
                style=if(lanterns) FolioTheme.typography.titleLarge else if(tile.value>=1024) FolioTheme.typography.titleMedium else FolioTheme.typography.headlineSmall,
                color=if(lanterns) accent else colors.textPrimary, textAlign=TextAlign.Center)
        }
    }
}

@Composable internal fun LanternLinesBoard(state: ArcadeState, onConnect: (List<Int>) -> Unit,
    onSelection: () -> Unit, modifier: Modifier = Modifier, accessible: Boolean = false) {
    var path by remember(state.moves) { mutableStateOf(emptyList<Int>()) }
    val connect by rememberUpdatedState(onConnect)
    val selectFeedback by rememberUpdatedState(onSelection)
    fun select(index: Int) {
        if (index !in state.cells.indices) return
        if (path.isEmpty()) { path=listOf(index); selectFeedback(); return }
        if (path.size > 1 && index == path[path.lastIndex-1]) { path=path.dropLast(1); return }
        if (path.size > 1 && path.first() == path.last()) return
        if (state.cells[index] != state.cells[path.first()] || !ArcadeEngine.adjacent(path.last(),index,6)) return
        if (index == path.first() && path.size >= 4) { path=path+index; selectFeedback() }
        else if (index !in path) { path=path+index; selectFeedback() }
    }
    FolioPaperSheet(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(state.moves,accessible) {
            if (accessible) return@pointerInput
            fun index(point: Offset): Int {
                if (point.x < 0 || point.y < 0 || point.x >= size.width || point.y >= size.height) return -1
                return (point.y/size.height*6).toInt()*6+(point.x/size.width*6).toInt()
            }
            try {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    path=emptyList(); select(index(down.position)); down.consume()
                    do {
                        val event=awaitPointerEvent()
                        val change=event.changes.firstOrNull { it.id==down.id } ?: break
                        if(change.pressed) select(index(change.position))
                        change.consume()
                    } while(event.changes.any { it.pressed })
                    val chosen=path; path=emptyList(); connect(chosen)
                }
            } finally { path=emptyList() }
        }.semantics { contentDescription="Lantern Lines. Connect matching symbols horizontally or vertically. Close a loop to clear that symbol everywhere." }) {
            TileBoard(state,6,maxWidth,true)
            val accent=FolioTheme.colors.accentProgress
            Canvas(Modifier.matchParentSize()) {
                val cell=size.width/6
                val preview=state.lanternSelection(path)
                if(preview?.loop==true) preview.cleared.forEach { index ->
                    drawCircle(accent.copy(alpha=.15f),cell*.44f,Offset((index%6+.5f)*cell,(index/6+.5f)*cell))
                }
                path.zipWithNext().forEach { (a,b) -> drawLine(accent.copy(alpha=.65f),Offset((a%6+.5f)*cell,(a/6+.5f)*cell),Offset((b%6+.5f)*cell,(b/6+.5f)*cell),cell*.14f,StrokeCap.Round) }
                path.distinct().forEach { index -> drawCircle(accent.copy(alpha=.5f),cell*.4f,Offset((index%6+.5f)*cell,(index/6+.5f)*cell),style=Stroke(2.dp.toPx())) }
            }
            if(accessible) for(index in state.cells.indices) Box(Modifier.offset(maxWidth/6*(index%6),maxWidth/6*(index/6)).size(maxWidth/6)
                .clickable { select(index) }.semantics { contentDescription="Row ${index/6+1}, column ${index%6+1}, ${lanternSymbols[state.cells[index]]}" })
        }
        val preview=state.lanternSelection(path)
        Text(when {
            preview?.loop==true -> "Loop · ${preview.cleared.size} cleared · +${scoreText(preview.points)}"
            preview!=null -> "${path.size} linked · +${scoreText(preview.points)}${if(path.size>=4 && ArcadeEngine.adjacent(path.last(),path.first(),6)) " · return to start for a loop" else ""}"
            path.isNotEmpty() -> "Keep connecting matching symbols"
            else -> "Close a loop to clear every matching symbol"
        },style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.textSecondary,
            modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
        if(accessible) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            com.folio.reader.ui.components.FolioActionButton(onClick={ val chosen=path; path=emptyList(); connect(chosen) },enabled=path.size>=2) { Text("Collect ${path.distinct().size}") }
            androidx.compose.material3.TextButton(onClick={path=emptyList()}) { Text("Clear") }
        }
    }
}

@Composable internal fun BookBalanceBoard(state: ArcadeState, onDrop: () -> Unit, modifier: Modifier = Modifier, offcut: BalanceBook? = null) {
    val colors=FolioTheme.colors
    val motion=rememberMotionEnabled()
    val fall=remember { Animatable(1f) }
    LaunchedEffect(state.moves) { if(motion && state.moves>0) { fall.snapTo(0f); fall.animateTo(1f,tween(450,easing=LinearEasing)) } }
    val action by rememberUpdatedState(onDrop)
    val palette=listOf(colors.accentProgress,colors.accentAnnotation,colors.accentDiscovery,colors.accentStreak)
    FolioPaperSheet(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(.86f).pointerInput(Unit) { detectTapGestures { action() } }
            .semantics { contentDescription="Book Balance. Tap anywhere to place the moving book on the tower."; onClick("Place book") { action(); true } }) {
            Canvas(Modifier.fillMaxSize()) {
                val unit=size.height/13
                val visible=state.books.takeLast(9)
                fun book(x:Float,width:Float,y:Float,index:Int,alpha:Float=1f) {
                    val c=palette[index.mod(4)]
                    drawRoundRect(c.copy(alpha=.16f*alpha),Offset(x*size.width,y),Size(width*size.width,unit*.83f),androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    drawRoundRect(c.copy(alpha=.8f*alpha),Offset(x*size.width,y),Size(width*size.width,unit*.83f),androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),style=Stroke(.8.dp.toPx()))
                    drawLine(colors.paperSurface.copy(alpha=.8f*alpha),Offset(x*size.width+8,y+unit*.42f),Offset((x+width)*size.width-5,y+unit*.42f),unit*.23f,StrokeCap.Round)
                    if(width>.08f) drawLine(c.copy(alpha=alpha),Offset(x*size.width+unit*.27f,y+3),Offset(x*size.width+unit*.27f,y+unit*.77f),1.dp.toPx())
                }
                val base=size.height-unit
                drawLine(colors.textPrimary.copy(alpha=.15f),Offset(0f,base+unit*.9f),Offset(size.width,base+unit*.9f),1.dp.toPx())
                visible.forEachIndexed { i,b -> book(b.x,b.width,base-i*unit,state.books.size-visible.size+i) }
                val activeY=base-visible.size*unit-unit*.35f
                if(!state.finished) {
                    val top=state.books.last()
                    val perfect=abs(top.x-state.movingX)<=.018f
                    val overlapLeft=max(top.x,state.movingX)
                    val overlapRight=min(top.x+top.width,state.movingX+top.width)
                    if(overlapRight>overlapLeft) drawRoundRect(
                        (if(perfect) colors.accentProgress else colors.accentDiscovery).copy(alpha=.2f),
                        Offset(overlapLeft*size.width,base-(visible.size-1)*unit-unit*.12f),
                        Size((overlapRight-overlapLeft)*size.width,unit*.12f),CornerRadius(2.dp.toPx()))
                    drawLine(colors.textPrimary.copy(alpha=.1f),Offset(top.x*size.width,activeY-unit*.6f),Offset(top.x*size.width,base-(visible.size-1)*unit),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5f,7f)))
                    drawLine(colors.textPrimary.copy(alpha=.1f),Offset((top.x+top.width)*size.width,activeY-unit*.6f),Offset((top.x+top.width)*size.width,base-(visible.size-1)*unit),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5f,7f)))
                    book(state.movingX,top.width,activeY,state.books.size)
                    if(perfect) drawRoundRect(colors.accentProgress,Offset(state.movingX*size.width,activeY),
                        Size(top.width*size.width,unit*.83f),CornerRadius(5.dp.toPx()),style=Stroke(2.dp.toPx()))
                }
                if(offcut!=null && fall.value<1) book(offcut.x,offcut.width,activeY+fall.value*size.height*.7f,state.books.size-1,1-fall.value)
            }
            val top=state.books.last()
            val unit=maxHeight/13
            PicaAvatar(Modifier.offset(maxWidth*(top.x+top.width/2)-25.dp,maxHeight-unit*(min(state.books.size,9)+2.7f)).size(50.dp),
                pose=if(state.combo>0) "wave" else "think",reaction=if(state.combo>0) "celebrate" else "idle",cue=state.moves)
        }
        Text("Align the edges · 3 perfect landings restore width",style=FolioTheme.typography.labelSmall,color=colors.textSecondary)
    }
}
