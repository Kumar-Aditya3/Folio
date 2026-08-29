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
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Panel
import java.io.File
import java.net.URLDecoder
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    html: String,
    chapterHref: String,
    settings: ReaderSettings,
    position: ReadingPosition?,
    highlights: List<Highlight>,
    enabled: Boolean,
    modifier: Modifier,
    onProgress: (Float) -> Unit,
    onPageChange: (Int, Int) -> Unit,
    onChapterEnd: () -> Unit,
    onChapterStart: () -> Unit,
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveResource: suspend (chapterHref: String, src: String) -> String?,
    onHighlightParagraph: ((paragraphIndex: Int, selectedText: String) -> Unit)?,
    onSelectionChanged: ((paragraphIndex: Int, selectedText: String?) -> Unit)?,
    clearSelectionRequest: Long?,
    seekRequest: Pair<Float, Long>?,
    seekTargetRequest: Pair<String, Long>?
) {
    val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
    val backgroundColor = Color(theme.background)
    val progressColor = Color(theme.progress)

    // Latest-callback holder: effects stay keyed on content only, so progress
    // updates (which recompose the reader constantly) never restart them.
    val callbacks = remember { SurfaceCallbacks() }
    callbacks.onProgress = onProgress
    callbacks.onPageChange = onPageChange
    callbacks.onChapterEnd = onChapterEnd
    callbacks.onChapterStart = onChapterStart
    callbacks.onTap = onTap
    callbacks.onLinkClick = onLinkClick
    callbacks.onHighlightParagraph = onHighlightParagraph
    callbacks.onSelectionChanged = onSelectionChanged

    val resolver by rememberUpdatedState(onResolveResource)
    val positionState by rememberUpdatedState(position)
    val settingsState by rememberUpdatedState(settings)

    val overlayHtml = LocalOverlayHtml.current
    callbacks.onOverlayAction = LocalOverlayAction.current

    var session by remember { mutableStateOf<JcefSession?>(null) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var resolvedHtml by remember(html, chapterHref) { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var appliedSettings by remember { mutableStateOf<ReaderSettings?>(null) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    // Chapter whose document has been handed to the browser; null again on change.
    var loadedChapter by remember(chapterHref) { mutableStateOf<String?>(null) }

    // Only geometry changes (page columns, measure cap, gutter) need a document
    // reload; theme/typography changes swap the stylesheet in place, so switching
    // a theme no longer flashes through the other layouts.
    fun structuralSig(s: ReaderSettings) = Triple(
        s.layoutMode, s.textWidth, s.margins.left.toInt()
    )

    // 1) Start Chromium: install (if needed) on a worker thread, then create the
    //    client/browser on the AWT EDT where Swing components must be built.
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { JcefSession.create(callbacks) }
        }
        result
            .onSuccess { session = it }
            .onFailure { fatalError = it.message ?: "Unable to start embedded Chromium" }
    }

    DisposableEffect(Unit) {
        onDispose { session?.dispose() }
    }

    // 2) Resolve EPUB resources (images, stylesheets, fonts) to cache files.
    LaunchedEffect(html, chapterHref, session) {
        val current = session ?: return@LaunchedEffect
        resolvedHtml = null
        if (!hasLoadedOnce) preparing = true
        val result = withContext(Dispatchers.IO) {
            runCatching { resolveResources(html, chapterHref) { href, src -> resolver(href, src) } }
        }
        result
            .onSuccess { resolvedHtml = it }
            .onFailure { fatalError = it.message ?: "Unable to prepare chapter" }
    }

    // 3) Write and load the styled document — on content changes and on geometry
    //    changes only (layout mode / text width / gutter).
    LaunchedEffect(resolvedHtml, session, reloadTick) {
        val current = session ?: return@LaunchedEffect
        val source = resolvedHtml ?: return@LaunchedEffect
        if (!hasLoadedOnce) preparing = true
        val s = settingsState
        val fraction = (positionState?.scrollOffset ?: 0.0).toFloat().coerceIn(0f, 1f)
        val pagedCols = when (s.layoutMode) {
            com.folio.reader.settings.LayoutMode.PAGINATED -> 1
            com.folio.reader.settings.LayoutMode.TWO_COLUMN -> 2
            else -> 0
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val styled = injectReaderCss(source, s)
                current.writeDocument(styled).toFileUrl()
            }
        }
        result.onSuccess { url ->
            val js = if (pagedCols > 0) PageEngine.js(fraction, pagedCols, s.margins.left, PageEngine.measurePx(s.textWidth)) else readerBridgeJs(fraction)
            current.load(url, js)
            loadedChapter = chapterHref
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
    LaunchedEffect(settings, session) {
        val current = session ?: return@LaunchedEffect
        val applied = appliedSettings ?: return@LaunchedEffect
        if (applied == settings) return@LaunchedEffect
        if (structuralSig(settings) != structuralSig(applied)) {
            reloadTick++
        } else {
            current.applyStyle(fontFaceCss(settings), readerStyleCss(settings))
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
    LaunchedEffect(seekRequest, session) {
        val current = session ?: return@LaunchedEffect
        val req = seekRequest ?: return@LaunchedEffect
        current.seek(req.first)
    }

    // 7) Paint the chapter's highlights. Waits for this chapter's document so the
    //    marks are never drawn into the previous chapter, and re-runs when the
    //    selection set or the theme's palette changes. Declared before the jump
    //    effect below: effects run in declaration order, and a jump has to be able
    //    to find the mark the painter creates.
    LaunchedEffect(highlights, settings.themeId, settings.customTheme, session, loadedChapter) {
        val current = session ?: return@LaunchedEffect
        if (loadedChapter != chapterHref) return@LaunchedEffect
        current.applyHighlights(HighlightPaint.js(highlights, theme))
    }

    // 8) Annotation jumps: scroll to a highlight mark or paragraph. Waits for this
    //    chapter's document to be handed over, so it never moves an older document
    //    that happens to still be on screen.
    var appliedSeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(seekTargetRequest, session, loadedChapter) {
        val current = session ?: return@LaunchedEffect
        val req = seekTargetRequest ?: return@LaunchedEffect
        if (loadedChapter != chapterHref) return@LaunchedEffect
        if (appliedSeek == req.second) return@LaunchedEffect
        appliedSeek = req.second
        current.seekTo(req.first)
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
            Text("Embedded Chromium could not be started: $error", color = Color(theme.primaryText))
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
    @Volatile var onChapterEnd: () -> Unit = {}
    @Volatile var onChapterStart: () -> Unit = {}
    @Volatile var onTap: () -> Unit = {}
    @Volatile var onLinkClick: ((String) -> Unit)? = null
    @Volatile var onOverlayAction: ((String) -> Unit)? = null
    @Volatile var onHighlightParagraph: ((Int, String) -> Unit)? = null
    @Volatile var onSelectionChanged: ((Int, String?) -> Unit)? = null
    @Volatile var onLoadError: (String) -> Unit = {}
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
    val component: Panel,
    private val callbacks: SurfaceCallbacks
) {
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
    private var readyTimer: Timer? = null

    companion object {
        fun create(callbacks: SurfaceCallbacks): JcefSession {
            val app = JcefRuntime.app()
            lateinit var client: CefClient
            lateinit var browser: CefBrowser
            val task = Runnable {
                client = app.createClient()
                browser = client.createBrowser("about:blank", false, false)
            }
            if (EventQueue.isDispatchThread()) task.run() else EventQueue.invokeAndWait(task)
            val component = Panel(BorderLayout()).apply {
                add(browser.uiComponent, BorderLayout.CENTER)
            }
            return JcefSession(client, browser, component, callbacks).also {
                it.installHandlers()
                it.startReadyFallback()
            }
        }
    }

    private fun installHandlers() {
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onTitleChange(browser: CefBrowser, title: String?) {
                val t = title ?: return
                when {
                    t.startsWith("folio-progress:") -> {
                        val parts = t.split(':')
                        if (parts.size != 5) return
                        val fraction = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: return
                        callbacks.onProgress(fraction)
                        callbacks.onPageChange(
                            parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                            parts[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
                        )
                    }

                    t.startsWith("folio-link:") -> {
                        val encoded = t.removePrefix("folio-link:").substringAfter(':', "")
                        val href = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return
                        callbacks.onLinkClick?.invoke(href)
                    }

                    t.startsWith("folio-tap:") -> callbacks.onTap()

                    t.startsWith("folio-edge:end:") -> callbacks.onChapterEnd()

                    t.startsWith("folio-edge:start:") -> callbacks.onChapterStart()

                    t.startsWith("folio-selclear:") -> callbacks.onSelectionChanged?.invoke(0, null)

                    t.startsWith("folio-sel:") -> {
                        val rest = t.removePrefix("folio-sel:")
                        val idx = rest.substringBefore(':').toIntOrNull() ?: 0
                        val encoded = rest.substringAfter(':').substringBeforeLast(':')
                        val text = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                        if (text.isNullOrBlank()) callbacks.onSelectionChanged?.invoke(idx, null)
                        else callbacks.onSelectionChanged?.invoke(idx, text)
                    }

                    t.startsWith("folio-ovl:") -> {
                        val rest = t.removePrefix("folio-ovl:")
                        val last = rest.substringAfterLast(':', "")
                        val body = if (last.toIntOrNull() != null) rest.substringBeforeLast(':') else rest
                        callbacks.onOverlayAction?.invoke(body)
                    }

                    t.startsWith("folio-ovlscroll:") -> {
                        val v = t.removePrefix("folio-ovlscroll:").substringBefore(':').toIntOrNull()
                        if (v != null) lastOverlayScrollVal = v
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
                if (!frame.isMain) return
                markReady()
                val expected = expectedUrl
                val js = pendingJs
                if (expected != null && js != null && sameUrl(frame.getURL(), expected)) {
                    pendingJs = null
                    browser.executeJavaScript(js, expected, 0)
                    val overlay = lastOverlay
                    if (overlay != null) browser.executeJavaScript(overlayJs(overlay, lastOverlayScrollVal, false), expected, 0)
                    lastHighlightJs?.let { browser.executeJavaScript(it, expected, 0) }
                    flushPendingSeek()
                    flushPendingSeekFraction()
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
                callbacks.onLoadError(message)
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
        file.writeText(html)
        lastDocument?.takeIf { it != file }?.delete()
        lastDocument = file
        return file
    }

    /** Loads a document URL once the native browser exists; [js] runs on its load-end. */
    fun load(url: String, js: String) {
        expectedUrl = url
        pendingJs = js
        val action = Runnable { if (!disposed) browser.loadURL(url) }
        val runNow: Boolean
        synchronized(this) {
            runNow = ready
            if (!ready) pendingLoad = action
        }
        if (runNow) EventQueue.invokeLater(action)
    }

    /** Swaps the reader stylesheet in place (theme/typography) without navigating. */
    fun applyStyle(fontsCss: String, styleCss: String) {
        val js = "(function(){var f=document.getElementById('folio-fonts');if(f){f.textContent=${fontsCss.toJsStringLiteral()};}" +
                "var s=document.getElementById('folio-reader-style');if(s){s.textContent=${styleCss.toJsStringLiteral()};}" +
                "if(window.__folioRelayout)window.__folioRelayout();})();"
        EventQueue.invokeLater { if (!disposed) browser.executeJavaScript(js, browser.url ?: "about:blank", 0) }
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
        EventQueue.invokeLater { if (!disposed) browser.executeJavaScript(js, browser.url ?: "about:blank", 0) }
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
        if (pendingJs != null) return
        EventQueue.invokeLater { if (!disposed) browser.executeJavaScript(js, browser.url ?: "about:blank", 0) }
    }

    /** Drops the page selection so the chrome's Highlight button dims again. */
    fun clearSelection() {
        val js = "window.__folioClearSel&&window.__folioClearSel();"
        EventQueue.invokeLater { if (!disposed) browser.executeJavaScript(js, browser.url ?: "about:blank", 0) }
    }

    /** Jumps to an absolute 0..1 fraction of the chapter via the page hook. */
    fun seek(fraction: Float) {
        pendingSeekFraction = fraction.coerceIn(0f, 1f)
        flushPendingSeekFraction()
    }

    private fun flushPendingSeekFraction() {
        val target = pendingSeekFraction ?: return
        if (!ready || pendingJs != null) return
        val url = expectedUrl ?: return
        pendingSeekFraction = null
        val js = "window.__folioSeek&&window.__folioSeek($target);"
        EventQueue.invokeLater { if (!disposed) browser.executeJavaScript(js, url, 0) }
    }

    /** Scrolls to a target ("h:<id>" highlight mark or "p:<index>" paragraph). */
    fun seekTo(target: String) {
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
        if (!ready || pendingJs != null) return
        val url = expectedUrl ?: return
        pendingSeekTarget = null
        val js = "window.__folioSeekTo&&window.__folioSeekTo(${target.toJsStringLiteral()});"
        EventQueue.invokeLater { if (!disposed) browser.executeJavaScript(js, url, 0) }
    }

    private fun sameUrl(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
    }

    fun dispose() {
        disposed = true
        readyTimer?.cancel()
        val c = client
        val b = browser
        EventQueue.invokeLater {
            runCatching { b.close(true) }
            runCatching { c.dispose() }
        }
        lastDocument?.delete()
    }
}

private object JcefRuntime {
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
            // Chapter pages are file:// documents that must reach other file://
            // resources (images, cached CSS, bundled fonts).
            addJcefArgs("--disable-gpu", "--allow-file-access-from-files", "--disable-web-security", "--allow-file-access")
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
 * Injects the reader stylesheet. Mirrors the Android surface's rules and adds the
 * pieces a desktop window needs: reading-mode layout (continuous / paginated /
 * two-column), a text-width cap, formatting-mode override strength, word spacing
 * and hyphenation.
 */
private fun fontFaceCss(settings: ReaderSettings): String =
    settings.customFonts.mapNotNull { font ->
        val file = fontCandidates(font.fileName).firstOrNull { it.exists() } ?: return@mapNotNull null
        val format = if (font.fileName.endsWith(".otf", true)) "opentype" else "truetype"
        "@font-face{font-family:'${font.familyName}';src:url('${file.toFileUrl()}') format('$format');" +
                "font-weight:${font.weight};font-style:normal;font-display:swap;}"
    }.joinToString("")

private fun readerStyleCss(settings: ReaderSettings): String {
    val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)

    fun Int.rgb(): String = toUInt().toString(16).padStart(8, '0').drop(2)

    val align = when (settings.alignment) {
        com.folio.reader.settings.TextAlignment.CENTER -> "center"
        com.folio.reader.settings.TextAlignment.JUSTIFIED -> "justify"
        else -> "left"
    }
    val paginated = settings.layoutMode == com.folio.reader.settings.LayoutMode.PAGINATED
    val pagedCols = when (settings.layoutMode) {
        com.folio.reader.settings.LayoutMode.PAGINATED -> 1
        com.folio.reader.settings.LayoutMode.TWO_COLUMN -> 2
        else -> 0
    }

    // Paged modes use the shared book engine: discrete viewport pages (or a
    // two-page spread) turned with a leaf flip, never free scrolling.
    val layoutCss = if (pagedCols > 0) {
        PageEngine.css(pagedCols, settings.margins.top, settings.margins.bottom, "#${theme.background.rgb()}")
    } else "body{opacity:0;transition:opacity .15s ease;}"

    // Line-length cap for scrolling modes, mirroring the phone reader's readerWidth.
    val widthCss = if (pagedCols == 0) {
        when (settings.textWidth) {
            com.folio.reader.settings.TextWidth.NARROW -> "body{max-width:560px;margin-left:auto;margin-right:auto;}"
            com.folio.reader.settings.TextWidth.MEDIUM -> "body{max-width:720px;margin-left:auto;margin-right:auto;}"
            com.folio.reader.settings.TextWidth.WIDE -> "body{max-width:960px;margin-left:auto;margin-right:auto;}"
            com.folio.reader.settings.TextWidth.CUSTOM -> "body{max-width:1200px;margin-left:auto;margin-right:auto;}"
            com.folio.reader.settings.TextWidth.FULL -> ""
        }
    } else ""

    val original = settings.formattingMode == com.folio.reader.model.FormattingMode.ORIGINAL
    val normalized = settings.formattingMode == com.folio.reader.model.FormattingMode.NORMALIZED

    val themeBgCss = if (original) "" else
        "body,body div,body section,body article,body figure{background-color:transparent !important;}"

    val requested = settings.customFonts.firstOrNull { it.name == settings.fontFamily }?.familyName
        ?: settings.fontFamily
    val family = fontStackFor(requested)

    val typographyCss = if (original) "" else
        "font-family:$family !important;font-size:${settings.fontSize}px !important;" +
                "font-weight:${settings.fontWeight} !important;line-height:${settings.lineHeight} !important;" +
                "letter-spacing:${settings.letterSpacing}px !important;" +
                (if (settings.wordSpacing != 0f) "word-spacing:${settings.wordSpacing}em !important;" else "")
    val alignCss = when {
        original -> ""
        else -> "text-align:$align !important;"
    }
    val colorCss = if (original) "" else "color:#${theme.primaryText.rgb()} !important;"
    val hyphenCss = if (!original && settings.hyphenation) "-webkit-hyphens:auto;hyphens:auto;" else ""
    val normalizedExtra = if (normalized) {
        "body p,body div,body h1,body h2,body h3,body h4,body h5,body h6,body li,body blockquote" +
                "{text-align:$align !important;font-family:$family !important;}"
    } else ""
    val elementForceCss = if (original) "" else
        "body p,body div,body span,body li,body blockquote{color:#${theme.primaryText.rgb()} !important;font-family:$family !important;}" +
                "body h1,body h2,body h3,body h4,body h5,body h6{font-family:$family !important;}" +
                "body,body p,body div,body li,body blockquote{text-indent:0 !important;}"

    return "html,body{margin:0;padding:0;background:#${theme.background.rgb()};color:#${theme.primaryText.rgb()};}" +
            themeBgCss +
            // Paged modes need zero horizontal body padding: columns must be
            // exactly 100vw wide or the pager's per-page steps drift out of
            // alignment. Gutters come from the engine's per-block margins.
            "body{padding:${if (pagedCols > 0) "${settings.margins.top}px 0 ${settings.margins.bottom}px 0" else "${settings.margins.top}px ${settings.margins.right}px ${settings.margins.bottom}px ${settings.margins.left}px"} !important;" +
            "$typographyCss$alignCss$colorCss$hyphenCss}" +
            layoutCss +
            widthCss.replace("margin-left:auto;margin-right:auto;", "margin-left:auto !important;margin-right:auto !important;") +
            normalizedExtra +
            elementForceCss +
            "h1,h2,h3,h4,h5,h6{color:#${theme.headingText.rgb()};}" +
            HighlightPaint.css +
            PageDim.css(settings.brightness) +
            "img{max-width:100%;height:auto;break-inside:avoid;}" +
            "a{color:inherit;text-decoration:none;}a[href^=\"http\"],a[href^=\"mailto\"]{color:#${theme.link.rgb()} !important;}"
}

private fun injectReaderCss(html: String, settings: ReaderSettings): String {
    val fontFaces = fontFaceCss(settings)
    val css = "<meta charset=\"utf-8\"/><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            (if (fontFaces.isNotEmpty()) "<style id=\"folio-fonts\">$fontFaces</style>" else "") +
            "<style id=\"folio-reader-style\">" + readerStyleCss(settings) + "</style>"
    return if (html.contains("</head>", true)) html.replaceFirst(Regex("(?i)</head>"), "$css</head>") else "$css$html"
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
  document.body.style.opacity='1';
  window.__folioSeek=function(f){var s=document.scrollingElement||document.documentElement;var range=Math.max(0,s.scrollHeight-s.clientHeight);s.scrollTop=range*Math.min(1,Math.max(0,f||0));restorePending=false;schedule();};
  window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};
  window.__folioSeekTo=function(t){var parts=String(t).split(':'),el=null;
    if(parts[0]==='h'&&parts[1]){el=document.querySelector('[data-folio-hl="'+parts[1]+'"]');}
    if(!el){var pi=parts[0]==='h'?parts[2]:parts[1];var ps=document.querySelectorAll('p');if(!ps.length)return;var n=parseInt(pi,10);if(isNaN(n))n=0;el=ps[Math.min(Math.max(0,n),ps.length-1)];}
    if(!el)return;el.scrollIntoView({block:'start'});restorePending=false;schedule();};
  ${PageEngine.selectionWatchJs}
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
  window.addEventListener('wheel',function(e){if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;var d=e.deltaY||0;if(d<0&&atTop())edge('start');if(d>0&&atBottom())edge('end');restorePending=false;},{passive:true});
  window.addEventListener('scroll',function(){schedule();clearTimeout(window.__folioSettleT);window.__folioSettleT=setTimeout(schedule,180);},{passive:true});
  window.addEventListener('resize',schedule);
  document.addEventListener('click',function(ev){
    var el=ev.target;
    var a=(el&&el.closest)?el.closest('a[href]'):null;
    if(a){
      var href=a.getAttribute('href')||'';
      if(href&&href.charAt(0)!=='#'){ev.preventDefault();document.title='folio-link:'+(++nonce)+':'+encodeURIComponent(href);}
    }
  },true);
  var downX=0,downY=0,downT=0;
  document.addEventListener('pointerdown',function(e){downX=e.clientX;downY=e.clientY;downT=Date.now();},true);
  document.addEventListener('pointerup',function(e){
    if(e.target&&e.target.closest&&e.target.closest('#folio-overlay-root,#folio-selbtn'))return;
    if(Date.now()-downT<350&&Math.hypot(e.clientX-downX,e.clientY-downY)<24){
      var w=Math.max(1,window.innerWidth),h=Math.max(1,window.innerHeight);
      if(e.clientX>w*0.3&&e.clientX<w*0.7&&e.clientY>h*0.25&&e.clientY<h*0.75){document.title='folio-tap:'+(++nonce);}
    }
  },true);
  if(window.ResizeObserver){new ResizeObserver(schedule).observe(document.documentElement);if(document.body)new ResizeObserver(schedule).observe(document.body);}
  if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){schedule();setTimeout(schedule,150);});}
  document.querySelectorAll('img').forEach(function(i){i.addEventListener('load',schedule);i.addEventListener('error',schedule);});
  schedule();setTimeout(schedule,250);setTimeout(schedule,700);setTimeout(schedule,1500);
})();
"""
