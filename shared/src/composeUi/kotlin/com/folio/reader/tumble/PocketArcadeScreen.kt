package com.folio.reader.tumble

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.reader.experience.ReadingEnvironment
import com.folio.reader.experience.ReaderForegroundEffect
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import java.util.UUID
import kotlin.math.*

private fun arcadeDay() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
internal fun PocketGame.instructions() = when(this) {
    PocketGame.SHELVES -> "Swipe matching books together. Each book merges once per swipe. Consecutive merges earn a streak bonus. Every 12 moves, choose a bookmark that changes how this run scores. Combine up to three."
    PocketGame.LANTERNS -> "Drag through matching symbols, horizontally or vertically. Release to collect. Return to your first lantern to close a loop and clear that symbol everywhere. You have 25 moves."
    PocketGame.BALANCE -> "Tap the board or Place book to land the moving book. Overhang falls away. Three perfect landings restore a little width. How tall can you go?"
}

@Composable internal fun PocketArcadeHost(environment: ReadingEnvironment) {
    val pica=environment.pica
    val repository=environment.tumble
    val library by repository.library.collectAsState()
    val scope=rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf("Desk") }
    var historyGame by rememberSaveable {mutableStateOf(PocketGame.SHELVES)}
    var engine by remember { mutableStateOf<ArcadeEngine?>(null) }
    var result by remember { mutableStateOf<ArcadeRun?>(null) }
    var pauseSignal by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    fun perform(action:suspend ()->Unit) { scope.launch {
        try { action(); pica.error=null } catch(e:CancellationException) { throw e }
        catch(_:Exception) { pica.error="Couldn't save that change. Your progress is still here; try again." }
    } }
    fun start(game:PocketGame,daily:Boolean=false,seed:Int?=null,practice:Boolean=false) {
        if(busy || !pica.ready || pica.active!=null) return
        busy=true
        perform {
            try {
                val day=if(daily) arcadeDay() else ""
                val next=ArcadeEngine(ArcadeState(UUID.randomUUID().toString(),game,
                    seed ?: if(daily) day.hashCode() xor (game.ordinal*104729) else kotlin.random.Random.nextInt(),
                    Clock.System.now().toEpochMilliseconds(),readingTitle=pica.bookTitle,daily=day,practice=practice))
                repository.checkpointArcade(next.state)
                historyGame=game;pica.active=next.state; engine=next; result=null; pauseSignal=0; pica.react("greet")
            } finally { busy=false }
        }
    }
    fun back() {
        when { engine!=null->pauseSignal++; result!=null->result=null; pica.selectedGame!=null->pica.selectedGame=null;tab!="Desk"->tab="Desk";else->pica.open=false }
    }
    fun resume() { pica.active?.let {engine=ArcadeEngine(it);pauseSignal=0} }
    Dialog(onDismissRequest=::back,
        properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnBackPress=true,dismissOnClickOutside=false)) {
        PocketArcadeWindow()
        FolioGlassRoot(GlassCapabilities.None) {
            Box(Modifier.fillMaxSize().folioField(),contentAlignment=Alignment.TopCenter) {
                if(engine!=null) {
                    PocketGameSession(engine!!,environment,pauseSignal,onReturn={ snapshot -> pica.active=snapshot; engine=null },onFinish={ run ->
                        pica.active=null; engine=null; result=run; pica.react(if(!run.abandoned) "celebrate" else "greet")
                    })
                } else Column(Modifier.widthIn(max=680.dp).fillMaxSize().safeDrawingPadding().padding(horizontal=FolioTokens.gutter)) {
                    FolioBackHandler { back() }
                    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(result!=null || tab!="Desk" || pica.selectedGame!=null) IconButton(::back) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back",tint=FolioTheme.colors.textPrimary)}
                        FolioSectionHead(result?.state?.game?.title ?: if(tab!="Desk") "Your journal" else pica.selectedGame?.title ?: "Between chapters",
                            Modifier.weight(1f),eyebrow=if(tab=="Desk" && pica.selectedGame==null && result==null) "FOLIO" else "BETWEEN CHAPTERS")
                        IconButton(onClick={pica.open=false}) { Icon(Icons.Filled.Close,"Back to Folio",tint=FolioTheme.colors.textPrimary) }
                    }
                    pica.error?.let { Text(it,style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.error,modifier=Modifier.padding(vertical=8.dp)) }
                    when {
                        !pica.ready -> Column { Text("Finding your saved place…",style=FolioTheme.typography.bodyLarge);if(pica.error!=null) FolioActionButton({pica.reload++}) {Text("Try loading again")} }
                        result!=null -> PocketResult(result!!,library.arcadeRuns,pica.active==null && !busy,
                            onAgain={ val r=result!!.state; start(r.game,r.daily.isNotEmpty(),if(r.practice) r.seed else null,r.practice) },
                            onReplay={ val r=result!!.state; start(r.game,seed=r.seed,practice=true) },onHistory={historyGame=result!!.state.game;tab="My runs";pica.selectedGame=null;result=null})
                        tab!="Desk" -> {
                            Row(Modifier.padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FolioChip(tab=="Journal",{tab="Journal"},"Memories")
                                FolioChip(tab=="My runs",{tab="My runs"},"All my attempts")
                            }
                            if(tab=="My runs") PocketHistory(library,historyGame,onOpen={result=it})
                            else SableJournal(library,pica,onDecorate={id->perform {repository.decorate(id)}},onVisible={value->perform {repository.visible(value)}})
                        }
                        pica.selectedGame!=null -> SableGameObject(pica.selectedGame!!,library,pica,busy,
                            onStart={daily,practice->start(pica.selectedGame!!,daily,practice=practice)},onResume=::resume,
                            onAbandon={pica.active?.let {active->perform {repository.finishArcade(ArcadeRun(active,Clock.System.now().toEpochMilliseconds(),abandoned=true));pica.active=null}}},
                            onHistory={historyGame=pica.selectedGame!!;pica.selectedGame=null;tab="My runs"})
                        else -> SableNook(library,pica,
                            onPet={pica.react("pet");perform {repository.pet()}},onStretch={pica.react("stretch")},
                            onChoice={choice->perform {repository.discover(choice);pica.react("bookmark")}},
                            onGame={pica.selectedGame=it},onJournal={tab="Journal"})
                    }
                }
            }
        }
    }
}

@Composable internal fun SableGameObject(game:PocketGame,data:TumbleLibrary,pica:PicaSession,busy:Boolean,
    onStart:(Boolean,Boolean)->Unit,onResume:()->Unit,onAbandon:()->Unit,onHistory:()->Unit) {
    val best=data.arcadeRuns.filter {it.state.game==game && it.state.daily.isEmpty() && it.keepsakeEligible()}.maxOfOrNull {it.state.score}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PocketGameIcon(game,Modifier.size(72.dp))
            Column(Modifier.weight(1f)) {
                Text(game.subtitle,style=FolioTheme.typography.titleMedium,color=FolioTheme.colors.textPrimary)
                Text(when(game) {PocketGame.SHELVES->"Open ended · swipe or arrows";PocketGame.LANTERNS->"25 moves · drag or tap";PocketGame.BALANCE->"Timing · tap or spacebar"},style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
            }
        }
        Text(game.instructions(),style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textSecondary)
        Text(if(best==null) "No scores yet." else "Your best · ${scoreText(best)} points",style=FolioTheme.typography.labelMedium,color=FolioTheme.colors.accentProgress)
        if(pica.active!=null) {
            Text("${pica.active!!.game.title} · ${scoreText(pica.active!!.score)} points saved",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
            FolioActionButton(onResume,Modifier.fillMaxWidth(),!busy) {Text("Resume saved game")}
            TextButton(onAbandon,enabled=!busy) {Text("End saved attempt")}
        } else {
            FolioActionButton({onStart(false,false)},Modifier.fillMaxWidth(),!busy) {Text("Play")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton({onStart(true,false)},enabled=!busy) {Text("Today's board")}
                TextButton({onStart(false,true)},enabled=!busy) {Text("Practice")}
            }
        }
        TextButton(onHistory) {Text("Scores & past runs")}
        Text("Your best scores are saved in the journal.",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun PocketGameSession(engine:ArcadeEngine,environment:ReadingEnvironment,pauseSignal:Int,
    onReturn:(ArcadeState)->Unit,onFinish:(ArcadeRun)->Unit) {
    var state by remember(engine) {mutableStateOf(engine.state)}
    var paused by remember {mutableStateOf(false)}
    var help by remember {mutableStateOf(false)}
    var accessible by rememberSaveable {mutableStateOf(false)}
    var offcut by remember {mutableStateOf<BalanceBook?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    val haptics=rememberFolioHaptics()
    val scope=rememberCoroutineScope()
    val pica=environment.pica
    val currentReturn by rememberUpdatedState(onReturn)
    val currentFinish by rememberUpdatedState(onFinish)
    val focus=remember {FocusRequester()}
    fun saveReturn() {scope.launch {try {
        environment.tumble.checkpointArcade(engine.state);currentReturn(engine.state)
    } catch(e:CancellationException) {throw e} catch(_:Exception) {error="Couldn't save this game. Try again."} } }
    fun moved(before:Int) {
        state=engine.state;pica.active=state
        if(state.moves!=before) {
            val missed=state.finished && state.game==PocketGame.BALANCE && state.lastGain==0
            haptics.play(if(missed) FolioHaptic.Reject else if(state.combo>0) FolioHaptic.Confirm else FolioHaptic.ScrubTick)
            pica.react(if(missed) "surprised" else if(state.combo>0) "celebrate" else "wave")
        }
    }
    fun slide(direction:Slide) {if(!paused) {val before=state.moves;engine.slide(direction);moved(before)}}
    fun connect(path:List<Int>) {if(!paused) {val before=state.moves;engine.connect(path);moved(before)}}
    fun drop() {if(!paused) {
        val previous=engine.state
        val top=previous.books.last()
        offcut=if(abs(top.x-previous.movingX)<=.018f) null else if(previous.movingX<top.x) BalanceBook(previous.movingX,(top.x-previous.movingX).coerceAtMost(top.width)) else BalanceBook(top.x+top.width,(previous.movingX-top.x).coerceAtMost(top.width))
        engine.drop();moved(previous.moves)
    }}
    ReaderForegroundEffect {if(!it) paused=true}
    FolioBackHandler {paused=true}
    LaunchedEffect(pauseSignal) {if(pauseSignal>0) paused=true}
    LaunchedEffect(engine) {focus.requestFocus()}
    DisposableEffect(engine) {onDispose { val snapshot=engine.state; if(!snapshot.finished) {
        pica.active=snapshot;environment.scope.launch {runCatching {environment.tumble.checkpointArcade(snapshot)}}
    } } }
    LaunchedEffect(engine,paused) {
        if(paused || engine.state.game!=PocketGame.BALANCE) return@LaunchedEffect
        var previous=withFrameNanos {it}
        while(!engine.state.finished) {
            val now=withFrameNanos {it};val seconds=(now-previous)/1_000_000_000f;previous=now
            if(seconds>.5f) {paused=true;break}
            engine.tick(seconds);state=engine.state
        }
    }
    LaunchedEffect(engine,state.moves,state.bookmarks,paused) {
        if(!engine.state.finished) try {environment.tumble.checkpointArcade(engine.state)}
        catch(e:CancellationException) {throw e} catch(_:Exception) {error="Couldn't save. Your game is still here.";paused=true}
    }
    LaunchedEffect(engine) {while(!engine.state.finished) {
        delay(2000)
        if(engine.state.game==PocketGame.BALANCE && !paused) try {environment.tumble.checkpointArcade(engine.state)}
        catch(e:CancellationException) {throw e} catch(_:Exception) {error="Couldn't save. Your game is still here.";paused=true}
    }}
    LaunchedEffect(state.finished,retry) {if(state.finished) try {
        val run=ArcadeRun(state,Clock.System.now().toEpochMilliseconds())
        environment.tumble.finishArcade(run);delay(260);currentFinish(run)
    } catch(e:CancellationException) {throw e} catch(_:Exception) {error="Couldn't save your score. Try saving again.";paused=true} }
    Box(Modifier.fillMaxSize().safeDrawingPadding().focusRequester(focus).onPreviewKeyEvent {
        if(it.type!=KeyEventType.KeyDown) false else when {
            it.key==Key.Escape->{paused=true;true}
            paused->false
            state.game==PocketGame.BALANCE && it.key==Key.Spacebar->{drop();true}
            state.game==PocketGame.SHELVES && state.draft.isEmpty()->when(it.key) {
                Key.DirectionLeft->{slide(Slide.LEFT);true};Key.DirectionRight->{slide(Slide.RIGHT);true}
                Key.DirectionUp->{slide(Slide.UP);true};Key.DirectionDown->{slide(Slide.DOWN);true};else->false
            }
            else->false
        }
    }.focusable(),contentAlignment=Alignment.TopCenter) {
        if(paused) Column(Modifier.widthIn(max=560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            SableAvatar(Modifier.size(160.dp),"sleep",pica.reaction,pica.cue,onTap={pica.react("pet")},tapLabel="Flip bookmark")
            Text(if(error!=null) "Save your game" else "Game paused",style=FolioTheme.typography.headlineSmall,color=FolioTheme.colors.textPrimary)
            Text(error ?: "Your game is exactly where you left it.",style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textSecondary,modifier=Modifier.padding(vertical=16.dp))
            if(state.finished) FolioActionButton({retry++},Modifier.fillMaxWidth()) {Text("Retry saving score")}
            else {
                FolioActionButton({paused=false;error=null},Modifier.fillMaxWidth()) {Text("Keep playing")}
                FolioActionButton(::saveReturn,Modifier.fillMaxWidth().padding(top=10.dp)) {Text("Save & return")}
                TextButton({engine.end();state=engine.state;paused=false}) {Text("Bank this score & finish")}
            }
        } else PocketGameContent(state,pica,::slide,::connect,::drop,{paused=true},{help=!help},help,accessible,{accessible=!accessible},offcut,
            onSelect={haptics.play(FolioHaptic.ScrubTick)},onBookmark={bookmark->if(engine.chooseBookmark(bookmark)) {state=engine.state;pica.active=state;pica.react("celebrate");haptics.play(FolioHaptic.Confirm)}})
    }
}

@Composable internal fun PocketGameContent(state:ArcadeState,pica:PicaSession,onSlide:(Slide)->Unit,onConnect:(List<Int>)->Unit,
    onDrop:()->Unit,onPause:()->Unit,onHelp:()->Unit,help:Boolean,accessible:Boolean,onAccessible:()->Unit,offcut:BalanceBook?,onSelect:()->Unit,onBookmark:(RunBookmark)->Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    val boardWidth=(maxHeight*.50f).coerceIn(200.dp,460.dp)
    Column(Modifier.widthIn(max=620.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.game.title,style=FolioTheme.typography.headlineSmall,color=FolioTheme.colors.textPrimary)
                Text(if(state.practice) "SEED PRACTICE" else if(state.daily.isNotEmpty()) "DAILY · ${state.daily}" else "YOUR NEXT PERSONAL BEST",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.accentProgress)
            }
            TextButton(onPause) {Text("Pause")}
        }
        FolioGlassPanel(contentPadding=PaddingValues(horizontal=14.dp,vertical=6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                PicaAvatar(Modifier.size(58.dp),if(state.combo>0) "wave" else "think",pica.reaction,pica.cue)
                Column(Modifier.weight(1f)) {Text(scoreText(state.score),style=FolioTheme.typography.headlineSmall,color=FolioTheme.colors.textPrimary);Text("points",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.textSecondary)}
                Column(horizontalAlignment=Alignment.End) {
                    Text(when(state.game) {PocketGame.SHELVES->"Next ${state.nextTile}";PocketGame.LANTERNS->"${25-state.moves} moves";PocketGame.BALANCE->"${state.books.size-1} ${if(state.books.size==2) "book" else "books"}"},style=FolioTheme.typography.titleMedium,color=FolioTheme.colors.textPrimary)
                    Text(if(state.combo>0) "${state.combo} streak" else "Take your time",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.textSecondary)
                }
            }
        }
        if(state.draft.isNotEmpty()) {
            FolioPaperSheet(accent=FolioTheme.colors.accentDiscovery) {
                FolioEyebrow("MAKE THIS RUN YOURS")
                Text("A bookmark for the next chapter",style=FolioTheme.typography.headlineSmall,color=FolioTheme.colors.textPrimary)
                Text("Pick one. Its bonus lasts for this run and combines with your other bookmarks.",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
                state.draft.forEach {bookmark->
                    FolioGlassPanel {
                        Text(bookmark.title,style=FolioTheme.typography.titleMedium,color=FolioTheme.colors.textPrimary)
                        Text(bookmark.description,style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
                        FolioActionButton({onBookmark(bookmark)},Modifier.fillMaxWidth()) {Text("Take ${bookmark.title.lowercase()}")}
                    }
                }
            }
        } else when(state.game) {
            PocketGame.SHELVES->ShelfShiftBoard(state,onSlide,Modifier.widthIn(max=boardWidth).align(Alignment.CenterHorizontally))
            PocketGame.LANTERNS->LanternLinesBoard(state,onConnect,onSelect,Modifier.widthIn(max=boardWidth).align(Alignment.CenterHorizontally),accessible=accessible)
            PocketGame.BALANCE->BookBalanceBoard(state,onDrop,Modifier.widthIn(max=boardWidth).align(Alignment.CenterHorizontally),offcut=offcut)
        }
        Text(state.message.ifBlank {state.game.subtitle},style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary,modifier=Modifier.fillMaxWidth())
        if(state.draft.isEmpty()) when(state.game) {
            PocketGame.SHELVES->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("←" to Slide.LEFT,"↑" to Slide.UP,"↓" to Slide.DOWN,"→" to Slide.RIGHT).forEach { (label,direction)->
                    FolioActionButton({onSlide(direction)},Modifier.weight(1f).semantics {contentDescription="Slide ${direction.name.lowercase()}"},enabled=!state.finished) {Text(label)}
                }
            }
            PocketGame.BALANCE->FolioActionButton(onDrop,Modifier.fillMaxWidth(),enabled=!state.finished) {Text("Place book")}
            PocketGame.LANTERNS->TextButton(onAccessible) {Text(if(accessible) "Use drag gestures" else "Use tap controls")}
        }
        if(state.readingTitle.isNotBlank()) Text("Between chapters of ${state.readingTitle}",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.textSecondary,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onHelp) {Text(if(help) "Hide how to play" else "How to play")}
        if(state.game==PocketGame.SHELVES && state.bookmarks.size<3 && state.draft.isEmpty()) {
            val remaining=12-state.moves%12
            Text("Next bookmark in $remaining ${if(remaining==1) "move" else "moves"}",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.accentDiscovery)
        }
        if(state.bookmarks.isNotEmpty()) Text("Bookmarks · ${state.bookmarks.joinToString(" + ") {it.title}}",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.accentProgress)
        if(help) Text(state.game.instructions(),style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
        Spacer(Modifier.height(8.dp))
    }
}
}

@Composable internal fun PocketResult(run:ArcadeRun,all:List<ArcadeRun>,enabled:Boolean,onAgain:()->Unit,onReplay:()->Unit,onHistory:()->Unit) {
    val ranked=!run.abandoned && !run.state.practice
    val best=all.filter {it.state.id!=run.state.id && !it.abandoned && !it.state.practice && sameArcadeBoard(it.state,run.state)}.maxOfOrNull {it.state.score} ?: 0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        PicaAvatar(Modifier.fillMaxWidth().height(125.dp),"wave","celebrate",1)
        FolioEyebrow(if(ranked && run.state.score>best) "A NEW PERSONAL BEST" else if(run.state.practice) "A LITTLE PRACTICE" else "ONE FOR THE JOURNAL")
        Text(scoreText(run.state.score),style=FolioTheme.typography.displayLarge,color=FolioTheme.colors.textPrimary)
        Text(if(ranked) "Your #${arcadeRank(run,all)} ${run.state.game.title} run" else "${if(run.abandoned) "Unfinished" else "Practice"} · outside the leaderboard",style=FolioTheme.typography.bodyLarge,color=FolioTheme.colors.textSecondary)
        FolioGlassPanel {
            Text("${run.state.moves} moves · best streak ${run.state.bestCombo}",style=FolioTheme.typography.titleSmall,color=FolioTheme.colors.textPrimary)
            if(run.state.game==PocketGame.SHELVES) Text("Largest book ${run.state.cells.maxOrNull() ?: 0}",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
            if(run.state.game==PocketGame.BALANCE) Text("${run.state.books.size-1} books balanced",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
            if(run.state.bookmarks.isNotEmpty()) Text(run.state.bookmarks.joinToString(" + ") {it.title},style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.accentProgress)
        }
        if(run.state.readingTitle.isNotBlank()) Text("Between chapters of ${run.state.readingTitle}",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
        if(run.keepsakeEligible()) Text("Saved to your journal.",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.accentProgress)
        FolioActionButton(onAgain,Modifier.fillMaxWidth(),enabled) {Text("One more run")}
        FolioActionButton(onReplay,Modifier.fillMaxWidth(),enabled) {Text("Practise this seed")}
        TextButton(onHistory) {Text("All my attempts")}
        Text("Seed ${run.state.seed} · ${Instant.fromEpochMilliseconds(run.endedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date}",style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.textSecondary)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable internal fun PocketHistory(data:TumbleLibrary,initialGame:PocketGame=PocketGame.SHELVES,onOpen:(ArcadeRun)->Unit) {
    var selected by rememberSaveable(initialGame) {mutableStateOf(initialGame)}
    var recent by rememberSaveable {mutableStateOf(false)}
    var mode by rememberSaveable {mutableStateOf("Arcade")}
    var challenge by rememberSaveable {mutableStateOf(arcadeDay())}
    var menu by remember {mutableStateOf(false)}
    var legacy by remember {mutableStateOf(false)}
    val days=(data.arcadeRuns.filter {it.state.game==selected && it.state.daily.isNotEmpty()}.map {it.state.daily}+arcadeDay()).distinct().sortedDescending()
    val filtered=data.arcadeRuns.filter {it.state.game==selected && when(mode) {"Daily"->it.state.daily==challenge && !it.state.practice;"Practice"->it.state.practice;else->it.state.daily.isEmpty() && !it.state.practice} && (recent || !it.abandoned && it.state.finished)}
        .let {if(recent) it.sortedByDescending {run->run.endedAt} else it.sortedWith(arcadeRanking)}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.folioBackdropSource()) {
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {PocketGame.entries.forEach {game->FolioChip(selected==game,{selected=game},game.title)}}}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Arcade","Daily","Practice").forEach {value->FolioChip(mode==value,{mode=value},value)}}}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FolioChip(!recent,{recent=false},"Best scores");FolioChip(recent,{recent=true},"Recent attempts")}}
        if(mode=="Daily") item {Box {
            TextButton({menu=true}) {Text("Challenge $challenge ▾")}
            DropdownMenu(menu,{menu=false}) {days.forEach {day->DropdownMenuItem(text={Text(day)},onClick={challenge=day;menu=false})}}
        }}
        if(filtered.isEmpty()) item {Text("Your first ${selected.title} run belongs here.",style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textSecondary,modifier=Modifier.padding(vertical=20.dp))}
        items(filtered,key={it.state.id}) {run->
            FolioGlassPanel(Modifier.clickable {onOpen(run)}) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(!run.abandoned && !run.state.practice) "#${arcadeRank(run,filtered)}" else "—",style=FolioTheme.typography.titleLarge,color=FolioTheme.colors.accentProgress)
                    Column(Modifier.weight(1f)) {
                        Text("${scoreText(run.state.score)} points",style=FolioTheme.typography.titleLarge,color=FolioTheme.colors.textPrimary)
                        Text("${run.state.moves} moves · best streak ${run.state.bestCombo}${if(run.abandoned) " · unfinished" else ""}",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
                        Text(Instant.fromEpochMilliseconds(run.endedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),style=FolioTheme.typography.labelSmall,color=FolioTheme.colors.textSecondary)
                    }
                }
            }
        }
        if(data.runs.isNotEmpty()) {
            item {TextButton({legacy=!legacy}) {Text(if(legacy) "Hide earlier Ricochet runs" else "Earlier Ricochet runs · ${data.runs.size}")}}
            if(legacy) items(data.runs.sortedByDescending {it.endedAt},key={"legacy-${it.state.id}"}) {run->
                FolioGlassPanel {Text("Ricochet · ${scoreText(run.state.score)} points",style=FolioTheme.typography.titleSmall,color=FolioTheme.colors.textPrimary);Text("${run.state.shelf} shelves · earlier game rules",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)}
            }
        }
        item {Spacer(Modifier.height(24.dp))}
    }
}
