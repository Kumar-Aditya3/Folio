package com.folio.reader.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentFormat {
    PDF,
    TXT,
    HTML,
    DOCX,
    ODT
}

@Serializable
data class DocumentCategory(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val updatedAt: Instant = Clock.System.now()
) {
    companion object {
        const val MAIN_ID = "main"
        const val MAIN_NAME = "Main"
    }
}

@Serializable
data class Document(
    val id: String,
    val title: String,
    val originalFilename: String,
    val format: DocumentFormat,
    val mimeType: String,
    val contentHash: String,
    val byteSize: Long,
    val localPath: String? = null,
    val thumbnailPath: String? = null,
    val author: String? = null,
    val description: String? = null,
    val pageCount: Int? = null,
    val sectionCount: Int? = null,
    val importedAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val lastOpenedAt: Instant? = null,
    val normalizedProgress: Double = 0.0
)

@Serializable
sealed class DocumentLocator {
    abstract val version: Int

    @Serializable
    @SerialName("fixed_page")
    data class FixedPage(
        override val version: Int = CURRENT_VERSION,
        val pageIndex: Int,
        val normalizedX: Double = 0.0,
        val normalizedY: Double = 0.0
    ) : DocumentLocator()

    @Serializable
    @SerialName("reflowable")
    data class Reflowable(
        override val version: Int = CURRENT_VERSION,
        val sectionId: String,
        val domLocator: String? = null,
        val textLocator: String? = null,
        val characterOffset: Int = 0
    ) : DocumentLocator()

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class DocumentPosition(
    val documentId: String,
    val locator: DocumentLocator,
    val normalizedProgress: Double = 0.0,
    val updatedAt: Instant = Clock.System.now()
)

@Serializable
data class DocumentBookmark(
    val id: String,
    val documentId: String,
    val locator: DocumentLocator,
    val label: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)
