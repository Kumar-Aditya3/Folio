package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTokens

/**
 * Hosted state for [SemanticSearchSettingsPanel].
 *
 * Deliberately primitives only — no `ModelDownloadState` or `IndexProgress` — so the panel stays a
 * pure function of what it is told and both platforms can drive it from their own sources.
 */
data class SemanticSearchPanelState(
    val modelName: String = "",
    val modelSizeLabel: String = "",
    val installed: Boolean = false,
    /** 0..1 while a download is in flight; null when none is. */
    val downloadFraction: Float? = null,
    val downloadLabel: String? = null,
    val isIndexing: Boolean = false,
    /**
     * Work is enqueued but no worker is running — sitting in retry backoff, waiting on a
     * constraint (the battery is low), or held back by the system's job scheduler. Distinct
     * from [isIndexing] because the reader can act on this state and cannot act on a running
     * one.
     *
     * This used to also cover "paused because the app is in the foreground". That state no
     * longer exists: the pass used to wait for the app to be backgrounded before each slice,
     * and on the test device that meant waiting for ColorOS to freeze the process — so the
     * work now proceeds either way and takes fewer cores while the UI is on screen.
     */
    val isQueued: Boolean = false,
    val indexedChapters: Int = 0,
    val totalChapters: Int = 0,
    val indexedChunks: Int = 0,
    val error: String? = null,
    /**
     * The models the reader may choose between, as `id to label`. Empty hides the picker,
     * which is what a single-model build should show rather than a one-row list.
     */
    val choices: List<Pair<String, String>> = emptyList(),
    /** Id of the model in force; blank when the host has not resolved one yet. */
    val selectedModelId: String = "",
    /**
     * True when a model has vectors in the library but is not the one in force.
     *
     * Switching leaves the other model's vectors in place — they are keyed by `model_id` and
     * cost nothing to keep — so the honest thing to tell the reader is that the new model
     * needs its own index, not that anything was deleted.
     */
    val selectionNeedsIndex: Boolean = false,
    /**
     * Whether the OS will let this app run in the background at all.
     *
     * Defaults to `true` so a host that cannot answer the question (or a platform where the
     * question does not arise) never shows a prompt the reader cannot act on. On Android the
     * host reads it from `PowerManager.isIgnoringBatteryOptimizations`.
     *
     * This is not a nicety on every device. Measured on the ColorOS test phone, an app that is
     * *not* exempt is frozen about five seconds after it leaves the screen:
     *
     * ```
     * 00:57:29  OplusHansManager: unfreeze uid: 10452 com.folio.reader
     * 00:57:34  OplusHansManager: freeze   uid: 10452 com.folio.reader  pids: [29524]
     * ```
     *
     * A frozen process executes nothing, so indexing advances only while the app is on screen —
     * which is exactly the report this prompt exists to answer ("it only works when I'm on the
     * app"). With the exemption granted the same device logged **no freeze events at all** across
     * three minutes of backgrounded indexing, four full 40-chapter slices, and a flat ~620 MB.
     */
    val batteryExempt: Boolean = true,
) {
    val indexFraction: Float
        get() = if (totalChapters <= 0) 0f else (indexedChapters.toFloat() / totalChapters).coerceIn(0f, 1f)

    /** Nothing to index yet, or every searchable chapter already carries vectors. */
    val indexComplete: Boolean get() = totalChapters > 0 && indexedChapters >= totalChapters

    val hasLibrary: Boolean get() = totalChapters > 0

    val isDownloading: Boolean get() = downloadFraction != null

    /** Show the picker only when there is a genuine choice to make. */
    val showModelPicker: Boolean get() = choices.size > 1
}

/**
 * Semantic search settings (ML_PLAN Phase 4).
 *
 * Two steps, shown as two steps, because they fail for different reasons and cost different
 * things: the model is a one-time ~23 MB download, the index is local CPU work over the whole
 * library that can be stopped and resumed. Conflating them into one "enable" switch would make
 * a stalled download and a half-built index look identical.
 */
@Composable
fun SemanticSearchSettingsPanel(
    state: SemanticSearchPanelState,
    onDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onBuildIndex: () -> Unit,
    onSelectModel: (String) -> Unit = {},
    /** Null when the host has no way to ask — the prompt is then hidden rather than inert. */
    onAllowBackgroundIndexing: (() -> Unit)? = null,
) {
    Column {
        Text(
            "Search finds passages by meaning, not just by the words you typed — so a " +
                "\"the ship sinks\" query can reach a chapter that says \"the vessel foundered\". " +
                "Everything runs on this device; no text or query ever leaves it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(Modifier.padding(vertical = FolioTokens.space2))

        // The model picker. Above the model row because it is what that row describes: the
        // name below is the *chosen* model, so choosing comes first.
        if (state.showModelPicker) {
            Text("Model", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Different models trade size for retrieval quality. Each keeps its own index, " +
                    "so switching costs one background pass over your library and loses nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(modifier = Modifier.padding(top = FolioTokens.space1)) {
                state.choices.forEach { (id, label) ->
                    ModelChoiceRow(
                        label = label,
                        selected = id == state.selectedModelId,
                        // Disabled mid-download: the download is keyed to one model, and letting
                        // the selection move underneath it would leave the progress readout
                        // describing a model the reader is no longer looking at.
                        enabled = !state.isDownloading && !state.isIndexing,
                        onSelect = { onSelectModel(id) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = FolioTokens.space2))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    if (state.modelName.isBlank()) "Embedding model" else state.modelName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    buildString {
                        append(state.modelSizeLabel)
                        append(" · ")
                        append(if (state.installed) "Downloaded" else "Not downloaded")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.installed) {
                TextButton(onClick = onDeleteModel, enabled = !state.isDownloading) {
                    Text("Remove")
                }
            }
        }

        val downloadLabel = state.downloadLabel
        if (downloadLabel != null) {
            Spacer(Modifier.padding(FolioTokens.space1))
            ProgressBar(state.downloadFraction ?: 0f)
            Text(
                downloadLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FolioTokens.space1)
            )
        }

        if (!state.installed && !state.isDownloading) {
            Spacer(Modifier.padding(FolioTokens.space1))
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Download model")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = FolioTokens.space2))

        Text("Library index", style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                !state.hasLibrary -> "No books to index yet."
                state.indexedChapters == 0 -> "${state.totalChapters} chapters to index."
                state.indexComplete -> "All ${state.totalChapters} chapters indexed."
                else -> "${state.indexedChapters} of ${state.totalChapters} chapters indexed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        // Switching models starts its index from zero without touching the other model's
        // vectors — they are keyed by `model_id` and cost nothing to keep — so a reader who
        // has just switched sees "0 of 2561" and needs to be told why that is expected
        // rather than reading it as the index having been thrown away.
        if (state.selectionNeedsIndex && state.hasLibrary && state.indexedChapters == 0) {
            Text(
                "This model needs its own index. Your other model's index is untouched, and " +
                    "switching back finds it ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FolioTokens.space1)
            )
        }

        if (state.hasLibrary && state.indexedChapters > 0) {
            Spacer(Modifier.padding(FolioTokens.space1))
            ProgressBar(state.indexFraction)
        }

        Spacer(Modifier.padding(FolioTokens.space1))
        OutlinedButton(
            onClick = onBuildIndex,
            // Honest gate: without the model there is nothing to embed with, and re-running a
            // finished index would just rewrite identical rows.
            enabled = state.installed && state.hasLibrary && !state.isIndexing && !state.indexComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    state.isIndexing -> "Indexing…"
                    state.indexComplete -> "Index complete"
                    state.indexedChapters > 0 -> "Resume indexing"
                    else -> "Build index"
                }
            )
        }

        Text(
            "New books are indexed as they are imported. The automatic pass keeps running " +
                "whether or not you are using the app, using fewer processor cores while you " +
                "read so it stays out of your way — this button starts it now. Either way it " +
                "resumes where it left off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FolioTokens.space1)
        )

        // Only reachable when work is scheduled but nothing is executing. Without this the
        // panel shows a stalled-looking "2311 of 2561" with no explanation of what it is
        // waiting for, which is how a parked job reads as a broken button.
        if (state.isQueued && !state.isIndexing && !state.indexComplete) {
            Text(
                "Waiting to continue — this runs in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FolioTokens.space1)
            )
        }

        // The prompt that makes background indexing possible on a vendor ROM.
        //
        // Shown only while there is still work to do: once the index is complete it asks the
        // reader for a permission it can no longer use, which is how a settings screen trains
        // people to ignore it. Hidden rather than disabled when the host cannot ask.
        if (!state.batteryExempt && state.hasLibrary && !state.indexComplete && onAllowBackgroundIndexing != null) {
            Text(
                "This phone restricts background apps, so indexing only continues while Folio " +
                    "is open. Allow Folio to run in the background and it will finish on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FolioTokens.space1)
            )
            OutlinedButton(
                onClick = onAllowBackgroundIndexing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Allow background indexing")
            }
        }

        val error = state.error
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = FolioTokens.space1)
            )
        }
    }
}

/**
 * One row of the model picker: a radio, a label, and the whole row tappable.
 *
 * Not a `DropdownMenu`: the choice is between two or three named models with a rationale the
 * reader benefits from seeing at once, and a menu would hide the alternatives behind a tap.
 */
@Composable
private fun ModelChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = FolioTokens.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Flat determinate bar — the M3 `LinearProgressIndicator` signature differs across versions. */
@Composable
private fun ProgressBar(fraction: Float) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
