package com.folio.reader.experience

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.folio.reader.ui.quotes.QuoteDisplayItem

fun QuoteDisplayItem.exportSource() = ExportSource(highlight?.id ?: quote.id, book.id, book.displayTitle, book.displayAuthor,
    quote.text, chapter?.title.orEmpty(), chapter?.id, highlight?.spineIndex ?: chapter?.spineIndex,
    highlight?.startLocator, note?.content, book.coverPath)

@Composable fun QuoteExperienceActions(item: QuoteDisplayItem) {
    val env = LocalReadingEnvironment.current ?: return
    Row {
        TextButton(onClick = { env.exports = listOf(item.exportSource()) }) { Text("Export") }
        TextButton(onClick = { env.perform { repository.schedule(item.exportSource(), System.currentTimeMillis()) } }) { Text("Review later") }
    }
}

@Composable fun BookJournalAction(bookId: String) {
    val env = LocalReadingEnvironment.current ?: return
    TextButton(onClick = { env.perform { openJournal(bookId) } }) { Text("Export reading journal") }
}

@Composable fun QuoteSequencePicker(items: List<QuoteDisplayItem>, onDismiss: () -> Unit) {
    val env = LocalReadingEnvironment.current ?: return
    var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(items.map { it.quote.id }.toSet()) }
    ExperienceDialog("Choose a quote sequence", onDismiss) {
        items.forEach { item -> androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(item.quote.id in chosen, { checked -> chosen = if (checked) chosen + item.quote.id else chosen - item.quote.id })
            Text(item.quote.text.take(140), maxLines = 3)
        } }
        androidx.compose.material3.Button(enabled = chosen.isNotEmpty(), onClick = {
            env.exports = items.filter { it.quote.id in chosen }.sortedWith(compareBy<QuoteDisplayItem> { it.book.displayTitle }.thenBy { it.chapter?.spineIndex ?: 0 }.thenBy { exportPassageOrder(it.highlight?.startLocator) }).map { it.exportSource() }
            onDismiss()
        }) { Text("Design ${chosen.size} passages") }
    }
}
