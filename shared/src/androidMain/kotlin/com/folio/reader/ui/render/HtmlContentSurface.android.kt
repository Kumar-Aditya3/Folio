package com.folio.reader.ui.render

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.settings.ReaderSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

actual fun htmlSurfaceOccludesOverlays(): Boolean = false

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
    seekTargetRequest: Pair<String, Long>?
) {
    val currentTapHandler = rememberUpdatedState(onTap)
    val latestHighlight by rememberUpdatedState(onHighlightParagraph)
    val latestSelection by rememberUpdatedState(onSelectionChanged)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Sections on screen, including DOM extensions: the `sections` param is the
    // document as loaded (extensions never rebuild it), so the live list grows
    // and trims here as window ops apply. One state object for the whole
    // lifetime of the surface, so the remembered clients always read current.
    val liveSectionsState = remember { mutableStateOf(sections) }
    LaunchedEffect(sections) { liveSectionsState.value = sections }
    // A chapter window loads as one document; the single-section path (documents,
    // and non-windowed chapters) keys everything on the chapter href as before.
    val loadKey = if (windowed) {
        "window:" + sections.joinToString("-") { it.spineIndex.toString() }
    } else {
        sections.firstOrNull()?.href ?: ""
    }
    // Paragraph seeks have to wait for the chapter they were requested for: running
    // one against the page still on screen would move the previous chapter.
    val pageState = remember { PageLoadState() }
    // A chapter change does not put a new document on screen immediately — the
    // resource pass defers loadDataWithBaseURL — so mark the page unseekable up
    // front. Declared before the seek effects so it runs first in composition order;
    // without it a jump's seek moved the previous chapter and the new one then
    // opened at its saved fraction, i.e. the top of the chapter.
    LaunchedEffect(loadKey) { pageState.loading(loadKey) }
    // A windowed seek is scoped to the anchor's section so paragraph indices and
    // highlight marks resolve inside the right chapter.
    fun scopedTarget(target: String): String =
        if (windowed && anchorChapterId != null) "c:$anchorChapterId|$target" else target
    LaunchedEffect(seekRequest, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val req = seekRequest ?: return@LaunchedEffect
        pageState.seekFraction(req.first.coerceIn(0f, 1f), wv, loadKey)
    }
    LaunchedEffect(seekTargetRequest, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val req = seekTargetRequest ?: return@LaunchedEffect
        pageState.seekTo(scopedTarget(req.first), wv, loadKey)
    }
    LaunchedEffect(clearSelectionRequest, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (clearSelectionRequest == null || clearSelectionRequest == 0L) return@LaunchedEffect
        wv.evaluateJavascript("window.__folioClearSel&&window.__folioClearSel();", null)
    }
    // Adding or removing a highlight re-runs the painter on the page that is
    // already up — reloading the chapter here would flash the whole screen.
    LaunchedEffect(highlights, settings.themeId, settings.customTheme, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val theme = settings.customTheme
            ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
        wv.evaluateJavascript(
            HighlightPaint.applyJs(highlights, theme), null
        )
    }
    // The document: a window is assembled from its sections with every resource
    // reference rewritten to the canonical file URL the interceptor serves; a
    // single section loads exactly as it always did. Keyed on settings *minus
    // the theme fields*: a theme switch only swaps the style element in place
    // (see the CSS-swap effect below), so it never rewrites the document — the
    // full reload it used to trigger restored scroll from the last *reported*
    // progress fraction, which lags the finger and jumps the page up.
    val content = remember(sections, windowed, settings.copy(themeId = "", customTheme = null)) {
        if (windowed) {
            val rewritten = sections.map { section ->
                section.copy(html = rewriteToCanonicalUrls(section.html, section.href))
            }
            injectReaderCss(ReaderWindowAssembler.assemble(rewritten), settings)
        } else {
            injectReaderCss(sections.firstOrNull()?.html.orEmpty(), settings)
        }
    }
    // Live CSS swap: a theme-only change rewrites the baked <style> element's
    // text in place — colors change, scroll position and DOM stay untouched.
    // Patterned on the highlight-repaint effect above; also fires once after
    // each real load (setting identical CSS), which is a harmless no-op.
    LaunchedEffect(settings.themeId, settings.customTheme, content, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val css = readerStyleSheet(settings)
        wv.evaluateJavascript(
            "(function(){var s=document.getElementById('folio-reader-style');" +
                "if(s)s.textContent=" + jsLiteral(css) + ";})();",
            null
        )
    }
    val resourceCache = remember { ConcurrentHashMap<String, File>() }
    var resourcesReady by remember(loadKey) { mutableStateOf(false) }
    suspend fun resolveSectionSources(section: ReaderSection) {
        val pending = ArrayDeque<Pair<String, String>>()
        sourcesOf(section.html).forEach { pending.addLast(section.href to it) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (baseHref, src) = pending.removeFirst()
            val canonical = canonicalEpubPath(baseHref, src)
            if (!visited.add(canonical)) continue
            onResolveResource(baseHref, canonical)?.let { path ->
                val file = File(path)
                if (file.isFile) {
                    resourceCache[canonical] = file
                    // EPUB stylesheets commonly reference fonts/images through
                    // url(); preload those too because WebView requests are
                    // normalized and cannot call a suspend resolver.
                    if (file.extension.equals("css", true)) {
                        Regex("""url\(\s*["']?([^)"']+)["']?\s*\)""", RegexOption.IGNORE_CASE)
                            .findAll(file.readText())
                            .map { it.groupValues[1] }
                            .filter { !it.startsWith("data:") && !it.startsWith("#") }
                            .forEach { pending.addLast(canonical to it) }
                    }
                }
            }
        }
    }
    LaunchedEffect(loadKey, sections, onResolveResource) {
        resourcesReady = false
        for (section in sections) resolveSectionSources(section)
        resourcesReady = true
    }
    // Grow/trim the window on screen. Each op is consumed once; an append or
    // prepend re-runs the highlight painter afterwards so the freshly injected
    // section wears its own marks.
    LaunchedEffect(windowOp, webViewRef) {
        val op = windowOp ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        when (op) {
            is WindowOp.Append -> {
                resolveSectionSources(op.section)
                val fragment = ReaderWindowAssembler.sectionFragment(
                    op.section.copy(html = rewriteToCanonicalUrls(op.section.html, op.section.href))
                )
                wv.evaluateJavascript(
                    "window.__folioAppend&&window.__folioAppend(${op.section.spineIndex}," +
                        "${jsLiteral(op.section.chapterId)}," +
                        "${jsLiteral(fragment)});",
                    null
                )
                liveSectionsState.value = liveSectionsState.value + op.section
            }

            is WindowOp.Prepend -> {
                resolveSectionSources(op.section)
                val fragment = ReaderWindowAssembler.sectionFragment(
                    op.section.copy(html = rewriteToCanonicalUrls(op.section.html, op.section.href))
                )
                wv.evaluateJavascript(
                    "window.__folioPrepend&&window.__folioPrepend(${op.section.spineIndex}," +
                        "${jsLiteral(op.section.chapterId)}," +
                        "${jsLiteral(fragment)});",
                    null
                )
                liveSectionsState.value = listOf(op.section) + liveSectionsState.value
            }

            is WindowOp.Trim -> {
                wv.evaluateJavascript(
                    "window.__folioTrim&&window.__folioTrim(${op.fromSpine},${op.toSpine});",
                    null
                )
                liveSectionsState.value = liveSectionsState.value.filter {
                    it.spineIndex in op.fromSpine..op.toSpine
                }
            }
        }
        val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
        wv.evaluateJavascript(HighlightPaint.applyJs(highlights, theme), null)
        onWindowOpApplied(op.nonce)
    }
    // Hold pending JS so onPageFinished can inject after layout. This fixes the 1/1
    // measurement that happened immediately after loadDataWithBaseURL before layout.
    // The JS is tagged with the load token embedded in its base URL: when two loads
    // overlap (every settings change is a full reload), a stale onPageFinished from
    // the outgoing document must not eat the JS meant for the document on screen —
    // in paged modes that left the new document gated at body opacity 0 with no
    // engine, i.e. a solid blank page.
    var loadNonce by remember { mutableStateOf(0) }
    var pendingJs by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Page bridging must post to main thread — @JavascriptInterface and title
    // callbacks arrive on WebView background threads.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val latestProgress by rememberUpdatedState(onProgress)
    val latestPage by rememberUpdatedState(onPageChange)
    val latestVisible by rememberUpdatedState(onVisibleSection)
    val latestExtendFwd by rememberUpdatedState(onExtendForward)
    val latestExtendBwd by rememberUpdatedState(onExtendBackward)
    val latestEnd by rememberUpdatedState(onChapterEnd)
    val latestStart by rememberUpdatedState(onChapterStart)
    val latestLink by rememberUpdatedState(onLinkClick)
    val latestAnchorChapterId by rememberUpdatedState(anchorChapterId)
    // Selection events name a spine; resolve the chapter through the live window.
    fun chapterIdForSpine(spine: Int): String =
        liveSectionsState.value.firstOrNull { it.spineIndex == spine }?.chapterId
            ?: liveSectionsState.value.firstOrNull()?.chapterId.orEmpty()
    val client = remember(loadKey, onResolveResource, resourceCache) {
        object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                resourceResponse(request.url.toString(), resourceCache)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                onLinkClick?.invoke(request.url.toString())
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.getSettings().javaScriptEnabled = true
                pageState.loadStarted()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Inject the bridge JS only into the document it was queued for:
                // the base URL carries the load token (folio-load=N) assigned when
                // this load was issued. A mismatched finish belongs to an outgoing
                // document; its JS stays pending for the real finish or the fallback.
                val finishedToken = url?.substringAfter("folio-load=", "")?.toIntOrNull()
                val pj = pendingJs
                android.util.Log.i("FolioLoad", "finished token=$finishedToken pending=${pj?.first} consumed=${pj != null && finishedToken == pj.first}")
                if (pj != null && finishedToken == pj.first) {
                    pendingJs = null
                    view.evaluateJavascript(pj.second, null)
                }
                pageState.markReady(view)
            }
        }
    }
    // The bridge instance is registered with addJavascriptInterface exactly once, in
    // the factory, and can never be swapped. Calling the onProgress/onPageChange
    // lambdas captured here would pin the first composition's copies forever — and
    // since ReaderScreen recreates its currentPage/totalPages state on every chapter
    // change, the reports would land on orphaned state and the counter would stick at
    // 1/1 for the rest of the session. Read through rememberUpdatedState instead.
    val progressBridge = remember {
        object {
            @JavascriptInterface
            fun report(fraction: Float, current: Int, total: Int) {
                mainHandler.post {
                    latestProgress(fraction.coerceIn(0f, 1f))
                    latestPage(current.coerceAtLeast(1), total.coerceAtLeast(1))
                }
            }
        }
    }
    // Both layout modes talk through document.title: the paged engine always did,
    // and the continuous bridge joined it when chapter windows arrived, so one
    // parser covers progress, links, taps, edges, selections and extend requests.
    val chromeClient = remember {
        object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val t = title ?: return
                if (t.startsWith("folio-")) android.util.Log.i("FolioLoad", "title=${t.take(140)}")
                when {
                    t.startsWith("folio-progress:") -> {
                        val parts = t.split(':')
                        if (parts.size < 5) return
                        val f = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: return
                        val spine = parts.getOrNull(5)?.toIntOrNull()
                        mainHandler.post {
                            latestProgress(f)
                            latestPage(
                                parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                parts[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
                            )
                            if (spine != null) latestVisible(spine)
                            if (parts[4] == "true") latestEnd()
                        }
                    }

                    t.startsWith("folio-extend:fwd:") -> mainHandler.post { latestExtendFwd() }

                    t.startsWith("folio-extend:bwd:") -> mainHandler.post { latestExtendBwd() }

                    t.startsWith("folio-link:") -> {
                        val encoded = t.removePrefix("folio-link:").substringAfter(':', "")
                        val href = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return
                        mainHandler.post { latestLink?.invoke(href) }
                    }

                    t.startsWith("folio-tap:") -> mainHandler.post { currentTapHandler.value() }

                    t.startsWith("folio-edge:end:") -> mainHandler.post { latestEnd() }

                    t.startsWith("folio-edge:start:") -> mainHandler.post { latestStart() }

                    t.startsWith("folio-selclear:") -> mainHandler.post {
                        latestSelection?.invoke(latestAnchorChapterId ?: liveSectionsState.value.firstOrNull()?.chapterId.orEmpty(), 0, null)
                    }

                    t.startsWith("folio-sel2:") -> {
                        val rest = t.removePrefix("folio-sel2:")
                        val spine = rest.substringBefore(':').toIntOrNull() ?: -1
                        val afterSpine = rest.substringAfter(':')
                        val idx = afterSpine.substringBefore(':').toIntOrNull() ?: 0
                        val encoded = afterSpine.substringAfter(':').substringBeforeLast(':')
                        val text = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                        mainHandler.post {
                            latestSelection?.invoke(chapterIdForSpine(spine), idx, text?.takeIf { it.isNotBlank() })
                        }
                    }

                    t.startsWith("folio-sel:") -> {
                        val rest = t.removePrefix("folio-sel:")
                        val idx = rest.substringBefore(':').toIntOrNull() ?: 0
                        val encoded = rest.substringAfter(':').substringBeforeLast(':')
                        val text = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                        mainHandler.post {
                            latestSelection?.invoke(
                                latestAnchorChapterId ?: liveSectionsState.value.firstOrNull()?.chapterId.orEmpty(),
                                idx,
                                text?.takeIf { it.isNotBlank() }
                            )
                        }
                    }

                    t.startsWith("folio-engdiag:") ->
                        android.util.Log.i("FolioPage", "engine report: ${t.removePrefix("folio-engdiag:").substringBeforeLast(':')}")
                }
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d("FolioPage", "${message.message()} @${message.sourceId()?.substringAfterLast('/').orEmpty()}:${message.lineNumber()}")
                return true
            }
        }
    }
    AndroidView<ReaderWebView>(
        modifier = modifier,
        factory = { context ->
            // DevTools access for on-device diagnosis; debug builds only.
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            }
            ReaderWebView(context).apply {
                getSettings().javaScriptEnabled = true
                getSettings().domStorageEnabled = true
                getSettings().allowFileAccess = true
                getSettings().allowContentAccess = false
                getSettings().useWideViewPort = true
                getSettings().loadWithOverviewMode = true
                addJavascriptInterface(progressBridge, "FolioReader")
                webViewClient = client
                webChromeClient = chromeClient
                webViewRef = this
                var downX = 0f
                var downY = 0f
                var downAt = 0L
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            downAt = android.os.SystemClock.uptimeMillis()
                        }
                        MotionEvent.ACTION_UP -> {
                            val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                            val center = downX > view.width * .3f && downX < view.width * .7f &&
                                downY > view.height * .25f && downY < view.height * .75f
                            if (center && moved < 24f &&
                                android.os.SystemClock.uptimeMillis() - downAt < 350L
                            ) currentTapHandler.value()
                        }
                    }
                    false
                }
            }
        },
        onRelease = { webView ->
            // Compose has already detached the view; release the engine underneath.
            runCatching { webView.destroy() }
        },
        update = { webView ->
            webView.isEnabled = enabled
            val contentKey = "$loadKey:${content.hashCode()}"
            if (resourcesReady && webView.tag != contentKey) {
                webView.tag = contentKey
                val importedFonts = settings.customFonts.joinToString("") { font ->
                    val url = "file://${webView.context.filesDir.absolutePath}/fonts/${font.fileName}"
                    "@font-face{font-family:'${font.familyName}';src:url('$url') format('truetype');font-weight:${font.weight};font-style:normal;font-display:swap;}"
                }
                val fraction = position?.scrollOffset ?: 0.0
                // Android never offers spread; coerce to single-page paginated if a
                // synced/stored setting lands here with SPREAD selected.
                val androidLayoutMode = if (settings.layoutMode == com.folio.reader.settings.LayoutMode.SPREAD)
                    com.folio.reader.settings.LayoutMode.PAGINATED else settings.layoutMode
                val pagedCols = PageEngine.colsFor(androidLayoutMode)
                // Paged modes run the shared book engine (discrete pages + leaf
                // flip); continuous runs the section-aware bridge — one
                // implementation for windows and plain single-section documents.
                val baseJs = if (pagedCols > 0) {
                    PageEngine.js(fraction.toFloat(), pagedCols, settings.margins.left, PageEngine.measurePx(settings.textWidth))
                } else {
                    val seedSpine = sections.firstOrNull { it.chapterId == anchorChapterId }?.spineIndex
                        ?: sections.firstOrNull()?.spineIndex
                        ?: -1
                    ContinuousEngine.js(seedSpine, fraction.toFloat(), desktopEvents = false)
                }
                val theme = settings.customTheme
                    ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
                val js = baseJs + HighlightPaint.js(highlights, theme)
                val token = loadNonce + 1
                loadNonce = token
                pendingJs = token to js
                pageState.loading(loadKey)
                val baseUrl = if (windowed) {
                    "file:///folio/?folio-load=$token"
                } else {
                    val href = sections.firstOrNull()?.href ?: ""
                    "file:///folio/$href?folio-load=$token"
                }
                webView.loadDataWithBaseURL(baseUrl, "<style>$importedFonts</style>$content", "text/html", "UTF-8", null)
                android.util.Log.i("FolioLoad", "issued token=$token key=$contentKey")
                // Only for a WebView whose onPageFinished never fires: re-running the
                // bridge is not idempotent, it restores scrollTop from the saved
                // fraction and re-registers listeners. Inject only once THIS load's
                // document has committed (webView.url carries the token) — evaluating
                // earlier would run the JS against the outgoing document and the new
                // one would never get its engine.
                fun tryInject(attempt: Int) {
                    val pj = pendingJs
                    if (pj == null || pj.first != token || webView.tag != contentKey) return
                    val committed = webView.url?.substringAfter("folio-load=", "")?.toIntOrNull()
                    if (committed == token) {
                        android.util.Log.i("FolioLoad", "fallback inject token=$token")
                        pendingJs = null
                        webView.evaluateJavascript(pj.second, null)
                        pageState.markReady(webView)
                    } else if (attempt < 10) {
                        webView.postDelayed({ tryInject(attempt + 1) }, 200)
                    } else {
                        android.util.Log.i("FolioLoad", "fallback gave up token=$token committed=$committed")
                    }
                }
                webView.postDelayed({ tryInject(0) }, 400)
            } else if (resourcesReady) {
                // Content already loaded but position may have changed (scroll restore without reload).
                // Re-trigger report with updated fraction if needed.
            }
        }
    )
}

/** Embeds a string as a JSON literal for evaluateJavascript. */
private fun jsLiteral(s: String): String = ReaderWindowAssembler.jsStringLiteral(s)

/** Relative src/href attribute values of a chapter document, deduplicated. */
private fun sourcesOf(html: String): List<String> =
    Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(html).map { it.groupValues[1] }
        .filter { !it.startsWith("#") && !it.startsWith("http") && !it.startsWith("data:") }
        .distinct().toList()

/**
 * Rewrites a section's resource references to the canonical file:// URLs the
 * request interceptor serves. Chapter-to-chapter links stay relative — the
 * click bridge resolves those to spine targets, and an absolute file URL would
 * make them dead.
 */
private fun rewriteToCanonicalUrls(html: String, href: String): String =
    Regex("""((?:src|href)\s*=\s*)(["'])([^"']+)\2""", RegexOption.IGNORE_CASE).replace(html) { m ->
        val attr = m.groupValues[1].trim().lowercase()
        val quote = m.groupValues[2]
        val src = m.groupValues[3]
        val keep = src.startsWith("#") || src.startsWith("http") || src.startsWith("data:") ||
            src.startsWith("file:") ||
            (attr.startsWith("href") && Regex("""\.x?html?(#.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(src))
        if (keep) m.value
        else m.groupValues[1] + quote + "file:///folio/" + canonicalEpubPath(href, src) + quote
    }

/**
 * WebView that never opens the floating text-selection toolbar. Folio shows its own
 * Highlight button for a selection; Android's Copy/Share bar landing on top of it
 * (and on top of the page) read as clutter. Selection and handles still work.
 */
private class ReaderWebView(context: android.content.Context) : WebView(context) {
    override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? = null
}

/**
 * Tracks which chapter is laid out, holding a seek until the chapter it was requested
 * against is the one on screen.
 *
 * The request has to carry its chapter: a jump issued while a new document is still
 * being prepared must survive that load rather than run against the outgoing page,
 * but a request for a chapter the reader has already moved past is worthless and is
 * dropped.
 */
private class PageLoadState {
    private var pageReady = false
    private var loadingHref: String? = null
    private var readyHref: String? = null
    private var pending: String? = null
    private var pendingHref: String? = null

    /** [href] is the load key (chapter href, or the window's key) about to be handed to the WebView. */
    fun loading(href: String) {
        pageReady = false
        loadingHref = href
        if (pendingHref != null && pendingHref != href) {
            pending = null
            pendingHref = null
        }
    }

    /** A load began for a chapter we already know about; only readiness is unknown. */
    fun loadStarted() {
        pageReady = false
    }

    fun markReady(view: WebView) {
        pageReady = true
        readyHref = loadingHref
        val href = readyHref
        if (href != null && pendingHref == href) {
            val js = pending
            pending = null
            pendingHref = null
            js?.let { view.evaluateJavascript(it, null) }
        }
    }

    private fun run(js: String, view: WebView, href: String) {
        if (pageReady && readyHref == href) {
            view.evaluateJavascript(js, null)
        } else {
            pending = js
            pendingHref = href
        }
    }

    fun seekTo(target: String, view: WebView, href: String) {
        val literal = ReaderWindowAssembler.jsStringLiteral(target)
        run("window.__folioSeekTo&&window.__folioSeekTo($literal);", view, href)
    }

    fun seekFraction(fraction: Float, view: WebView, href: String) {
        run("window.__folioSeek&&window.__folioSeek($fraction);", view, href)
    }
}

private fun resourceResponse(
    url: String,
    resourceCache: Map<String, File>
): WebResourceResponse? {
    val normalizedPath = url.substringAfter("file:///folio/", "").substringBefore("?")
    if (normalizedPath.isBlank() || normalizedPath == url) return null
    val file = resourceCache["/$normalizedPath"] ?: resourceCache[normalizedPath]
        ?: resourceCache.entries.firstOrNull { it.key.endsWith("/$normalizedPath") }?.value
        ?: return null
    if (!file.isFile) return null
    val mime = when (file.extension.lowercase()) {
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "woff", "woff2" -> "font/woff"
        else -> "application/octet-stream"
    }

    return WebResourceResponse(mime, "UTF-8", file.inputStream())
}

private fun canonicalEpubPath(baseHref: String, src: String): String {
    val clean = src.substringBefore("#").substringBefore("?")
    if (clean.startsWith("file:") || clean.startsWith("http:") || clean.startsWith("https:")) return clean
    val root = baseHref.substringBefore('/').takeIf { it.isNotBlank() }
    if (root != null && clean.startsWith("$root/")) return clean
    val baseDir = baseHref.substringBeforeLast('/', "")
    val segments = mutableListOf<String>()
    for (part in "$baseDir/$clean".split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(part)
        }
    }
    return segments.joinToString("/")
}

/**
 * The Android reader sheet: the shared [ReaderCss] stylesheet with this
 * platform's font stack and scrolling-mode rules. Both the baked document
 * ([injectReaderCss]) and the live theme swap write exactly this, so the two
 * cannot drift.
 */
private fun readerStyleSheet(settings: ReaderSettings): String {
    // Coerce SPREAD → PAGINATED on Android: spread is desktop-only.
    val safeLayoutMode = if (settings.layoutMode == com.folio.reader.settings.LayoutMode.SPREAD)
        com.folio.reader.settings.LayoutMode.PAGINATED else settings.layoutMode
    return ReaderCss.styleSheet(
        settings,
        PageEngine.colsFor(safeLayoutMode),
        fontStack = { "'$it',serif" },
        // Scrolling mode: publisher height rules otherwise clamp the document
        // box and the page cannot grow.
        continuousCss = "html,body{height:auto !important;min-height:100% !important;overflow-y:visible !important;}html{overflow-y:auto !important;}"
    )
}

private fun injectReaderCss(rawHtml: String, settings: ReaderSettings): String {
    val html = com.folio.reader.epub.ChapterSanitizer.sanitize(rawHtml)
    val css = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style id=\"folio-reader-style\">" +
            readerStyleSheet(settings) + "</style>"
    return if (html.contains("</head>", ignoreCase = true)) html.replaceFirst(Regex("(?i)</head>"), "$css</head>") else "$css$html"
}
