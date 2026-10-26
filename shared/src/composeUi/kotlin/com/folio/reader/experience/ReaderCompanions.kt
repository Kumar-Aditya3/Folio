@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.reader.experience

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.model.*
import com.folio.reader.ui.components.FolioPaperSheet
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.sin

@Composable fun ReaderSavedTools() {
    val session = LocalReaderExperience.current ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Keep the reading with you", style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
        FlowRow {
            TextButton(onClick = { session.journal() }) { Text("Export reading journal") }
            TextButton(enabled = session.sources.isNotEmpty(), onClick = { session.showSequence = true }) { Text("Select for export") }
            TextButton(onClick = { session.showReminder = true }) { Text("Leave myself a reminder") }
        }
    }
}

@Composable fun ReaderCompanionHost(session: ReaderExperienceSession, onFinish: () -> Unit,
    reflection: ReflectionInvitation? = null, onSaveReflection: (String) -> Unit = {}, onDismissReflection: () -> Unit = {},
    onOpenReturn: (ReadingReturnContext) -> Unit = {}) {
    val env = session.environment
    var recentNote by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(session.returnCard) {
        val id = session.returnCard?.bookId
        if (env != null && id != null) recentNote = env.notes.getNotesForBook(id).first().filterNot { it.isDeleted }.maxByOrNull { it.updatedAt }?.content
    }
    session.returnCard?.let { context -> ExperienceDialog("Where I left off", { session.returnCard = null }) {
        FolioPaperSheet {
            Text(context.chapter, style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
            if (context.excerpt.isNotBlank()) Text(context.excerpt, style = FolioTheme.typography.quote, color = FolioTheme.colors.onSurface)
            recentNote?.takeIf { it.isNotBlank() }?.let { Text("My last note", style = FolioTheme.typography.labelSmall); Text(it) }
            if (context.reminder.isNotBlank()) { Text("A reminder from me", style = FolioTheme.typography.labelSmall); Text(context.reminder) }
        }
        Row {
            Button(onClick = { session.returnCard = null }) { Text("Continue reading") }
            if (context.reminder.isNotBlank()) TextButton(onClick = { onOpenReturn(context); session.returnCard = null }) { Text("Open saved place") }
        }
    } }
    if (session.showReminder && env != null) {
        val context = env.repository.state.collectAsState().value.returns.firstOrNull { it.bookId == session.position?.bookId }
        var text by remember { mutableStateOf(context?.reminder.orEmpty()) }
        ExperienceDialog("A note for next time", { session.showReminder = false }) {
            Text("Leave a thought beside the place you are reading.", style = FolioTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Remind me…") })
            Row {
                Button(onClick = {
                    val position = session.position ?: return@Button
                    val chapter = session.chapters.getOrNull(session.chapterIndex)?.title.orEmpty()
                    val excerpt = session.latestExcerpt
                    val reminder = text.trim()
                    env.perform {
                        val old = repository.state.value.returns.firstOrNull { it.bookId == position.bookId }
                        val id = old?.reminderNoteId ?: "return-reminder-${position.bookId}"
                        if (reminder.isNotBlank()) {
                            val existing = notes.getNote(id)
                            val note = existing?.copy(content = reminder, updatedAt = kotlinx.datetime.Clock.System.now(), isDeleted = false)
                                ?: Note(id, position.bookId, position.chapterId, position.spineIndex, position.contentLocator,
                                    reminder, NoteType.BOOK_NOTE, deviceId = position.deviceId)
                            if (existing == null) notes.insertNote(note) else notes.updateNote(note)
                        } else if (old?.reminderNoteId != null) notes.deleteNote(old.reminderNoteId)
                        repository.saveReturn(ReadingReturnContext(position.bookId, position.copy(previousPosition = null), chapter, excerpt, reminder, System.currentTimeMillis(), id.takeIf { reminder.isNotBlank() }))
                    }
                    session.showReminder = false
                }) { Text("Save reminder") }
                TextButton(onClick = { text = "" }) { Text("Clear") }
            }
        }
    }
    if (session.showSequence && env != null) {
        var chosen by remember { mutableStateOf(session.sources.map { it.id }.toSet()) }
        ExperienceDialog("Choose your passages", { session.showSequence = false }) {
            session.sources.forEach { source -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(source.id in chosen, { chosen = if (it) chosen + source.id else chosen - source.id })
                Text(source.text.take(140), style = FolioTheme.typography.bodyMedium, maxLines = 3)
            } }
            Button(enabled = chosen.isNotEmpty(), onClick = {
                env.exports = session.sources.filter { it.id in chosen }.toList(); session.showSequence = false
            }) { Text("Design ${chosen.size} passages") }
        }
    }
    reflection?.let { invitation ->
        var text by remember(invitation) { mutableStateOf("") }
        if (session.reflectionEditor) ExperienceDialog("What stayed with you?", { session.reflectionEditor = false; onDismissReflection() }) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4, label = { Text("My reflection") })
            Button(enabled = text.isNotBlank(), onClick = { onSaveReflection(text.trim()) }) { Text("Keep reflection") }
        }
    }
}

@Composable fun ReaderPauseActions(session: ReaderExperienceSession, reflection: ReflectionInvitation?, onFinish: () -> Unit,
    onDismissReflection: () -> Unit, modifier: Modifier = Modifier) {
    if (!session.targetFinished && reflection == null) return
    FolioPaperSheet(modifier = modifier) {
        if (session.targetFinished) {
            Text("You reached your reading target", style = FolioTheme.typography.labelMedium, color = FolioTheme.colors.onSurface)
            Row { TextButton(onClick = { session.targetFinished = false }) { Text("Continue") }
                TextButton(onClick = { session.targetFinished = false; onFinish() }) { Text("Finish session") } }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { session.reflectionEditor = true }) { Text("What stayed with you?") }
            TextButton(onClick = onDismissReflection) { Text("Later") }
        }
    }
}

@Composable fun ReaderSoundAtmosphere(session: ReaderExperienceSession, modifier: Modifier = Modifier) {
    val playback = remember(session.sound) { session.sound.state.map { it.copy(remainingMs = null, volume = if (it.volume > 0f) 1f else 0f) }.distinctUntilChanged() }
    val sound by playback.collectAsState(session.sound.state.value)
    if (!sound.playing || sound.volume <= 0f || !sound.preferences.responsiveAtmosphere || sound.preferences.scene == Soundscape.OFF) return
    if (sound.preferences.primaryLevel <= 0f && (sound.preferences.secondary == Soundscape.OFF || sound.preferences.secondaryLevel <= 0f)) return
    val motion = LocalMotion.current
    val active = !motion.reduced && motion.fieldIntensityFactor > 0f && motion.fieldSpeedFactor > 0f
    val time = remember { mutableStateOf(0f) }
    val envelope = if (active) session.sound.atmosphereSignal.collectAsState() else rememberUpdatedState(0f)
    LaunchedEffect(active) { if (active) while (true) { delay(100); time.value += .1f } }
    val accent = when (sound.preferences.scene) {
        Soundscape.RAIN -> FolioTheme.colors.primary
        Soundscape.CAFE -> FolioTheme.colors.accentProgress
        else -> FolioTheme.colors.accentAnnotation
    }
    Canvas(modifier) {
        val edge = 16.dp.toPx().coerceAtMost(size.width * .04f)
        val amount = if (active) .035f + envelope.value * .045f else .035f
        val glow = accent.copy(alpha = amount)
        drawRect(Brush.horizontalGradient(listOf(glow, Color.Transparent), 0f, edge), size = Size(edge, size.height))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, glow), size.width - edge, size.width), topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
        if (active) when (sound.preferences.scene) {
            Soundscape.RAIN -> repeat(4) { i ->
                val y = ((time.value * (12 + i * 3) + i * size.height / 4) % size.height)
                drawLine(accent.copy(alpha = .08f), Offset(edge * .3f,y), Offset(edge * .3f,y + 10.dp.toPx()), 1.dp.toPx())
                drawLine(accent.copy(alpha = .06f), Offset(size.width - edge * .3f,y + 90), Offset(size.width - edge * .3f,y + 100), 1.dp.toPx())
            }
            Soundscape.FIREPLACE, Soundscape.CAFE -> {
                val alpha = (.025 + .012 * sin(time.value * .4) + envelope.value * .025).toFloat()
                drawRect(accent.copy(alpha = alpha), Offset(0f, size.height - 5.dp.toPx()), Size(size.width, 5.dp.toPx()))
            }
            else -> Unit
        }
    }
}

@Composable fun ScheduledReviews(limit: Int = Int.MAX_VALUE, includeUpcoming: Boolean = false, onOpen: (ExportSource) -> Unit = {}) {
    val env = LocalReadingEnvironment.current ?: return
    val data by env.repository.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var foreground by remember { mutableStateOf(true) }
    ReaderForegroundEffect { foreground = it }
    LaunchedEffect(data.reviews, foreground) {
        if (foreground) while (true) {
            now = System.currentTimeMillis()
            val next = data.reviews.filter { it.dueAt > now }.minOfOrNull { it.dueAt } ?: break
            delay((next - now).coerceIn(1000, 60_000))
        }
    }
    val due = data.reviews.filter { includeUpcoming || it.dueAt <= now }.sortedBy { it.dueAt }.take(limit)
    if (due.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Worth another read", style = FolioTheme.typography.titleLarge, color = FolioTheme.colors.onSurface)
        due.forEach { review -> FolioPaperSheet {
            Text(review.source.text, style = FolioTheme.typography.quote, color = FolioTheme.colors.onSurface, modifier = Modifier.clickable { onOpen(review.source) })
            Text(review.source.title, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            if (review.dueAt > System.currentTimeMillis()) Text("Next review: " + java.text.DateFormat.getDateInstance().format(java.util.Date(review.dueAt)), style = FolioTheme.typography.bodySmall)
            FlowRow { listOf("remember" to "Remember", "again" to "Again", "snooze" to "Tomorrow", "remove" to "Remove").forEach { (action,label) ->
                TextButton(onClick = { env.perform { repository.review(review.source.id, action, System.currentTimeMillis()) } }) { Text(label) }
            } }
        } }
    }
}
