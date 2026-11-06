package com.folio.reader.tumble

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.legibleOn
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*

/** The paper flexes around its face; the top stays anchored to the book. */
@Composable internal fun SableAvatar(modifier: Modifier = Modifier, pose: String = "watch", reaction: String = "idle", cue: Int = 0,
    onTap: (() -> Unit)? = null, onHold: (() -> Unit)? = null, onStroke: (() -> Unit)? = null, decoration: String? = null,
    tapLabel: String = "Open Between chapters") {
    var inViewport by remember { mutableStateOf(true) }
    val motion=rememberMotionEnabled() && LocalFolioMotionAllowed.current!=false && LocalFolioWindowVisible.current &&
        !LocalMotion.current.reduced && LocalPicaMotionActive.current && inViewport
    var action by remember { mutableStateOf("idle") }
    val progress=remember { Animatable(1f) }
    var localAction by remember { mutableStateOf("idle") }
    var localCue by remember { mutableIntStateOf(0) }
    val delivered=remember { intArrayOf(-1,0) }
    LaunchedEffect(cue,localCue,motion,pose) {
        val first=delivered[0]<0
        val requested=when {
            localCue!=delivered[1] -> localAction
            cue!=delivered[0] && reaction!="idle" -> reaction
            first && pose!="sleep" && motion -> "greet"
            else -> "idle"
        }
        delivered[0]=cue;delivered[1]=localCue
        if(!motion) {action=requested;progress.snapTo(.4f);return@LaunchedEffect}
        suspend fun play(next:String) {
            action=next;progress.snapTo(0f)
            progress.animateTo(1f,tween(if(next=="inspect" || next=="look") 2600 else 1900,easing=LinearEasing))
            action="idle"
        }
        if(requested!="idle") play(requested)
        var turn=0
        while(true) {
            delay(6500L+(turn%3)*1700L)
            play(if(pose=="sleep") "settle" else listOf("look","peek","relax")[turn%3])
            turn++
        }
    }
    val clock=if(motion) rememberInfiniteTransition(label="bookmark life").animateFloat(0f,13f,
        infiniteRepeatable(tween(13_000,easing=LinearEasing)),label="breath and blink") else remember {mutableFloatStateOf(0f)}
    var gaze by remember {mutableStateOf(Offset.Zero)}
    var tracking by remember {mutableStateOf(false)}
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val stroke by rememberUpdatedState(onStroke)
    fun respond(next:String,callback:(()->Unit)?) {localAction=next;localCue++;callback?.invoke()}
    val surface=modifier.onGloballyPositioned { coordinates ->
        val bounds=coordinates.boundsInWindow()
        inViewport=bounds.width>0 && bounds.height>0 && bounds.overlaps(coordinates.findRootCoordinates().boundsInWindow())
    }.semantics {
        contentDescription="Folio bookmark"
        stateDescription=when(action) {
            "celebrate","proud" -> "Delighted"
            "inspect","peek","think" -> "Curious"
            "surprised" -> "Surprised"
            "pet","relax","greet","wave" -> "Smiling"
            "bookmark","read" -> "Following the page"
            "stretch" -> "Uncurling"
            else -> if(pose=="sleep") "Sleepy" else "Between chapters"
        }
        if(onTap!=null) onClick(tapLabel) {respond("greet",tap);true}
        if(onHold!=null) onLongClick("Uncurl bookmark") {respond("stretch",hold);true}
        if(onStroke!=null) customActions=listOf(CustomAccessibilityAction("Unfold bookmark") {respond("pet",stroke);true})
    }.pointerInput(onTap!=null,onHold!=null) {
        if(onTap!=null || onHold!=null) detectTapGestures(onTap={respond("greet",tap)},onLongPress={respond("stretch",hold)})
    }.pointerInput(motion) {
        if(!motion) return@pointerInput
        try {
            awaitPointerEventScope {
                while(true) {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    val point=event.changes.firstOrNull()?.position
                    if(event.type==PointerEventType.Exit || event.type==PointerEventType.Release) tracking=false
                    else if(point!=null) {
                        gaze=Offset((point.x/size.width.coerceAtLeast(1)*2-1).coerceIn(-1f,1f),(point.y/size.height.coerceAtLeast(1)*2-1).coerceIn(-1f,1f))
                        tracking=true
                    }
                }
            }
        } finally {tracking=false}
    }
    BookmarkDrawing(surface,decoration) {
        bookmarkFrame(action,progress.value,pose,clock.value,motion,
            gazeX=if(motion && tracking) gaze.x else null,gazeY=if(motion && tracking) gaze.y else null)
    }
}

/** Still portraits use exactly the same renderer as the live companion. */
@Composable internal fun BookmarkPortrait(frame:BookmarkFrame,modifier:Modifier=Modifier,decoration:String?=null) =
    BookmarkDrawing(modifier,decoration) {frame}

@Composable private fun BookmarkDrawing(modifier:Modifier,decoration:String?,frame:()->BookmarkFrame) {
    val colors=FolioTheme.colors
    val paper=FolioTheme.elements.paper
    val accent=if(decoration=="lamp") colors.accentAnnotation else colors.accentDiscovery
    val body=lerp(paper,accent,.18f)
    val ink=legibleOn(colors.textPrimary,body,FolioTheme.elements.ink)
    Canvas(modifier) {drawBookmark(frame(),paper,body,ink,accent,decoration)}
}

private fun DrawScope.drawBookmark(frame:BookmarkFrame,paper:Color,body:Color,ink:Color,accent:Color,decoration:String?) {
    val unit=min(size.width,size.height)
    val w=unit*.5f*frame.width
    val h=unit*.81f*frame.height
    val x=(size.width-w)/2
    val y=(size.height-h)/2-frame.lift*unit
    fun p(u:Float,v:Float)=Offset(x+u*w+sin(v*PI.toFloat()*.9f)*w*frame.bend,
        y+v*h+(u-.5f)*w*frame.curl*sin(v*PI.toFloat()))
    fun Path.move(point:Offset)=moveTo(point.x,point.y)
    fun Path.line(point:Offset)=lineTo(point.x,point.y)
    fun Path.quad(control:Offset,end:Offset)=quadraticTo(control.x,control.y,end.x,end.y)
    fun Path.curve(a:Offset,b:Offset,end:Offset)=cubicTo(a.x,a.y,b.x,b.y,end.x,end.y)
    fun ribbon()=Path().apply {
        move(p(.07f,.025f));line(p(.76f,.025f))
        quad(p(.80f,.025f),p(.835f,.07f));line(p(.98f,.17f))
        quad(p(.995f,.185f),p(.995f,.225f))
        curve(p(.99f,.46f),p(.99f,.7f),p(.99f,.94f))
        quad(p(.99f,.98f),p(.94f,.965f));line(p(.52f,.85f))
        quad(p(.5f,.845f),p(.48f,.85f));line(p(.06f,.965f))
        quad(p(.01f,.98f),p(.01f,.94f))
        curve(p(.015f,.65f),p(.015f,.3f),p(.015f,.08f))
        quad(p(.015f,.025f),p(.07f,.025f));close()
    }
    val thin=max(.65.dp.toPx(),unit*.006f)
    val faceStroke=max(.8.dp.toPx(),unit*.009f)
    rotate(frame.lean,p(.5f,.08f)) {
        translate(unit*.012f,unit*.016f) {drawPath(ribbon(),Color.Black.copy(alpha=.09f))}
        drawPath(ribbon(),Brush.linearGradient(listOf(lerp(body,paper,.24f),body,lerp(body,accent,.08f)),p(0f,0f),p(1f,.85f)))
        drawPath(ribbon(),ink.copy(alpha=.18f),style=Stroke(thin,join=StrokeJoin.Round))
        drawPath(Path().apply {move(p(.14f,.10f));curve(p(.10f,.35f),p(.12f,.65f),p(.13f,.86f))},
            accent.copy(alpha=.40f),style=Stroke(thin,cap=StrokeCap.Round))
        drawPath(Path().apply {move(p(.93f,.23f));curve(p(.97f,.48f),p(.91f,.76f),p(.92f,.91f))},
            paper.copy(alpha=.30f),style=Stroke(thin,cap=StrokeCap.Round))
        // The lifted corner has its own crease, shadow and highlight.
        val fold=Path().apply {
            move(p(.78f,.03f));quad(p(.76f,.115f),p(.77f,.19f))
            quad(p(.88f,.175f),p(.99f,.18f));quad(p(.89f,.115f),p(.78f,.03f));close()
        }
        translate(0f,thin) {drawPath(fold,ink.copy(alpha=.10f))}
        drawPath(fold,Brush.linearGradient(listOf(lerp(body,paper,.5f),lerp(body,accent,.3f)),p(.76f,.03f),p(.98f,.20f)))
        drawPath(Path().apply {move(p(.78f,.035f));quad(p(.76f,.115f),p(.77f,.19f));line(p(.98f,.18f))},
            ink.copy(alpha=.20f),style=Stroke(thin,cap=StrokeCap.Round,join=StrokeJoin.Round))
        // Ink apertures carry the expression without cartoon whites, pupils or blush.
        for((index,u) in listOf(.37f,.66f).withIndex()) {
            val lid=if(index==0) frame.leftBrow else frame.rightBrow
            val center=p(u+frame.gazeX*.032f,.35f+frame.gazeY*.018f-lid*.35f)
            val rx=max(.65.dp.toPx(),w*.032f)
            val ry=h*.033f*frame.eyes*(1+lid*3)
            when {
                frame.happyEyes>.5f -> drawPath(Path().apply {
                    move(center+Offset(-w*.05f,h*.004f));quad(center+Offset(0f,-h*.012f),center+Offset(w*.05f,h*.004f))
                },ink.copy(alpha=.9f),style=Stroke(faceStroke,cap=StrokeCap.Round))
                frame.eyes<.18f -> drawLine(ink.copy(alpha=.7f),center-Offset(w*.045f,0f),center+Offset(w*.045f,0f),faceStroke,StrokeCap.Round)
                else -> drawOval(ink.copy(alpha=.95f),center-Offset(rx,ry),Size(rx*2,ry*2))
            }
        }
        if(frame.smile>.12f || frame.mouthOpen>.04f) {
            val center=.515f
            drawPath(Path().apply {
                move(p(center-.09f,.455f));quad(p(center,.455f+frame.smile*.045f+frame.mouthOpen*.012f),p(center+.09f,.452f))
            },ink.copy(alpha=(.35f+frame.smile*.6f).coerceAtMost(.7f)),style=Stroke(faceStroke*.8f,cap=StrokeCap.Round))
        }
        // Existing styles become small printer's marks near the foot of the ribbon.
        when(decoration) {
            "stars" -> repeat(2) {i ->
                val u=.27f+i*.17f
                drawLine(accent.copy(alpha=.5f),p(u-.025f,.75f),p(u+.025f,.75f),thin*.8f)
                drawLine(accent.copy(alpha=.5f),p(u,.735f),p(u,.765f),thin*.8f)
            }
            "plant" -> {
                drawPath(Path().apply {move(p(.26f,.79f));quad(p(.29f,.735f),p(.33f,.69f))},accent.copy(alpha=.5f),style=Stroke(thin*.75f,cap=StrokeCap.Round))
                drawLine(accent.copy(alpha=.45f),p(.29f,.755f),p(.24f,.725f),thin*.75f,StrokeCap.Round)
                drawLine(accent.copy(alpha=.45f),p(.30f,.735f),p(.36f,.72f),thin*.75f,StrokeCap.Round)
            }
            "books" -> repeat(3) {i ->
                val v=.71f+i*.035f
                drawPath(Path().apply {move(p(.27f,v));quad(p(.44f,v-.004f),p(.61f,v))},ink.copy(alpha=.15f),style=Stroke(thin*.65f,cap=StrokeCap.Round))
            }
            else -> drawLine(accent.copy(alpha=.35f),p(.27f,.755f),p(.45f,.755f),thin*.8f,StrokeCap.Round)
        }
    }
}

@Composable internal expect fun rememberSableAtlas(): ImageBitmap
