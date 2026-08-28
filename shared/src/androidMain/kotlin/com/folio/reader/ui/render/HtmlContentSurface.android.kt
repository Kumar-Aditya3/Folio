package com.folio.reader.ui.render

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveResource: suspend (chapterHref: String, src: String) -> String?
) {
    val currentTapHandler = rememberUpdatedState(onTap)
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
    var pendingFraction by remember { mutableStateOf(0.0) }
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
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Inject measurement JS only after page is laid out. Fallback if pendingJs already set.
                pendingJs?.let { js ->
                    view.evaluateJavascript(js, null)
                }
            }
        }
    }
    // Page bridging must post to main thread — @JavascriptInterface is called on WebView background thread.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val progressBridge = remember(onProgress, onPageChange, onChapterEnd) {
        object {
            @JavascriptInterface
            fun report(fraction: Float, current: Int, total: Int, userCrossed: Boolean) {
                mainHandler.post {
                    onProgress(fraction.coerceIn(0f, 1f))
                    onPageChange(current.coerceAtLeast(1), total.coerceAtLeast(1))
                    if (userCrossed && fraction >= 0.995f) onChapterEnd()
                }
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                getSettings().javaScriptEnabled = true
                getSettings().domStorageEnabled = true
                getSettings().allowFileAccess = true
                getSettings().allowContentAccess = false
                getSettings().useWideViewPort = true
                getSettings().loadWithOverviewMode = true
                addJavascriptInterface(progressBridge, "FolioReader")
                webViewClient = client
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
                val paginated = settings.layoutMode == com.folio.reader.settings.LayoutMode.PAGINATED
                // Build JS that uses scrollingElement and forces layout-aware measurement.
                // Do NOT evaluate here — defer to onPageFinished + post to ensure layout.
                val js = """(function(){
                            var paginated=$paginated,scheduled=false,last=0,userCrossed=false;
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
                                var current=paginated?Math.min(total,Math.floor(offset/vw)+1):Math.min(total,Math.floor(offset/vh)+1);
                                FolioReader.report(range>0?Math.min(1,offset/range):0,current,total,userCrossed);
                            }
                            function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(report);}}
                            window.addEventListener('touchmove',function(){userCrossed=true},{passive:true});
                            window.addEventListener('wheel',function(){userCrossed=true},{passive:true});
                            window.addEventListener('scroll',schedule,{passive:true});
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
                pendingJs = js
                pendingFraction = fraction
                webView.loadDataWithBaseURL("file:///folio/$chapterHref", "<style>$importedFonts</style>$content", "text/html", "UTF-8", null)
                // Do not evaluate immediately — onPageFinished will inject. Keep a post fallback for WebView that doesn't trigger onPageFinished.
                webView.postDelayed({
                    if (webView.tag == contentKey) {
                        webView.evaluateJavascript(js, null)
                    }
                }, 400)
            } else if (resourcesReady) {
                // Content already loaded but position may have changed (scroll restore without reload).
                // Re-trigger report with updated fraction if needed.
            }
        }
    )
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
    val columns = if (settings.layoutMode == com.folio.reader.settings.LayoutMode.PAGINATED)
        "column-width: 100vw; column-gap: 0; column-fill: auto; height: 100vh; overflow-x: auto; overflow-y: hidden;"
    else
        "html,body{height:auto !important;min-height:100% !important;overflow-y:visible !important;} html{overflow-y:auto !important;}"
    val fontFamily = settings.customFonts.firstOrNull { it.name == settings.fontFamily }?.familyName ?: settings.fontFamily
    val css = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style id=\"folio-reader-style\">html,body{margin:0;padding:0;background:#${theme.background.toUInt().toString(16).padStart(8,'0').drop(2)};color:#${theme.primaryText.toUInt().toString(16).padStart(8,'0').drop(2)};font-family:'$fontFamily',serif;font-size:${settings.fontSize}px;font-weight:${settings.fontWeight};line-height:${settings.lineHeight};letter-spacing:${settings.letterSpacing}px;text-align:$align;}body{padding:${settings.margins.top}px ${settings.margins.right}px ${settings.margins.bottom}px ${settings.margins.left}px;}$columns img{max-width:100%;height:auto;break-inside:avoid;}a{color:#${theme.link.toUInt().toString(16).padStart(8,'0').drop(2)};}</style>"
    return if (html.contains("</head>", ignoreCase = true)) html.replaceFirst(Regex("(?i)</head>"), "$css</head>") else "$css$html"
}
