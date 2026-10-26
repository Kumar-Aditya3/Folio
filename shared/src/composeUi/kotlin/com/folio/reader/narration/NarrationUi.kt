package com.folio.reader.narration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.ReadingPosition
import com.folio.reader.ui.components.FolioDialog
import com.folio.reader.ui.theme.FolioTheme

val LocalNarrationController = staticCompositionLocalOf<NarrationController?> { null }

@Composable
fun NarrationTransport(controller: NarrationController, onOpen: () -> Unit,
    modifier: Modifier = Modifier, showStop: Boolean = false) {
    val state by controller.state.collectAsState()
    @Composable fun title(modifier: Modifier) {
        Column(modifier.clickable(onClick = onOpen).padding(8.dp)) {
            Text(state.title.ifEmpty { "English narration" }, style = FolioTheme.typography.labelLarge,
                color = FolioTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${state.label} · ${state.position?.progressPercent ?: 0}%", style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun controls() {
        IconButton(onClick = { controller.skip(false) }, enabled = state.phase !in setOf(NarrationPhase.Preparing,NarrationPhase.Error)) {
            Icon(Icons.Default.SkipPrevious,"Previous passage",tint = FolioTheme.colors.onSurface)
        }
        IconButton(onClick = { if (state.wantsPlayback) controller.pause() else controller.play() }) {
            Icon(if (state.wantsPlayback) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (state.wantsPlayback) "Pause narration" else "Resume narration", tint = FolioTheme.colors.primary)
        }
        IconButton(onClick = { controller.skip(true) }, enabled = state.phase !in setOf(NarrationPhase.Preparing,NarrationPhase.Error)) {
            Icon(Icons.Default.SkipNext,"Next passage",tint = FolioTheme.colors.onSurface)
        }
        if (showStop) IconButton(onClick = controller::stop) {
            Icon(Icons.Default.Stop,"Stop narration",tint = FolioTheme.colors.onSurface)
        }
    }
    if (showStop) Column(modifier.fillMaxWidth()) {
        title(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly) { controls() }
    } else Row(modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp),verticalAlignment = Alignment.CenterVertically) {
        title(Modifier.weight(1f))
        controls()
    }
}

/** Embedded in the existing reader workspace or settings plane; no additional material shell. */
@Composable
fun NarrationPanel(controller: NarrationController, position: ReadingPosition?, modifier: Modifier = Modifier, scrollable: Boolean = true) {
    val state by controller.state.collectAsState()
    val pack by controller.pack.collectAsState()
    var wifiOnly by remember { mutableStateOf(true) }
    var remove by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    fun begin() {
        if (position == null) return
        if (state.hasBook && state.bookId != position.bookId) replace = true else controller.start(position)
    }
    val body = if (scrollable) modifier.fillMaxSize().verticalScroll(rememberScrollState()) else modifier.fillMaxWidth()
    Column(body.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("English narration", style = FolioTheme.typography.titleLarge, color = FolioTheme.colors.onSurface)
        Text("Read this book aloud, entirely on your device. Listening counts toward your daily goal and streak.",
            style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
        Text("Soundscapes pause during narration.",style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        when (pack.phase) {
            VoicePackPhase.Unsupported -> Text("English narration is unavailable on this device.",color = FolioTheme.colors.onSurfaceVariant)
            VoicePackPhase.NotInstalled,VoicePackPhase.Failed -> {
                pack.error?.let { Text(it,color = FolioTheme.colors.error) }
                Text("Download · 65.1 MB",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wifiOnly,{ wifiOnly = it }); Text("Wi-Fi only",color = FolioTheme.colors.onSurface)
                }
                Button(onClick = { controller.download(wifiOnly) }) { Text(if (pack.phase == VoicePackPhase.Failed) "Retry download" else "Download English narration") }
                Text("Downloading does not start playback. You can remove the voice pack at any time.",
                    style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
            }
            VoicePackPhase.Downloading,VoicePackPhase.Verifying -> {
                Text(if (pack.phase == VoicePackPhase.Verifying) "Verifying English narration" else
                    "Downloading · ${(pack.bytes/1_000_000.0).toInt()} of 65 MB",color = FolioTheme.colors.onSurface)
                LinearProgressIndicator(progress = { (pack.bytes.toFloat()/pack.totalBytes).coerceIn(0f,1f) },modifier = Modifier.fillMaxWidth())
                TextButton(onClick = controller::cancelDownload) { Text("Cancel download") }
            }
            VoicePackPhase.Removing -> Text("Removing voice pack",color = FolioTheme.colors.onSurfaceVariant)
            VoicePackPhase.Installed -> {
                if (state.hasBook) {
                    NarrationTransport(controller, {}, showStop = true)
                    if (!state.followText && state.bookId == position?.bookId) TextButton(onClick = { controller.setFollowText(true) }) { Text("Return to narration") }
                    if (state.phase in setOf(NarrationPhase.Paused,NarrationPhase.Interrupted)) TextButton(onClick = controller::stop) { Text("Stop and continue reading") }
                    if (state.chapterTitle.isNotEmpty()) Text(state.chapterTitle,style = FolioTheme.typography.labelMedium,
                        color = FolioTheme.colors.onSurfaceVariant)
                    if (state.passage.isNotEmpty()) Text(state.passage,style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    state.error?.let { Text(it,color = FolioTheme.colors.error) }
                }
                if (position != null) Button(onClick = ::begin) {
                    Text(if (state.hasBook) "Listen from this page" else "Listen from here")
                }
                if (state.hasBook) {
                    var speed by remember(state.speed) { mutableFloatStateOf(state.speed) }
                    Text("Speed · ${state.speed}×",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
                    Slider(speed,{ speed = it },valueRange = .75f..2f,steps = 4,
                        onValueChangeFinished = { controller.setSpeed(speed); speed = controller.state.value.speed })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(state.followText,controller::setFollowText)
                        Text("Follow text",Modifier.padding(start = 12.dp),color = FolioTheme.colors.onSurface)
                    }
                    Text("Touching the page pauses text following. Playback continues.",
                        style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
                    Text("Sleep timer",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(15,30,60).forEach { minutes -> TextButton(onClick = { controller.setSleepTimer(minutes) }) { Text("$minutes min") } }
                        TextButton(onClick = { controller.setSleepTimer(null,true) }) { Text("End of chapter") }
                        if (state.sleepDeadlineMs != null || state.stopAtChapterEnd)
                            TextButton(onClick = { controller.setSleepTimer(null) }) { Text("Turn off") }
                    }
                    if (state.sleepDeadlineMs != null || state.stopAtChapterEnd) Text(
                        if (state.stopAtChapterEnd) "Pauses at the end of this chapter" else "Sleep timer is on",
                        style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
                }
                TextButton(onClick = { remove = true }) { Text(if (state.hasBook) "Stop and remove voice pack" else "Remove voice pack") }
                Text("Phonon 31M by Gradium · Apache 2.0",style = FolioTheme.typography.labelSmall,color = FolioTheme.colors.onSurfaceVariant)
            }
        }
    }
    if (remove) FolioDialog(true,{ remove = false },title = "Remove English narration?") {
        Text("This frees approximately 65 MB. Your book and listening progress stay saved.",color = FolioTheme.colors.onSurface)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) { TextButton(onClick = { remove = false }) { Text("Cancel") }
            TextButton(onClick = { remove = false; controller.removePack() }) { Text("Remove") } }
    }
    if (replace) FolioDialog(true,{ replace = false },title = "Switch narration?") {
        Text("Stop listening to ${state.title} and start this book?",color = FolioTheme.colors.onSurface)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) { TextButton(onClick = { replace = false }) { Text("Keep listening") }
            TextButton(onClick = { replace = false; position?.let(controller::start) }) { Text("Switch book") } }
    }
}
