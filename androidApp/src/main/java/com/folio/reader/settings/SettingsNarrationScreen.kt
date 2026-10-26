package com.folio.reader.settings

import androidx.compose.runtime.Composable
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.narration.NarrationPanel
import com.folio.reader.ui.components.FolioGlassPanel

@Composable
fun SettingsNarrationScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    SettingsCategoryScaffold(title = "Narration", onBack = onBack, scrollBody = {
        FolioGlassPanel { NarrationPanel(navModel.graph.narration,null,scrollable = false) }
    })
}
