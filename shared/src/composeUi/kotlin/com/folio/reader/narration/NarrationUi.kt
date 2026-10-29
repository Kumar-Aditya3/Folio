package com.folio.reader.narration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
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
    val configuration by controller.configuration.collectAsState()
    val pack by controller.pack.collectAsState()
    val ready = pack.phase == VoicePackPhase.Installed && !configuration.changing && configuration.narratorReady
    @Composable fun title(modifier: Modifier) {
        Column(modifier.clickable(onClick = onOpen).padding(8.dp)) {
            Text(state.title.ifEmpty { "English narration" }, style = FolioTheme.typography.labelLarge,
                color = FolioTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${state.label} · ${state.position?.progressPercent ?: 0}%", style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun controls() {
        IconButton(onClick = { controller.skip(false) }, enabled = ready && state.phase !in setOf(NarrationPhase.Preparing,NarrationPhase.Error)) {
            Icon(Icons.Default.SkipPrevious,"Previous passage",tint = FolioTheme.colors.onSurface)
        }
        IconButton(onClick = { if (state.wantsPlayback) controller.pause() else controller.play() }, enabled = ready) {
            Icon(if (state.wantsPlayback) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (state.wantsPlayback) "Pause narration" else "Resume narration", tint = FolioTheme.colors.primary)
        }
        IconButton(onClick = { controller.skip(true) }, enabled = ready && state.phase !in setOf(NarrationPhase.Preparing,NarrationPhase.Error)) {
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
fun NarrationPanel(controller: NarrationController, position: ReadingPosition?, modifier: Modifier = Modifier, scrollable: Boolean = true,
    showPlaybackSettings: Boolean = true) {
    val state by controller.state.collectAsState()
    val pack by controller.pack.collectAsState()
    val configuration by controller.configuration.collectAsState()
    val model = configuration.model
    val options by controller.options.collectAsState()
    val storage by controller.storage.collectAsState()
    val narratorPacks by controller.narratorPacks.collectAsState()
    DisposableEffect(controller) { onDispose { controller.stopPreview() } }
    val windowVisible = com.folio.reader.ui.theme.LocalFolioWindowVisible.current
    LaunchedEffect(windowVisible) { if (!windowVisible) controller.stopPreview() }
    val uriHandler = LocalUriHandler.current
    var accessToken by remember(model) { mutableStateOf("") }
    var termsAccepted by remember(model) { mutableStateOf(false) }
    val downloadMb = ((model.downloadBytes + 50_000) / 100_000) / 10.0
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
        if (!state.hasBook) state.error?.let { Text(it,color = FolioTheme.colors.error) }
        Text("Voice model",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
        NarrationModel.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().selectable(selected = model == option,
                enabled = !configuration.changing, role = Role.RadioButton,
                onClick = { controller.selectModel(option) }).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = model == option, onClick = null, enabled = !configuration.changing)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(option.label,style = FolioTheme.typography.bodyLarge,color = FolioTheme.colors.onSurface)
                    Text(when(option) {
                        NarrationModel.Phonon31M -> "Smallest download · 65.1 MB"
                        NarrationModel.Phonon40M -> "75.1 MB · Hugging Face access required"
                        NarrationModel.Kokoro82M -> "Multiple English voices · 132.4 MB download"
                    },style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
                }
            }
        }
        Text("Changing model or voice pauses narration and keeps your listening position.",
            style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        Text(model.attribution,style = FolioTheme.typography.labelSmall,color = FolioTheme.colors.onSurfaceVariant)
        if (model == NarrationModel.Kokoro82M) Text("Uses approximately 190 MB after installation, including voices and pronunciation data.",
            style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        if (configuration.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (model == NarrationModel.Kokoro82M) {
            Text("Voice",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KokoroVoices.all.forEach { voice -> FilterChip(selected = configuration.voiceId == voice.id,
                    onClick = { controller.selectVoice(voice.id) }, enabled = !configuration.changing,
                    label = { Text(voice.label) }) }
            }
        } else {
            Text("Narrator",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
            if (pack.phase == VoicePackPhase.Installed) NarrationToggle("Wi-Fi only for narrator downloads",wifiOnly) { wifiOnly = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = configuration.phononVoiceId == "default",onClick = { controller.selectPhononVoice("default") },
                    enabled = !configuration.changing,label = { Text("Default") })
                PhononVoices.all.forEach { voice -> FilterChip(selected = configuration.phononVoiceId == voice.id,
                    onClick = { controller.selectPhononVoice(voice.id) }, enabled = !configuration.changing,
                    label = { Text(voice.label) }) }
            }
            PhononVoices.all.firstOrNull { it.id == configuration.phononVoiceId }?.let { voice ->
                Text("${voice.description} · Gradium · Apache 2.0",style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
                val narrator = narratorPacks[voice.id] ?: VoicePackState()
                when(narrator.phase) {
                    VoicePackPhase.Installed -> TextButton(onClick = { controller.removeNarrator(voice.id) }) { Text("Remove narrator") }
                    VoicePackPhase.Downloading,VoicePackPhase.Verifying -> {
                        LinearProgressIndicator(progress = { (narrator.bytes / 256_136f).coerceIn(0f,1f) },modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { controller.cancelNarratorDownload(voice.id) }) { Text("Cancel narrator download") }
                    }
                    else -> {
                        narrator.error?.let { Text(it,color = FolioTheme.colors.error) }
                        TextButton(onClick = { controller.downloadNarrator(voice.id,wifiOnly) },enabled = !configuration.changing) { Text("Download narrator · 256 KB") }
                    }
                }
            }
            Text("Additional narrators are optional and work with either Phonon model.",style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        }
        if (pack.phase == VoicePackPhase.Installed && configuration.narratorReady) {
            TextButton(onClick = { if (storage.previewing) controller.stopPreview() else controller.previewVoice() },enabled = !configuration.changing) {
                Text(if (storage.previewing) "Stop voice preview" else "Preview this voice")
            }
            Text("Every preview reads the same passage. Your book position and listening totals stay saved.",
                style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        }
        when (pack.phase) {
            VoicePackPhase.Unsupported -> Text("English narration is unavailable on this device.",color = FolioTheme.colors.onSurfaceVariant)
            VoicePackPhase.NotInstalled,VoicePackPhase.Failed -> {
                pack.error?.let { Text(it,color = FolioTheme.colors.error) }
                Text("Download · $downloadMb MB",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wifiOnly,{ wifiOnly = it }); Text("Wi-Fi only",color = FolioTheme.colors.onSurface)
                }
                if (model == NarrationModel.Phonon40M) {
                    Text("Phonon 40M requires accepting Gradium’s terms with your Hugging Face account. Its license allows non-commercial use.",
                        style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
                    TextButton(onClick = { uriHandler.openUri("https://huggingface.co/gradium/phonon-40m-en") }) { Text("Open model terms") }
                    TextButton(onClick = { uriHandler.openUri("https://huggingface.co/settings/tokens") }) { Text("Create a read token") }
                    OutlinedTextField(accessToken,{ accessToken = it },label = { Text("Hugging Face read token") },
                        modifier = Modifier.fillMaxWidth(),singleLine = true,visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Row(Modifier.fillMaxWidth().selectable(termsAccepted,onClick = { termsAccepted = !termsAccepted },role = Role.Checkbox),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(termsAccepted,null)
                        Text("I accepted the model terms and will use it non-commercially.",Modifier.weight(1f),
                            style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurface)
                    }
                    Text("The token is used for this download only and is not saved. Playback works offline after installation.",
                        style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
                }
                Button(onClick = {
                    controller.downloadWithAccess(wifiOnly,accessToken)
                    accessToken = ""
                },enabled = !configuration.changing && (model != NarrationModel.Phonon40M || termsAccepted && accessToken.trim().startsWith("hf_"))) {
                    Text(if (pack.phase == VoicePackPhase.Failed) "Retry download" else "Download ${model.label}")
                }
                Text("Downloading does not start playback. You can remove the voice pack at any time.",
                    style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
            }
            VoicePackPhase.Downloading,VoicePackPhase.Verifying -> {
                Text(if (pack.phase == VoicePackPhase.Verifying) "Verifying English narration" else
                    "Downloading · ${(pack.bytes/1_000_000.0).toInt()} of $downloadMb MB",color = FolioTheme.colors.onSurface)
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
                if (position != null) Button(onClick = ::begin, enabled = !configuration.changing && configuration.narratorReady) {
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
            }
        }
        if (!showPlaybackSettings) return@Column
        Text("Delivery",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
        NarrationToggle("Paragraph phrasing",options.paragraphPhrasing) { controller.setOptions(options.copy(paragraphPhrasing = it)) }
        NarrationToggle("Pauses for book structure",options.structuralPauses) { controller.setOptions(options.copy(structuralPauses = it)) }
        Text("Delivery changes pause playback. Short paragraph groups keep phrasing together while retaining the original text position.",
            style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        val dictionaryBook = position?.bookId ?: state.bookId
        if (dictionaryBook != null) PronunciationEditor(controller,dictionaryBook)
        Text("Saved audio",style = FolioTheme.typography.titleMedium,color = FolioTheme.colors.onSurface)
        NarrationToggle("Cache narrated passages",options.audioCache) { controller.setOptions(options.copy(audioCache = it)) }
        Text("Optional, private audio on this device. Replays use saved speech when available. Old audio is removed as the limit is reached.",
            style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        if (options.audioCache) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(64,128,256).forEach { mb -> FilterChip(selected = options.cacheMegabytes == mb,
                    onClick = { controller.setOptions(options.copy(cacheMegabytes = mb)) },label = { Text("$mb MB") }) }
            }
            NarrationToggle("Prepare while charging",options.prepareWhileCharging) { controller.setOptions(options.copy(prepareWhileCharging = it)) }
            Text("Prepares the paused chapter while charging, using installed voices. Playback takes priority. Each preparation is limited to 8 minutes of work and 20 minutes of audio.",
                style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
            val preparationPosition = position ?: state.position
            if (options.prepareWhileCharging && preparationPosition != null && !storage.preparing)
                TextButton(onClick = { controller.prepareChapter(preparationPosition) }) { Text("Prepare this chapter when charging") }
        }
        if (storage.preparing) TextButton(onClick = controller::cancelPreparation) { Text("Cancel preparation") }
        storage.message?.let { Text(it,style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant) }
        Text("${storage.bytes / 1_000_000} MB saved",style = FolioTheme.typography.labelSmall,color = FolioTheme.colors.onSurfaceVariant)
        if (storage.bytes > 0) TextButton(onClick = controller::clearAudioCache) { Text("Clear saved audio") }
    }
    if (remove) FolioDialog(true,{ remove = false },title = "Remove ${model.label}?") {
        Text("The selected voice pack will be removed. Your book and listening progress stay saved.",color = FolioTheme.colors.onSurface)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) { TextButton(onClick = { remove = false }) { Text("Cancel") }
            TextButton(onClick = { remove = false; controller.removePack() }) { Text("Remove") } }
    }
    if (replace) FolioDialog(true,{ replace = false },title = "Switch narration?") {
        Text("Stop listening to ${state.title} and start this book?",color = FolioTheme.colors.onSurface)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) { TextButton(onClick = { replace = false }) { Text("Keep listening") }
            TextButton(onClick = { replace = false; position?.let(controller::start) }) { Text("Switch book") } }
    }
}

@Composable
private fun NarrationToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style = FolioTheme.typography.bodyMedium,color = FolioTheme.colors.onSurface)
        Switch(checked,change)
    }
}

@Composable
private fun PronunciationEditor(controller: NarrationController, bookId: String) {
    val rules by remember(controller,bookId) { controller.pronunciations(bookId) }.collectAsState()
    var expanded by remember(bookId) { mutableStateOf(false) }
    var written by remember(bookId) { mutableStateOf("") }
    var spoken by remember(bookId) { mutableStateOf("") }
    TextButton(onClick = { expanded = !expanded }) { Text("Book pronunciations · ${rules.size}") }
    if (expanded) {
        Text("Only this book’s spoken words change. The original text and progress remain intact. Replacing an existing word updates its pronunciation.",
            style = FolioTheme.typography.bodySmall,color = FolioTheme.colors.onSurfaceVariant)
        rules.forEach { rule -> Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
            Text("${rule.written} → ${rule.spoken}",Modifier.weight(1f),style = FolioTheme.typography.bodyMedium,color = FolioTheme.colors.onSurface)
            IconButton(onClick = { controller.setPronunciations(bookId,rules-rule) }) { Icon(Icons.Default.Close,"Remove pronunciation for ${rule.written}") }
        } }
        OutlinedTextField(written,{ written = it.take(128) },label = { Text("Written in the book") },singleLine = true,modifier = Modifier.fillMaxWidth())
        OutlinedTextField(spoken,{ spoken = it.take(256) },label = { Text("Say it as") },singleLine = true,modifier = Modifier.fillMaxWidth())
        TextButton(onClick = {
            controller.setPronunciations(bookId,rules.filterNot { it.written.equals(written.trim(),true) } + PronunciationRule(written,spoken))
            written = ""; spoken = ""
        },enabled = written.isNotBlank() && spoken.isNotBlank() && (rules.size < 100 || rules.any { it.written.equals(written.trim(),true) })) { Text("Save pronunciation") }
    }
}
