package com.folio.reader.sync

/**
 * StorageSync implementation that keeps books device-local.
 * EPUBs are synced as metadata only; file bodies stay on the device that imported them.
 */
object NoopStorageSync : StorageSync {
    override val uid: String get() = ""
    override suspend fun uploadBook(uid: String, bookId: String, localPath: String, onProgress: ((Float) -> Unit)?) = Unit
    override suspend fun downloadBook(uid: String, bookId: String, destinationPath: String, onProgress: ((Float) -> Unit)?) = Unit
    override suspend fun uploadCover(uid: String, bookId: String, localPath: String, onProgress: ((Float) -> Unit)?) = Unit
    override suspend fun deleteBook(uid: String, bookId: String) = Unit
}
