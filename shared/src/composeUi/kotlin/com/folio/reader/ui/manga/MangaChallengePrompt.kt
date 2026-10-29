package com.folio.reader.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import com.folio.reader.ui.components.FolioActionButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.folio.reader.manga.MangaChallenges
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Browser inspection for a blocked source, without an automatic retry handshake.
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
 * Closing the browser is not proof of source access. The screen's explicit Retry
 * action verifies the result using the extension's normal request.
 */
@Composable
fun MangaChallengePrompt(
    modifier: Modifier = Modifier,
    sourceId: Long? = null,
) {
    val challenge by MangaChallenges.pending.collectAsState()
    val bySource by MangaChallenges.bySource.collectAsState()

    val target = (if (sourceId != null) bySource[sourceId] else challenge) ?: return
    if (sourceId != null && target.sourceId != null && sourceId != target.sourceId) return
    if (!MangaChallenges.canSolve) return

    // Browse only notifies. Opening a browser requires visiting the affected source.
    if (sourceId == null) {
        Text(
            "Some sources need attention. Open the affected source to complete its browser check, then retry.",
            modifier = modifier,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space1),
    ) {
        Text(
            text = "${target.host}: ${target.reason.message} Return here and retry the source to verify access.",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = { MangaChallenges.solve(target) }) {
            Icon(Icons.Filled.Public, contentDescription = null)
            Text("  Open browser view")
        }
    }
}
