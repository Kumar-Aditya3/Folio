package com.folio.reader.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folio.reader.ml.EmbeddingModelCatalog
import com.folio.reader.ml.ModelDownloadState
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.narration.NarrationPanel
import com.folio.reader.narration.VoicePackPhase
import com.folio.reader.surfaces.ReadingNotifications
import com.folio.reader.surfaces.ReadingReminderWorker
import com.folio.reader.surfaces.ReadingSurfacePreferences
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*
import com.folio.reader.work.EmbeddingBackfillScheduler
import com.folio.reader.work.MangaUpdateScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The install-time setup owns the screen; Home is mounted only after completion. */
@Composable
internal fun FirstRunSetup(navModel: FolioNavModelImpl, state: FirstRunState) {
    FirstRunWizard(state.step, onNext = { state.moveTo(state.step.next()) },
        onBack = { state.moveTo(state.step.previous()) }, onFinish = state::finish) { step ->
        when (step) {
            FirstRunStep.Welcome -> {
                SetupDescription("Your library stays yours. Read books, manga and documents, with optional on-device discovery and spoken pages.")
                SetupDescription("Choose your appearance, Echoes model, voice model and notifications next. Every download is optional and shows its size before you start it.")
            }
            FirstRunStep.Appearance -> FirstRunAppearance(navModel.globalSettings, navModel::updateSettings)
            FirstRunStep.Echoes -> FirstRunEchoes(navModel)
            FirstRunStep.Narration -> NarrationPanel(navModel.graph.narration, null,
                scrollable = false, showPlaybackSettings = false)
            FirstRunStep.Notifications -> FirstRunNotifications(navModel)
            FirstRunStep.Ready -> FirstRunSummary(navModel)
        }
    }
}

/** Separate from the overlay registry: setup cannot capture or hide a mounted Home screen. */
@Composable
internal fun FirstRunWizard(step: FirstRunStep, onNext: () -> Unit, onBack: () -> Unit,
    onFinish: () -> Unit, content: @Composable ColumnScope.(FirstRunStep) -> Unit) {
    FolioBackHandler(step != FirstRunStep.Welcome, onBack)
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
            .folioVeil(FolioShapes.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FolioEyebrow("SETUP · ${step.ordinal + 1} / ${FirstRunStep.entries.size}")
            Text(step.title, style = FolioTheme.typography.headlineSmall, color = FolioTheme.colors.onSurface)
            SetupDescription(step.introduction)
            FolioProgressBar((step.ordinal + 1f) / FirstRunStep.entries.size)
            key(step) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) { content(step) }
            }
            FolioRule()
            FolioActionButton(if (step == FirstRunStep.Ready) onFinish else onNext, Modifier.fillMaxWidth()) {
                Text(when (step) {
                    FirstRunStep.Welcome -> "Choose my setup"
                    FirstRunStep.Ready -> "Start reading"
                    else -> "Continue"
                })
            }
            TextButton(if (step == FirstRunStep.Welcome) onFinish else onBack, Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (step == FirstRunStep.Welcome) "Use defaults" else "Back")
            }
        }
    }
}

@Composable
internal fun FirstRunAppearance(settings: ReaderSettings, onSettingsChange: (ReaderSettings) -> Unit) {
    val dark = AppPalette.byId(settings.appThemeId).isDark
    val family = ThemePack.ALL.firstOrNull { settings.appThemeId in listOf(it.lightAppPaletteId, it.darkAppPaletteId) }
        ?: ThemePack.ALL.first()
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to "Light", true to "Dark").forEach { (value, label) ->
            FolioActionButton({ onSettingsChange(settings.copy(appThemeId = family.appPaletteId(value),
                themeId = family.readerThemeId(value), customTheme = null, customAppTheme = null)) }, Modifier.weight(1f)) {
                RadioButton(value == dark, null)
                Text(label)
            }
        }
    }
    Text("Theme family", style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
    ThemePack.ALL.take(4).forEach { pack ->
        SetupChoice(pack.name(dark), pack.material,
            settings.appThemeId in listOf(pack.lightAppPaletteId, pack.darkAppPaletteId)) {
            onSettingsChange(settings.copy(appThemeId = pack.appPaletteId(dark),
                themeId = pack.readerThemeId(dark), customTheme = null, customAppTheme = null))
        }
    }
    SetupDescription("More theme families, fonts and glass treatments are available in Settings → Themes & appearance.")
}

@Composable
private fun FirstRunEchoes(navModel: FolioNavModelImpl) {
    val graph = navModel.graph
    val context = LocalContext.current
    val model by graph.modelSelection.model.collectAsState()
    val download by graph.modelDownloader.state(model).collectAsState()
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = navModel.globalSettings.semanticDiscovery
    val busy = selecting || download is ModelDownloadState.Downloading || download is ModelDownloadState.Verifying
    SetupToggle("Enable Echoes", "Related passages and search by meaning", enabled) {
        navModel.updateSettings(navModel.globalSettings.copy(semanticDiscovery = it))
    }
    if (!enabled) {
        SetupDescription("You can enable Echoes later. No model will be downloaded here.")
        return
    }
    EmbeddingModelCatalog.selectable.forEach { choice ->
        SetupChoice(choice.displayName,
            if (choice.id == EmbeddingModelCatalog.default.id) "Smallest download · a good place to start" else "Longer passages · a larger download",
            choice.id == model.id, !busy) {
            error = null
            selecting = true
            navModel.activity.appScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        graph.modelSelection.persist(choice)
                        graph.modelSelection.adopt(choice)
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) { error = failure.message ?: "Could not save this model choice." }
                finally { selecting = false }
            }
        }
    }
    when (val progress = download) {
        is ModelDownloadState.Installed -> SetupDescription("Model installed. Echoes is ready for books added to your library.")
        is ModelDownloadState.Downloading -> {
            FolioProgressBar(progress.fraction)
            SetupDescription("Downloading · ${(progress.fraction * 100).toInt()}%. You can continue setup while it finishes.")
        }
        is ModelDownloadState.Verifying -> SetupDescription("Verifying your model…")
        else -> {
            if (progress is ModelDownloadState.Failed) Text(progress.message, color = FolioTheme.colors.error)
            FolioActionButton(onClick = {
                error = null
                navModel.activity.appScope.launch {
                    graph.modelDownloader.ensureModel(model)
                        .onSuccess { EmbeddingBackfillScheduler.runNow(context) }
                        .onFailure { error = it.message ?: "Could not download this model. You can try again later." }
                }
            }, enabled = !selecting) { Text("Download model · ${model.sizeBytes / 1_000_000} MB") }
            SetupDescription("Or continue without downloading. Echoes will become available after you install a model in Settings → Semantic search.")
        }
    }
    error?.let { Text(it, color = FolioTheme.colors.error) }
}

@Composable
private fun FirstRunNotifications(navModel: FolioNavModelImpl) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ReadingNotifications.allowed(context)) }
    var asked by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = ReadingNotifications.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        allowed = ReadingNotifications.allowed(context)
    }
    val preferences = remember { ReadingSurfacePreferences(context) }
    var reminders by remember { mutableStateOf(preferences.reminders) }
    if (allowed) SetupDescription("Notifications are allowed on this device.")
    else {
        SetupDescription(if (asked) "Notifications are off. Reading and every model still work; permission can be changed later in Android settings."
            else "Allow notifications for the features you choose below. They are optional.")
        FolioActionButton({
            if (Build.VERSION.SDK_INT >= 33 && !asked) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(if (asked || Build.VERSION.SDK_INT < 33) "Open notification settings" else "Allow notifications") }
    }
    SetupToggle("Daily reading reminder", "A gentle nudge at 8 pm; adjust the time later in Settings", reminders) {
        reminders = it
        preferences.reminders = it
        ReadingReminderWorker.sync(context)
    }
    val updates = navModel.globalSettings.mangaUpdateIntervalHours > 0
    SetupToggle("New manga chapters", "Check followed series every 12 hours", updates) {
        val hours = if (it) 12 else 0
        navModel.updateSettings(navModel.globalSettings.copy(mangaUpdateIntervalHours = hours))
        MangaUpdateScheduler.sync(context, hours)
    }
    SetupDescription(if (allowed) "Your notification choices can be changed in Settings at any time."
        else "You can continue with notifications off and change these choices later in Settings.")
}

@Composable
private fun FirstRunSummary(navModel: FolioNavModelImpl) {
    val settings = navModel.globalSettings
    val model by navModel.graph.modelSelection.model.collectAsState()
    val echoes by navModel.graph.modelDownloader.state(model).collectAsState()
    val narration by navModel.graph.narration.pack.collectAsState()
    SetupDescription("Appearance · ${AppPalette.byId(settings.appThemeId).label}")
    SetupDescription("Echoes · " + when {
        !settings.semanticDiscovery -> "Off"
        echoes is ModelDownloadState.Installed -> model.displayName
        echoes is ModelDownloadState.Downloading || echoes is ModelDownloadState.Verifying -> "Downloading your chosen model"
        else -> "Add a model later"
    })
    SetupDescription("Read aloud · " + if (narration.phase == VoicePackPhase.Installed) "Voice model installed" else "Add a voice model later")
    SetupDescription("Notifications · " + if (ReadingNotifications.allowed(LocalContext.current)) "Allowed" else "Off")
    SetupDescription("Use + in your library to import your first book, manga or document. Folder scanning is available in Settings → Library scan.")
}

@Composable
private fun SetupDescription(text: String) {
    Text(text, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
}

@Composable
private fun SetupChoice(title: String, detail: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(FolioTheme.colors.primary.copy(alpha = if (selected) .08f else .025f), FolioShapes.inset)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
            SetupDescription(detail)
        }
    }
}

@Composable
private fun SetupToggle(title: String, detail: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
            SetupDescription(detail)
        }
        Switch(enabled, onChange)
    }
}
