package com.folio.reader.tumble

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.experience.ReadingEnvironment
import com.folio.reader.experience.LocalReadingEnvironment
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.HomeUiState
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlin.math.*

/** Covered application chrome does not need its own animation clock. */
val LocalPicaMotionActive = compositionLocalOf { true }
internal val LocalSableBaseMotion = compositionLocalOf<Boolean?> {null}
internal val LocalSableBaseVisible = compositionLocalOf {true}

@Stable class PicaSession {
    var open by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var active by mutableStateOf<ArcadeState?>(null)
    var reaction by mutableStateOf("idle")
    var cue by mutableIntStateOf(0)
    var todayMinutes by mutableLongStateOf(0)
    var bookTitle by mutableStateOf("")
    var bookAuthor by mutableStateOf("")
    var readingTitle by mutableStateOf("")
    var context by mutableStateOf("Folio")
    var hour by mutableIntStateOf(12)
    var reload by mutableIntStateOf(0)
    fun react(action: String) { reaction = action; cue++ }
    var bookId by mutableStateOf("")
    var bookCover by mutableStateOf<String?>(null)
    var quote by mutableStateOf("")
    var quoteCount by mutableIntStateOf(0)
    var finishedTitles by mutableStateOf<List<String>>(emptyList())
    var sessionLine by mutableStateOf("")
    var readingHabit by mutableStateOf("watch")
    var selectedGame by mutableStateOf<PocketGame?>(null)
    var newArrival by mutableStateOf("")
    var onContinue: ((String) -> Unit)? = null
    fun continueReading() { val id=bookId; if(id.isNotBlank()) {open=false;onContinue?.invoke(id)} }
    fun openFromHome(key: String = "", title: String = "", cover: String? = null, author: String = "") {
        if(key.isNotBlank() && !key.startsWith("MANGA:") && title.isNotBlank()) {
            val id=key.removePrefix("BOOK:")
            if(bookId!=id) {quote="";quoteCount=0;sessionLine=""}
            bookId=id;bookTitle=title;bookCover=cover;bookAuthor=author
        }
        selectedGame=null;open=true
    }
    fun personality(data: TumbleLibrary): PicaPersonality {
        return when {
            hour >= 22 || hour < 5 -> PicaPersonality("After hours", "Your book, a saved passage, or a quick game.", "sleep")
            quoteCount >= 3 -> PicaPersonality("Saved passages", "$quoteCount passages from this book.", "read")
            readingHabit == "return" -> PicaPersonality("Back to your book", "Continue where you left off.", "read")
            todayMinutes > 0 -> PicaPersonality("Between chapters", "$todayMinutes minutes read today.", "read")
            else -> PicaPersonality("Between chapters", "A place for a quick game or a line worth keeping.", "watch")
        }
    }
}
private data class PicaReadingPulse(val total: Long, val today: Long, val title: String, val id: String, val finished: Map<String,String>, val session: String, val habit: String, val cover: String?, val author: String)

@Composable fun PicaReadingContext(title: String) {
    val environment=LocalReadingEnvironment.current ?: return
    DisposableEffect(environment,title) {
        environment.pica.context="Reading";environment.pica.readingTitle=title;environment.pica.react("read")
        onDispose { if(environment.pica.readingTitle==title) {environment.pica.context="Folio";environment.pica.readingTitle="";environment.pica.react("bookmark")} }
    }
}

/** One owner for loading, reading rewards, the arcade, and interactions across every route. */
@Composable fun PicaHost(environment: ReadingEnvironment) {
    val pica = environment.pica
    val library by environment.tumble.library.collectAsState()
    LaunchedEffect(pica.cue) { if (pica.cue > 0) { delay(4400); pica.reaction = "idle" } }
    LaunchedEffect(pica.newArrival) { if(pica.newArrival.isNotBlank()) { delay(90_000); pica.newArrival="" } }
    LaunchedEffect(environment) {
        var known: Set<String>? = null
        environment.books.getAllBooks().catch { /* Optional arrival acknowledgement must not interrupt the library. */ }.collect { books ->
            known?.let { previous -> books.firstOrNull { it.id !in previous }?.let {
                pica.newArrival=it.displayTitle; pica.react("inspect")
            } }
            known=books.mapTo(mutableSetOf()) {it.id}
        }
    }
    LaunchedEffect(environment, pica.reload) {
        try {
            environment.tumble.load()
            pica.active = environment.tumble.loadArcade()
            pica.ready = true
            pica.error = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { pica.error = "Couldn't load your saved games. Your progress hasn't been changed." }
    }
    LaunchedEffect(environment, pica.ready) {
        if (!pica.ready) return@LaunchedEffect
        var lastReward = library.readingMinutes / 15
        var knownFinished: Set<String>? = null
        combine(environment.sessions.observeSessionsSince(ReadingSession.FIRST_MEASURED_SESSION), environment.books.getAllBooks(),
            environment.mangaStats?.observeStatistics() ?: flowOf(com.folio.reader.manga.MangaStatistics())) { sessions, books, manga ->
            val ids = books.mapTo(mutableSetOf()) { it.id }
            val local = sessions.filter { it.bookId in ids }
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val todayMs = local.filter { (it.endedAt ?: it.startedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date == today }.sumOf { it.durationMs.coerceAtLeast(0) }
            val last = local.maxByOrNull { it.endedAt ?: it.startedAt }
            val current = books.filter {it.lastOpenedAt!=null}.maxByOrNull {it.lastOpenedAt!!} ?: books.firstOrNull { it.id == last?.bookId }
            val recentBooks = local.sortedByDescending {it.endedAt ?: it.startedAt}.take(12).map {it.bookId}.distinct()
            val recentAuthors = books.filter {it.id in recentBooks}.flatMap {it.authors}.distinct()
            val returnDays = local.filter {it.bookId==current?.id}.map {it.startedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date}.distinct().size
            PicaReadingPulse(local.sumOf { it.durationMs.coerceAtLeast(0) } / 60000 + manga.totalReadMinutes, todayMs / 60000,
                current?.displayTitle.orEmpty(), current?.id.orEmpty(),
                books.filter {it.status==com.folio.reader.model.BookStatus.FINISHED}.associate {it.id to it.displayTitle},
                if(last!=null && last.durationMs>=60000 && (last.endedAt ?: last.startedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date==today)
                    "${last.durationMs/60000} min with ${books.firstOrNull {it.id==last.bookId}?.displayTitle.orEmpty()} · your place is kept" else "",
                if(returnDays>=3) "return" else if(recentAuthors.size>=3) "explore" else "watch",current?.coverPath,current?.authors?.joinToString(", ").orEmpty())
        }.catch { /* Keep the companion and games usable if reading statistics cannot be loaded. */ }.collect { (total, today, title, id, finished, session, habit, cover, author) ->
            pica.todayMinutes = today; pica.bookTitle = title; pica.bookId=id
            pica.bookCover=cover
            pica.bookAuthor=author
            pica.finishedTitles=finished.values.toList();pica.sessionLine=session;pica.readingHabit=habit
            try {
                environment.tumble.readingProgress(total)
                knownFinished?.let { previous -> (finished.keys-previous).forEach {id->environment.tumble.rememberBook(id,finished.getValue(id));pica.react("celebrate")} }
                knownFinished=finished.keys
                if (total / 15 > lastReward) { pica.react("celebrate"); lastReward = total / 15 }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pica.error = "Couldn't save reading rewards. I'll try again after your next session." }
        }
    }
    LaunchedEffect(environment) {
        snapshotFlow {pica.bookId}.distinctUntilChanged().flatMapLatest { id ->
            if(id.isBlank()) flowOf(emptyList()) else environment.highlights.getHighlightsForBook(id)
        }.catch { pica.quote="";pica.quoteCount=0 }.collect { highlights ->
            val live=highlights.filterNot {it.isDeleted}.sortedByDescending {it.createdAt}
            pica.quote=live.firstOrNull()?.selectedText.orEmpty();pica.quoteCount=live.size
        }
    }
    LaunchedEffect(pica) {
        while (true) { pica.hour = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour; delay(60_000) }
    }
}

/** Place in the app's room/material scope, alongside its navigation surface. */
@Composable fun PicaOverlayHost() {
    val environment=LocalReadingEnvironment.current ?: return
    if(environment.pica.open) {
        // This is an opaque full-window destination; none of the underlying field is visible.
        FolioFieldOccluder()
        CompositionLocalProvider(LocalPicaMotionActive provides true,
            LocalFolioMotionAllowed provides LocalSableBaseMotion.current,
            LocalFolioWindowVisible provides LocalSableBaseVisible.current) {PocketArcadeHost(environment)}
    }
}

@Composable fun PicaHomeEntry(home: HomeUiState, modifier: Modifier = Modifier) {
    SableBookPresence(home.readingNow.firstOrNull()?.id.orEmpty(),home.readingNow.firstOrNull()?.title.orEmpty(),modifier)
}

/** Attached to the current book's composition, with no card or separate Home destination. */
@Composable fun SableBookPresence(bookKey: String, title: String, modifier: Modifier = Modifier, characterOnly: Boolean = false, cover: String? = null, author: String = "") {
    val environment = LocalReadingEnvironment.current ?: return
    val data by environment.tumble.library.collectAsState()
    val pica = environment.pica
    if (!data.homeVisible) return
    var previous by remember { mutableStateOf(bookKey) }
    LaunchedEffect(bookKey) {
        if (previous != bookKey) { pica.react("inspect"); previous=bookKey }
    }
    val pet = { pica.react("pet"); environment.perform { tumble.pet() } }
    val visit = { pica.openFromHome(bookKey,title,cover,author) }
    if(characterOnly) SableAvatar(modifier,pica.personality(data).pose,pica.reaction,pica.cue,
        visit,{pica.react("stretch")},pet,
        data.decoration)
    else SableBookCompany(data, pica, title, modifier,onPet=pet,onVisit=visit)
}

@Composable fun SableNookLink(modifier: Modifier = Modifier, bookKey: String = "", title: String = "", cover: String? = null, author: String = "") {
    val environment=LocalReadingEnvironment.current ?: return
    val data by environment.tumble.library.collectAsState()
    if(data.homeVisible) TextButton({environment.pica.openFromHome(bookKey,title,cover,author)},modifier) {Text("Between chapters  ↗")}
}

@Composable internal fun PicaHomeCard(data: TumbleLibrary, pica: PicaSession, modifier: Modifier = Modifier,
    onPet: () -> Unit, onVisit: () -> Unit) {
    SableBookCompany(data,pica,pica.bookTitle,modifier,onPet,onVisit)
}

@Composable internal fun SableBookCompany(data: TumbleLibrary, pica: PicaSession, title: String, modifier: Modifier = Modifier,
    onPet: () -> Unit, onVisit: () -> Unit) {
    val colors=FolioTheme.colors
    Row(modifier.fillMaxWidth().heightIn(min=84.dp),verticalAlignment=Alignment.CenterVertically) {
        SableAvatar(Modifier.size(84.dp),pica.personality(data).pose,pica.reaction,pica.cue,
            onVisit,{pica.react("stretch")},onPet,data.decoration)
        TextButton(onVisit,contentPadding=PaddingValues(horizontal=4.dp)) {
            Column(horizontalAlignment=Alignment.Start,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("Between chapters  ↗",style=FolioTheme.typography.labelLarge)
                Text("Games · passages · journal",style=FolioTheme.typography.bodySmall,color=colors.textSecondary)
            }
        }
    }
}

/** A brief, dismissible acknowledgement in the library when new books arrive. */
@Composable fun SableArrivalNotice(modifier: Modifier = Modifier) {
    val environment=LocalReadingEnvironment.current ?: return
    val data by environment.tumble.library.collectAsState()
    val pica=environment.pica
    if(!data.homeVisible || pica.newArrival.isBlank()) return
    Row(modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
        SableAvatar(Modifier.size(88.dp),reaction=pica.reaction.takeUnless {it=="idle"} ?: "inspect",cue=pica.cue,
            onTap={pica.selectedGame=null;pica.open=true})
        Column(Modifier.weight(1f)) {
            Text("Added to your library",style=FolioTheme.typography.labelMedium,color=FolioTheme.colors.textPrimary)
            Text(pica.newArrival,style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        TextButton({pica.newArrival=""}) {Text("Dismiss")}
    }
}

/** Compatibility seam for existing game boards and saved companion state. */
@Composable internal fun PicaAvatar(modifier: Modifier = Modifier, pose: String = "watch", reaction: String = "idle", cue: Int = 0,
    onTap: (() -> Unit)? = null, onHold: (() -> Unit)? = null, onStroke: (() -> Unit)? = null, decoration: String? = null) =
    SableAvatar(modifier, pose, reaction, cue, onTap, onHold, onStroke, decoration)
