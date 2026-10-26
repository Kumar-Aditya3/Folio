package com.folio.reader.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folio.reader.nav.FolioDestination
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.surfaces.*
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioDialog
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsReadingSurfacesScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ReadingSurfacePreferences(context) }
    var enabled by remember { mutableStateOf(prefs.reminders) }
    var minute by remember { mutableIntStateOf(prefs.minute) }
    var allowed by remember { mutableStateOf(ReadingNotifications.allowed(context)) }
    var clockOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<ReadingSurfaceSnapshot?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = ReadingNotifications.allowed(context)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = ReadingNotifications.allowed(context)
        if (granted) {
            enabled = true; prefs.reminders = true; ReadingReminderWorker.sync(context)
        } else message = "Notifications are off. You can enable them in Android settings."
    }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { navModel.graph.readingSurfaceSnapshot() } }
            .onSuccess { snapshot = it }.onFailure { message = "Your library preview is taking a moment. Reopen this page to try again." }
    }
    fun pin(goal: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
            val extras = android.os.Bundle().apply {
                snapshot?.let { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, WidgetRefreshWorker.views(context, it, goal)) }
            }
            manager.requestPinAppWidget(ComponentName(context, if (goal) ReadingGoalWidget::class.java else ContinueReadingWidget::class.java), extras, null)
        } else message = "Touch and hold your home screen, choose Widgets, then Folio."
    }
    SettingsCategoryScaffold(title = "Widgets & notifications", onBack = onBack, scrollBody = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("A little Folio, wherever you are.", style = FolioTheme.typography.titleLarge, color = FolioTheme.colors.onSurface)
        Text("Your place in the story. Your own reading rhythm.", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        snapshot?.let { data ->
            Text("CONTINUE READING", style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.accentProgress)
            NativeWidgetPreview(data, false)
            TextButton(onClick = { pin(false) }) { Text("Add Continue Reading widget") }
            Spacer(Modifier.height(14.dp))
            Text("DAILY RHYTHM", style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.accentProgress)
            NativeWidgetPreview(data, true)
            TextButton(onClick = { pin(true) }) { Text("Add Daily Rhythm widget") }
        }
        Text("Widgets follow your Folio palette. Resize them on your home screen; tap to return to your reader or statistics.",
            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        FolioRule(Modifier.padding(vertical = 20.dp))
        com.folio.reader.ui.components.FolioGlassPanel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("A quiet reading reminder", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
                Text("Only on days you haven’t read. One invitation, without a sound.", style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { value ->
                if (value && Build.VERSION.SDK_INT >= 33 && !allowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else {
                    enabled = value; prefs.reminders = value; ReadingReminderWorker.sync(context)
                    if (value && !allowed) message = "Enable Folio notifications in Android settings to receive reminders."
                }
            })
        }
        TextButton(onClick = { clockOpen = true }, enabled = enabled) { Text("Around %02d:%02d".format(minute / 60, minute % 60)) }
        if (allowed && snapshot != null) TextButton(onClick = {
            snapshot?.let {
                message = if (ReadingNotifications.remind(context, it)) "Preview sent. Take a look in your notification shade."
                    else "Reading-time notifications are disabled in Android settings."
            }
        }) { Text("Preview reminder") }
        Text("Android may delay a reminder to save battery. Reading earlier that day skips it automatically.",
            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        TextButton(onClick = {
            val settingsIntent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
            context.startActivity(settingsIntent)
        }) { Text("Manage notifications") }
        }
        }
        FolioRule(Modifier.padding(vertical = 20.dp))
        Text("New chapters, together", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
        Text("A single manga digest with a direct way back to your chapters. Downloads keep their quiet progress notification.",
            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        TextButton(onClick = { navModel.navController?.navigate(FolioDestination.settings(FolioSettingsCategory.MANGA)) }) { Text("Manage manga updates") }
        message?.let { Text(it, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.accentProgress, modifier = Modifier.padding(top = 12.dp)) }
        }
    })
    if (clockOpen) {
        var hour by remember { mutableStateOf((minute / 60).toString()) }
        var minutes by remember { mutableStateOf((minute % 60).toString().padStart(2, '0')) }
        FolioDialog(true, { clockOpen = false }, title = "Your reading time") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("Hour · 0–23") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, label = { Text("Minute · 0–59") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { clockOpen = false }) { Text("Cancel") }
                TextButton(enabled = hour.toIntOrNull() in 0..23 && minutes.toIntOrNull() in 0..59, onClick = {
                    minute = hour.toInt() * 60 + minutes.toInt(); prefs.minute = minute; clockOpen = false
                }) { Text("Save time") }
            }
        }
    }
}

@Composable
private fun NativeWidgetPreview(snapshot: ReadingSurfaceSnapshot, goal: Boolean) {
    // Preview the actual RemoteViews tree used by the launcher.
    AndroidView(factory = { FrameLayout(it) }, update = { host ->
        host.removeAllViews()
        val view = WidgetRefreshWorker.views(host.context, snapshot, goal).apply(host.context, host)
        view.isClickable = false
        host.addView(view)
    }, modifier = Modifier.fillMaxWidth().height(184.dp).padding(top = 8.dp))
}
