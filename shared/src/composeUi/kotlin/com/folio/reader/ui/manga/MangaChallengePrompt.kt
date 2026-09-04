package com.folio.reader.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.folio.reader.manga.MangaChallenges
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * The way out of a bot check.
 *
 * Some sources sit behind a Cloudflare challenge that cannot be cleared without a
 * human: a checkbox, a slider, an image puzzle. The network layer publishes the
 * blocked URL through [MangaChallenges]; this offers to open it in a WebView the
 * reader can touch, and the cookie the site sets there is the same cookie the
 * source's next request sends, because both go through the system cookie store.
 *
 * Renders nothing when nothing is blocked, or on a platform with no WebView to
 * open (desktop registers no solver).
 *
 * [onCleared] fires once the challenge is gone, which is the moment a failed load
 * is worth retrying — the caller passes its own reload.
 */
@Composable
fun MangaChallengePrompt(
    modifier: Modifier = Modifier,
    onCleared: () -> Unit = {},
) {
    val challenge by MangaChallenges.pending.collectAsState()
    // The bus emptying is the "solved" signal: the WebView clears it as soon as the
    // site hands over a clearance cookie, so the screen underneath can reload itself
    // instead of asking the reader to press Retry.
    var wasBlocked by remember { mutableStateOf(challenge != null) }
    LaunchedEffect(challenge) {
        if (challenge != null) {
            wasBlocked = true
        } else if (wasBlocked) {
            wasBlocked = false
            onCleared()
        }
    }

    val target = challenge ?: return
    if (!MangaChallenges.canSolve) return

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space1),
    ) {
        Text(
            text = "${target.host} is asking for a bot check before it will answer.",
            style = MaterialTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { MangaChallenges.solve(target) }) {
            Icon(Icons.Filled.Public, contentDescription = null)
            Text("  Solve in browser view")
        }
    }
}
