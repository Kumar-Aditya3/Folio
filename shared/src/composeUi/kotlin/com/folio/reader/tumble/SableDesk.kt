package com.folio.reader.tumble

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*

@Composable internal fun SableJournal(data:TumbleLibrary,pica:PicaSession,onDecorate:(String)->Unit,onVisible:(Boolean)->Unit) {
    var room by rememberSaveable {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(pica.quote.isNotBlank()) FolioPaperSheet {
            FolioEyebrow("A LINE YOU KEPT")
            Text("“${pica.quote}”",style=FolioTheme.typography.bodyLarge,color=FolioTheme.colors.textPrimary)
            Text(pica.bookTitle,style=FolioTheme.typography.labelMedium,color=FolioTheme.colors.textSecondary)
        }
        if(pica.finishedTitles.isNotEmpty()) {
            FolioSectionHead("Finished books")
            Text(pica.finishedTitles.joinToString(" · "),style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textSecondary)
        }
        FolioSectionHead("Reading journal")
        if(data.bond.memories.isEmpty()) Text("Finished books, reading prompts and personal bests appear here.",style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textSecondary)
        data.bond.memories.takeLast(30).reversed().forEach {memory->
            Text(when {
                memory.key.startsWith("book:") -> memory.text.replace("We finished ","Finished · ").removeSuffix(" together.")
                memory.text.contains("Sable") || memory.text.contains("Pica") -> "Reading prompt saved."
                else -> memory.text
            },style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textPrimary)
            FolioRule()
        }
        TextButton({room=!room}) {Text(if(room) "Close bookmark styles" else "Bookmark styles")}
        if(room) {
            Text("${data.scraps()} paper scraps · reading and listening earn one every 15 minutes",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
            tumbleDecorations.forEach {decor->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(decor.name,Modifier.weight(1f),style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textPrimary)
                TextButton({onDecorate(decor.id)},enabled=decor.id in data.owned || data.scraps()>=decor.cost) {
                    Text(if(data.decoration==decor.id) "Selected" else if(decor.id in data.owned) "Use" else "${decor.cost} scraps")
                }
            }}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Show bookmark on Home",Modifier.weight(1f),style=FolioTheme.typography.bodyMedium,color=FolioTheme.colors.textPrimary);Switch(data.homeVisible,onVisible)
            }
            Text("Reading unlocks prompts and bookmark styles. Games are always available.",style=FolioTheme.typography.bodySmall,color=FolioTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(24.dp))
    }
}
