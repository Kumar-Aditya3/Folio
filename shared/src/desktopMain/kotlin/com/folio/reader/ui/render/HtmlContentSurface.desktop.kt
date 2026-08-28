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
    onTap: () -> Unit,
    onLinkClick: ((String) -> Unit)?,
    onResolveResource: suspend (chapterHref: String, src: String) -> String?
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
    callbacks.onTap = onTap
    callbacks.onLinkClick = onLinkClick

    val resolver by rememberUpdatedState(onResolveResource)
    val positionState by rememberUpdatedState(position)

    var session by remember { mutableStateOf<JcefSession?>(null) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var resolvedHtml by remember(html, chapterHref) { mutableStateOf<String?>(null) }

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
        preparing = true
        val result = withContext(Dispatchers.IO) {
            runCatching { resolveResources(html, chapterHref) { href, src -> resolver(href, src) } }
        }
        result
            .onSuccess { resolvedHtml = it }
            .onFailure { fatalError = it.message ?: "Unable to prepare chapter" }
    }

    // 3) Apply reader styling, write the document and load it. Runs for every
    //    settings change too so typography/theme updates apply live.
    LaunchedEffect(resolvedHtml, settings, session) {
        val current = session ?: return@LaunchedEffect
        val source = resolvedHtml ?: return@LaunchedEffect
        preparing = true
        val fraction = (positionState?.scrollOffset ?: 0.0).toFloat().coerceIn(0f, 1f)
        val paginated = settings.layoutMode == com.folio.reader.settings.LayoutMode.PAGINATED
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val styled = injectReaderCss(source, settings)
                current.writeDocument(styled).toFileUrl()
            }
        }
        result.onSuccess { url ->
            current.load(url, readerBridgeJs(fraction, paginated))
            preparing = false
        }.onFailure {
            fatalError = it.message ?: "Unable to render chapter"
            preparing = false
        }
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
        } else if (!enabled) {
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
    @Volatile var onTap: () -> Unit = {}
    @Volatile var onLinkClick: ((String) -> Unit)? = null
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
                        if (parts[4] == "true") callbacks.onChapterEnd()
                    }

                    t.startsWith("folio-link:") -> {
                        val encoded = t.removePrefix("folio-link:").substringAfter(':', "")
                        val href = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return
                        callbacks.onLinkClick?.invoke(href)
                    }

                    t.startsWith("folio-tap:") -> callbacks.onTap()
                }
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
        "calluna", "shancalluna" -> return "'Shancalluna', Calluna, Georgia, serif"
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
private fun injectReaderCss(html: String, settings: ReaderSettings): String {
    val theme = settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)

    fun Int.rgb(): String = toUInt().toString(16).padStart(8, '0').drop(2)

    val align = when (settings.alignment) {
        com.folio.reader.settings.TextAlignment.CENTER -> "center"
        com.folio.reader.settings.TextAlignment.JUSTIFIED -> "justify"
        else -> "left"
    }
    val paginated = settings.layoutMode == com.folio.reader.settings.LayoutMode.PAGINATED

    val layoutCss = when (settings.layoutMode) {
        com.folio.reader.settings.LayoutMode.PAGINATED ->
            "html{height:100vh;overflow:hidden;}" +
                    "body{height:100vh;overflow-x:auto;overflow-y:hidden;" +
                    "column-width:100vw;column-gap:0;column-fill:auto;}"

        com.folio.reader.settings.LayoutMode.TWO_COLUMN ->
            "body{columns:2;column-gap:${(settings.fontSize * 2).toInt()}px;}"

        else -> "" // CONTINUOUS and FOCUS scroll vertically
    }

    // Line-length cap for scrolling modes, mirroring the phone reader's readerWidth.
    val widthCss = if (!paginated) {
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

    // Publisher CSS frequently paints pages white through class selectors on body or
    // wrapper divs, which outranks the element-level theme background and leaves
    // light theme text invisible on dark themes. Outside ORIGINAL mode the chosen
    // theme owns the page color.
    val themeBgCss = if (original) "" else
        "body,body div,body section,body article,body figure{background-color:transparent !important;}"

    val requested = settings.customFonts.firstOrNull { it.name == settings.fontFamily }?.familyName
        ?: settings.fontFamily
    val family = fontStackFor(requested)

    val fontFaces = settings.customFonts.mapNotNull { font ->
        val file = fontCandidates(font.fileName).firstOrNull { it.exists() } ?: return@mapNotNull null
        val format = if (font.fileName.endsWith(".otf", true)) "opentype" else "truetype"
        "@font-face{font-family:'${font.familyName}';src:url('${file.toFileUrl()}') format('$format');" +
                "font-weight:${font.weight};font-style:normal;font-display:swap;}"
    }.joinToString("")

    // Publisher CSS often redeclares body typography through class selectors
    // (body.calibre and friends), outranking element rules. Outside ORIGINAL the
    // reader owns these properties on body; publisher rules on individual elements
    // (centered headings, special paragraphs) still win over inheritance.
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

    val css = "<meta charset=\"utf-8\"/><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            (if (fontFaces.isNotEmpty()) "<style>$fontFaces</style>" else "") +
            "<style id=\"folio-reader-style\">" +
            "html,body{margin:0;padding:0;background:#${theme.background.rgb()};color:#${theme.primaryText.rgb()};}" +
            themeBgCss +
            "body{padding:${settings.margins.top}px ${settings.margins.right}px ${settings.margins.bottom}px ${settings.margins.left}px !important;" +
            "$typographyCss$alignCss$colorCss$hyphenCss}" +
            layoutCss +
            widthCss.replace("margin-left:auto;margin-right:auto;", "margin-left:auto !important;margin-right:auto !important;") +
            normalizedExtra +
            "h1,h2,h3,h4,h5,h6{color:#${theme.headingText.rgb()};}" +
            "img{max-width:100%;height:auto;break-inside:avoid;}" +
            "a{color:#${theme.link.rgb()};}" +
            "</style>"
    return if (html.contains("</head>", true)) html.replaceFirst(Regex("(?i)</head>"), "$css</head>") else "$css$html"
}

/**
 * Page-side bridge: restores the saved scroll position, reports progress/page
 * counts (only when they change), forwards link clicks and center taps back to
 * the app via document.title.
 */
private fun readerBridgeJs(fraction: Float, paginated: Boolean): String = """
(function(){
  if(window.__folioBridgeInstalled)return;
  window.__folioBridgeInstalled=true;
  var paginated=$paginated;
  var nonce=0;
  // Paginated mode scrolls the body horizontally (it owns the columns);
  // continuous mode scrolls the document vertically.
  function pickScroller(){return paginated?document.body:(document.scrollingElement||document.documentElement);}
  var scroller=pickScroller();
  if(paginated){scroller.scrollLeft=Math.max(0,scroller.scrollWidth-scroller.clientWidth)*$fraction;}
  else{scroller.scrollTop=Math.max(0,scroller.scrollHeight-scroller.clientHeight)*$fraction;}
  var scheduled=false,last=0,lastSig='',userCrossed=false;
  var restorePending=$fraction>0.001;
  function measure(){
    scheduled=false;
    var now=Date.now();
    if(now-last<100){schedule();return;}
    last=now;
    var d=document.documentElement,b=document.body||d;
    var s=pickScroller();
    var vw=Math.max(1,s.clientWidth||window.innerWidth||1);
    var vh=Math.max(1,s.clientHeight||window.innerHeight||1);
    var docW=Math.max(s.scrollWidth,d.scrollWidth,b.scrollWidth||0);
    var docH=Math.max(s.scrollHeight,d.scrollHeight,b.scrollHeight||0);
    var range=paginated?Math.max(0,docW-vw):Math.max(0,docH-vh);
    if(restorePending&&range>4){
      if(paginated){s.scrollLeft=range*$fraction;}else{s.scrollTop=range*$fraction;}
      if((paginated?(s.scrollLeft||0):(s.scrollTop||0))>0)restorePending=false;
    }
    var offset=paginated?(s.scrollLeft||window.scrollX):(s.scrollTop||window.scrollY);
    var total=paginated?Math.max(1,Math.ceil(docW/vw)):Math.max(1,Math.ceil(docH/vh));
    var current=paginated?Math.min(total,Math.floor(offset/vw)+1):Math.min(total,Math.floor(offset/vh)+1);
    var p=range>0?Math.min(1,offset/range):0;
    var crossed=userCrossed&&p>=0.995;
    var sig=p.toFixed(3)+':'+current+':'+total+':'+crossed;
    if(sig!==lastSig){lastSig=sig;document.title='folio-progress:'+p.toFixed(4)+':'+current+':'+total+':'+crossed;}
  }
  function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(measure);}}
  function flipPage(dir){
    var s=document.body;
    var vw=Math.max(1,s.clientWidth);
    var max=Math.max(0,s.scrollWidth-vw);
    var cur=Math.round(s.scrollLeft/vw);
    var next=Math.min(Math.max(0,cur+dir),Math.ceil(max/vw));
    s.scrollTo({left:next*vw,behavior:'smooth'});
    schedule();
  }
  var wheelAcc=0;
  window.addEventListener('wheel',function(e){
    userCrossed=true;restorePending=false;
    if(!paginated)return;
    // Discrete pages instead of Chromium's native horizontal slide.
    e.preventDefault();
    var delta=Math.abs(e.deltaX)>Math.abs(e.deltaY)?e.deltaX:e.deltaY;
    wheelAcc+=delta;
    if(wheelAcc>60){flipPage(1);wheelAcc=0;}
    else if(wheelAcc<-60){flipPage(-1);wheelAcc=0;}
  },{passive:false});
  if(paginated){
    document.addEventListener('keydown',function(e){
      var k=e.key;
      if(k==='ArrowRight'||k==='PageDown'||k===' '||k==='ArrowDown'){e.preventDefault();flipPage(1);}
      else if(k==='ArrowLeft'||k==='PageUp'||k==='ArrowUp'){e.preventDefault();flipPage(-1);}
    },true);
  }
  window.addEventListener('scroll',schedule,{passive:true});
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
