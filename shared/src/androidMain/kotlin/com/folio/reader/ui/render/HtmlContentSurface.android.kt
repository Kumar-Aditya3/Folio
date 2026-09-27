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

// Render-path regexes, compiled once. These run per chapter/section (and the attribute rewrite's
// inner suffix check runs per src/href), so compiling them per call was avoidable chapter-turn work
// (see HtmlRenderer.kt for the same convention).
private val SRC_HREF_VALUE = Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val SRC_HREF_ATTR = Regex("""((?:src|href)\s*=\s*)(["'])([^"']+)\2""", RegexOption.IGNORE_CASE)
private val HTML_LINK_SUFFIX = Regex("""\.x?html?(#.*)?$""", RegexOption.IGNORE_CASE)
private val CSS_URL_REF = Regex("""url\(\s*["']?([^)"']+)["']?\s*\)""", RegexOption.IGNORE_CASE)
private val HEAD_CLOSE = Regex("(?i)</head>")

// The synthetic base every chapter/window loads under, and its per-load token query. A tapped
// in-content link arrives resolved against this base; stripping both recovers the epub-relative
// href the shared link handler matches against the chapter list.
private const val FOLIO_BASE = "file:///folio/"
private val RE_FOLIO_LOAD = Regex("""[?&]folio-load=\d+""")

// Reader WebView diagnostics. The onReceivedTitle/onConsoleMessage callbacks fire on essentially
// every scroll frame, so their logging + string work must not run in shipped builds. The shared
// module has no BuildConfig, so this is a compile-time flag a developer flips locally.
private const val READER_DEBUG_LOG = true

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
    onContentReady: () -> Unit
) {
    val currentTapHandler = rememberUpdatedState(onTap)
    // Paged mode emits its own centre-tap (folio-tap) from inside the iframe, so the
    // native centre-tap detector below must stand down there or the two would toggle
    // the chrome twice (a net no-op). Continuous mode has no in-page tap detector on
    // Android, so it still relies on the native one.
    val pagedModeState = rememberUpdatedState(
        PageEngine.colsFor(
            if (settings.layoutMode == com.folio.reader.settings.LayoutMode.SPREAD)
                com.folio.reader.settings.LayoutMode.PAGINATED else settings.layoutMode
        ) > 0
    )
    val latestContentReady by rememberUpdatedState(onContentReady)
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
    // Bumped once the page has committed and its engine JS has run (onPageFinished, or the
    // fallback inject). The highlight paint is keyed on this so the marks are (re)applied the
    // moment the painter exists: the baked load-time paint can miss highlights that finish
    // loading from the DB a beat later — the "sometimes coloured instantly, sometimes not"
    // race. Painting is idempotent (it unwraps first), so an extra apply is harmless.
    var pageReadyTick by remember(loadKey) { mutableStateOf(0) }
    // Adding or removing a highlight re-runs the painter on the page that is
    // already up — reloading the chapter here would flash the whole screen.
    LaunchedEffect(highlights, settings.themeId, settings.customTheme, webViewRef, pageReadyTick) {
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
    // the theme and typography fields*: those are pure stylesheet — they swap in
    // place (see the restyle effect below) with a paragraph anchor, so a font
    // change never reloads the document (the reload restored scroll from the
    // last *reported* progress fraction, which both lags the finger and means
    // something else after a reflow — the "changing fonts jumps page position"
    // report). Geometry (margins/text width/layout mode) still reloads.
    val content = remember(
        sections, windowed,
        settings.copy(
            themeId = "", customTheme = null,
            fontFamily = "", fontSize = 0f, fontWeight = 0,
            lineHeight = 0f, letterSpacing = 0f, paragraphSpacing = 0f,
        ),
    ) {
        if (windowed) {
            injectReaderCss(
                ReaderWindowAssembler.assemble(sections.map { it.copy(html = canonicalSection(it)) }, shadow = false),
                settings
            )
        } else {
            // Paged attribute refs already resolve through the chapter's own base URL, so only
            // the CSS ones — which do not — are made absolute here.
            val only = sections.firstOrNull()
            injectReaderCss(
                if (only == null) "" else rewriteCssUrls(only.html, only.href), settings
            )
        }
    }
    // Live stylesheet swap: a theme or typography change rewrites the baked
    // <style> element's text in place through the engine's anchored restyle —
    // the same words stay under the reader's eye across the reflow. Also fires
    // once after each real load (setting identical CSS), which is a no-op.
    LaunchedEffect(settings, content, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        // Debounce: dragging the font-size / spacing sliders emits a new `settings`
        // every frame, and each live restyle re-columnises the whole chapter
        // (measure() reads scrollWidth = a forced reflow). Without this, a slider
        // drag queued dozens of full re-layouts and applied visibly late and jerkily.
        // LaunchedEffect cancels the prior coroutine on each change, so only the last
        // value within the window actually restyles; a real load re-keys via `content`
        // and eats just this short delay once.
        kotlinx.coroutines.delay(110)
        val css = readerStyleSheet(settings)
        val shadowCss = ReaderCss.shadowStyleSheet(settings)
        wv.evaluateJavascript(
            "(function(){if(window.__folioRestyle){window.__folioRestyle(''," + jsLiteral(css) + "," + jsLiteral(shadowCss) + ");}" +
                "else{var s=document.getElementById('folio-reader-style');" +
                "if(s)s.textContent=" + jsLiteral(css) + ";}})();",
            null
        )
    }
    val resourceCache = remember { ConcurrentHashMap<String, File>() }
    // Intrinsic image sizes (canonical epub path -> pixels), parsed header-only
    // during resource resolution and stamped onto <img> before layout so the
    // browser reserves the box (see ImageReserve) — kills text-over-image and
    // the decode reflow in both paged and continuous modes.
    val imageDims = remember { ConcurrentHashMap<String, com.folio.reader.epub.ImageDimensions.Size>() }
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
                    val ext = file.extension.lowercase()
                    if (ext in IMAGE_DIM_EXTS) {
                        com.folio.reader.epub.ImageDimensions.read(file)?.let { imageDims[canonical] = it }
                    }
                    // EPUB stylesheets commonly reference fonts/images through
                    // url(); preload those too because WebView requests are
                    // normalized and cannot call a suspend resolver.
                    if (file.extension.equals("css", true)) {
                        CSS_URL_REF
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
                    op.section.copy(html = canonicalSection(op.section)),
                    shadow = false
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
                    op.section.copy(html = canonicalSection(op.section)),
                    shadow = false
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
        // A chapter injected mid-read brings new images; reserve their boxes too.
        val dimsJson = buildDimsJson(imageDims)
        if (dimsJson.length > 2) {
            wv.evaluateJavascript("window.__folioStampImgs&&window.__folioStampImgs($dimsJson);", null)
        }
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
                // Content is loaded under a synthetic base (file:///folio/…?folio-load=N),
                // so the WebView hands us a fully-resolved file URL. The shared link
                // handler expects the epub-relative href the desktop surface passes, so
                // strip the base and the load token first; otherwise every in-content link
                // read as an unmatchable absolute path and silently did nothing.
                val raw = request.url.toString()
                val href = if (raw.startsWith(FOLIO_BASE))
                    raw.removePrefix(FOLIO_BASE).replace(RE_FOLIO_LOAD, "")
                else raw
                onLinkClick?.invoke(href)
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
                if (READER_DEBUG_LOG) android.util.Log.i("FolioLoad", "finished token=$finishedToken pending=${pj?.first} consumed=${pj != null && finishedToken == pj.first}")
                if (pj != null && finishedToken == pj.first) {
                    pendingJs = null
                    view.evaluateJavascript(pj.second, null)
                }
                pageState.markReady(view)
                // The document has committed and the engine JS has run: this is the
                // first frame the reader's text actually paints. Tell Compose so the
                // morph cover plate can dissolve on real paint instead of on the
                // HTML *string* being ready (which left a blank-paper flash). Posted
                // to the main thread since onPageFinished can arrive off it.
                mainHandler.post { pageReadyTick++; latestContentReady() }
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
                if (READER_DEBUG_LOG && t.startsWith("folio-")) android.util.Log.i("FolioLoad", "title=${t.take(140)}")
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
                        if (READER_DEBUG_LOG) android.util.Log.i("FolioPage", "engine report: ${t.removePrefix("folio-engdiag:").substringBeforeLast(':')}")
                }
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (READER_DEBUG_LOG) android.util.Log.d("FolioPage", "${message.message()} @${message.sourceId()?.substringAfterLast('/').orEmpty()}:${message.lineNumber()}")
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
                // Paper from the first frame, before any `update` runs — a WebView's
                // own default is white, and this is the frame the reader sees first.
                applyReaderPaper(
                    (settings.customTheme
                        ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)).background
                )
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
                            if (!pagedModeState.value && center && moved < 24f &&
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
                    // Variable files declare a weight RANGE so real weights resolve
                    // from the axis; static files pin their single weight. Prefer the
                    // model's own flag (set for bundled faces and computed for imports),
                    // keeping the file-name sniff as a fallback for legacy entries.
                    val weight = if (font.isVariable || font.fileName.contains("variable")) "300 900" else "${font.weight}"
                    "@font-face{font-family:'${font.familyName}';src:url('$url') format('truetype');font-weight:$weight;font-style:normal;font-display:swap;}"
                }
                val fraction = position?.scrollOffset ?: 0.0
                // Android never offers spread; coerce to single-page paginated if a
                // synced/stored setting lands here with SPREAD selected.
                val androidLayoutMode = if (settings.layoutMode == com.folio.reader.settings.LayoutMode.SPREAD)
                    com.folio.reader.settings.LayoutMode.PAGINATED else settings.layoutMode
                val pagedCols = PageEngine.colsFor(androidLayoutMode)
                val theme = settings.customTheme
                    ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
                // Paint the view with the reader's own paper before the document
                // loads: a WebView is white until the HTML's CSS arrives, and on a
                // dark theme that white is the flash seen when opening a book.
                // Applied here rather than at construction so a theme change while
                // a chapter is up re-papers the view as well.
                webView.applyReaderPaper(theme.background)
                // Reserve image boxes before layout so text never lands on top of
                // an image and the page count is stable once art arrives.
                val dimsJson = buildDimsJson(imageDims)
                val stampJs = if (dimsJson.length > 2) ImageReserve.stampJs(dimsJson) else ""
                val js = if (pagedCols > 0) {
                    // Paged: chapter rendered inside its own iframe with CSS
                    // multi-column (MulticolEngine) so the browser breaks pages
                    // instead of the old hand-slicer. Image reservation and
                    // highlight painting run INSIDE the iframe; the bootstrap
                    // installs top-window proxies so the Kotlin bridge, highlight
                    // and restyle call sites stay unchanged.
                    val inner = MulticolEngine.innerJs(
                        fraction.toFloat(),
                        settings.margins.top,
                        settings.margins.bottom,
                        settings.margins.left,
                        settings.margins.right,
                        PageEngine.measurePx(settings.textWidth),
                        diag = READER_DEBUG_LOG
                    ) + ImageReserve.stampJs(dimsJson) + HighlightPaint.js(highlights, theme)
                    MulticolEngine.bootstrapJs(inner)
                } else {
                    val seedSpine = sections.firstOrNull { it.chapterId == anchorChapterId }?.spineIndex
                        ?: sections.firstOrNull()?.spineIndex
                        ?: -1
                    stampJs +
                        ContinuousEngine.js(
                            seedSpine, fraction.toFloat(), desktopEvents = false, diag = READER_DEBUG_LOG,
                            shadow = false, shadowCss = ReaderCss.shadowStyleSheet(settings)
                        ) +
                        HighlightPaint.js(highlights, theme)
                }
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
                if (READER_DEBUG_LOG) android.util.Log.i("FolioLoad", "issued token=$token key=$contentKey")
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
                        if (READER_DEBUG_LOG) android.util.Log.i("FolioLoad", "fallback inject token=$token")
                        pendingJs = null
                        webView.evaluateJavascript(pj.second, null)
                        pageState.markReady(webView)
                        // Same first-paint signal as onPageFinished, for the WebView
                        // whose onPageFinished never fires (see tryInject above).
                        mainHandler.post { pageReadyTick++; latestContentReady() }
                    } else if (attempt < 10) {
                        webView.postDelayed({ tryInject(attempt + 1) }, 200)
                    } else {
                        if (READER_DEBUG_LOG) android.util.Log.i("FolioLoad", "fallback gave up token=$token committed=$committed")
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

/** Extensions worth a header-only intrinsic-size probe (see ImageDimensions). */
private val IMAGE_DIM_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

/** JS object literal `{ "path":[w,h] }` of parsed intrinsic image sizes, consumed by ImageReserve. */
private fun buildDimsJson(dims: Map<String, com.folio.reader.epub.ImageDimensions.Size>): String =
    if (dims.isEmpty()) "{}"
    else dims.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
        jsLiteral(k) + ":[" + v.width + "," + v.height + "]"
    }

/**
 * Relative resource references of a chapter document, deduplicated.
 *
 * Attribute refs alone are not enough: `url()` inside a chapter's own inlined `<style>` or
 * `style=` attribute is rewritten to a canonical file URL the interceptor must answer, and an
 * entry that was never resolved is a guaranteed 404 on that artwork or face. (Refs inside a
 * separate `.css` file are picked up by the walk in [resolveSectionSources].)
 */
private fun sourcesOf(html: String): List<String> =
    (SRC_HREF_VALUE.findAll(html).map { it.groupValues[1] } +
        CSS_URL_REF.findAll(html).map { it.groupValues[1] })
        .filter { !it.startsWith("#") && !it.startsWith("http") && !it.startsWith("data:") &&
            !it.startsWith("file:") }
        .distinct().toList()

/**
 * A section's HTML with every resource reference made absolute — attribute `src`/`href` and CSS
 * `url()` alike. Used for the window document and for sections injected later by append/prepend,
 * so a chapter entering mid-read cannot wear different resource URLs from the ones loaded with it.
 */
private fun canonicalSection(section: ReaderSection): String =
    rewriteCssUrls(rewriteToCanonicalUrls(section.html, section.href), section.href)

/**
 * Rewrites a section's resource references to the canonical file:// URLs the
 * request interceptor serves. Chapter-to-chapter links stay relative — the
 * click bridge resolves those to spine targets, and an absolute file URL would
 * make them dead.
 */
private fun rewriteToCanonicalUrls(html: String, href: String): String =
    SRC_HREF_ATTR.replace(html) { m ->
        val attr = m.groupValues[1].trim().lowercase()
        val quote = m.groupValues[2]
        val src = m.groupValues[3]
        val keep = src.startsWith("#") || src.startsWith("http") || src.startsWith("data:") ||
            src.startsWith("file:") ||
            (attr.startsWith("href") && HTML_LINK_SUFFIX.containsMatchIn(src))
        if (keep) m.value
        else m.groupValues[1] + quote + FOLIO_BASE + canonicalEpubPath(href, src) + quote
    }

/**
 * Rewrites CSS `url()` references — in inlined `<style>` blocks and `style=` attributes — to the
 * canonical file URL the interceptor serves.
 *
 * Inlined stylesheets arrive with their `url()` refs already rebased to EPUB-root-relative paths
 * (see `JvmChapterContentProvider.withStylesheets`), but a relative reference still resolves
 * against *this document's* base URL, and the two layout modes load under different bases: a
 * window at `file:///folio/`, a single chapter at `file:///folio/<chapterHref>`. Under the chapter
 * base `url('Oebina/Images/a.png')` becomes `…/Oebina/Content/Oebina/Images/a.png`, so every font
 * and background that comes from CSS rather than an attribute 404s in paged mode while working in
 * scroll. Writing the absolute URL drops the base dependence entirely.
 */
private fun rewriteCssUrls(html: String, href: String): String =
    CSS_URL_REF.replace(html) { m ->
        val ref = m.groupValues[1]
        val keep = ref.startsWith("#") || ref.startsWith("data:") || ref.startsWith("file:") ||
            ref.startsWith("http:") || ref.startsWith("https:") || ref.startsWith("//")
        if (keep) m.value
        else {
            // A url() into an SVG sprite or a font with a query survives as a suffix: canonicalEpubPath
            // strips both, and dropping them here would break the reference it just fixed.
            val path = ref.substringBefore('#').substringBefore('?')
            if (path.isBlank()) m.value
            else "url('" + FOLIO_BASE + canonicalEpubPath(href, path) + ref.removePrefix(path) + "')"
        }
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
 * The paper a [ReaderWebView] shows before its document has painted.
 *
 * A WebView is **white** until the loaded HTML's own CSS arrives, which on a dark
 * reading theme is a full-screen white rectangle between the loading state and the
 * page — the "image flashing" seen when opening an EPUB. Using the theme's own
 * canvas colour makes that gap invisible: the window before content is the same
 * colour as the content.
 */
private fun android.webkit.WebView.applyReaderPaper(paperArgb: Int) {
    setBackgroundColor(paperArgb)
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
    val requestPath = url.substringAfter(FOLIO_BASE, "").substringBefore("?")
    if (requestPath.isBlank() || requestPath == url) return null
    // The WebView hands back the request URL percent-encoded, but the cache is keyed on the
    // EPUB's own path — so "plate 1.png" is looked up as "plate%201.png" and misses. The
    // interceptor then answers null, the WebView tries the literal file:///folio path that does
    // not exist, and the artwork is simply absent with no error anywhere. Try the raw form
    // first, then the decoded one.
    val file = cachedResource(resourceCache, requestPath)
        ?: cachedResource(resourceCache, decodePath(requestPath))
        ?: return null
    if (!file.isFile) return null
    val mime = when (file.extension.lowercase()) {
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }

    // Images are downscaled to a screen-sane size so a multi-thousand-pixel
    // publisher plate does not stall on a slow main-thread decode ("long image
    // loading"). This runs on the WebView's background request thread.
    if (mime.startsWith("image/")) return downscaledImageResponse(file, mime)
    return WebResourceResponse(mime, "UTF-8", file.inputStream())
}

/** Longest edge (px) any served image is decoded down to; a phone never needs more. */
private const val MAX_IMAGE_DIM = 1600

/**
 * Returns [file] as an image response, downsampling raster formats whose longest
 * edge exceeds [MAX_IMAGE_DIM]. Aspect ratio is preserved (so the intrinsic
 * dimensions stamped by [ImageReserve] still match), and anything that cannot be
 * decoded falls back to the original bytes. SVG/GIF/AVIF pass through untouched
 * (vector, animated, or not universally re-encodable).
 */
private fun downscaledImageResponse(file: File, mime: String): WebResourceResponse {
    val raster = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp" || mime == "image/bmp"
    if (!raster) return WebResourceResponse(mime, null, file.inputStream())
    return runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val longest = maxOf(w, h)
        if (w <= 0 || h <= 0 || longest <= MAX_IMAGE_DIM) {
            return WebResourceResponse(mime, null, file.inputStream())
        }
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_DIM) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return WebResourceResponse(mime, null, file.inputStream())
        val hasAlpha = bmp.hasAlpha()
        val fmt = if (hasAlpha) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
        val outMime = if (hasAlpha) "image/png" else "image/jpeg"
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(fmt, 85, baos)
        bmp.recycle()
        WebResourceResponse(outMime, null, java.io.ByteArrayInputStream(baos.toByteArray()))
    }.getOrElse { WebResourceResponse(mime, null, file.inputStream()) }
}

private fun cachedResource(cache: Map<String, File>, path: String): File? =
    cache["/$path"] ?: cache[path] ?: cache.entries.firstOrNull { it.key.endsWith("/$path") }?.value

/**
 * Percent-decodes a request path back to the EPUB's own naming. `+` is escaped first because
 * [java.net.URLDecoder] maps it to a space, and a malformed escape must not take the request down.
 */
private fun decodePath(path: String): String =
    if (!path.contains('%')) path
    else runCatching {
        java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(path)

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
        continuousCss = "html,body{height:auto !important;min-height:100% !important;overflow-y:visible !important;}html{overflow-y:auto !important;}",
        // Android paged renders inside an iframe (MulticolEngine); the top
        // document only needs a neutral box, layout happens in the iframe.
        pagedCss = MulticolEngine::frameCss
    )
}

private fun injectReaderCss(rawHtml: String, settings: ReaderSettings): String {
    val html = com.folio.reader.epub.ChapterSanitizer.sanitize(rawHtml)
    val css = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style id=\"folio-reader-style\">" +
            readerStyleSheet(settings) + "</style>"
    return if (html.contains("</head>", ignoreCase = true)) html.replaceFirst(HEAD_CLOSE, "$css</head>") else "$css$html"
}
