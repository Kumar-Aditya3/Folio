package com.folio.reader.ui.document

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun FixedPageContentSurface(
    path: String,
    documentId: String,
    pageIndex: Int,
    mode: DocumentReaderMode,
    rotationDegrees: Int,
    resetZoomKey: Int,
    modifier: Modifier = Modifier,
    onDocumentOpened: (Int) -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onError: (DocumentReaderError) -> Unit
)
