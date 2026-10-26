@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.reader.experience

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

@Composable internal fun ExperienceDialog(title: String, onDismiss: () -> Unit, anchor: Rect? = null, content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val timing = rememberMotionAnimation<Float>(if (title == "Export quote") MotionKey.QuoteExportReveal else MotionKey.ReadingPaperReveal)
    val reduced = LocalMotion.current.reduced
    LaunchedEffect(Unit) { if (reduced) progress.snapTo(1f) else progress.animateTo(1f, timing) }
    val atmosphere = FolioTheme.atmosphere
    val sourceWindow = LocalWindowInfo.current.containerSize
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 620.dp).heightIn(max = maxHeight * .94f)
                .onSizeChanged { paneSize = it }
                .graphicsLayer {
                    val amount = progress.value
                    alpha = amount
                    if (!reduced && anchor != null && paneSize.width > 0 && paneSize.height > 0) {
                        val width = (anchor.width / paneSize.width).coerceIn(.12f, .95f)
                        val height = (anchor.height / paneSize.height).coerceIn(.08f, .95f)
                        scaleX = width + (1f - width) * amount
                        scaleY = height + (1f - height) * amount
                        translationX = (anchor.center.x - sourceWindow.width / 2f) * (1f - amount)
                        translationY = (anchor.center.y - sourceWindow.height / 2f) * (1f - amount)
                    } else {
                        scaleX = if (reduced) 1f else .96f + .04f * amount; scaleY = scaleX
                    }
                }
                .border(1.dp, atmosphere.rimLight.copy(alpha = .25f), RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp), color = FolioTheme.colors.surface,
                contentColor = FolioTheme.colors.onSurface, tonalElevation = 3.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = FolioTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    content()
                }
            }
        }
    }
}

@Composable internal fun ExperienceChoices(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, label -> FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(label) }) }
    }
}
@Composable internal fun ExperienceToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = FolioTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable internal fun ReadingExportHost(environment: ReadingEnvironment) {
    val platform = rememberExperiencePlatform()
    val scope = rememberCoroutineScope()
    environment.exports?.let { sources ->
        QuoteExportComposer(sources, platform, environment.exportAnchor, onDismiss = { environment.exports = null; environment.exportAnchor = null })
    }
    environment.journal?.let { snapshot ->
        JournalComposer(snapshot, platform, environment.repository.state.collectAsState().value.reflections.map { it.noteId }.toSet(),
            onDismiss = { environment.journal = null })
    }
    environment.error?.let { message -> ExperienceDialog("Folio", { environment.error = null }) { Text(message) } }
}

@Composable private fun QuoteExportComposer(initial: List<ExportSource>, platform: ExperiencePlatform, anchor: Rect?, onDismiss: () -> Unit) {
    var sources by remember(initial) { mutableStateOf(initial.toList()) }
    val currentFont = FolioTheme.typography.fontTheme.let { if (it == FontTheme.CLASSIC) FontTheme.LITERARY else it }.id
    var options by remember { mutableStateOf(QuoteCardOptions(fontThemeId = currentFont)) }
    var selectedPage by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dragThreshold = with(LocalDensity.current) { 42.dp.toPx() }
    val clipboard = LocalClipboardManager.current
    val fontResolver = LocalFontFamilyResolver.current
    val measurer = remember(fontResolver) { androidx.compose.ui.text.TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr) }
    val coordinator = remember(fontResolver) { CanvasExportCoordinator(fontResolver) }
    val atmosphere = FolioTheme.atmosphere
    val palette = CardPalette(atmosphere.raisedFill, FolioTheme.colors.onSurface, FolioTheme.colors.primary, atmosphere.fieldBottom)
    val pages = remember(sources, options) { paginateQuoteCards(sources, options, measurer) }
    val index = selectedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    var covers by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var coversLoading by remember { mutableStateOf(false) }
    LaunchedEffect(sources, options.cover) {
        coversLoading = options.cover
        try {
            covers = if (options.cover) withContext(Dispatchers.IO) {
                val decoded = mutableMapOf<String, ImageBitmap>()
                sources.mapNotNull { source -> source.coverPath?.let { path -> runCatching {
                    source.id to decoded.getOrPut(path) { decodeCardImage(File(path).readBytes()) }
                }.getOrNull() } }.toMap()
            } else emptyMap()
        } finally { coversLoading = false }
    }
    fun export(share: Boolean) {
        if (busy || coversLoading || pages.isEmpty()) return
        busy = true; message = null
        val snapshotPages = pages.toList(); val snapshotOptions = options
        exportJob = scope.launch {
            try {
                val files = coordinator.images(snapshotPages, snapshotOptions, palette, covers.toMap())
                message = platform.saveImages(files, share)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = "Could not export. ${e.message.orEmpty()}" }
            finally { busy = false }
        }
    }
    ExperienceDialog("Export quote", { if (!busy) onDismiss() }, anchor = anchor) {
        ExperienceChoices(CardTemplate.entries.map { it.title }, options.template.ordinal) { options = options.copy(template = CardTemplate.entries[it]) }
        ExperienceChoices(CardFormat.entries.map { it.title }, options.format.ordinal) { options = options.copy(format = CardFormat.entries[it]) }
        pages.getOrNull(index)?.let { page ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.animation.Crossfade(options.template, animationSpec = rememberMotionAnimation<Float>(MotionKey.QuoteTemplateChange), label = "Quote template") { template ->
                val cardOptions = options.copy(template = template)
                val cardLayout = remember(page, cardOptions, measurer) { layoutQuoteCard(page, cardOptions, measurer) }
                Canvas(Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1080f / options.format.height)
                    .clip(RoundedCornerShape(12.dp))) {
                    scale(size.width / 1080, size.width / 1080, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                        drawQuoteCard(page, cardOptions, measurer, palette, covers[page.source.id], cardLayout)
                    }
                }
                }
            }
            if (pages.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = index > 0, onClick = { selectedPage = index - 1 }) { Text("Previous") }
                Text("${index + 1} / ${pages.size}")
                TextButton(enabled = index < pages.lastIndex, onClick = { selectedPage = index + 1 }) { Text("Next") }
            }
        }
        if (sources.size > 1) {
            Text("Sequence", style = FolioTheme.typography.titleSmall)
            sources.forEachIndexed { i, source ->
                key(source.id) {
                Row(Modifier.pointerInput(source.id) {
                    var travelled = 0f
                    detectDragGesturesAfterLongPress(onDragStart = { travelled = 0f }, onDrag = { change, delta ->
                        change.consume(); travelled += delta.y
                        val current = sources.indexOfFirst { it.id == source.id }
                        if (kotlin.math.abs(travelled) >= dragThreshold) {
                            val next = (current + if (travelled > 0) 1 else -1).coerceIn(0, sources.lastIndex)
                            if (next != current) sources = sources.toMutableList().apply { add(next, removeAt(current)) }
                            travelled = 0f
                        }
                    })
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${source.text.take(45)}", modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(enabled = i > 0, onClick = { sources = sources.toMutableList().apply { add(i - 1, removeAt(i)) } }) { Text("Up") }
                    TextButton(enabled = i < sources.lastIndex, onClick = { sources = sources.toMutableList().apply { add(i + 1, removeAt(i)) } }) { Text("Down") }
                }
                }
            }
        }
        Text("Colour treatment", style = FolioTheme.typography.titleSmall)
        ExperienceChoices(listOf("Current theme", "Ink-friendly"), if (options.inkFriendly) 1 else 0) {
            options = options.copy(inkFriendly = it == 1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(options.title, { options = options.copy(title = !options.title) }, label = { Text("Title") })
            FilterChip(options.author, { options = options.copy(author = !options.author) }, label = { Text("Author") })
            FilterChip(options.chapter, { options = options.copy(chapter = !options.chapter) }, label = { Text("Chapter") })
            FilterChip(options.cover, { options = options.copy(cover = !options.cover) }, label = { Text("Cover") })
            if (sources.any { !it.note.isNullOrBlank() }) FilterChip(options.note, { options = options.copy(note = !options.note) }, label = { Text("My notes") })
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { exportJob?.cancel() }) { Text("Cancel export") }
        }
        message?.let { Text(it, style = FolioTheme.typography.bodySmall) }
        if (coversLoading) Text("Loading covers…", style = FolioTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && !coversLoading && pages.isNotEmpty(), onClick = { export(false) }) { Text(if (busy) "Creating…" else "Save image${if (pages.size > 1) "s" else ""}") }
            if (platform.nativeSharing) OutlinedButton(enabled = !busy && !coversLoading, onClick = { export(true) }) { Text("Share") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(sources.joinToString("\n\n") { it.text + "\n— " + it.author + ", " + it.title })) }) { Text("Copy text") }
            if (!platform.nativeSharing) {
                TextButton(enabled = !busy && !coversLoading && pages.isNotEmpty(), onClick = { scope.launch {
                    try { platform.copyImage(renderQuoteCard(pages[index], options, measurer, palette, covers[pages[index].source.id])); message = "Image copied" }
                    catch (e: Exception) { message = "Could not copy image" }
                } }) { Text("Copy image") }
                TextButton(onClick = { platform.openExportFolder() }) { Text("Open folder") }
            }
        }
    }
}

@Composable private fun JournalComposer(snapshot: JournalSnapshot, platform: ExperiencePlatform, reflectionIds: Set<String>, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(JournalOptions()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun generate(print: Boolean) {
        if (busy) return
        busy = true; message = null
        val chosenOptions = options
        scope.launch {
            try {
                val sources = snapshot.sources.map { it.copy(isReflection = it.id in reflectionIds) }
                val css = withContext(Dispatchers.IO) { listOf("FolioDisplay" to "fraunces_variable.ttf", "FolioText" to "manrope_variable.ttf").joinToString("\n") { (family, file) ->
                    platform.fontData(file)?.let { "@font-face{font-family:$family;src:url(data:font/ttf;base64,${Base64.getEncoder().encodeToString(it)})}" }.orEmpty()
                } }
                val cover = if (chosenOptions.cover) withContext(Dispatchers.IO) { snapshot.book.coverPath?.let { runCatching { Base64.getEncoder().encodeToString(encodeCardPng(decodeCardImage(File(it).readBytes()))) }.getOrNull() } } else null
                val summary = "${snapshot.sessions.sumOf { it.durationMs } / 60000} minutes of reading · ${snapshot.sources.size} saved passages and notes"
                val html = readingJournalHtml(snapshot.book.displayTitle, snapshot.book.displayAuthor, sources, chosenOptions, summary, css, cover)
                message = platform.journal(snapshot.book.displayTitle, html, print)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = "Could not create journal. ${e.message.orEmpty()}" }
            finally { busy = false }
        }
    }
    ExperienceDialog("Your reading journal", { if (!busy) onDismiss() }) {
        Text(snapshot.book.displayTitle, style = FolioTheme.typography.quote)
        Text("${snapshot.sources.size} saved passages and notes", color = FolioTheme.colors.onSurfaceVariant)
        ExperienceChoices(listOf("Editorial", "Ink saver"), if (options.inkSaver) 1 else 0) { options = options.copy(inkSaver = it == 1) }
        ExperienceChoices(listOf("A4", "Letter"), if (options.letter) 1 else 0) { options = options.copy(letter = it == 1) }
        ExperienceToggle("Highlights", options.highlights) { options = options.copy(highlights = it) }
        ExperienceToggle("Associated notes", options.associatedNotes) { options = options.copy(associatedNotes = it) }
        ExperienceToggle("Standalone notes", options.standaloneNotes) { options = options.copy(standaloneNotes = it) }
        ExperienceToggle("Reflections", options.reflections) { options = options.copy(reflections = it) }
        ExperienceToggle("Book cover", options.cover) { options = options.copy(cover = it) }
        ExperienceToggle("Reading summary", options.readingSummary) { options = options.copy(readingSummary = it) }
        ExperienceToggle("Ruled reflection space", options.ruledSpace) { options = options.copy(ruledSpace = it) }
        Text("Chapters", style = FolioTheme.typography.titleSmall)
        TextButton(onClick = { options = options.copy(chapters = null) }) { Text("Include all chapters") }
        snapshot.chapters.filter { c -> snapshot.sources.any { it.chapterId == c.id } }.forEach { chapter ->
            val selected = options.chapters?.contains(chapter.id) ?: true
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, { checked ->
                    val chosen = options.chapters ?: snapshot.chapters.map { it.id }.toSet()
                    options = options.copy(chapters = if (checked) chosen + chapter.id else chosen - chapter.id)
                })
                Text(chapter.title, style = FolioTheme.typography.bodyMedium)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { generate(false) }) { Text(if (platform.nativeSharing) "Print / Save PDF" else "Save PDF") }
            if (!platform.nativeSharing) OutlinedButton(enabled = !busy, onClick = { generate(true) }) { Text("Print") }
        }
    }
}
