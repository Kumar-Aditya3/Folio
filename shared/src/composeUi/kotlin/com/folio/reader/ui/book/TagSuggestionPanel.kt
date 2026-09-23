package com.folio.reader.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.ml.TagSuggestion
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * The auto-tagging proposal panel (ML_PLAN Phase 5 #2).
 *
 * ### Why every suggestion shows its score
 *
 * The tagger's honest output is a *ranked guess*, and the plan's threshold only decides what
 * clears the bar — it cannot make a marginal match into a good one. A single vector cannot
 * summarise a book (mean-pooling 80 000 words collapses a novel to its dominant subject), so
 * "epic fantasy 0.41" and "nautical 0.37" are not equally trustworthy even when both pass the
 * threshold. Showing the number is what lets the reader tell them apart, and it is the same
 * reason the semantic search screen shows scores rather than hiding the ranking.
 *
 * ### Why nothing is assigned automatically
 *
 * The reader's tags are the only signal this feature has, and the reader's tags are theirs.
 * The panel proposes; `onApply` is the only write and it only ever runs from a tap. This is
 * also why there is no "apply all" for the marginal suggestions — only
 * [TagSuggestionState.Ready] entries the tagger marked `confident` can be bulk-applied,
 * because a bulk write that looks like the app's own idea has no undo.
 *
 * ### Why the three non-`Ready` states are separate
 *
 * `NotIndexed`, `NoMatch` and `Unavailable` are three different problems with three different
 * remedies — build the index, add tags the reader actually uses, download the model. Collapsing
 * them into one "no suggestions" message would send the reader to fix the wrong thing.
 */
@Composable
internal fun TagSuggestionPanel(
    state: TagSuggestionState,
    onApply: (TagSuggestion) -> Unit,
    onApplyConfident: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(FolioTokens.radiusCard),
        color = FolioTheme.colors.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Suggested tags",
                    style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    // Keep the 48dp touch target the forced .size(28.dp) was
                    // suppressing; the glyph stays small via the Icon size below.
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss suggestions",
                        tint = FolioTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            when (state) {
                TagSuggestionState.Idle -> Unit

                TagSuggestionState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Reading this book…",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }

                is TagSuggestionState.Ready -> {
                    Text(
                        text = "Proposals, not assignments — tap one to apply it. " +
                            "The score is how closely the book's text resembles that tag.",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                    state.suggestions.forEach { suggestion ->
                        SuggestionRow(suggestion = suggestion, onApply = onApply)
                    }
                    val confidentCount = state.suggestions.count { it.confident }
                    if (confidentCount > 1) {
                        TextButton(onClick = onApplyConfident) {
                            Text("Apply $confidentCount clear matches")
                        }
                    }
                }

                TagSuggestionState.NoMatch -> Text(
                    // Deliberately not "no similar tags": the tag list is the reader's own
                    // vocabulary, and a book can genuinely be the first thing of its kind in
                    // a library. This says what happened without blaming their tags.
                    text = "None of your current tags describe this book closely enough to " +
                        "propose. Add a tag that fits and try again.",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )

                is TagSuggestionState.NotIndexed -> Text(
                    // The prerequisite, stated as one. Saying "no matches" here would blame the
                    // tag list for a missing index — the same mistake `RelatedLookup` exists to
                    // avoid on the quote browser.
                    text = if (state.totalChapters == 0) {
                        "This book has no indexed text yet, so there is nothing to compare " +
                            "against your tags. Re-import the book to index it."
                    } else {
                        "This book's text is not indexed yet, so there is nothing to compare " +
                            "against your tags (${state.totalChapters} chapters). Build the " +
                            "index in Settings → Semantic search."
                    },
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )

                is TagSuggestionState.Unavailable -> Text(
                    text = state.reason,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One proposal, with its score.
 *
 * The score is shown to two decimals and always — a suggestion without its number invites the
 * reader to read every row as equally certain, which is the failure mode the panel exists to
 * prevent. Rows the tagger marked `confident` are emphasised, because those are the ones
 * where the leading tag genuinely led the field.
 */
@Composable
private fun SuggestionRow(
    suggestion: TagSuggestion,
    onApply: (TagSuggestion) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(FolioTokens.radiusControl),
        color = if (suggestion.confident) {
            FolioTheme.colors.secondaryContainer
        } else {
            FolioTheme.colors.surface
        },
        onClick = { onApply(suggestion) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.candidate.name,
                    style = FolioTheme.typography.bodyMedium,
                    color = if (suggestion.confident) {
                        FolioTheme.colors.onSecondaryContainer
                    } else {
                        FolioTheme.colors.onSurface
                    },
                )
                if (suggestion.candidate.alreadyAssigned) {
                    Text(
                        text = "Already on this book",
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatScore(suggestion.score),
                style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Two decimals, formatted by hand.
 *
 * Cosine is in [-1, 1] and a *negative* score is meaningful here — it means the book's text
 * and the tag point in opposite directions, which is worth seeing rather than clamping to
 * "0.00" alongside every weak positive. `String.format` would work, but it is locale-sensitive
 * and would render "0,41" under a comma-decimal locale; the tag column would then be ragged and
 * the assertions brittle, so the digits are assembled explicitly.
 */
private fun formatScore(score: Float): String {
    val clamped = score.coerceIn(-1f, 1f)
    val scaled = (kotlin.math.abs(clamped) * 100f + 0.5f).toInt()   // rounded hundredths
    val sign = if (clamped < 0f) "-" else ""
    return "$sign${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
