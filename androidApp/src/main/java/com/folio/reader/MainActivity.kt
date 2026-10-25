package com.folio.reader

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.folio.reader.importer.IncomingContent
import com.folio.reader.importer.IncomingContentResult
import com.folio.reader.importer.isAlreadyInLibrary
import com.folio.reader.importer.isNewImport
import com.folio.reader.model.Document
import com.folio.reader.nav.FolioDestination
import com.folio.reader.nav.FolioNavCallbacks
import com.folio.reader.nav.FolioNavHost
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.nav.FolioNavShell
import com.folio.reader.ui.theme.FolioThemeBackground
import com.folio.reader.ui.components.folioPredictiveBackScale
import com.folio.reader.ui.components.folioRoomSource
import com.folio.reader.ui.theme.folioLamp
import com.folio.reader.ui.theme.folioRoomScrim
import com.folio.reader.nav.FolioRoutes
import com.folio.reader.nav.changeMangaDownloadsLocation
import com.folio.reader.nav.handleAnnotationsExport
import com.folio.reader.nav.handleBackupRestore
import com.folio.reader.nav.handleFontImport
import com.folio.reader.nav.handleFullBackupExport
import com.folio.reader.nav.handleMangaBackupExport
import com.folio.reader.nav.handleMangaBackupImport
import com.folio.reader.ui.library.LibraryMode
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FontTheme
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.toFolioColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val FONT_MIMES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-opentype",
    "application/font-woff",
    "application/octet-stream"
)

// Import pickers run as GET_CONTENT with a wildcard type: the ACTION_OPEN_DOCUMENT
// picker filtered by a specific MIME (epub+zip, x-cbz) made its built-in search
// both slow (ExternalStorageProvider walks storage recursively) and lossy (EPUBs
// are frequently indexed as octet-stream, so they never matched the filter). The
// content chooser's search goes through the indexed MediaStore surfaces instead,
// and the import pipeline itself detects format from bytes and rejects
// unsupported files with a status message, so no filter is lost.
private const val IMPORT_PICKER_MIME = "*/*"

private const val MAX_INCOMING_SOURCE_BYTES = 512L * 1024L * 1024L
private const val MAX_OFFICE_SOURCE_BYTES = 100L * 1024L * 1024L

class MainActivity : ComponentActivity() {

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var importStatus by mutableStateOf("")
    /** Imported books waiting for the collection picker; non-null shows the dialog. */
    internal var pendingImportBooks by mutableStateOf<List<com.folio.reader.model.Book>?>(null)
    /** First imported item's route, opened once the shelf prompt is done. */
    private var pendingImportRoute: String? = null
    internal lateinit var navModel: FolioNavModelImpl
    private var pendingOpenRoute: String? = null

    override fun onDestroy() {
        super.onDestroy()
        // Only a real exit may close the graph: a configuration-change relaunch
        // destroys the activity too, and shutdown() stops sync and drops the
        // database connection under the surviving view models.
        if (isFinishing) {
            // Cancel this activity's scope so its in-flight coroutines don't outlive it. Guarded
            // by isFinishing for the same reason as shutdown(): a config-change relaunch must keep
            // any work the user started (e.g. an import) alive on the next instance.
            appScope.cancel()
            (application as? FolioApplication)?.graph?.shutdown()
        }
    }

    /**
     * The reader WebView already declines ActionMode, but OEM skins can raise the
     * selection toolbar (Copy / Share / Select all) at the window level, where it
     * lands on top of Folio's own Highlight button. Stripping the menu leaves the
     * selection and its handles working with no competing toolbar.
     */
    override fun onActionModeStarted(mode: android.view.ActionMode) {
        super.onActionModeStarted(mode)
        runCatching { mode.menu?.clear() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as FolioApplication).graph
        // Once per process, not once per Activity: a rotation / theme / font-scale relaunch must
        // not re-run a full library scan or re-hit the DB for the idempotent backfills. The guard
        // lives on the graph, so process-death restore (fresh graph) still runs them.
        graph.runStartupTasks(appScope)

        setContent {
            val navController = rememberNavController()
            // The nav model survives configuration changes inside a ViewModel
            // holder: rebuilding it per relaunch reset every tab view model and
            // flow mid-rotation — the shelves flashed the empty state ("library
            // empty screen flashes" / "manga appear and disappear") and each
            // rebuild leaked its own update-loop scope. Rebind re-points the
            // model at the new activity instance instead.
            val holder: NavModelHolder = viewModel()
            val model = holder.model ?: FolioNavModelImpl(this@MainActivity).also { holder.model = it }
            model.rebind(this@MainActivity)
            SideEffect {
                navModel = model
                model.navController = navController
                pendingOpenRoute?.let { route ->
                    pendingOpenRoute = null
                    navController.navigate(route)
                }
            }

            // ── Document pickers (bodies live in nav/FolioNavModelImporters.kt) ──
            val pickContent = rememberLauncherForActivityResult(
                ActivityResultContracts.GetMultipleContents()
            ) { uris -> importContentUris(uris) }
            val pickMangaArchives = rememberLauncherForActivityResult(
                ActivityResultContracts.GetMultipleContents()
            ) { uris -> importMangaUris(uris) }
            val pickMangaFolder = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) importMangaFolderUri(uri) }
            val pickMangaBackup = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.handleMangaBackupImport(uri) }
            val exportMangaBackup = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri -> if (uri != null) model.handleMangaBackupExport(uri) }
            val pickFont = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.handleFontImport(uri) }
            val backupExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri -> if (uri != null) model.handleFullBackupExport(uri) }
            val backupImportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.handleBackupRestore(uri) }
            val annotationsExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("*/*")
            ) { uri -> if (uri != null) model.handleAnnotationsExport(uri) }
            val pickMangaDlDir = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) changeMangaDownloadsLocation(uri) {
                    model.mangaDownloadsLocation = graph.mangaDownloadManager.storageDescription()
                }
            }

            // §16 the in-window overlay layer (issue 3). Created *here*, above `callbacks`, so the
            // model's own actions can raise a pane as well as the screens — the "Import manga"
            // chooser below used to be a platform `AlertDialog`, which is a second window and
            // therefore cannot blur. Published on the theme's provider chain further down, which is
            // where the host (and every screen) reads it from.
            val overlayController =
                com.folio.reader.ui.components.rememberFolioOverlayController()
            // The "Import manga" chooser's open flag. It is a plain state flag rather than a pane
            // raised straight from the callback because the callback is not a composable scope —
            // the `FolioDialog` below is what turns the flag into a pane.
            var mangaImportChooserOpen by remember { mutableStateOf(false) }

            val callbacks = object : FolioNavCallbacks {
                override fun onImportContent() = pickContent.launch(IMPORT_PICKER_MIME)
                override fun onImportMangaArchives() = pickMangaArchives.launch(IMPORT_PICKER_MIME)
                override fun onImportMangaFolder() = pickMangaFolder.launch(null)
                override fun onImportMangaChoice() {
                    // §16: the app's own in-window pane, not a platform `AlertDialog`. A platform
                    // dialog is a *second window*, so it can never blur the room behind it — this
                    // was the last genuinely opaque overlay in the app (see FolioOverlayHost).
                    mangaImportChooserOpen = true
                }
                override fun onImportMangaBackup() = pickMangaBackup.launch(arrayOf("*/*"))
                override fun onExportMangaBackup() = exportMangaBackup.launch("folio_manga.backup")
                override fun onImportFont() = pickFont.launch(FONT_MIMES)
                override fun onExportBackup() = backupExportLauncher.launch("folio-backup.json")
                override fun onImportBackup() = backupImportLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
                override fun onExportAnnotations(format: String) =
                    annotationsExportLauncher.launch("annotations.$format")
                override fun onPickMangaDownloadsLocation() = pickMangaDlDir.launch(null)
                override fun onShareBooks(bookIds: Set<String>) = shareBooks(bookIds)
                override fun onShareDocuments(documentIds: Set<String>) = shareDocuments(documentIds)
                override fun onShareEpub(bookId: String) = shareEpub(bookId)
            }
            model.callbacks = callbacks

            // The global settings row is warmed before the first frame, from the
            // activity's own scope, rather than one frame late.
            //
            // `globalSettings` starts as `ReaderSettings()` — every default — and
            // the row is a single small key/value read. Loading it from a
            // `LaunchedEffect` here walked straight into the classic ordering trap:
            // the effect runs *after* the frame it is keyed to, so frame one — the
            // frame on which the nav host resolves any route that depends on a
            // setting — always ran on the defaults.
            //
            // `morphIntoReader` is the sharpest case and the one that was reported.
            // It defaults to **false**, so a reader route composed on frame one read
            // `morphBookId = null` and published no cover key; the destination then
            // had nothing to pair with and the morph silently did not run. That is
            // the report "if the books aren't loaded the morph doesn't work" — the
            // books were never the dependency, the settings read was.
            //
            // `warmGlobalSettings()` starts the read on the activity scope before
            // composition, so by the time a shelf hands a cover over the flag is
            // already the reader's own. The `runCatching` lives in the model, so a
            // corrupt row still cannot take launch down.
            SideEffect { model.warmGlobalSettings() }

            // Success statuses auto-clear after 3 seconds; in-progress and error
            // messages persist until the next status replaces them.
            LaunchedEffect(importStatus) {
                if (com.folio.reader.ui.components.isTransientStatus(importStatus)) {
                    kotlinx.coroutines.delay(3000)
                    importStatus = ""
                }
            }

            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route.orEmpty()
            // The bar is visible on exactly the 4 top-level routes (§3.3).
            val showBottomBar = currentRoute in FolioRoutes.BAR_ROUTES

            // §16 predictive back: the gesture's progress drives the chrome —
            // the capsule and status banner recede as the swipe grows, and snap
            // back if it is abandoned. The committed action is the same back
            // walk the plain handler performed; below API 33 the opt-in is inert
            // and this behaves as an ordinary back handler.
            var backProgress by remember { mutableStateOf(0f) }

            // Back walks back through states instead of exiting: an active bulk
            // selection (manga or books) clears first, then pushed screens pop,
            // an open shelf search closes, Manga returns to Books, and only at
            // the Books root does back exit the app.
            fun onBackWalked() {
                when {
                    model.mangaLibVM.isSelectionMode.value -> model.mangaLibVM.clearSelection()
                    model.libraryVM.isSelectionMode.value -> model.libraryVM.clearSelection()
                    model.documentLibraryVM.isSelectionMode.value ->
                        model.documentLibraryVM.clearSelection()
                    model.activeMangaDetailVM?.chapterSelectionMode?.value == true ->
                        model.activeMangaDetailVM?.clearChapterSelection()
                    currentRoute == FolioRoutes.MANGA_BROWSE && model.mangaBrowseVM.searchActive.value ->
                        model.mangaBrowseVM.exitSearch()
                    navController.popBackStack() -> Unit
                    model.mangaSearchActive -> model.mangaSearchActive = false
                    model.bookSearchActive -> model.bookSearchActive = false
                    model.documentSearchActive -> model.documentSearchActive = false
                    model.libraryMode == LibraryMode.MANGA ||
                        model.libraryMode == LibraryMode.DOCUMENTS ->
                        model.libraryMode = LibraryMode.BOOKS
                    else -> finish()
                }
            }
            androidx.activity.compose.PredictiveBackHandler { progress ->
                try {
                    progress.collect { event -> backProgress = event.progress }
                    // The flow completing is the commit.
                    onBackWalked()
                    backProgress = 0f
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // The gesture was abandoned — snap the chrome back.
                    backProgress = 0f
                }
            }

            // The app chrome follows the app's own light/dark choice. A reading theme
            // describes the page and nothing else — feeding themeId in here is what
            // made the two bleed into each other.
            val isReadingScreen =
                currentRoute == FolioRoutes.READER ||
                    currentRoute == FolioRoutes.DOCUMENT_READER ||
                    currentRoute == FolioRoutes.MANGA_READER
            val appPalette = AppPalette.byId(model.globalSettings.appThemeId)
            // §16 Material You: the wallpaper-derived schemes are still published so
            // anything that reads `LocalDynamicSchemes` keeps working, but no palette
            // resolves through them any more — the System pack that used to is gone.
            val dynamicSchemes = com.folio.reader.ui.theme.rememberDynamicSchemes()
            // A custom theme replaces the pack's colours wholesale; its own
            // background lightness, not the pack's, decides the app's polarity.
            val customAppTheme = model.globalSettings.customAppTheme
            val appColors = remember(customAppTheme, appPalette) {
                customAppTheme?.toFolioColors() ?: appPalette.colors
            }
            val appDark = customAppTheme?.isDark ?: appPalette.isDark
            // The theme pack's identity, resolved once per palette. The guilloche plate
            // derives its whole figure from this string — its wave frequencies, braid
            // depths and angles — so every pack draws its own interference pattern rather
            // than the same plate at a different density. Resolved through ThemePack
            // exactly as `folioLightFor` does, so a pack's two faces share one identity
            // and flipping light/dark does not redraw the engraving.
            val packId = remember(appPalette) {
                com.folio.reader.ui.theme.ThemePack.ALL.firstOrNull {
                    it.lightAppPaletteId == appPalette.id || it.darkAppPaletteId == appPalette.id
                }?.id ?: "gallery"
            }
            // §16 liquid glass: the capability verdict for this device, provided
            // once at the root so every surface reads it instead of probing the
            // platform. Blur floor is API 32 (Haze disables 31 for RenderNode
            // invalidation issues); low-RAM devices opt out; the preference is
            // the user's. Desktop never enters this file, so its tree keeps
            // GlassCapabilities.None — today's look, byte-for-byte.
            val glassCapabilities = remember(model.globalSettings.liquidGlassEffects) {
                val activityManager = getSystemService(android.app.ActivityManager::class.java)
                com.folio.reader.ui.components.glassCapabilitiesFor(
                    platformBlurSupported = android.os.Build.VERSION.SDK_INT >= 32,
                    lowRamDevice = activityManager?.isLowRamDevice == true,
                    liquidGlassEffects = model.globalSettings.liquidGlassEffects,
                    // The device-class term the ladder was missing. `isLowRamDevice` is a
                    // 512MB-class flag and is false on every phone the "extremely laggy
                    // with liquid glass on" report came from, so until now a 2GB device
                    // and a flagship paid the identical full-area blur per surface per
                    // frame. The memory class is the same platform verdict at usable
                    // granularity, and it buys a coarser *resolution* rather than a
                    // switched-off material — see GlassCapabilities.budget.
                    memoryClassMb = activityManager?.memoryClass ?: 0,
                )
            }
            // Wide-gamut output. This is a platform switch, not a Compose one: raise
            // it and a Display-P3-tagged cover is composited at the saturation it was
            // authored with, instead of being mapped into sRGB by the window before it
            // ever reaches the panel. `android:configChanges="colorMode"` keeps the
            // change off the recreation path, so the preference lands on the next
            // frame rather than through a relaunch. The display's own verdict is
            // checked first because on a panel that cannot show more than sRGB the
            // system remaps either way — the request would buy a configuration change
            // and no picture.
            LaunchedEffect(model.globalSettings.wideGamutColor) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return@LaunchedEffect
                @Suppress("DEPRECATION")
                val wide = model.globalSettings.wideGamutColor &&
                    windowManager.defaultDisplay.isWideColorGamut
                window.colorMode = if (wide) {
                    android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                } else {
                    android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
                }
            }
            // A source behind an interactive bot check needs a window with a finger in
            // it, which an OkHttp interceptor does not have. Registering the opener
            // here (and dropping it on dispose) is what lets shared browse code offer
            // "solve in browser view" without knowing anything about Android.
            androidx.compose.runtime.DisposableEffect(appPalette, model.globalSettings.fontThemeId) {
                com.folio.reader.manga.MangaChallenges.solver = { challenge ->
                    startActivity(
                        com.folio.reader.manga.ChallengeWebViewActivity.intent(
                            context = this@MainActivity,
                            challenge = challenge,
                            paletteId = appPalette.id,
                            fontThemeId = model.globalSettings.fontThemeId,
                        )
                    )
                }
                // A blocked request waits for the window it opened. Android drops activity
                // starts from the background without telling anyone, so say when one is
                // possible rather than leaving the request to time out.
                com.folio.reader.manga.MangaChallenges.canShowWindow = {
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                }
                onDispose {
                    com.folio.reader.manga.MangaChallenges.solver = null
                    com.folio.reader.manga.MangaChallenges.canShowWindow = null
                }
            }
            LaunchedEffect(isReadingScreen, appDark) {
                // Outside the reader the status bar sits on the *page*, whose scrim
                // now follows the palette, so the icons have to invert with it:
                // dark icons over a light theme, light icons over a dark one.
                // Readers own their bars.
                if (!isReadingScreen) {
                    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                        .isAppearanceLightStatusBars = !appDark
                }
            }

            // §16: one backdrop registry for the whole shell — every screen
            // attaches its scrolling child as the source, and the capsule, the
            // mastheads and the status banner blur against it.
            com.folio.reader.ui.components.FolioGlassRoot(
                capabilities = glassCapabilities,
                inFlowGlass = !model.globalSettings.minimalLiquidGlass,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.folio.reader.ui.theme.LocalDynamicSchemes provides dynamicSchemes,
                ) {
                    FolioTheme.AppTheme(
                        palette = appPalette,
                        fontTheme = FontTheme.byId(model.globalSettings.fontThemeId),
                        colors = appColors,
                        isDark = appDark,
                        opacity = model.globalSettings.surfaceOpacity(),
                        // The Muted ↔ Vivid lever: provided through AppTheme so the Material
                        // bridge's planes and every screen's atmosphere read the user's setting.
                        vibrancy = com.folio.reader.ui.theme.FolioVibrancy(model.globalSettings.vibrancy),
                        // The metallic-sheen lever: provided through AppTheme so every
                        // paint site — surfaces, films, masthead, capsule, lamp — reads it.
                        sheen = com.folio.reader.ui.theme.FolioSheen(model.globalSettings.metallicSheen),
                        // The reader's motion preferences, resolved inside AppTheme — the
                        // app's true root on both platforms — so one `LocalMotion` serves
                        // every animation in the tree and Android and desktop cannot drift
                        // apart. See ui/theme/Motion.kt.
                        motion = model.globalSettings.motion,
                    ) {
                    // §17 living glass: the room's slow animated light, provided
                    // once at the Android root. Every material's sheen and the
                    // field's pools read it in the draw phase, so a resting page
                    // still has something travelling for the liquid glass to
                    // catch. Desktop and previews never provide it and keep the
                    // still room — today's look, byte-for-byte. Reduce-motion
                    // freezes it at neutral (rememberAmbientLight).
                    val folioAmbientTint = com.folio.reader.ui.theme.rememberFolioAmbientTint()
                    // The appearance switch, honoured once here rather than on every
                    // screen: with the feature off the holder is not provided, so each
                    // `FolioAmbientSource` call becomes a no-op, and the field and the
                    // backdrop are handed an always-null colour, so the room, the bars
                    // and the gel's refraction untint together and nothing stale can
                    // survive the switch.
                    val ambientOn = model.globalSettings.ambientColor
                    val ambientOff = remember {
                        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.Color?>(
                            null
                        )
                    }
                    val ambientShown = if (ambientOn) folioAmbientTint.shown else ambientOff
                    // The lamp. Its colour follows the ambient switch, and its
                    // position is written by whichever cover the current screen is
                    // featuring. Drawn with Canvas rather than AGSL, so it is not
                    // capability-gated: an API 24 phone gets the same light as a 33
                    // one. With the ambient colour off it lights the room in the
                    // palette's own accent rather than going dark — the room always
                    // has a source, the switch decides whose.
                    val folioLampAnchor = remember {
                        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Offset?>(
                            null
                        )
                    }
                    val lampFallback = FolioTheme.colors.accentProgress
                    // The pack's own light: where its source sits, how hard it burns,
                    // and how much of its colour it is willing to lend the book. The
                    // lamp glows in the theme's primary, which is what stops a light
                    // laid over every theme from overwriting the theme.
                    val folioLight = com.folio.reader.ui.theme.folioLightFor(appPalette)
                    val folioCoverLightState = remember {
                        androidx.compose.runtime.mutableStateOf<com.folio.reader.ui.components.CoverLight?>(
                            null
                        )
                    }
                    val folioLamp = com.folio.reader.ui.theme.rememberFolioLamp(
                        light = androidx.compose.runtime.derivedStateOf {
                            ambientShown.value ?: lampFallback
                        },
                        at = folioLampAnchor,
                        authored = folioLight,
                        themeColor = FolioTheme.colors.primary,
                        coverLight = folioCoverLightState,
                    )
                    // The room's closed form, published beneath the field so a
                    // refracting surface can bend what is actually behind it instead
                    // of what it contains. The field's pixel size arrives from the
                    // Box below as a state, so measuring it never recomposes anything.
                    val folioAtmos = FolioTheme.atmosphere
                    val folioFieldPx = remember {
                        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
                    }
                    val folioBackdrop = com.folio.reader.ui.theme.FolioBackdrop(
                        atmos = folioAtmos,
                        strength = folioAtmos.fieldTintStrength,
                        shift = com.folio.reader.ui.theme.LocalFolioDaylight.current.let {
                            it.azimuth * it.intensity
                        },
                        tint = ambientShown,
                        ambient = com.folio.reader.ui.theme.LocalFolioAmbient.current,
                        fieldSize = folioFieldPx,
                        lamp = folioLamp,
                    )
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.folio.reader.ui.theme.LocalFolioAmbient provides
                            com.folio.reader.ui.theme.rememberAmbientLight(),
                        // The room is lit by what is being read. One field sits under
                        // every screen, and a screen cannot hand a colour *up* to the
                        // ground it stands on — so it writes into this holder and the
                        // field below reads it in its draw pass.
                        com.folio.reader.ui.theme.LocalFolioAmbientTint provides
                            if (ambientOn) folioAmbientTint else null,
                        com.folio.reader.ui.theme.LocalFolioBackdrop provides folioBackdrop,
                        com.folio.reader.ui.theme.LocalFolioLamp provides folioLamp,
                        com.folio.reader.ui.theme.LocalFolioLight provides folioLight,
                        com.folio.reader.ui.theme.LocalFolioCoverLight provides
                            if (ambientOn) folioCoverLightState else null,
                        com.folio.reader.ui.theme.LocalFolioLampAnchor provides folioLampAnchor,
                        // Where the guilloche plate must be *exactly* absent. Screens
                        // register their bare-field text with `Modifier.folioGuillocheQuiet`;
                        // the plate reads the regions in its draw pass. A line pattern
                        // behind glyphs ragges — a thin stroke landing on a thin stroke —
                        // and that is a shape problem no contrast ratio can see, so the
                        // guarantee has to be categorical. The reader publishes a
                        // full-page region, which is what keeps paper plain.
                        com.folio.reader.ui.theme.LocalFolioGuillocheQuiet provides
                            com.folio.reader.ui.theme.rememberFolioGuillocheQuiet(),
                        // And the flag surfaces consult before spending the gel's second
                        // full-surface shader pass: a lens rim only pays over something with
                        // detail to bend, and the plate is that detail. Must agree with the
                        // `enabled` below term-for-term, so both read one value.
                        //
                        // Forced false. `Modifier.folioGuilloche` — the plate itself — has **no
                        // call site anywhere in the app**, so there is no engraving on screen for
                        // the gel to bend; it was refracting a closed-form figure nobody paints,
                        // which is a rim derived from something that is not there. That is a
                        // wrong edge on every blurred pane, and a second full-surface shader pass
                        // per pane to draw it. The flag stays a flag (the quiet-region machinery
                        // and the gel's uniforms are still wired to it) so the plate can be
                        // switched back on in one place if it ever gets a call site.
                        com.folio.reader.ui.theme.LocalFolioGuillocheActive provides false,
                        // The plate's identity, so a surface that refracts the engraving asks
                        // for the same figure the ground plane is painting instead of a
                        // default. Read by the gel via folioLiquidGlass's platePackId.
                        com.folio.reader.ui.theme.LocalFolioPackId provides packId,
                        // The user's per-theme animation overrides, so FolioThemeBackground can
                        // resolve Plain / a forced family without reaching the settings layer.
                        com.folio.reader.ui.theme.LocalFolioFieldOverrides provides
                            model.globalSettings.fieldOverrides,
                        // §16 the in-window overlay layer. See FolioOverlayHost.
                        com.folio.reader.ui.components.LocalFolioOverlay provides
                            overlayController,
                        // The field's occlusion counter. A route that covers the ground plane
                        // completely and opaquely — the three readers — calls FolioFieldOccluder()
                        // and the field leaves the composition entirely while it is on screen.
                        //
                        // This has to be a composition exit rather than a draw-time skip, and that
                        // is the whole point: the field's clock lives inside Mirage as a
                        // `LaunchedEffect` running `withFrameNanos` every vsync, and a
                        // `LaunchedEffect` only stops when it leaves the composition. Gating the
                        // *draw* would leave a full-rate frame request alive behind an opaque page.
                        // Until this local is provided the mechanism is inert (the default is null,
                        // so rememberFolioFieldVisible() returns true), which is why it is wired
                        // here rather than left to each reader.
                        com.folio.reader.ui.theme.LocalFolioFieldOcclusion provides
                            com.folio.reader.ui.theme.rememberFolioFieldOcclusion(),
                    ) {
                    // The app's ground plane — one unified field behind every screen.
                    // [FolioThemeBackground] paints the theme's vertical atmosphere wash
                    // edge to edge and then this theme's own Mirage animation opaquely over
                    // it, so every tab — Home, Library, Stats, Settings, Search, and whatever
                    // is added next — stands on the same living surface. A screen cannot be
                    // left out by forgetting to call it, and there is no per-screen background
                    // to drift out of sync with this one.
                    //
                    // The layers are explicit siblings so the z-order is unambiguous: the
                    // field is lowest, the lamp (one visible source in the room, anchored to
                    // whichever cover this screen is featuring) sits over the field and its
                    // film, and every screen sits in that light rather than on a flat tint.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { folioFieldPx.value = it }
                    ) {
                        // §16 the **room rung**: the ground plane, published as the lowest
                        // backdrop layer so in-flow glass has something real to frost and
                        // bend. It wraps the field and the lamp and deliberately nothing
                        // else — see folioRoomSource. Two consequences, both load-bearing:
                        //
                        //  • A card inside the page used to resolve *no* backdrop at all
                        //    (Haze excludes the source a surface sits inside), so its blur
                        //    was skipped and only the translucent fill remained. A tint.
                        //    It now resolves this layer.
                        //  • The masthead and the capsule used to blur a mostly-transparent
                        //    scroll container and fall back to a flat colour. They now have
                        //    the real room under the page.
                        //
                        // Nothing glassy may be placed inside this Box: that is what keeps
                        // self-reference impossible by construction rather than by a guard.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .folioRoomSource()
                        ) {
                            FolioThemeBackground(Modifier.matchParentSize())
                            Box(Modifier.matchParentSize().folioLamp(folioLamp))
                            // The **room scrim**: one permanent soft shade over the field and
                            // the lamp and under every screen in the app. See
                            // [com.folio.reader.ui.theme.FolioRoomScrim]. This is its one call
                            // site, and the placement is the whole design:
                            //
                            //  • **Last child of the room rung**, so it is painted over the
                            //    field *and* over the lamp's bloom — the bloom is half of what
                            //    makes bare type swim — and under everything the shell draws.
                            //  • **Inside** folioRoomSource's subtree, so the room Haze records
                            //    is the room the reader is looking at. In-flow glass frosts and
                            //    glassRoomPlate refracts the already-shaded field, so a card
                            //    cannot read brighter than the screen it sits on. Outside, the
                            //    glass would bend the raw field and every pane would glow
                            //    against its own surroundings.
                            //  • It is a pure draw-phase gradient — no Haze effect, no glass, no
                            //    page — so it does not touch this rung's rule that nothing
                            //    glassy may live in its subtree. A drawRect cannot
                            //    self-reference.
                            //
                            // It replaces the per-screen `folioPageWash`, which was opt-in on
                            // eighteen screens and painted in the field's own colour, so the app
                            // had two grounds and neither changed what a reader could see.
                            Box(Modifier.matchParentSize().folioRoomScrim())
                        }
                        FolioNavShell(
                            navController = navController,
                            showBottomBar = showBottomBar,
                            backProgress = backProgress,
                        ) {
                            // §16 predictive back: a pushed screen (reader, detail —
                            // anywhere the bar is hidden) recedes as the back gesture
                            // grows, previewing the pop. Top-level tabs pass 0 so they
                            // never scale; their back exits or switches mode instead.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .folioPredictiveBackScale(
                                        if (showBottomBar) 0f else backProgress
                                    )
                            ) {
                                FolioNavHost(
                                    navController = navController,
                                    navModel = model,
                                    callbacks = callbacks
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = 1f - (backProgress * 0.6f).coerceIn(0f, 1f)
                                }
                                .padding(com.folio.reader.ui.theme.FolioTokens.space3)
                                .navigationBarsPadding(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            com.folio.reader.ui.components.FolioStatusBanner(importStatus)
                        }

                        // Post-import shelf prompt: file the batch into collections
                        // (Main pre-checked — that is where the imports already are),
                        // then open the first imported item.
                        pendingImportBooks?.let { books ->
                            val graph = (application as FolioApplication).graph
                            val shelfCollections by remember(graph) {
                                graph.collectionRepository.getAllCollections()
                            }.collectAsState(initial = emptyList())
                            if (shelfCollections.isNotEmpty()) {
                                com.folio.reader.ui.library.BookImportCollectionsDialog(
                                    bookCount = books.size,
                                    collections = shelfCollections,
                                    onCreate = { name ->
                                        graph.collectionRepository.getCollectionByName(name)?.id
                                            ?: graph.collectionRepository.createCollection(name).id
                                    },
                                    onSave = { ids ->
                                        withContext(Dispatchers.IO) {
                                            val target = ids.ifEmpty {
                                                setOf(requireNotNull(graph.collectionRepository.defaultCollection()) {
                                                    "No default collection is available."
                                                }.id)
                                            }
                                            books.forEach { book -> graph.collectionRepository.assign(book.id, target) }
                                        }
                                    },
                                    onDismiss = {
                                        pendingImportBooks = null
                                        openPendingImportRoute()
                                    }
                                )
                            }
                        }

                        // §16 the in-window overlay layer, and the root Box's **last** child so it
                        // draws over the shell and the status banner. Placement is load-bearing:
                        // inside FolioGlassRoot (so it has the backdrop registry) and *outside*
                        // the room rung above (so its own glass resolves the room and the page
                        // exactly as the masthead does). It is the other half of the rule that
                        // nothing glassy may be placed inside the room rung.
                        //
                        // The "Import manga" chooser: a pane in this window rather than a platform
                        // `AlertDialog` in its own, which is what lets it be glass at all. It sits
                        // here rather than in a screen because the model raises it.
                        com.folio.reader.ui.components.FolioDialog(
                            expanded = mangaImportChooserOpen,
                            onDismissRequest = { mangaImportChooserOpen = false },
                            title = "Import manga",
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { androidx.compose.material3.Text("Archive files") },
                                onClick = {
                                    mangaImportChooserOpen = false
                                    pickMangaArchives.launch(IMPORT_PICKER_MIME)
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { androidx.compose.material3.Text("Folder") },
                                onClick = {
                                    mangaImportChooserOpen = false
                                    pickMangaFolder.launch(null)
                                },
                            )
                        }
                        com.folio.reader.ui.components.FolioOverlayHost()
                        // §16 P0: the glass stats readout, gated on a developer flag (default off).
                        // It draws over everything, so it is mounted last.
                        com.folio.reader.debug.FolioGlassStats(
                            enabled = com.folio.reader.debug.FOLIO_GLASS_STATS_ENABLED,
                        )
                    }
                    }
                    }
                }
            }
        }

        // Avoid importing the same launch intent again after an activity recreation.
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Copies selected/shared content into bounded private temporary files, then imports it. */
    private fun importContentUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val graph = (application as FolioApplication).graph
        appScope.launch(Dispatchers.IO) {
            val temporaryFiles = mutableListOf<File>()
            try {
                val incoming = uris.mapIndexedNotNull { index, uri ->
                    withContext(Dispatchers.Main) {
                        importStatus = "Copying ${index + 1}/${uris.size}..."
                    }
                    runCatching {
                        val displayName = contentDisplayName(uri, index)
                        val mimeType = contentResolver.getType(uri)
                        val extension = displayName.substringAfterLast('.', "")
                            .lowercase()
                            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                        val tempFile = File(
                            cacheDir,
                            "incoming_${UUID.randomUUID()}${extension?.let { ".$it" }.orEmpty()}"
                        )
                        temporaryFiles += tempFile
                        val (digest, streamedBytes) = copyIncomingUri(uri, tempFile, displayName, mimeType)
                        IncomingContent(
                            path = tempFile.absolutePath,
                            filename = displayName,
                            mimeType = mimeType,
                            // Trust the digest only if it covers every byte the importer will read;
                            // anything shorter falls back to hashing the file here.
                            sha256 = digest.takeIf { tempFile.length() == streamedBytes }
                        )
                    }.onFailure { error ->
                        withContext(Dispatchers.Main) {
                            importStatus = "Import failed: ${error.message ?: "unable to read content"}"
                        }
                    }.getOrNull()
                }

                if (incoming.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    importStatus = "Importing ${incoming.size} file(s)..."
                }
                val results = graph.incomingContentCoordinator.importMany(incoming)
                var restored = 0
                // Only genuinely new books earn the import handling — adopting cloud progress,
                // kicking the embedding backfill, and asking which shelf to file them on. An
                // already-imported book has all three already, so it just opens.
                val importedBooks = results.mapNotNull {
                    (it as? IncomingContentResult.ImportedBook)?.book
                }
                val duplicates = results.count { it.isAlreadyInLibrary }
                importedBooks.forEach { book ->
                    restored += runCatching {
                        graph.syncEngine?.adoptCloudProgressForBook(
                            book.id,
                            book.epubHash
                        ) ?: 0
                    }.getOrDefault(0)
                }
                if (importedBooks.isNotEmpty()) {
                    // Import no longer embeds inline (see FolioApplication's BookImporter wiring).
                    // Kick the backfill worker so the just-imported books get their vectors in the
                    // background now, rather than only on the next app launch's scheduled run.
                    com.folio.reader.work.EmbeddingBackfillScheduler.schedule(applicationContext)
                }
                val successful = results.count { it.isNewImport }
                // Open what was just added; otherwise fall back to the existing item.
                val firstRoute = results.firstNotNullOfOrNull { it.freshRoute() }
                    ?: results.firstNotNullOfOrNull { it.openRoute() }
                val failure = results.firstNotNullOfOrNull { it.failureReason() }
                withContext(Dispatchers.Main) {
                    importStatus = when {
                        successful > 0 && duplicates > 0 ->
                            "Imported $successful of ${uris.size} file(s) • " +
                                "$duplicates already in library"
                        successful > 0 ->
                            "Imported $successful of ${uris.size} file(s)"
                        duplicates > 0 -> "Already in library"
                        failure != null -> "Import failed: $failure"
                        else -> "No supported files were imported"
                    }
                    if (restored > 0) {
                        importStatus += " • progress restored from cloud"
                    }
                    // Imported books get the shelf prompt before anything opens:
                    // the batch already sits on Main, and this is the one moment
                    // filing them elsewhere is a single tap (the manga library's
                    // add-to-library prompt, on the books side).
                    if (importedBooks.isNotEmpty()) {
                        pendingImportRoute = firstRoute
                        pendingImportBooks = importedBooks
                    } else if (firstRoute != null) {
                        val controller =
                            if (::navModel.isInitialized) navModel.navController else null
                        if (controller != null) {
                            controller.navigate(firstRoute)
                        } else {
                            pendingOpenRoute = firstRoute
                        }
                    }
                }
            } finally {
                temporaryFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    /** Opens the first imported item once the shelf prompt is done with it. */
    private fun openPendingImportRoute() {
        val route = pendingImportRoute ?: return
        pendingImportRoute = null
        val controller = if (::navModel.isInitialized) navModel.navController else null
        if (controller != null) {
            controller.navigate(route)
        } else {
            pendingOpenRoute = route
        }
    }

    private fun contentDisplayName(uri: Uri, index: Int): String =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
            ?: "shared_$index"

    /**
     * Streams [uri] into [destination], digesting the bytes on the way past so the importer can
     * settle "already in the library?" without reading the copy a second time.
     *
     * The hex must stay byte-identical to `MessageDigestFileHasher.sha256File`: a digest that
     * merely *looks* right is a silent dedupe miss, not a failure.
     *
     * @return the content digest and how many bytes were written.
     */
    private fun copyIncomingUri(
        uri: Uri,
        destination: File,
        displayName: String,
        mimeType: String?
    ): Pair<String, Long> {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val isOfficeDocument =
            extension == "docx" ||
                extension == "odt" ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                mimeType == "application/vnd.oasis.opendocument.text"
        val limit = if (isOfficeDocument) {
            MAX_OFFICE_SOURCE_BYTES
        } else {
            MAX_INCOMING_SOURCE_BYTES
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) {
                        throw IllegalArgumentException("$displayName exceeds the import size limit")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) } to total
            }
        } ?: throw IllegalArgumentException("Unable to open $displayName")
    }

    private fun IncomingContentResult.openRoute(): String? = when (this) {
        is IncomingContentResult.ImportedBook -> FolioDestination.reader(book.id)
        is IncomingContentResult.DuplicateBook -> FolioDestination.reader(book.id)
        is IncomingContentResult.ImportedDocument ->
            FolioDestination.documentReader(document.id)
        is IncomingContentResult.DuplicateDocument ->
            FolioDestination.documentReader(document.id)
        else -> null
    }

    /** Route for a file this batch actually added — opened in preference to an existing item. */
    private fun IncomingContentResult.freshRoute(): String? = when (this) {
        is IncomingContentResult.ImportedBook -> FolioDestination.reader(book.id)
        is IncomingContentResult.ImportedDocument ->
            FolioDestination.documentReader(document.id)
        else -> null
    }

    private fun IncomingContentResult.failureReason(): String? = when (this) {
        is IncomingContentResult.Unsupported -> reason
        is IncomingContentResult.Unsafe -> reason
        is IncomingContentResult.Corrupt -> reason
        is IncomingContentResult.Encrypted -> reason
        is IncomingContentResult.TooLarge -> reason
        is IncomingContentResult.IoError -> reason
        else -> null
    }

    /** Imports CBZ/ZIP manga archives into the local manga source. */
    private fun importMangaUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val graph = (application as FolioApplication).graph
        appScope.launch(Dispatchers.IO) {
            var imported = 0
            var restored = 0
            for ((index, uri) in uris.withIndex()) {
                withContext(Dispatchers.Main) {
                    importStatus = "Importing manga ${index + 1}/${uris.size}..."
                }
                val displayName = runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: "manga_$index.cbz"
                val tempFile = File(cacheDir, "manga_${UUID.randomUUID()}_$displayName")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalArgumentException("Unable to open archive")

                    val seriesName = graph.mangaBackend.localSource.import(tempFile)
                    val entryId = com.folio.reader.manga.mangaId(com.folio.reader.manga.LOCAL_SOURCE_ID, seriesName)
                    graph.mangaRepository.upsert(
                        com.folio.reader.manga.MangaEntry(
                            id = entryId,
                            sourceId = com.folio.reader.manga.LOCAL_SOURCE_ID,
                            sourceName = "Local manga",
                            url = seriesName,
                            title = seriesName,
                            inLibrary = true,
                            initialized = true,
                        )
                    )
                    val adopted = runCatching {
                        graph.syncEngine?.adoptCloudProgressForManga(entryId) ?: 0
                    }.getOrDefault(0)
                    if (adopted > 0) restored++
                    imported++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        importStatus = "Manga import failed: ${e.message ?: "unknown error"}"
                    }
                } finally {
                    tempFile.delete()
                }
            }

            withContext(Dispatchers.Main) {
                if (imported > 0) {
                    importStatus = if (imported == 1) "Imported 1 manga" else "Imported $imported manga"
                    if (restored > 0) importStatus += " • progress restored from cloud"
                }
            }
        }
    }

    /** Imports a folder selected via ACTION_OPEN_DOCUMENT_TREE as one manga collection. */
    private fun importMangaFolderUri(treeUri: Uri) {
        val graph = (application as FolioApplication).graph
        appScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { importStatus = "Reading manga folder..." }
            try {
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    ?: throw IllegalArgumentException("Cannot read selected folder")
                val folderName = docFile.name ?: "manga_folder"
                val resolver = contentResolver
                val archives = docFile.listFiles()
                    .filter { child ->
                        child.isFile && child.name?.substringAfterLast('.', "")?.lowercase()
                            .let { it == "cbz" || it == "zip" }
                    }
                    .mapNotNull { child ->
                        val name = child.name ?: return@mapNotNull null
                        com.folio.reader.manga.LocalMangaSource.PendingArchive(name) {
                            resolver.openInputStream(child.uri)
                                ?: throw java.io.IOException("Cannot open $name")
                        }
                    }
                if (archives.isEmpty()) {
                    withContext(Dispatchers.Main) { importStatus = "No CBZ/ZIP files found in folder" }
                    return@launch
                }
                val seriesName = graph.mangaBackend.localSource.importFolder(folderName, archives) { done, total ->
                    withContext(Dispatchers.Main) { importStatus = "Importing manga folder... $done/$total" }
                }
                val entryId = com.folio.reader.manga.mangaId(com.folio.reader.manga.LOCAL_SOURCE_ID, seriesName)
                graph.mangaRepository.upsert(
                    com.folio.reader.manga.MangaEntry(
                        id = entryId,
                        sourceId = com.folio.reader.manga.LOCAL_SOURCE_ID,
                        sourceName = "Local manga",
                        url = seriesName,
                        title = seriesName,
                        thumbnailUrl = seriesName,
                        inLibrary = true,
                        initialized = true,
                    )
                )
                runCatching {
                    graph.mangaBackend.localSource.materializeCover(seriesName)?.let { cover ->
                        graph.mangaRepository.setCoverPath(entryId, cover.absolutePath)
                    }
                }
                runCatching { graph.syncEngine?.adoptCloudProgressForManga(entryId) }
                withContext(Dispatchers.Main) {
                    importStatus = "Imported folder '$seriesName' with ${archives.size} chapters"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    importStatus = "Folder import failed: ${e.message ?: "unknown error"}"
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        val uris = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let(uris::add)
        }
        if (intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_SEND_MULTIPLE
        ) {
            IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )?.let(uris::addAll)
            IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )?.let(uris::add)
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index ->
                    clip.getItemAt(index).uri?.let(uris::add)
                }
            }
        }
        uris.removeAll { it.scheme == "folio" }
        if (uris.isEmpty()) return

        val manga = mutableListOf<Uri>()
        val content = mutableListOf<Uri>()
        uris.forEach { uri ->
            val path = uri.path.orEmpty()
            val type = contentResolver.getType(uri).orEmpty()
                .ifBlank { intent.type.orEmpty() }
            val isManga = path.endsWith(".cbz", ignoreCase = true) ||
                path.endsWith(".zip", ignoreCase = true) ||
                type.contains("cbz", ignoreCase = true) ||
                type.contains("comicbook", ignoreCase = true)
            if (isManga) manga += uri else content += uri
        }
        if (manga.isNotEmpty()) importMangaUris(manga)
        if (content.isNotEmpty()) importContentUris(content)
    }

    private data class ShareableFile(val file: File, val mimeType: String)

    /**
     * The importer stores every book as `<bookDir>/original.epub`, so sharing that file directly
     * makes the receiver see "original.epub". Copy it into the cache dir under the book title
     * first; `file_paths.xml` already exposes the cache root to the FileProvider.
     */
    private suspend fun namedEpubCopy(bookId: String): ShareableFile? {
        val graph = (application as FolioApplication).graph
        val source = File(graph.platform.fileSystem.getBookEpubPath(bookId)).takeIf(File::isFile)
            ?: return null
        val title = runCatching { graph.bookRepository.getBook(bookId)?.title }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "book"
        val named = File(cacheDir, "${sanitizeFileName(title)}.epub")
        runCatching { source.copyTo(named, overwrite = true) }.getOrNull() ?: return null
        return ShareableFile(named, "application/epub+zip")
    }

    /**
     * Only the characters a filesystem rejects: international titles must survive intact.
     * Shared by the book and document share paths, so the empty fallback is neutral.
     */
    private fun sanitizeFileName(title: String): String = title
        .replace(Regex("""[\\/:*?"<>|\s*\x00-\x1F]"""), " ")
        .trim()
        .trimEnd('.')
        .take(120)
        .ifBlank { "file" }

    private fun shareBooks(bookIds: Set<String>) {
        appScope.launch(Dispatchers.IO) {
            val files = bookIds.mapNotNull { namedEpubCopy(it) }
            withContext(Dispatchers.Main) { shareFiles(files) }
        }
    }

    private fun shareDocuments(documentIds: Set<String>) {
        val graph = (application as FolioApplication).graph
        appScope.launch(Dispatchers.IO) {
            val files = documentIds.mapNotNull { id ->
                val document = runCatching { graph.documentRepository.getDocument(id) }.getOrNull()
                    ?: return@mapNotNull null
                namedDocumentCopy(document)
            }
            withContext(Dispatchers.Main) { shareFiles(files) }
        }
    }

    /**
     * Documents are stored as `<documentDir>/original.<ext>` (see `AndroidPlatform`), so sharing
     * `localPath` directly makes the receiver see "original.pdf". Copy it into the cache dir
     * under the name it was imported with. Each document gets its own subdirectory: two imports
     * that happen to share a filename would otherwise overwrite each other while the share
     * sheet is still resolving the URIs, and the receiver would get the same file twice.
     */
    private fun namedDocumentCopy(document: Document): ShareableFile? {
        val source = document.localPath?.let(::File)?.takeIf(File::isFile) ?: return null
        // The stored file's extension *is* the canonical one the importer chose, so taking it
        // from the source keeps the shared name and the bytes in agreement.
        val extension = source.extension.ifBlank { "bin" }
        // `originalFilename` normally carries the extension already; only its stem is used so
        // the shared file always ends in the canonical extension rather than a stale one.
        val stem = document.originalFilename
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { document.title }
        val named = File(File(cacheDir, "share/${document.id}"), "${sanitizeFileName(stem)}.$extension")
        named.parentFile?.mkdirs()
        runCatching { source.copyTo(named, overwrite = true) }.getOrNull() ?: return null
        return ShareableFile(named, document.mimeType.ifBlank { "*/*" })
    }

    private fun shareFiles(files: List<ShareableFile>) {
        val shared = files.mapNotNull { item ->
            runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", item.file) to item.mimeType
            }.getOrNull()
        }
        if (shared.isEmpty()) {
            importStatus = "No selected files are available"
            return
        }

        val uris = ArrayList(shared.map { it.first })
        val mimeTypes = shared.map { it.second }.distinct()
        val shareIntent = Intent(
            if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        ).apply {
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            clipData = ClipData.newRawUri("Shared files", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share files"))
    }

    /** Shares the imported EPUB using the app's existing FileProvider grant. */
    fun shareEpub(bookId: String) {
        appScope.launch(Dispatchers.IO) {
            val item = namedEpubCopy(bookId)
            withContext(Dispatchers.Main) {
                if (item == null) importStatus = "EPUB file is unavailable"
                else shareFiles(listOf(item))
            }
        }
    }
}

/** Retains [FolioNavModelImpl] (and every tab view model it owns) across rotation. */
// Public: the ViewModel provider instantiates it reflectively — a private class
// crashes with IllegalAccessException on first access.
class NavModelHolder : ViewModel() {
    var model: FolioNavModelImpl? = null
}
