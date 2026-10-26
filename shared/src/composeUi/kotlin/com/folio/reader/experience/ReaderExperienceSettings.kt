@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.reader.experience

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.*
import com.folio.reader.ui.components.FolioPaperSheet
import com.folio.reader.ui.theme.*
import kotlin.math.sin
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private data class SoundControlStatus(val playing: Boolean, val remainingMs: Long?, val error: String?)

@Composable fun ReaderSoundscapeSettings(settings: ReaderSettings, apply: (ReaderSettings) -> Unit) {
    val session = LocalReaderExperience.current ?: return
    val status = remember(session.sound) { session.sound.state.map { SoundControlStatus(it.playing, it.remainingMs, it.error) }.distinctUntilChanged() }
    val initial = session.sound.state.value
    val sound by status.collectAsState(SoundControlStatus(initial.playing, initial.remainingMs, initial.error))
    val p = settings.soundscape
    var expanded by remember { mutableStateOf(true) }
    var customTimer by remember { mutableStateOf(false) }
    val expansion = rememberMotionAnimation<androidx.compose.ui.unit.IntSize>(MotionKey.SoundscapeExpand)
    Column(Modifier.fillMaxWidth().animateContentSize(expansion), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Soundscape", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
                val remaining = sound.remainingMs?.let { " · ${it / 60000}:${((it / 1000) % 60).toString().padStart(2, '0')} left" }.orEmpty()
                Text(if (p.scene == Soundscape.OFF) "A little atmosphere for your pages" else "${p.scene.title} · ${if (sound.playing) "Playing" else "Paused"}$remaining",
                    style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            }
            Text(if (expanded) "−" else "+", color = FolioTheme.colors.onSurface)
        }
        if (expanded) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 360.dp && LocalDensity.current.fontScale > 1.2f) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Soundscape.entries.chunked(columns).forEach { scenes ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                scenes.forEach { scene ->
                    val selected = p.scene == scene
                    Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 96.dp).background(if (selected) FolioTheme.colors.primary.copy(alpha = .12f) else FolioTheme.colors.surfaceVariant,
                        RoundedCornerShape(14.dp)).semantics { role = Role.RadioButton; this.selected = selected }
                        .clickable {
                            val updated = p.copy(scene = scene).normalized()
                            apply(settings.copy(soundscape = updated)); session.sound.select(updated)
                        }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SceneMiniature(scene, Modifier.size(42.dp))
                        Text(scene.title, style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurface)
                        if (selected) Text("•", color = FolioTheme.colors.primary)
                    }
                }
            }
            }
            }
            }
            if (p.scene != Soundscape.OFF) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (sound.playing) session.sound.pause() else session.sound.play() }) { Text(if (sound.playing) "Pause" else if (sound.error != null) "Retry" else "Play") }
                    TextButton(onClick = { apply(settings.copy(soundscape = p.copy(scene = Soundscape.OFF))); session.sound.off() }) { Text("Off") }
                }
                sound.error?.let { Text(it, color = FolioTheme.colors.onSurfaceVariant, style = FolioTheme.typography.bodySmall) }
                MasterSoundLevel(session)
                SoundLevel("${p.scene.title} level", p.primaryLevel,
                    onFinished = { apply(settings.copy(soundscape = session.sound.state.value.preferences)) }) {
                    session.sound.restore(session.sound.state.value.preferences.copy(primaryLevel = it))
                }
                Text("Add a second sound", style = FolioTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
                val secondary = Soundscape.entries.filter { it != p.scene }
                ExperienceChoices(secondary.map { if (it == Soundscape.OFF) "None" else it.title }, secondary.indexOf(p.secondary).coerceAtLeast(0)) {
                    apply(settings.copy(soundscape = p.copy(secondary = secondary[it])))
                }
                if (p.secondary != Soundscape.OFF) SoundLevel("${p.secondary.title} level", p.secondaryLevel,
                    onFinished = { apply(settings.copy(soundscape = session.sound.state.value.preferences)) }) {
                    session.sound.restore(session.sound.state.value.preferences.copy(secondaryLevel = it))
                }
                Text("Stop sound after", style = FolioTheme.typography.labelMedium, color = FolioTheme.colors.onSurfaceVariant)
                val durations = listOf(0,15,30,60)
                ExperienceChoices(listOf("Off", "15 min", "30 min", "60 min", if (p.timerMinutes !in durations) "${p.timerMinutes} min" else "Custom"), durations.indexOf(p.timerMinutes).let { if (it < 0) 4 else it }) {
                    if (it == 4) customTimer = true else apply(settings.copy(soundscape = p.copy(timerMinutes = durations[it])))
                }
                ExperienceToggle("Responsive atmosphere", p.responsiveAtmosphere) { apply(settings.copy(soundscape = p.copy(responsiveAtmosphere = it))) }
                Text("Plays while you read. Leaving Folio pauses it.", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            }
        }
    }
    if (customTimer) MinutesDialog("Stop sound after", p.timerMinutes.coerceAtLeast(1), { customTimer = false }) {
        apply(settings.copy(soundscape = p.copy(timerMinutes = it))); customTimer = false
    }
}

@Composable private fun SoundLevel(label: String, value: Float, onFinished: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    val displayed = if (dragging) draft else value
    Column { Text("$label · ${(displayed * 100).toInt()}%", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        Slider(displayed, { dragging = true; draft = it; onChange(it) }, modifier = Modifier.fillMaxWidth(), valueRange = 0f..1f,
            onValueChangeFinished = { onFinished?.invoke(); dragging = false }) }
}

@Composable private fun MasterSoundLevel(session: ReaderExperienceSession) {
    val levels = remember(session.sound) { session.sound.state.map { it.volume }.distinctUntilChanged() }
    val volume by levels.collectAsState(session.sound.state.value.volume)
    SoundLevel("Master volume", volume, onChange = { session.sound.setVolume(it) },
        onFinished = { session.environment?.perform { repository.saveVolume(session.sound.state.value.volume) } })
}

@Composable internal fun SceneMiniature(scene: Soundscape, modifier: Modifier = Modifier) {
    val ink = FolioTheme.colors.onSurfaceVariant
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (scene) {
            Soundscape.OFF -> { drawCircle(ink, w * .23f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.7.dp.toPx())); drawLine(ink, Offset(w*.31f,h*.69f), Offset(w*.69f,h*.31f), 1.7.dp.toPx()) }
            Soundscape.RAIN -> repeat(6) { i -> val x = w * (.18f + (i % 3) * .28f); val y = h * (.18f + (i / 3) * .4f); drawLine(ink, Offset(x,y),Offset(x-w*.1f,y+h*.2f),1.5.dp.toPx()) }
            Soundscape.CAFE -> {
                drawRoundRect(ink, Offset(w*.15f,h*.38f), androidx.compose.ui.geometry.Size(w*.52f,h*.37f), androidx.compose.ui.geometry.CornerRadius(w*.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                drawCircle(ink, w*.13f, Offset(w*.72f,h*.53f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                drawLine(ink,Offset(w*.12f,h*.85f),Offset(w*.8f,h*.85f),1.dp.toPx())
                drawLine(ink,Offset(w*.35f,h*.12f),Offset(w*.35f,h*.26f),1.dp.toPx())
            }
            Soundscape.FIREPLACE -> {
                val path = Path().apply { moveTo(w*.5f,h*.1f); cubicTo(w*.25f,h*.45f,w*.15f,h*.68f,w*.4f,h*.82f); cubicTo(w*.8f,h*.98f,w*.91f,h*.52f,w*.62f,h*.35f); quadraticTo(w*.69f,h*.61f,w*.5f,h*.1f); close() }
                drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable fun ReaderPresetSettings(settings: ReaderSettings, apply: (ReaderSettings) -> Unit) {
    val session = LocalReaderExperience.current ?: return
    val env = session.environment ?: return
    val data by env.repository.state.collectAsState()
    var editor by remember { mutableStateOf<ReadingPreset?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val builtins = listOf(
        ReadingPreset("bedtime", "Bedtime", BookReaderSettings(themeId = "ember", soundscape = SoundscapePreferences(Soundscape.RAIN, timerMinutes = 30))),
        ReadingPreset("study", "Study", BookReaderSettings(themeId = "classic", soundscape = SoundscapePreferences(Soundscape.CAFE, timerMinutes = 25))),
        ReadingPreset("weekend", "Weekend", BookReaderSettings(themeId = "paper", soundscape = SoundscapePreferences(Soundscape.FIREPLACE))))
    Text("Reading presets", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (builtins.filterNot { b -> data.presets.any { it.id == b.id } } + data.presets).forEach { preset ->
            AssistChip(onClick = {
                session.sound.pause()
                var updated = preset.settings.toReaderSettings(settings)
                if (preset.settings.themeId != null) updated = updated.copy(customTheme = preset.settings.customTheme)
                val font = updated.fontFamily
                val imported = settings.customFonts.firstOrNull { it.familyName == font || it.name == font }
                val available = font in com.folio.reader.ui.reader.platformBaseReaderFonts() || font in listOf("Literata", "serif", "sans-serif", "monospace") ||
                    (imported != null && UiFonts.fromFile(imported.fileName) != null)
                if (font != settings.fontFamily && !available) {
                    updated = updated.copy(fontFamily = "serif"); env.error = "The saved font is unavailable. A bundled serif font is being used."
                }
                session.sound.restore(updated.soundscape); apply(updated)
            }, label = { Text(preset.name) })
        }
        AssistChip(onClick = { editor = null; name = ""; showEditor = true }, label = { Text("Save current…") })
    }
    if (data.presets.isNotEmpty()) FlowRow { data.presets.forEach { preset ->
        TextButton(onClick = { editor = preset; name = preset.name; showEditor = true }) { Text("Edit ${preset.name}") }
    } }
    TextButton(onClick = { manage = true }) { Text("Manage presets") }
    if (manage) ExperienceDialog("Reading presets", { manage = false }) {
        (builtins.filterNot { b -> data.presets.any { it.id == b.id } } + data.presets).forEach { preset ->
            TextButton(onClick = { editor = preset; name = preset.name; manage = false; showEditor = true }) { Text(preset.name) }
        }
    }
    if (showEditor) ExperienceDialog(if (editor == null) "Save reading preset" else "Edit reading preset", { showEditor = false }) {
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true)
        Button(enabled = name.isNotBlank(), onClick = {
            val existing = editor
            env.perform { repository.savePreset(ReadingPreset(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), existing?.settings ?: settings.toBookSettings())) }
            showEditor = false
        }) { Text("Save") }
        if (editor != null) {
            TextButton(onClick = { val preset = editor!!; env.perform { repository.savePreset(preset.copy(name = name.trim().ifBlank { preset.name }, settings = settings.toBookSettings())) }; showEditor = false }) { Text("Replace with current settings") }
            TextButton(onClick = { val id = editor!!.id; env.perform { repository.removePreset(id) }; showEditor = false }) { Text("Delete preset") }
        }
    }
}

@Composable fun ReaderTimeSettings() {
    val session = LocalReaderExperience.current ?: return
    var minutes by remember { mutableStateOf(15) }
    var custom by remember { mutableStateOf(false) }
    val position = session.position
    val estimate = position?.let { estimateReadingTime(session.history, it.bookId, session.chapters, session.chapterIndex, it.chapterProgress, minutes) }
    Text("Reading time", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
    val choices = listOf(5,10,15,20)
    ExperienceChoices(listOf("5 min", "10 min", "15 min", "20 min", if (minutes !in choices) "$minutes min" else "Custom"), choices.indexOf(minutes).let { if (it < 0) 4 else it }) {
        if (it == 4) custom = true else minutes = choices[it]
    }
    estimate?.let { Text(it.message + if (!it.personal) " Estimated at 220 words/min." else "", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant) }
    session.targetRemainingMs?.let { Text("${it / 60000}:${((it / 1000) % 60).toString().padStart(2,'0')} remaining", color = FolioTheme.colors.onSurface) }
    Row {
        TextButton(onClick = { session.targetRemainingMs = minutes * 60_000L; session.targetFinished = false }) { Text(if (session.targetRemainingMs == null) "Start reading target" else "Restart target") }
        if (session.targetRemainingMs != null) TextButton(onClick = { session.targetRemainingMs = null }) { Text("Cancel") }
    }
    session.environment?.let { env ->
        val data by env.repository.state.collectAsState()
        ExperienceToggle("Chapter reflections", data.chapterReflections) { enabled -> env.perform { repository.update { it.copy(chapterReflections = enabled) } } }
    }
    if (custom) MinutesDialog("Time to read", minutes, { custom = false }) { minutes = it; custom = false }
}

@Composable internal fun MinutesDialog(title: String, initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    ExperienceDialog(title, onDismiss) {
        OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, label = { Text("Minutes · 1–180") }, singleLine = true)
        Button(enabled = value != null && value in 1..180, onClick = { value?.let(onSave) }) { Text("Set time") }
    }
}
