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
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import com.folio.reader.nav.FolioDestination
import com.folio.reader.nav.FolioNavCallbacks
import com.folio.reader.nav.FolioNavHost
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.nav.FolioNavShell
import com.folio.reader.nav.LAUNCH_ANIMATION_PREF_KEY
import com.folio.reader.nav.LAUNCH_PREFS_NAME
import com.folio.reader.ui.components.folioField
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioPredictiveBackScale
import com.folio.reader.ui.theme.folioAmbientShader
import com.folio.reader.ui.theme.folioLamp
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

    /**
     * §redesign: the Android 14+ window-open "pop". Scales the app window up and
     * fades it in when it opens, reversing on close, via `overrideActivityTransition`.
     *
     * A strict no-op below API 34, where the method and its `OVERRIDE_TRANSITION_*`
     * constants were added — older devices keep the system default transition.
     * Gated on the user's [com.folio.reader.settings.ReaderSettings.launchAnimation]
     * flag, read from the [LAUNCH_PREFS_NAME] SharedPreferences mirror because the
     * JDBC settings row is async-warmed and not readable this early (default on
     * when the key is absent), and on system animations being enabled
     * (reduce-motion off), mirroring the §13 Compose motion gate in
     * MotionCapabilities.android.kt. Everything is kept in this one method so the
     * single `SDK_INT` guard covers every API-34-only reference for lint's
     * version-check flow analysis.
     */
    private fun maybeApplyLaunchTransition() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val enabled = getSharedPreferences(
            LAUNCH_PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        ).getBoolean(LAUNCH_ANIMATION_PREF_KEY, true)
        if (!enabled) return
        // Animator duration scale 0 means the user has turned system animations
        // off; honour it exactly as rememberMotionEnabled does.
        val motionEnabled = runCatching {
            android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
        if (!motionEnabled) return
        overrideActivityTransition(
            android.app.Activity.OVERRIDE_TRANSITION_OPEN,
            R.anim.folio_open_enter,
            R.anim.folio_open_exit,
        )
        overrideActivityTransition(
            android.app.Activity.OVERRIDE_TRANSITION_CLOSE,
            R.anim.folio_close_enter,
            R.anim.folio_close_exit,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §redesign: register the Android 14+ window-open "pop" before anything
        // draws. Must run in onCreate (the overrideActivityTransition contract),
        // reads a synchronous SharedPreferences mirror because the JDBC settings
        // row is async-warmed and not available this early, and is a no-op below
        // API 34 / when the user turns it off / when system animations are off.
        maybeApplyLaunchTransition()
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

            val callbacks = object : FolioNavCallbacks {
                override fun onImportContent() = pickContent.launch(IMPORT_PICKER_MIME)
                override fun onImportMangaArchives() = pickMangaArchives.launch(IMPORT_PICKER_MIME)
                override fun onImportMangaFolder() = pickMangaFolder.launch(null)
                override fun onImportMangaChoice() {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import manga")
                        .setItems(arrayOf("Archive files", "Folder")) { _, which ->
                            when (which) {
                                0 -> pickMangaArchives.launch(IMPORT_PICKER_MIME)
                                1 -> pickMangaFolder.launch(null)
                            }
                        }
                        .show()
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
            // §16 Material You: the wallpaper-derived schemes; the System pack's
            // faces resolve through the contrast-enforcing derivation, and fall
            // back to the pack's gallery placeholders below API 31.
            val dynamicSchemes = com.folio.reader.ui.theme.rememberDynamicSchemes()
            // A custom theme replaces the pack's colours wholesale; its own
            // background lightness, not the pack's, decides the app's polarity.
            val customAppTheme = model.globalSettings.customAppTheme
            val appColors = remember(customAppTheme, appPalette, dynamicSchemes) {
                when {
                    customAppTheme != null -> customAppTheme.toFolioColors()
                    dynamicSchemes != null && appPalette == AppPalette.SYSTEM ->
                        com.folio.reader.ui.theme.deriveSystemPalette(dynamicSchemes.first)
                    dynamicSchemes != null && appPalette == AppPalette.SYSTEM_DARK ->
                        com.folio.reader.ui.theme.deriveSystemPalette(dynamicSchemes.second)
                    else -> appPalette.colors
                }
            }
            val appDark = customAppTheme?.isDark ?: appPalette.isDark
            // §16 liquid glass: the capability verdict for this device, provided
            // once at the root so every surface reads it instead of probing the
            // platform. Blur floor is API 32 (Haze disables 31 for RenderNode
            // invalidation issues); low-RAM devices opt out; the preference is
            // the user's. Desktop never enters this file, so its tree keeps
            // GlassCapabilities.None — today's look, byte-for-byte.
            val glassCapabilities = remember(model.globalSettings.liquidGlassEffects) {
                com.folio.reader.ui.components.glassCapabilitiesFor(
                    platformBlurSupported = android.os.Build.VERSION.SDK_INT >= 32,
                    lowRamDevice = getSystemService(android.app.ActivityManager::class.java)
                        ?.isLowRamDevice == true,
                    liquidGlassEffects = model.globalSettings.liquidGlassEffects,
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
                onDispose { com.folio.reader.manga.MangaChallenges.solver = null }
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
            com.folio.reader.ui.components.FolioGlassRoot(capabilities = glassCapabilities) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.folio.reader.ui.theme.LocalDynamicSchemes provides dynamicSchemes,
                ) {
                    FolioTheme.AppTheme(
                        palette = appPalette,
                        fontTheme = FontTheme.byId(model.globalSettings.fontThemeId),
                        colors = appColors,
                        isDark = appDark,
                        opacity = model.globalSettings.surfaceOpacity()
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
                    // The hoisted cosmic-intensity holder: screens write it via
                    // CosmicIntensitySource, folioField reads it. One per app root.
                    val folioCosmicIntensity =
                        com.folio.reader.ui.theme.rememberCosmicIntensityState()
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.folio.reader.ui.theme.LocalFolioAmbient provides
                            com.folio.reader.ui.theme.rememberAmbientLight(),
                        // The room is lit by what is being read. One field sits under
                        // every screen, and a screen cannot hand a colour *up* to the
                        // ground it stands on — so it writes into this holder and the
                        // field below reads it in its draw pass.
                        com.folio.reader.ui.theme.LocalFolioAmbientTint provides
                            if (ambientOn) folioAmbientTint else null,
                        // The per-screen cosmic intensity, hoisted the same way: a screen
                        // calls CosmicIntensitySource(level) and the field below reads it.
                        // Provided above folioField so the field (and the screens that
                        // write it) share one holder; default Atmospheric.
                        com.folio.reader.ui.theme.LocalCosmicIntensityController provides
                            folioCosmicIntensity,
                        com.folio.reader.ui.theme.LocalFolioBackdrop provides folioBackdrop,
                        com.folio.reader.ui.theme.LocalFolioLamp provides folioLamp,
                        com.folio.reader.ui.theme.LocalFolioLight provides folioLight,
                        com.folio.reader.ui.theme.LocalFolioCoverLight provides
                            if (ambientOn) folioCoverLightState else null,
                        com.folio.reader.ui.theme.LocalFolioLampAnchor provides folioLampAnchor,
                    ) {
                    // The app's ground plane. `folioField` replaces the flat
                    // background fill with the theme's atmosphere — a vertical wash
                    // plus three enormous, very low-alpha accent pools — so every
                    // screen sits in an environment instead of on a colour. One
                    // drawing pass, no recomposition; see FolioAtmosphere.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { folioFieldPx.value = it }
                            .folioField(ambient = ambientShown)
                            // §17 living light: an animated ambient bloom film over
                            // the static field, so a resting Home/Library page has a
                            // slow drift of accent light. API 33+/pref/motion gated
                            // in the actual; a no-op everywhere else.
                            .folioAmbientShader(
                                colorA = FolioTheme.colors.primary,
                                colorB = FolioTheme.colors.tertiary,
                                enabled = glassCapabilities.specular,
                            )
                            // The lamp: one visible source in the room, anchored to
                            // whichever cover this screen is featuring. Over the field
                            // and its film, under every screen, so content sits *in*
                            // the light rather than on top of a tint.
                            .folioLamp(folioLamp)
                            // §16: register the field itself as a glass backdrop source,
                            // so liquid-glass surfaces (cosmic cards, the nav capsule)
                            // blur the *actual* cosmic field — nebula, stars, lamp —
                            // behind them, instead of falling back to a flat field
                            // colour (the "dull shade" a card showed with nothing but
                            // screen content in the source). No-op when blur is off.
                            .folioBackdropSource()
                    ) {
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
                            var shelfCollections by remember {
                                mutableStateOf(emptyList<com.folio.reader.model.Collection>())
                            }
                            LaunchedEffect(Unit) {
                                shelfCollections = runCatching {
                                    graph.collectionRepository.getAllCollections().first()
                                }.getOrDefault(emptyList())
                            }
                            if (shelfCollections.isNotEmpty()) {
                                com.folio.reader.ui.library.BookImportCollectionsDialog(
                                    bookCount = books.size,
                                    collections = shelfCollections,
                                    onCreate = { name ->
                                        runCatching {
                                            graph.collectionRepository.getCollectionByName(name)?.id
                                                ?: graph.collectionRepository.createCollection(name).id
                                        }.getOrNull()
                                    },
                                    onSave = { ids ->
                                        appScope.launch(Dispatchers.IO) {
                                            if (ids.isNotEmpty()) {
                                                books.forEach { book ->
                                                    runCatching {
                                                        graph.collectionRepository.assign(book.id, ids)
                                                    }
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                pendingImportBooks = null
                                                openPendingImportRoute()
                                            }
                                        }
                                    },
                                    onDismiss = {
                                        pendingImportBooks = null
                                        openPendingImportRoute()
                                    }
                                )
                            }
                        }
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

    /** Only the characters a filesystem rejects: international titles must survive intact. */
    private fun sanitizeFileName(title: String): String = title
        .replace(Regex("""[\\/:*?"<>|\s*\x00-\x1F]"""), " ")
        .trim()
        .trimEnd('.')
        .take(120)
        .ifBlank { "book" }

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
                val file = document.localPath?.let(::File)?.takeIf(File::isFile)
                    ?: return@mapNotNull null
                ShareableFile(file, document.mimeType.ifBlank { "*/*" })
            }
            withContext(Dispatchers.Main) { shareFiles(files) }
        }
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
