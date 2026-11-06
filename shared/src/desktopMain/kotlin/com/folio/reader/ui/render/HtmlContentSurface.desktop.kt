package com.folio.reader.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionKey
import java.awt.BorderLayout
import java.awt.EventQueue
import java.io.File
import java.net.URLDecoder
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import me.friwi.jcefmaven.CefAppBuilder

actual fun htmlSurfaceOccludesOverlays(): Boolean = true

/**
 * Desktop EPUB surface: embedded Chromium (JCEF) renders chapters the same way
 * Android's WebView does. No Compose text fallback — the browser owns rendering
 * so EPUB CSS, images, fonts and pagination survive.
 *
 * JCEF creates the native browser lazily, the first time its Swing component is
 * painted. Calling loadURL before that moment is silently dropped and leaves the
 * page blank, so loads are queued until the browser genuinely exists.
 */
@Composable
actual fun HtmlContentSurface(
    sections: List<ReaderSection>,
    windowed: Boolean,
    anchorChapterId: String?,
    settings: ReaderSettings,
    position: ReadingPosition?,
    highlights: List<Highlight>,
    enabled: Boolean,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    onPageChange: (Int, Int) -> Unit,
    onVisibleSection: (spineIndex: Int) -> Unit,
    onExtendForward: () -> Unit,
    onExtendBackward: () -> Unit,
    windowOp: WindowOp?,
    onWindowOpApplied: (nonce: Long) -> Unit,
    onChapterEnd: () -> Unit,
    onChapterStart: () -> Unit,
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveResource: suspend (chapterHref: String, src: String) -> String?,
    onHighlightParagraph: ((chapterId: String, paragraphIndex: Int, selectedText: String) -> Unit)?,
    onSelectionChanged: ((chapterId: String, paragraphIndex: Int, selectedText: String?) -> Unit)?,
    clearSelectionRequest: Long?,
    seekRequest: Pair<Float, Long>?,
    seekTargetRequest: Pair<String, Long>?,
    onContentReady: () -> Unit,
    onSelectionBounds: ((ReaderSelectionBounds?) -> Unit)?,
) {
    val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
    val backgroundColor = Color(theme.background)
    val progressColor = Color(theme.progress)
    val resolvedMotion = LocalMotion.current
    val selectionMotion = resolvedMotion[MotionKey.SelectionToolbar]
    val selectionMotionJs = "window.__folioSelectionMotion={duration:${if (resolvedMotion.reduced) 0L else selectionMotion.durationMs},scale:${if (resolvedMotion.reduced) 1f else 1f - .04f * selectionMotion.intensity}};"

    // Latest-callback holder: effects stay keyed on content only, so progress
    // updates (which recompose the reader constantly) never restart them.
    val callbacks = remember { SurfaceCallbacks() }
    callbacks.onProgress = onProgress
    callbacks.onPageChange = onPageChange
    callbacks.onVisibleSection = onVisibleSection
    callbacks.onExtendForward = onExtendForward
    callbacks.onExtendBackward = onExtendBackward
    callbacks.onChapterEnd = onChapterEnd
    callbacks.onChapterStart = onChapterStart
    callbacks.onTap = onTap
    callbacks.onLinkClick = onLinkClick
    callbacks.onHighlightParagraph = onHighlightParagraph
    callbacks.onSelectionChanged = onSelectionChanged
    callbacks.onSelectionBounds = onSelectionBounds
    callbacks.onContentReady = onContentReady

    val resolver by rememberUpdatedState(onResolveResource)
    val positionState by rememberUpdatedState(position)
    val settingsState by rememberUpdatedState(settings)
    val sectionsState by rememberUpdatedState(sections)
    // A chapter window loads as one document; the single-section path (documents,
    // and non-windowed chapters) keys everything on the chapter href as before.
    val loadKey = if (windowed) {
        "window:" + sections.joinToString("-") { it.spineIndex.toString() }
    } else {
        sections.firstOrNull()?.href ?: ""
    }

    val overlayHtml = LocalOverlayHtml.current
    callbacks.onOverlayAction = LocalOverlayAction.current

    var session by remember { mutableStateOf<JcefSession?>(null) }
    LaunchedEffect(session, selectionMotionJs) { session?.execute(selectionMotionJs) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    callbacks.onLoadError = { fatalError = it }
    var preparing by remember { mutableStateOf(true) }
    val navigator = LocalReaderPageNavigator.current
    val windowVisible = com.folio.reader.ui.theme.LocalFolioWindowVisible.current
    LaunchedEffect(session, enabled, windowVisible) { session?.setActive(enabled && windowVisible) }
    DisposableEffect(session, navigator) {
        val current = session
        val turn: (Int) -> Unit = { current?.turn(it) }
        val focus: () -> Unit = { current?.focus() }
        navigator?.onTurn = turn
        navigator?.onFocus = focus
        onDispose {
            if (navigator?.onTurn === turn) navigator.onTurn = null
            if (navigator?.onFocus === focus) navigator.onFocus = null
        }
    }
    var resolvedHtml by remember(loadKey) { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var appliedSettings by remember { mutableStateOf<ReaderSettings?>(null) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    // Chapter whose document has been handed to the browser; null again on change.
    var loadedChapter by remember(loadKey) { mutableStateOf<String?>(null) }

    // Only geometry changes (page columns, measure cap, gutter) need a document
    // reload; theme/typography changes swap the stylesheet in place, so switching
    // a theme no longer flashes through the other layouts.
    fun structuralSig(s: ReaderSettings) = Triple(
        s.layoutMode, s.textWidth, s.margins.left.toInt()
    )

    // 1) Start Chromium: install (if needed) on a worker thread, then create the
    //    client/browser on the AWT EDT where Swing components must be built.
    LaunchedEffect(Unit) {
        val owned = AtomicReference<JcefSession?>(null)
        try {
            val created = withContext(Dispatchers.IO) {
                JcefSession.create(callbacks).also { owned.set(it) }
            }
            session = created
            owned.set(null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fatalError = error.message ?: "Unable to start embedded Chromium"
        } finally {
            withContext(NonCancellable) { owned.getAndSet(null)?.dispose() }
        }
    }

    DisposableEffect(Unit) {
        onDispose { session?.dispose() }
    }

    // 2) Resolve EPUB resources (images, stylesheets, fonts) to cache files — per
    //    section, then assemble the window document when one is on screen.
    LaunchedEffect(loadKey, sections, session) {
        val current = session ?: return@LaunchedEffect
        current.invalidateDocument()
        loadedChapter = null
        fatalError = null
        resolvedHtml = null
        if (!hasLoadedOnce) preparing = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val resolved = sections.map { section ->
                    section.copy(html = resolveResources(section.html, section.href) { href, src -> resolver(href, src) })
                }
                resolved
            }
        }
        result
            .onSuccess {
                current.sectionsForCallbacks = it
                current.anchorChapterIdForCallbacks = anchorChapterId
                resolvedHtml = if (windowed) ReaderWindowAssembler.assemble(it) else it.firstOrNull()?.html.orEmpty()
            }
            .onFailure { fatalError = it.message ?: "Unable to prepare chapter" }
    }

    // 2b) Grow/trim the window on screen. Each op is consumed once; an append or
    //     prepend re-runs the highlight painter afterwards so the freshly
    //     injected section wears its own marks.
    LaunchedEffect(windowOp, session, loadedChapter) {
        val op = windowOp ?: return@LaunchedEffect
        val current = session ?: return@LaunchedEffect
        if (loadedChapter != loadKey) return@LaunchedEffect
        val section = when (op) {
            is WindowOp.Append -> op.section
            is WindowOp.Prepend -> op.section
            is WindowOp.Trim -> null
        }
        val resolved = section?.let {
            withContext(Dispatchers.IO) {
                it.copy(html = resolveResources(com.folio.reader.epub.ChapterSanitizer.sanitize(it.html), it.href) { href, src -> resolver(href, src) })
            }
        }
        val mutation = if (resolved != null) {
            val fragment = ReaderWindowAssembler.sectionFragment(resolved)
            if (fragment.isBlank()) return@LaunchedEffect
            val hook = if (op is WindowOp.Append) "__folioAppend" else "__folioPrepend"
            val fixed = if (resolved.isFixedLayout && resolved.fxlWidth > 0 && resolved.fxlHeight > 0)
                "${resolved.fxlWidth}:${resolved.fxlHeight}" else ""
            "window.$hook(${resolved.spineIndex},${ReaderWindowAssembler.jsStringLiteral(resolved.chapterId)}," +
                "${ReaderWindowAssembler.jsStringLiteral(fragment)},${ReaderWindowAssembler.jsStringLiteral(fixed)});"
        } else {
            val trim = op as WindowOp.Trim
            "window.__folioTrim(${trim.fromSpine},${trim.toSpine});"
        }
        current.applyWindowOperation(op.nonce, mutation) {
            current.sectionsForCallbacks = when (op) {
                is WindowOp.Append -> (current.sectionsForCallbacks.filterNot { it.spineIndex == op.section.spineIndex } + checkNotNull(resolved))
                is WindowOp.Prepend -> listOf(checkNotNull(resolved)) + current.sectionsForCallbacks.filterNot { it.spineIndex == op.section.spineIndex }
                is WindowOp.Trim -> current.sectionsForCallbacks.filter { it.spineIndex in op.fromSpine..op.toSpine }
            }
            current.applyHighlights(HighlightPaint.motionJs(if (resolvedMotion.reduced) 0L else resolvedMotion[com.folio.reader.ui.theme.MotionKey.HighlightInkSettle].durationMs) + HighlightPaint.applyJs(highlights, theme))
            onWindowOpApplied(op.nonce)
        }
    }

    // 3) Write and load the styled document — on content changes and on geometry
    //    changes only (layout mode / text width / gutter).
    LaunchedEffect(resolvedHtml, session, reloadTick) {
        val current = session ?: return@LaunchedEffect
        val source = resolvedHtml ?: return@LaunchedEffect
        loadedChapter = null
        current.invalidateDocument()
        if (!hasLoadedOnce) preparing = true
        val s = settingsState
        val fraction = (positionState?.scrollOffset ?: 0.0).toFloat().coerceIn(0f, 1f)
        System.err.println("[FolioPage] load seed=$fraction chapter=$loadKey seek=${seekRequest?.first} target=${seekTargetRequest?.first}")
        val pagedCols = PageEngine.colsFor(s.layoutMode)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val liveSource = if (windowed && current.sectionsForCallbacks.isNotEmpty())
                    ReaderWindowAssembler.assemble(current.sectionsForCallbacks) else source
                val styled = injectReaderCss(liveSource, s)
                current.writeDocument(styled).toFileUrl()
            }
        }
        result.onSuccess { url ->
            val js = when {
                pagedCols > 0 -> PageEngine.js(fraction, pagedCols, s.margins.left, PageEngine.measurePx(s.textWidth))
                windowed -> ContinuousEngine.js(
                    seedSpine = sections.firstOrNull { it.chapterId == anchorChapterId }?.spineIndex
                        ?: sections.firstOrNull()?.spineIndex ?: -1,
                    seedFraction = fraction,
                    desktopEvents = true,
                    shadowCss = ReaderCss.shadowStyleSheet(s)
                )

                else -> readerBridgeJs(fraction)
            }
            current.load(url, selectionMotionJs + "window.__folioReduceMotion=${resolvedMotion.reduced};window.__folioNativeSelectionTools=${onHighlightParagraph != null && onSelectionChanged != null};" + js)
            loadedChapter = loadKey
            appliedSettings = s
            hasLoadedOnce = true
            preparing = false
        }.onFailure {
            fatalError = it.message ?: "Unable to render chapter"
            preparing = false
        }
    }

    // 4) Style-only settings changes (theme, colors, typography) swap the sheet
    //    in place — no navigation, no layout flash. Geometry changes reload.
    LaunchedEffect(settings, session, appliedSettings) {
        val current = session ?: return@LaunchedEffect
        val applied = appliedSettings ?: return@LaunchedEffect
        if (applied == settings) return@LaunchedEffect
        if (structuralSig(settings) != structuralSig(applied)) {
            reloadTick++
        } else {
            if (readerStyleCss(applied) != readerStyleCss(settings) || fontFaceCss(applied) != fontFaceCss(settings)) {
                current.applyStyle(fontFaceCss(settings), readerStyleCss(settings), ReaderCss.shadowStyleSheet(settings))
            }
            appliedSettings = settings
        }
    }

    // 5) Push the glass overlay (contents/annotations/settings) into the page.
    //    The session keeps the panel's own scroll position, so re-pushing (for
    //    example after picking a chapter) never moves the list.
    LaunchedEffect(overlayHtml, session, resolvedHtml, reloadTick) {
        val current = session ?: return@LaunchedEffect
        current.pushOverlay(overlayHtml ?: "")
    }

    // 6) Bottom-bar seeks: jump to the tapped fraction of the chapter.
    var appliedFractionSeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(seekRequest, session, loadedChapter) {
        val current = session ?: return@LaunchedEffect
        val req = seekRequest ?: return@LaunchedEffect
        if (loadedChapter != loadKey) return@LaunchedEffect
        if (appliedFractionSeek == req.second) return@LaunchedEffect
        appliedFractionSeek = req.second
        current.seek(req.first)
    }

    // 7) Paint the chapter's highlights. Waits for this chapter's document so the
    //    marks are never drawn into the previous chapter, and re-runs when the
    //    selection set or the theme's palette changes. Declared before the jump
    //    effect below: effects run in declaration order, and a jump has to be able
    //    to find the mark the painter creates.
    LaunchedEffect(highlights, settings.themeId, settings.customTheme, session, loadedChapter) {
        val current = session ?: return@LaunchedEffect
        if (loadedChapter != loadKey) return@LaunchedEffect
        current.applyHighlights(HighlightPaint.js(highlights, theme))
    }

    // 8) Annotation jumps: scroll to a highlight mark or paragraph. Waits for this
    //    chapter's document to be handed over, so it never moves an older document
    //    that happens to still be on screen. A windowed seek is scoped to the
    //    anchor's section so paragraph indices resolve inside the right chapter.
    var appliedSeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(seekTargetRequest, session, loadedChapter) {
        val current = session ?: return@LaunchedEffect
        val req = seekTargetRequest ?: return@LaunchedEffect
        if (loadedChapter != loadKey) return@LaunchedEffect
        if (appliedSeek == req.second) return@LaunchedEffect
        appliedSeek = req.second
        val scoped = if (windowed && anchorChapterId != null) "c:$anchorChapterId|${req.first}" else req.first
        current.seekTo(scoped)
    }

    // 9) The chrome committed a selection; drop it so the control dims again.
    LaunchedEffect(clearSelectionRequest, session) {
        val current = session ?: return@LaunchedEffect
        if (clearSelectionRequest == null || clearSelectionRequest == 0L) return@LaunchedEffect
        current.clearSelection()
    }

    val error = fatalError
    if (error != null) {
        Box(modifier = modifier.background(backgroundColor), contentAlignment = Alignment.Center) {
            Text("The reading page could not be loaded: $error", color = Color(theme.primaryText))
        }
        return
    }

    Box(modifier = modifier.background(backgroundColor)) {
        val current = session
        if (current != null) {
            // Always composed (never swapped out) so the heavyweight canvas keeps
            // its native window across chapter/settings reloads.
            SwingPanel(
                modifier = Modifier.matchParentSize(),
                factory = { current.component }
            )
        }
        if (current == null || preparing) {
            Box(
                modifier = Modifier.matchParentSize().background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = progressColor)
            }
        } else if (!enabled && overlayHtml == null) {
            // TOC/annotations overlays are open: swallow pointer input so the page
            // underneath stops scrolling or following clicks.
            Box(
                modifier = Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            )
        }
    }
}

/** Latest UI callbacks, updated on recomposition without restarting effects. */
private class SurfaceCallbacks {
    @Volatile var onProgress: (Float) -> Unit = {}
    @Volatile var onPageChange: (Int, Int) -> Unit = { _, _ -> }
    @Volatile var onVisibleSection: (Int) -> Unit = {}
    @Volatile var onExtendForward: () -> Unit = {}
    @Volatile var onExtendBackward: () -> Unit = {}
    @Volatile var onChapterEnd: () -> Unit = {}
    @Volatile var onChapterStart: () -> Unit = {}
    @Volatile var onTap: () -> Unit = {}
    @Volatile var onLinkClick: ((String) -> Unit)? = null
    @Volatile var onOverlayAction: ((String) -> Unit)? = null
    @Volatile var onHighlightParagraph: ((String, Int, String) -> Unit)? = null
    @Volatile var onSelectionChanged: ((String, Int, String?) -> Unit)? = null
    @Volatile var onSelectionBounds: ((ReaderSelectionBounds?) -> Unit)? = null
    @Volatile var onLoadError: (String) -> Unit = {}
    /** Fired on the main frame's first onLoadEnd — the chapter's real first paint. */
    @Volatile var onContentReady: () -> Unit = {}
}

/**
 * Owns one JCEF client + windowed browser plus the load pipeline. The native
 * browser appears only after the Swing component is first painted, so [load]
 * queues the URL until that happens (about:blank's load-end, or the native-ref
 * fallback poll) and then navigates.
 */
private class JcefSession private constructor(
    private val client: CefClient,
    val browser: CefBrowser,
    val component: javax.swing.JPanel,
    private val callbacks: SurfaceCallbacks
) {
    private val documentGate = DesktopReaderDocumentGate()
    private val deferredCommands = mutableListOf<Pair<Long, String>>()
    private val pendingOperations = mutableMapOf<Long, () -> Unit>()
    @Volatile private var pendingStyle: String? = null
    @Volatile private var readyDelivered = false
    @Volatile private var ready = false
    @Volatile private var disposed = false
    @Volatile private var pendingLoad: Runnable? = null
    @Volatile private var expectedUrl: String? = null
    @Volatile private var pendingJs: String? = null
    @Volatile private var lastOverlay: String? = null
    @Volatile private var lastOverlayKind: String = ""
    @Volatile private var lastOverlayScrollVal: Int = 0
    @Volatile private var overlayCentered: Boolean = false
    @Volatile private var pendingSeekTarget: String? = null
    @Volatile private var pendingSeekFraction: Float? = null
    @Volatile private var lastHighlightJs: String? = null
    @Volatile private var lastDocument: File? = null
    // Window context for title-event resolution: sections (spine → chapter) and
    // the anchor chapter. Updated by the composable on every new window.
    @Volatile var sectionsForCallbacks: List<ReaderSection> = emptyList()
    @Volatile var anchorChapterIdForCallbacks: String? = null
    private var readyTimer: Timer? = null

    companion object {
        fun create(callbacks: SurfaceCallbacks): JcefSession {
            val app = JcefRuntime.app()
            var client: CefClient? = null
            var browser: CefBrowser? = null
            var session: JcefSession? = null
            val task = Runnable {
                try {
                    val createdClient = app.createClient().also { client = it }
                    val createdBrowser = createdClient.createBrowser("about:blank", false, false).also { browser = it }
                    // An extra heavyweight AWT Panel creates a second native paint owner
                    // around CEF. Use Swing's lightweight host, as SwingPanel expects.
                    val component = javax.swing.JPanel(BorderLayout()).apply { add(createdBrowser.uiComponent, BorderLayout.CENTER) }
                    session = JcefSession(createdClient, createdBrowser, component, callbacks).also {
                        it.installHandlers()
                        it.startReadyFallback()
                    }
                } catch (error: Throwable) {
                    runCatching { browser?.close(true) }
                    runCatching { client?.dispose() }
                    throw error
                }
            }
            if (EventQueue.isDispatchThread()) task.run() else EventQueue.invokeAndWait(task)
            return checkNotNull(session)
        }
    }

    private fun installHandlers() {
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onTitleChange(browser: CefBrowser, title: String?) {
                val generation = documentGate.generation
                val receivedUrl = browser.url
                EventQueue.invokeLater titleDelivery@{
                if (!documentGate.matches(generation, receivedUrl)) return@titleDelivery
                var t = documentGate.message(title, receivedUrl) ?: return@titleDelivery
                if (t.startsWith("folio-ready:$generation|")) {
                    if (!documentGate.acknowledge(generation, receivedUrl)) return@titleDelivery
                    pendingStyle?.let { execute(it) }
                    lastOverlay?.let { execute(overlayJs(it, lastOverlayScrollVal, false)) }
                    lastHighlightJs?.let { execute(it) }
                    val queued = synchronized(deferredCommands) { deferredCommands.toList().also { deferredCommands.clear() } }
                    queued.filter { it.first == generation }.forEach { execute(it.second) }
                    flushPendingSeek()
                    flushPendingSeekFraction()
                    deliverReady()
                    t = t.substringAfter('|', "")
                }
                if (t.startsWith("folio-error:")) { callbacks.onLoadError(t.removePrefix("folio-error:")); return@titleDelivery }
                if (t.startsWith("folio-op:") && documentGate.ready) {
                    val nonce = t.removePrefix("folio-op:").substringBefore(':').toLongOrNull()
                    if (nonce != null) pendingOperations.remove(nonce)?.invoke()
                    return@titleDelivery
                }
                if (!documentGate.ready || !documentGate.active) return@titleDelivery
                when {
                    t.startsWith("folio-progress:") -> {
                        val parts = t.split(':')
                        if (parts.size < 5) return@titleDelivery
                        val fraction = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: return@titleDelivery
                        val spine = parts.getOrNull(5)?.toIntOrNull()
                        callbacks.onProgress(fraction)
                        callbacks.onPageChange(
                            parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                            parts[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
                        )
                        if (spine != null) callbacks.onVisibleSection(spine)
                    }

                    t.startsWith("folio-extend:fwd:") -> callbacks.onExtendForward()

                    t.startsWith("folio-extend:bwd:") -> callbacks.onExtendBackward()

                    t.startsWith("folio-link:") -> {
                        val encoded = t.removePrefix("folio-link:").substringAfter(':', "")
                        val href = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return@titleDelivery
                        callbacks.onLinkClick?.invoke(href)
                    }

                    t.startsWith("folio-tap:") -> callbacks.onTap()

                    t.startsWith("folio-edge:end:") -> callbacks.onChapterEnd()

                    t.startsWith("folio-edge:start:") -> callbacks.onChapterStart()

                    t.startsWith("folio-sel3:") -> {
                        parseReaderSelectionMessage(t)?.let { message ->
                            val text = runCatching { URLDecoder.decode(message.encodedText, "UTF-8") }.getOrNull()
                            val chapterId = sectionsForCallbacks.firstOrNull { it.spineIndex == message.spine }?.chapterId
                                ?: anchorChapterIdForCallbacks?.takeIf { message.spine < 0 } ?: return@titleDelivery
                            callbacks.onSelectionBounds?.invoke(message.bounds)
                            callbacks.onSelectionChanged?.invoke(chapterId, message.paragraph, text?.takeIf { it.isNotBlank() })
                        }
                    }
                    t.startsWith("folio-selclear:") -> {
                        callbacks.onSelectionBounds?.invoke(null)
                        callbacks.onSelectionChanged?.invoke("", 0, null)
                    }

                    t.startsWith("folio-sel2:") -> {
                        callbacks.onSelectionBounds?.invoke(null)
                        val rest = t.removePrefix("folio-sel2:")
                        val spine = rest.substringBefore(':').toIntOrNull() ?: -1
                        val afterSpine = rest.substringAfter(':')
                        val idx = afterSpine.substringBefore(':').toIntOrNull() ?: 0
                        val encoded = afterSpine.substringAfter(':').substringBeforeLast(':')
                        val text = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                        // The spine names a section of the window; resolve it to the
                        // chapter the host knows.
                        val chapterId = sectionsForCallbacks.firstOrNull { it.spineIndex == spine }?.chapterId
                            ?: anchorChapterIdForCallbacks?.takeIf { spine < 0 } ?: return@titleDelivery
                        if (text.isNullOrBlank()) callbacks.onSelectionChanged?.invoke(chapterId, idx, null)
                        else callbacks.onSelectionChanged?.invoke(chapterId, idx, text)
                    }

                    t.startsWith("folio-sel:") -> {
                        callbacks.onSelectionBounds?.invoke(null)
                        val rest = t.removePrefix("folio-sel:")
                        val idx = rest.substringBefore(':').toIntOrNull() ?: 0
                        val encoded = rest.substringAfter(':').substringBeforeLast(':')
                        val text = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                        val chapterId = anchorChapterIdForCallbacks
                            ?: sectionsForCallbacks.firstOrNull()?.chapterId.orEmpty()
                        if (text.isNullOrBlank()) callbacks.onSelectionChanged?.invoke(chapterId, idx, null)
                        else callbacks.onSelectionChanged?.invoke(chapterId, idx, text)
                    }

                    t.startsWith("folio-ovl:") -> {
                        callbacks.onOverlayAction?.invoke(desktopReaderOverlayAction(t))
                    }

                    t.startsWith("folio-ovlscroll:") -> {
                        val v = t.removePrefix("folio-ovlscroll:").substringBefore(':').toIntOrNull()
                        if (v != null) lastOverlayScrollVal = v
                    }

                    t.startsWith("folio-engdiag:") ->
                        System.err.println("[FolioPage] ${t.removePrefix("folio-engdiag:").substringBeforeLast(':')}")
                }
                }
            }
        })
        // Chromium's right-click menu (Copy, View source, Print…) has no place in a
        // book page: it breaks the reading surface and can leave the app. An empty
        // model means CEF shows no menu at all.
        client.addContextMenuHandler(object : org.cef.handler.CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(
                browser: CefBrowser,
                frame: CefFrame?,
                params: org.cef.callback.CefContextMenuParams?,
                model: org.cef.callback.CefMenuModel?
            ) {
                model?.clear()
            }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                // Subresource loads (CSS, images) also trigger this; main frame only.
                if (!frame.isMain || disposed) return
                markReady()
                val expected = expectedUrl
                val js = pendingJs
                if (expected != null && js != null && sameUrl(frame.getURL(), expected)) {
                    pendingJs = null
                    val generation = documentGate.generation
                    EventQueue.invokeLater {
                        if (documentGate.matches(generation, browser.url)) browser.executeJavaScript(
                            desktopReaderBootstrap(generation, documentGate.active, false, js, expected), expected, 0)
                    }
                }
            }

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String
            ) {
                if (!frame.isMain || disposed) return
                // Navigation aborts (reloads, stopped loads) are routine, not errors.
                if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
                if (failedUrl.isNullOrBlank() || failedUrl == "about:blank") return
                val message = "Folio JCEF load failed: $errorCode $errorText ($failedUrl)"
                System.err.println(message)
                EventQueue.invokeLater { if (!disposed && sameUrl(failedUrl, expectedUrl)) callbacks.onLoadError(message) }
            }
        })
    }

    /**
     * Fallback for the ready signal: if the about:blank load-end never arrives
     * (component not painted yet when a load is queued), watch the native handle
     * which JNI sets as soon as the browser window exists.
     */
    private fun startReadyFallback() {
        val timer = Timer("folio-jcef-ready", true)
        timer.schedule(object : TimerTask() {
            private var attempts = 0

            override fun run() {
                if (disposed) {
                    cancel()
                    return
                }
                val nativeRef = (browser as? org.cef.callback.CefNative)?.getNativeRef("CefBrowser") ?: 0L
                if (nativeRef != 0L) {
                    markReady()
                    cancel()
                } else if (++attempts > 300) { // ~30s
                    EventQueue.invokeLater { if (!disposed) callbacks.onLoadError("Embedded Chromium did not start") }
                    readyTimer?.cancel()
                    cancel()
                }
            }
        }, 100, 100)
        readyTimer = timer
    }

    private fun markReady() {
        val queued: Runnable?
        synchronized(this) {
            if (ready) return
            ready = true
            readyTimer?.cancel()
            queued = pendingLoad
            pendingLoad = null
        }
        queued?.let { EventQueue.invokeLater(it) }
    }

    /** Writes HTML to a temp file and returns it; the previous document is dropped. */
    fun writeDocument(html: String): File {
        val dir = File(JcefRuntime.dataDir(), "jcef-cache")
        dir.mkdirs()
        val file = File(dir, "html-${UUID.randomUUID()}.html")
        writeDesktopReaderDocument(file, html)
        lastDocument?.takeIf { it != file }?.delete()
        lastDocument = file
        return file
    }

    /** Loads a document URL once the native browser exists; [js] runs on its load-end. */
    fun load(url: String, js: String) {
        val generation = documentGate.begin(url)
        readyDelivered = false
        pendingStyle = null
        synchronized(deferredCommands) { deferredCommands.clear() }
        pendingOperations.clear()
        pendingSeekFraction = null
        pendingSeekTarget = null
        expectedUrl = url
        pendingJs = js
        val action = Runnable { if (!disposed && generation == documentGate.generation) browser.loadURL(url) }
        val runNow: Boolean
        synchronized(this) {
            runNow = ready
            if (!ready) pendingLoad = action
        }
        if (runNow) EventQueue.invokeLater(action)
    }

    /** Runs JS on the current document immediately (window grow/trim mutations). */
    fun execute(js: String) {
        val generation = documentGate.generation
        EventQueue.invokeLater {
            if (disposed || generation != documentGate.generation || documentGate.url == null) return@invokeLater
            if (!documentGate.ready) {
                synchronized(deferredCommands) { deferredCommands.add(generation to js) }
                return@invokeLater
            }
            if (!documentGate.matches(generation, browser.url)) return@invokeLater
            val guarded = "if(window.__folioGeneration===$generation){\n$js\n}"
            browser.executeJavaScript(guarded, expectedUrl, 0)
        }
    }

    fun invalidateDocument() {
        val outgoing = documentGate.generation
        EventQueue.invokeLater {
            if (!disposed) browser.executeJavaScript(
                "if(window.__folioGeneration===$outgoing){window.__folioInputEnabled=false;if(window.__folioSetActive)window.__folioSetActive(false);}",
                browser.url, 0)
        }
        documentGate.invalidate()
    }
    private fun deliverReady() {
        if (documentGate.ready && documentGate.active && !readyDelivered) {
            readyDelivered = true
            callbacks.onContentReady()
            focus()
        }
    }

    fun setActive(active: Boolean) {
        documentGate.active = active
        execute("window.__folioInputEnabled=$active;if(window.__folioSetActive)window.__folioSetActive($active);")
        if (active) deliverReady()
    }

    fun focus() {
        EventQueue.invokeLater {
            val owner = javax.swing.SwingUtilities.getWindowAncestor(component)
            if (!disposed && documentGate.active && owner != null && owner.isActive) {
                component.requestFocusInWindow()
                browser.setFocus(true)
            }
        }
    }

    fun turn(direction: Int) {
        if (!documentGate.ready || !documentGate.active || direction !in listOf(-1, 1)) return
        execute("window.__folioTurn&&window.__folioTurn($direction);")
        focus()
    }

    fun applyWindowOperation(nonce: Long, js: String, onApplied: () -> Unit) {
        pendingOperations[nonce] = onApplied
        execute("if(document.getElementById('folio-continuous-root')){$js;document.title='folio-op:$nonce:ok';}")
    }

    /** Swaps the reader stylesheet in place (theme/typography) without navigating. */
    fun applyStyle(fontsCss: String, styleCss: String, shadowCss: String) {
        val fontsLit = fontsCss.toJsStringLiteral()
        val styleLit = styleCss.toJsStringLiteral()
        // __folioRestyle (both engines) anchors the viewport to its paragraph/block
        // across the swap — without it the pixel offset survives a reflow and the
        // reader's eye lands on different words ("changing fonts jumps page position").
        val js = "(function(){if(window.__folioRestyle){window.__folioRestyle($fontsLit,$styleLit,${shadowCss.toJsStringLiteral()});}" +
                "else{var f=document.getElementById('folio-fonts');if(f)f.textContent=$fontsLit;" +
                "var s=document.getElementById('folio-reader-style');if(s)s.textContent=$styleLit;" +
                "if(window.__folioRelayout)window.__folioRelayout();}})();"
        pendingStyle = js
        execute(js)
    }

    /** Renders (or clears) the glass overlay panel inside the page. */
    fun pushOverlay(html: String) {
        val kind = overlayKind(html)
        if (kind != lastOverlayKind) {
            lastOverlayKind = kind
            lastOverlayScrollVal = 0
            overlayCentered = false
        }
        lastOverlay = html.ifEmpty { null }
        // Centering is a one-shot on open: re-pushing the same panel (picking a
        // chapter keeps it open) must leave the list exactly where the user left it.
        val centerActive = kind.isNotEmpty() && !overlayCentered
        overlayCentered = overlayCentered || centerActive
        val js = overlayJs(html, lastOverlayScrollVal, centerActive)
        execute(js)
    }

    private fun overlayKind(html: String): String =
        Regex("data-kind=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""

    private fun overlayJs(html: String, initScroll: Int, centerActive: Boolean): String =
        "(function(){var h=${html.toJsStringLiteral()};var old=document.getElementById('folio-overlay-root');var kind='';" +
                "var m=h.match(/data-kind=\"([^\"]+)/);if(m)kind=m[1];" +
                "var keep=old&&kind&&old.getAttribute('data-kind')===kind;" +
                "var list=keep?old.querySelector('[data-scroll]'):null;var sc=list?list.scrollTop:$initScroll;" +
                "if(!keep){if(old&&old.parentNode)old.parentNode.removeChild(old);" +
                "var d=document.createElement('div');d.id='folio-overlay-root';document.documentElement.appendChild(d);d.innerHTML=h;" +
                "} else {old.innerHTML=h;}" +
                "var root=document.getElementById('folio-overlay-root');" +
                "var r2=root.querySelector('[data-kind]');if(r2)r2.setAttribute('data-kind',kind);" +
                "if(kind)root.setAttribute('data-kind',kind);" +
                "var list2=root.querySelector('[data-scroll]');if(list2){" +
                " if(sc>0){list2.scrollTop=sc;}" +
                " else if($centerActive){var act=root.querySelector('.ovl-active');if(act){" +
                "  var lr=list2.getBoundingClientRect(),ar=act.getBoundingClientRect();" +
                "  list2.scrollTop=Math.max(0,list2.scrollTop+(ar.top-lr.top)-(list2.clientHeight/2-ar.height/2));" +
                "  document.title='folio-ovlscroll:'+Math.round(list2.scrollTop)+':'+Math.random().toString(36).slice(2);}}}" +
                "if(list2)list2.addEventListener('scroll',function(){clearTimeout(window.__folioOvlSdT);window.__folioOvlSdT=setTimeout(function(){document.title='folio-ovlscroll:'+Math.round(list2.scrollTop)+':'+Math.random().toString(36).slice(2);},80);},{passive:true});" +
                "var n=0;" +
                "root.querySelectorAll('[data-act]').forEach(function(el){" +
                "  if(el.tagName==='INPUT'||el.tagName==='SELECT'){" +
                "    el.addEventListener('change',function(e){e.stopPropagation();document.title='folio-ovl:'+el.getAttribute('data-act')+':'+encodeURIComponent(el.value)+':'+(++n);});" +
                "  } else {" +
                "    el.addEventListener('click',function(e){e.stopPropagation();var a=el.getAttribute('data-act');" +
                "      if(a&&a.indexOf('savenote')===0){var ta=root.querySelector('[data-note-input]');a=a+':'+encodeURIComponent(ta?ta.value:'');}" +
                "      document.title='folio-ovl:'+a+':'+(++n);});" +
                "  }" +
                "});})();"

    /** Re-paints the chapter's highlights; a load in flight defers to onLoadEnd. */
    fun applyHighlights(js: String) {
        lastHighlightJs = js
        if (pendingJs != null || !documentGate.ready) return
        execute(js)
    }

    /** Drops the page selection so the chrome's Highlight button dims again. */
    fun clearSelection() {
        val js = "window.__folioClearSel&&window.__folioClearSel();"
        execute(js)
    }

    /** Jumps to an absolute 0..1 fraction of the chapter via the page hook. */
    fun seek(fraction: Float) {
        System.err.println("[FolioPage] seek fraction=$fraction")
        pendingSeekFraction = fraction.coerceIn(0f, 1f)
        flushPendingSeekFraction()
    }

    private fun flushPendingSeekFraction() {
        val target = pendingSeekFraction ?: return
        if (!ready || pendingJs != null || !documentGate.ready) return
        val url = expectedUrl ?: return
        pendingSeekFraction = null
        val js = "window.__folioSeek&&window.__folioSeek($target);"
        execute(js)
    }

    /** Scrolls to a target ("h:<id>" highlight mark or "p:<index>" paragraph). */
    fun seekTo(target: String) {
        System.err.println("[FolioPage] seek target=$target")
        pendingSeekTarget = target
        flushPendingSeek()
    }

    /**
     * Applies a queued jump once the document it targets is on screen. Running it
     * while a load is still in flight would move the *previous* chapter (and can
     * falsely report end-of-chapter there).
     */
    private fun flushPendingSeek() {
        val target = pendingSeekTarget ?: return
        if (!ready || pendingJs != null || !documentGate.ready) return
        val url = expectedUrl ?: return
        pendingSeekTarget = null
        val js = "window.__folioSeekTo&&window.__folioSeekTo(${target.toJsStringLiteral()});"
        execute(js)
    }

    private fun sameUrl(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        documentGate.dispose()
        readyTimer?.cancel()
        pendingLoad = null
        pendingJs = null
        synchronized(deferredCommands) { deferredCommands.clear() }
        pendingOperations.clear()
        val c = client
        val b = browser
        EventQueue.invokeLater {
            runCatching { b.executeJavaScript("window.__folioDispose&&window.__folioDispose();", b.url, 0) }
            runCatching { b.close(true) }
            runCatching { c.dispose() }
        }
        lastDocument?.delete()
    }
}

/** Selection tools use millisecond timestamps, which do not fit in a 32-bit Int. */
internal fun desktopReaderOverlayAction(message: String): String {
    val body = message.removePrefix("folio-ovl:")
    return if (body.substringAfterLast(':', "").toLongOrNull() != null) body.substringBeforeLast(':') else body
}

internal object JcefRuntime {
    @Volatile private var instance: CefApp? = null

    fun dataDir(): File = File(
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
        if (System.getenv("LOCALAPPDATA").isNullOrBlank()) ".folio" else "Folio"
    )

    fun app(): CefApp = instance ?: synchronized(this) {
        instance ?: CefAppBuilder().apply {
            setInstallDir(File(dataDir(), "jcef"))
            // Windowed rendering: OSR would use a JOGL GLCanvas (also heavyweight,
            // plus an OpenGL dependency) without solving the overlay problem; the
            // reader chrome reserves its own space instead (see ReaderScreen).
            getCefSettings().windowless_rendering_enabled = false
            // Opt-in local diagnostics for native rendering/idle profiling.
            getCefSettings().remote_debugging_port = Integer.getInteger("folio.reader.debugPort", 0)
                .takeIf { it in 1024..65535 } ?: 0
            // Chapter pages are file:// documents that must reach other file://
            // resources (images, cached CSS, bundled fonts). Scripts are stripped
            // by ChapterSanitizer before load, so web security stays on.
            // Let Chromium select a supported accelerated renderer and apply its
            // driver fallback. Forcing --disable-gpu made every scroll software-rendered.
            addJcefArgs("--allow-file-access-from-files")
        }.build().also { instance = it }
    }
}

/** Rewrites EPUB resource references to extracted cache files as file:// URLs. */
private suspend fun resolveResources(
    html: String,
    chapterHref: String,
    resolve: suspend (chapterHref: String, src: String) -> String?
): String {
    val attributeSources = Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(html).map { it.groupValues[1] }
    val cssSources = Regex("""url\(\s*["']?([^)"']+)["']?\s*\)""", RegexOption.IGNORE_CASE)
        .findAll(html).map { it.groupValues[1] }
    val sources = (attributeSources + cssSources)
        .filter {
            !it.startsWith("#") && !it.startsWith("http") && !it.startsWith("data:") && !it.startsWith("file:")
        }
        .filterNot {
            // Keep chapter/TOC links relative: the click bridge resolves them to
            // spine targets. Rewriting them to extracted file:// paths makes the
            // links dead.
            Regex("""\.x?html?(#.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        .distinct()
    var result = html
    for (src in sources) {
        val path = resolve(chapterHref, src) ?: continue
        result = result.replace(src, File(path).toFileUrl())
    }
    return result
}

private fun File.toFileUrl(): String = toURI().toString().let {
    if (it.startsWith("file:/") && !it.startsWith("file:///")) it.replaceFirst("file:/", "file:///") else it
}

/** Embeds a string as a JS string literal (double-quoted, escaped). */
private fun String.toJsStringLiteral(): String {
    val sb = StringBuilder(this.length + 16)
    sb.append('"')
    for (ch in this) when (ch) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> {}
        else -> sb.append(ch)
    }
    sb.append('"')
    return sb.toString()
}

private fun fontCandidates(fileName: String): List<File> {
    val home = File(System.getProperty("user.home"), ".folio/fonts/$fileName")
    val local = System.getenv("LOCALAPPDATA")?.let { File(it, "Folio/fonts/$fileName") }
    return listOfNotNull(home, local)
}

/** CSS font stack for a requested family, preferring real installed/bundled faces. */
private fun fontStackFor(requested: String): String {
    when (requested.lowercase()) {
        "literata" -> return "Literata, Georgia, 'Times New Roman', serif"
        "merriweather" -> return "Merriweather, Georgia, serif"
        "eb garamond", "garamond" -> return "'EB Garamond', Georgia, serif"
        "lora" -> return "Lora, Georgia, serif"
        "calluna", "shancalluna" -> return "Calluna, 'Shancalluna', Georgia, serif"
        "comfortaa" -> return "Comfortaa, 'Segoe UI', sans-serif"
        "georgia" -> return "Georgia, 'Times New Roman', serif"
        "inter" -> return "Inter, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
        "open sans" -> return "'Open Sans', 'Segoe UI', sans-serif"
        "noto serif" -> return "'Noto Serif', Georgia, serif"
        "serif" -> return "Georgia, 'Times New Roman', serif"
        "sans serif" -> return "'Segoe UI', Arial, sans-serif"
        "monospace" -> return "'Cascadia Mono', Consolas, monospace"
    }
    val lower = requested.lowercase()
    return when {
        lower.contains("mono") || lower.contains("courier") || lower.contains("consolas") ->
            "'$requested', Consolas, monospace"

        lower.contains("sans") || lower.contains("segoe") || lower.contains("arial") ||
                lower.contains("helvetica") || lower.contains("roboto") || lower.contains("verdana") ->
            "'$requested', 'Segoe UI', Arial, sans-serif"

        else -> "'$requested', Georgia, serif"
    }
}

/**
 * @font-face rules for fonts the user imported, pointed at their extracted file.
 * The reader stylesheet itself is shared — see [ReaderCss].
 */
private fun fontFaceCss(settings: ReaderSettings): String =
    settings.customFonts.mapNotNull { font ->
        val file = fontCandidates(font.fileName).firstOrNull { it.exists() } ?: return@mapNotNull null
        val format = if (font.fileName.endsWith(".otf", true)) "opentype" else "truetype"
        // Variable files declare a weight RANGE so real weights resolve from the axis.
        // Prefer the model's own flag (set for bundled faces and computed for imports),
        // keeping the file-name sniff as a fallback for legacy entries.
        val weight = if (font.isVariable || font.fileName.contains("variable")) "300 900" else "${font.weight}"
        "@font-face{font-family:'${font.familyName}';src:url('${file.toFileUrl()}') format('$format');" +
                "font-weight:$weight;font-style:normal;font-display:swap;}"
    }.joinToString("")

/**
 * The desktop sheet is the shared reader stylesheet; only the font stack (real
 * installed and bundled faces) and the continuous-mode reveal belong here. The
 * bridge sets `body.style.opacity` once the document is painted, and that inline
 * style outranks the `opacity:0` rule below, so an in-place style swap mid-read
 * never blanks the page.
 */
private fun readerStyleCss(settings: ReaderSettings): String = ReaderCss.styleSheet(
    settings,
    PageEngine.colsFor(settings.layoutMode),
    fontStack = ::fontStackFor,
    continuousCss = "body{opacity:0;transition:opacity .15s ease;}"
) + run {
    val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
    fun rgb(color: Int) = color.toUInt().toString(16).padStart(8, '0').takeLast(6)
    // Publisher sheets can leave body transparent. Context actions need their
    // own opaque paper so the prose underneath cannot show through the buttons.
    "#folio-selection-tools{background:#${rgb(theme.background)}!important;" +
        "color:#${rgb(theme.primaryText)}!important;border:1px solid #${rgb(theme.secondaryText)}33;}"
}

internal fun writeDesktopReaderDocument(file: File, html: String) {
    // Publisher charset declarations describe the original EPUB bytes, not this
    // decoded String. A UTF-8 BOM wins Chromium's file:// encoding sniff even if
    // a legacy declaration survives or the publisher head exceeds the sniff limit.
    file.writeText("\uFEFF" + html.removePrefix("\uFEFF"), Charsets.UTF_8)
}

internal fun injectReaderCss(html: String, settings: ReaderSettings): String {
    val safeHtml = com.folio.reader.epub.ChapterSanitizer.sanitize(html)
    val fontFaces = fontFaceCss(settings)
    val charset = "<meta charset=\"utf-8\"/>"
    val css = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            (if (fontFaces.isNotEmpty()) "<style id=\"folio-fonts\">$fontFaces</style>" else "") +
            "<style id=\"folio-reader-style\">" + readerStyleCss(settings) + "</style>"
    return if (safeHtml.contains("</head>", true)) {
        val head = Regex("(?i)<head\\b[^>]*>").find(safeHtml)
        val declared = if (head != null) safeHtml.replaceRange(head.range.last + 1, head.range.last + 1, charset)
            else "$charset$safeHtml"
        // Keep reader overrides after publisher styles; only encoding belongs first.
        declared.replaceFirst(Regex("(?i)</head>"), "$css</head>")
    } else "$charset$css$safeHtml"
}

/**
 * Page-side bridge: restores the saved scroll position, reports progress/page
 * counts (only when they change), forwards link clicks and center taps back to
 * the app via document.title.
 */
private fun readerBridgeJs(fraction: Float): String = """
(function(){
  if(window.__folioBridgeInstalled)return;
  window.__folioBridgeInstalled=true;
  var nonce=0;
  var scroller=document.scrollingElement||document.documentElement;
  scroller.scrollTop=Math.max(0,scroller.scrollHeight-scroller.clientHeight)*$fraction;
  // Reveal only after web-fonts resolve and a layout frame, not immediately: the
  // reader sheet gates the body at opacity:0, and showing the raw markup before the
  // real face loads makes it paint in a fallback font and then reflow when the face
  // swaps in (font-display:swap) — the DOCX "text hard-cuts in and reflows" flash.
  // The html paper (ReaderCss sets the paper colour on html as well as body) shows
  // through the hidden body, so there is no white flash meanwhile. The timeout is a
  // backstop so a blocked or absent font can never leave the page hidden. The inline
  // opacity set here outranks the stylesheet's opacity:0, so a later in-place
  // restyle cannot re-blank the page.
  var __folioShown=false;
  function __folioReveal(){if(__folioShown)return;__folioShown=true;if(document.body)document.body.style.opacity='1';schedule();}
  if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){requestAnimationFrame(__folioReveal);});}
  else{requestAnimationFrame(__folioReveal);}
  setTimeout(__folioReveal,400);
  window.__folioSeek=function(f){var s=document.scrollingElement||document.documentElement;var range=Math.max(0,s.scrollHeight-s.clientHeight);s.scrollTop=range*Math.min(1,Math.max(0,f||0));restorePending=false;schedule();};
  window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};
  window.__folioSeekTo=function(t){var parts=String(t).split(':'),el=null;
    if(parts[0]==='h'&&parts[1]){el=document.querySelector('[data-folio-hl="'+parts[1]+'"]');}
    if(!el){var pi=parts[0]==='h'?parts[2]:parts[1];var ps=document.querySelectorAll('p');if(!ps.length)return;var n=parseInt(pi,10);if(isNaN(n))n=0;el=ps[Math.min(Math.max(0,n),ps.length-1)];}
    if(!el)return;el.scrollIntoView({block:'start'});restorePending=false;schedule();};
  ${PageEngine.selectionWatchJs}
  ${PageEngine.emptyHideJs}
  var scheduled=false,last=0,lastSig='',maxTotal=1;
  var restorePending=$fraction>0.001;
  function measure(){
    scheduled=false;
    var now=Date.now();
    if(now-last<100){schedule();return;}
    last=now;
    var d=document.documentElement,b=document.body||d;
    var s=document.scrollingElement||d;
    var vh=Math.max(1,s.clientHeight||window.innerHeight||1);
    var docH=Math.max(s.scrollHeight,d.scrollHeight,b.scrollHeight||0);
    var range=Math.max(0,docH-vh);
    if(restorePending&&range>4){
      s.scrollTop=range*$fraction;
      if((s.scrollTop||0)>0)restorePending=false;
    }
    var offset=s.scrollTop||window.scrollY;
    var total=Math.max(1,Math.ceil(docH/vh));
    // A chapter's content only grows as fonts and images settle, so a smaller total
    // is always a mid-fling measurement artifact — never let it collapse the counter.
    if(total>maxTotal)maxTotal=total;else total=maxTotal;
    var current=Math.min(total,Math.floor(offset/vh)+1);
    var p=range>0?Math.min(1,offset/range):0;
    var crossed=false;
    var sig=p.toFixed(3)+':'+current+':'+total;
    if(sig!==lastSig){lastSig=sig;document.title='folio-progress:'+p.toFixed(4)+':'+current+':'+total+':'+crossed;}
  }
  function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(measure);}}
  var lastEdgeHop=0;
  function edge(which){
    var now=Date.now();
    if(now-lastEdgeHop<600)return;
    lastEdgeHop=now;
    document.title='folio-edge:'+which+':'+(++nonce);
  }
  function atTop(){var s=document.scrollingElement||document.documentElement;return (s.scrollTop||0)<=1;}
  function atBottom(){var s=document.scrollingElement||document.documentElement;return s.scrollTop+s.clientHeight>=s.scrollHeight-2;}
  window.__folioTurn=function(direction){
    if(window.__folioInputEnabled===false)return false;
    restorePending=false;
    if(direction<0&&atTop()){edge('start');return true;}
    if(direction>0&&atBottom()){edge('end');return true;}
    scroller.scrollBy({top:scroller.clientHeight*.9*(direction>0?1:-1),behavior:window.__folioReduceMotion?'auto':'smooth'});return true;
  };
  window.addEventListener('wheel',function(e){if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;var d=e.deltaY||0;if(d<0&&atTop())edge('start');if(d>0&&atBottom())edge('end');restorePending=false;},{passive:true});
  window.addEventListener('scroll',function(){schedule();clearTimeout(window.__folioSettleT);window.__folioSettleT=setTimeout(schedule,180);},{passive:true});
  window.addEventListener('resize',schedule);
  ${DesktopReaderInput.js()}
  if(window.ResizeObserver){new ResizeObserver(schedule).observe(document.documentElement);if(document.body)new ResizeObserver(schedule).observe(document.body);}
  if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){schedule();setTimeout(schedule,150);});}
  document.querySelectorAll('img').forEach(function(i){i.addEventListener('load',schedule);i.addEventListener('error',schedule);});
  schedule();setTimeout(schedule,250);setTimeout(schedule,700);setTimeout(schedule,1500);
})();
"""
