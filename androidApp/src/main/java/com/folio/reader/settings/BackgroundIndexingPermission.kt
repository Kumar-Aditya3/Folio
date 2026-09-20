package com.folio.reader.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The battery-optimization exemption, which on this class of device is what decides whether the
 * semantic index can be built at all.
 *
 * ### Why an app needs this
 *
 * Android's Doze already defers background work, but the vendor ROMs go further and *freeze* the
 * process. Measured on the ColorOS test device while the backfill was running:
 *
 * ```
 * 00:57:29  OplusHansManager: unfreeze uid: 10452 com.folio.reader  reason: TransBinder
 * 00:57:30  OnnxMem: session created: pss 389MB -> 462MB (delta 73MB; threads=4 ...)
 * 00:57:34  OplusHansManager: freeze   uid: 10452 com.folio.reader  pids: [29524]
 * ```
 *
 * Five seconds of work, then frozen — and a frozen process executes nothing. That is the whole of
 * the reader's report that indexing *"only works when im on the app"*: the app was not slow in the
 * background, it was stopped. Throughput in the two states differs by more than an order of
 * magnitude (≈5 chunks/s unfrozen, ≈0.4 chunks/s frozen) while the process stays alive throughout,
 * which is why it presented as slowness rather than as a stall.
 *
 * Granting the exemption changes it completely. The same device, same build, backgrounded for three
 * minutes after the exemption was granted, logged **no freeze events at all** — four full
 * 40-chapter slices, 1363 chunks, `oom_score_adj` 200, and PSS flat at 613–629 MB.
 *
 * ### Why this is a user-facing prompt rather than something the app just does
 *
 * The exemption cannot be granted by an app to itself. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * shows a system dialog the reader has to accept, which is deliberate: it is the OS asking the
 * person to take responsibility for an app that will now run in the background. An app that could
 * grant it silently would make the setting meaningless.
 *
 * The alternative — not asking, and letting the index creep forward only while the app is open —
 * is what produced the bug report. So the prompt is honest about the trade: it says what is
 * currently true ("only continues while Folio is open") and what granting it buys.
 */
object BackgroundIndexingPermission {

    /**
     * Whether the OS currently exempts this app from background restrictions.
     *
     * A `null` `PowerManager` — which should not happen on any real device — is reported as
     * `true`, i.e. "nothing to ask for". Failing closed here would show a prompt on a device where
     * the reader cannot act on it, and an unactionable prompt is worse than a missing one.
     */
    fun isExempt(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching true
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * Opens the system's "unrestricted battery" dialog for this app.
     *
     * `@SuppressLint("BatteryLife")` because the lint rule flags the *permission* this call pairs
     * with, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which the manifest declares. That permission is
     * normally reserved for apps whose core function needs it, and this one qualifies on the same
     * grounds the rule is about: the feature is a multi-hour local index that the app cannot build
     * while the OS is holding it frozen, and the exemption is the only mechanism the platform
     * offers. It is requested through the system dialog, never silently, so the reader always makes
     * the decision.
     *
     * Falls back to this app's own battery-usage page when the direct dialog is unavailable — a
     * settings screen the reader can navigate beats a button that does nothing. Returns whether an
     * activity was actually started, so a caller could tell the two apart.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true

        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback) }.isSuccess
    }
}
