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
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

actual fun htmlSurfaceOccludesOverlays(): Boolean = false

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
    val currentTapHandler = rememberUpdatedState(onTap)
    val latestHighlight by rememberUpdatedState(onHighlightParagraph)
    val latestSelection by rememberUpdatedState(onSelectionChanged)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Paragraph seeks have to wait for the chapter they were requested for: running
    // one against the page still on screen would move the previous chapter.
    val pageState = remember { PageLoadState() }
    // A chapter change does not put a new document on screen immediately — the
    // resource pass defers loadDataWithBaseURL — so mark the page unseekable up
    // front. Declared before the seek effects so it runs first in composition order;
    // without it a jump's seek moved the previous chapter and the new one then
    // opened at its saved fraction, i.e. the top of the chapter.
    LaunchedEffect(chapterHref) { pageState.loading(chapterHref) }
    LaunchedEffect(seekRequest, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val req = seekRequest ?: return@LaunchedEffect
        pageState.seekFraction(req.first.coerceIn(0f, 1f), wv, chapterHref)
    }
    LaunchedEffect(seekTargetRequest, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val req = seekTargetRequest ?: return@LaunchedEffect
        pageState.seekTo(req.first, wv, chapterHref)
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
    // Brightness is applied through its own style element rather than the reader
    // stylesheet: changing that stylesheet reloads the chapter, which would flash on
    // every tick of a slider meant to be dragged.
    LaunchedEffect(settings.brightness, webViewRef) {
        webViewRef?.evaluateJavascript(PageDim.applyJs(settings.brightness), null)
    }
    val content = remember(html, settings, chapterHref, position, highlights) {
        injectReaderCss(html, settings)
    }
    val resourceCache = remember { ConcurrentHashMap<String, File>() }
    var resourcesReady by remember(chapterHref, html) { mutableStateOf(false) }
    LaunchedEffect(chapterHref, html, onResolveResource) {
        resourcesReady = false
        val sources = Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }
            .filter { !it.startsWith("#") && !it.startsWith("http") && !it.startsWith("data:") }
            .distinct().toList()
        val pending = ArrayDeque<Pair<String, String>>()
        sources.forEach { pending.addLast(chapterHref to it) }
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
        resourcesReady = true
    }
    // Hold pending JS so onPageFinished can inject after layout. This fixes the 1/1
    // measurement that happened immediately after loadDataWithBaseURL before layout.
    var pendingJs by remember { mutableStateOf<String?>(null) }
    // Page bridging must post to main thread — @JavascriptInterface and title
    // callbacks arrive on WebView background threads.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val latestProgress by rememberUpdatedState(onProgress)
    val latestPage by rememberUpdatedState(onPageChange)
    val latestEnd by rememberUpdatedState(onChapterEnd)
    val latestStart by rememberUpdatedState(onChapterStart)
    val latestLink by rememberUpdatedState(onLinkClick)
    val client = remember(chapterHref, onResolveResource, resourceCache) {
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
                // Inject measurement JS only after the page is laid out, and consume it:
                // the delayed fallback must not run a second time.
                pendingJs?.let { js ->
                    pendingJs = null
                    view.evaluateJavascript(js, null)
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
    // The paged engine talks through document.title (same protocol as the
    // desktop surface): progress/pages, link clicks, center taps.
    val chromeClient = remember {
        object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val t = title ?: return
                when {
                    t.startsWith("folio-progress:") -> {
                        val parts = t.split(':')
                        if (parts.size != 5) return
                        val f = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: return
                        mainHandler.post {
                            latestProgress(f)
                            latestPage(
                                parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                parts[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
                            )
                            if (parts[4] == "true") latestEnd()
                        }
                    }

                    t.startsWith("folio-link:") -> {
                        val encoded = t.removePrefix("folio-link:").substringAfter(':', "")
                        val href = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return
                        mainHandler.post { latestLink?.invoke(href) }
                    }

                    t.startsWith("folio-tap:") -> mainHandler.post { currentTapHandler.value() }

                    t.startsWith("folio-edge:end:") -> mainHandler.post { latestEnd() }

                    t.startsWith("folio-edge:start:") -> mainHandler.post { latestStart() }

                    t.startsWith("folio-selclear:") -> mainHandler.post { latestSelection?.invoke(0, null) }

                    t.startsWith("folio-sel:") -> {
                        val rest = t.removePrefix("folio-sel:")
                        val idx = rest.substringBefore(':').toIntOrNull() ?: 0
                        val encoded = rest.substringAfter(':').substringBeforeLast(':')
                        val text = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                        mainHandler.post { latestSelection?.invoke(idx, text?.takeIf { it.isNotBlank() }) }
                    }
                }
            }
        }
    }
    AndroidView<ReaderWebView>(
        modifier = modifier,
        factory = { context ->
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
        update = { webView ->
            webView.isEnabled = enabled
            val contentKey = "$chapterHref:${content.hashCode()}"
            if (resourcesReady && webView.tag != contentKey) {
                webView.tag = contentKey
                val importedFonts = settings.customFonts.joinToString("") { font ->
                    val url = "file://${webView.context.filesDir.absolutePath}/fonts/${font.fileName}"
                    "@font-face{font-family:'${font.familyName}';src:url('$url') format('truetype');font-weight:${font.weight};font-style:normal;font-display:swap;}"
                }
                val fraction = position?.scrollOffset ?: 0.0
                // Engine gated off on phone (see injectReaderCss): WebView paints
                // paged modes blank; keep the continuous measurement script.
                val pagedCols = 0
                // Paged modes run the shared book engine (discrete pages + leaf
                // flip, progress via the title protocol); continuous keeps the
                // layout-aware scrolling measurement below.
                val baseJs = if (pagedCols > 0) {
                    PageEngine.js(fraction.toFloat(), pagedCols, settings.margins.left, PageEngine.measurePx(settings.textWidth))
                } else """(function(){
                            var paginated=false,scheduled=false,last=0,nonce=0,maxTotal=1;
                            function atBottom(){var s=document.scrollingElement||document.documentElement;return s.scrollTop+s.clientHeight>=s.scrollHeight-2;}
                            window.__folioSeek=function(f){var s=document.scrollingElement||document.documentElement;var range=Math.max(0,s.scrollHeight-s.clientHeight);s.scrollTop=range*Math.min(1,Math.max(0,f||0));schedule();};
                            window.__folioSeekPara=function(i){window.__folioSeekTo('p:'+i);};
                            window.__folioSeekTo=function(t){var parts=String(t).split(':'),isH=parts[0]==='h';var id=isH?(parts[1]||''):'',para=isH?parts[2]:parts[1],frac=isH?parts[3]:parts[2];function byMark(){if(!id)return null;try{return document.querySelector('[data-folio-hl="'+id+'"]');}catch(e){return null;}}function land(el){el.scrollIntoView({block:'start'});schedule();}function byPara(){if(para===undefined||para==='')return false;var n=parseInt(para,10);if(isNaN(n))return false;var ps=document.querySelectorAll('p');if(!ps.length)return false;land(ps[Math.min(Math.max(0,n),ps.length-1)]);return true;}function byFrac(){if(frac===undefined||frac==='')return false;var f=parseFloat(frac);if(isNaN(f))return false;if(window.__folioSeek)window.__folioSeek(f);return true;}var m=byMark();if(m){land(m);return;}if(!byPara())byFrac();};
                            ${PageEngine.selectionWatchJs}
                            function report(){
                                scheduled=false;
                                var now=Date.now();
                                if(now-last<100){schedule();return;}
                                last=now;
                                var scroller = document.scrollingElement || document.documentElement;
                                var d=document.documentElement,b=document.body||d;
                                var vw=Math.max(1, (scroller.clientWidth || d.clientWidth || window.innerWidth || 1)),vh=Math.max(1, (scroller.clientHeight || d.clientHeight || window.innerHeight || 1));
                                var docWidth=Math.max(scroller.scrollWidth, d.scrollWidth, b.scrollWidth);
                                var docHeight=Math.max(scroller.scrollHeight, d.scrollHeight, b.scrollHeight || 0);
                                var range=paginated?Math.max(0,docWidth-vw):Math.max(0,docHeight-vh);
                                var offset=paginated? (scroller.scrollLeft || window.scrollX) : (scroller.scrollTop || window.scrollY);
                                var total=paginated?Math.max(1,Math.ceil(docWidth/vw)):Math.max(1,Math.ceil(docHeight/vh));
                                if(total>maxTotal)maxTotal=total;else total=maxTotal;
                                var current=paginated?Math.min(total,Math.floor(offset/vw)+1):Math.min(total,Math.floor(offset/vh)+1);
                                FolioReader.report(range>0?Math.min(1,offset/range):0,current,total);
                            }
                            function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(report);}}
                            var lastEdgeHop=0;
                            function edge(which){
                              var now=Date.now();
                              if(now-lastEdgeHop<600)return;
                              lastEdgeHop=now;
                              document.title='folio-edge:'+which+':'+(++nonce);
                            }
                            function atTop(){return ((document.scrollingElement||document.documentElement).scrollTop||0)<=1;}
                            var lastY=0;
                            window.addEventListener('touchstart',function(e){var t=e.touches&&e.touches[0];lastY=t?t.clientY:0;},{passive:true});
                            window.addEventListener('touchmove',function(e){var t=e.touches&&e.touches[0];if(!t)return;var dy=t.clientY-lastY;lastY=t.clientY;if(dy>16&&atTop())edge('start');if(dy<-16&&atBottom())edge('end');},{passive:true});
                            window.addEventListener('wheel',function(e){var d=e.deltaY||0;if(d<0&&atTop())edge('start');if(d>0&&atBottom())edge('end');},{passive:true});
                            window.addEventListener('scroll',function(){schedule();clearTimeout(window.__folioSettleT);window.__folioSettleT=setTimeout(schedule,180);},{passive:true});
                            window.addEventListener('resize',schedule);
                            window.addEventListener('load',schedule);
                            if(window.ResizeObserver){new ResizeObserver(schedule).observe(document.documentElement); if(document.body) new ResizeObserver(schedule).observe(document.body);}
                            // Restore saved scroll position; use scroller
                            var scroller2 = document.scrollingElement || document.documentElement;
                            if(paginated){ scroller2.scrollLeft = scroller2.scrollWidth*$fraction; } else { scroller2.scrollTop = scroller2.scrollHeight*$fraction; }
                            if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){ schedule(); setTimeout(schedule,150); });
                            document.querySelectorAll('img').forEach(function(img){img.addEventListener('load',schedule);img.addEventListener('error',schedule);});
                            schedule();setTimeout(schedule,250);setTimeout(schedule,600);setTimeout(schedule,1000);setTimeout(schedule,1800);
                        })();"""
                val theme = settings.customTheme
                    ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
                val js = baseJs + HighlightPaint.js(highlights, theme) +
                        PageDim.applyJs(settings.brightness)
                pendingJs = js
                pageState.loading(chapterHref)
                webView.loadDataWithBaseURL("file:///folio/$chapterHref", "<style>$importedFonts</style>$content", "text/html", "UTF-8", null)
                // Only for a WebView that never fires onPageFinished: re-running the
                // bridge is not idempotent, it restores scrollTop from the saved
                // fraction and re-registers listeners.
                webView.postDelayed({
                    if (pendingJs != null && webView.tag == contentKey) {
                        webView.evaluateJavascript(js, null)
                        pageState.markReady(webView)
                    }
                }, 400)
            } else if (resourcesReady) {
                // Content already loaded but position may have changed (scroll restore without reload).
                // Re-trigger report with updated fraction if needed.
            }
        }
    )
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

    /** [href] is the chapter about to be handed to the WebView. */
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
        val literal = kotlinx.serialization.json.Json.encodeToString(
            String.serializer(), target
        )
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

private fun injectReaderCss(html: String, settings: ReaderSettings): String {
    val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
    val align = when (settings.alignment) {
        com.folio.reader.settings.TextAlignment.CENTER -> "center"
        com.folio.reader.settings.TextAlignment.JUSTIFIED -> "justify"
        else -> "left"
    }
    // The JS page engine currently paints blank inside Android WebView; keep the
    // proven continuous scroll on phone (paged modes degrade to scrolling) while
    // desktop uses the full book engine. Follow-up: debug the engine on WebView.
    val pagedCols = 0
    val columns = if (pagedCols > 0) {
        PageEngine.css(
            pagedCols, settings.margins.top, settings.margins.bottom,
            "#${theme.background.toUInt().toString(16).padStart(8, '0').drop(2)}"
        )
    } else {
        "html,body{height:auto !important;min-height:100% !important;overflow-y:visible !important;} html{overflow-y:auto !important;}"
    }
    val fontFamily = settings.customFonts.firstOrNull { it.name == settings.fontFamily }?.familyName ?: settings.fontFamily
    fun Int.rgb(): String = toUInt().toString(16).padStart(8, '0').drop(2)
    val original = settings.formattingMode == com.folio.reader.model.FormattingMode.ORIGINAL
    // Publisher CSS can otherwise leave black text on dark themes or wipe the
    // reader typography; outside ORIGINAL the reader owns these properties.
    val themeBgCss = if (original) "" else
        "body,body div,body section,body article,body figure{background-color:transparent !important;}"
    val imp = if (original) "" else " !important"
    val typographyCss = if (original) "" else
        "font-family:'$fontFamily',serif$imp;font-size:${settings.fontSize}px$imp;" +
                "font-weight:${settings.fontWeight}$imp;line-height:${settings.lineHeight}$imp;" +
                "letter-spacing:${settings.letterSpacing}px$imp;"
    val alignCss = if (original) "" else "text-align:$align$imp;"
    val colorCss = if (original) "" else "color:#${theme.primaryText.rgb()}$imp;"
    val hyphenCss = if (!original && settings.hyphenation) "-webkit-hyphens:auto;hyphens:auto;" else ""
    // Publisher color/font rules declared on elements (e.g. .calibre p{color:#000})
    // outrank an inherited body rule even with !important — force them on the
    // elements themselves so the theme's text is always readable.
    val elementForceCss = if (original) "" else
        "body p,body div,body span,body li,body blockquote{color:#${theme.primaryText.rgb()} !important;font-family:'$fontFamily',serif !important;}" +
                "body h1,body h2,body h3,body h4,body h5,body h6{font-family:'$fontFamily',serif !important;}" +
                "body,body p,body div,body li,body blockquote{text-indent:0 !important;}"
    val css = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style id=\"folio-reader-style\">" +
            "html,body{margin:0;padding:0;background:#${theme.background.rgb()};color:#${theme.primaryText.rgb()};}" +
            themeBgCss +
            "body{padding:${settings.margins.top}px ${settings.margins.right}px ${settings.margins.bottom}px ${settings.margins.left}px$imp;" +
            "$typographyCss$alignCss$colorCss$hyphenCss}" +
            elementForceCss +
            HighlightPaint.css +
            "$columns img{max-width:100%;height:auto;break-inside:avoid;}a{color:inherit;text-decoration:none;}a[href^=\"http\"],a[href^=\"mailto\"]{color:#${theme.link.rgb()};}</style>"
    return if (html.contains("</head>", ignoreCase = true)) html.replaceFirst(Regex("(?i)</head>"), "$css</head>") else "$css$html"
}
