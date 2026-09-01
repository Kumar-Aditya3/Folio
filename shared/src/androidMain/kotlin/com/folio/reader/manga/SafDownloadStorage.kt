package com.folio.reader.manga

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Download storage backed by a user-picked SAF tree. Uses raw DocumentsContract calls
 * (not androidx documentfile) and caches resolved directory URIs in memory, because
 * child listings on some volumes proved unreliable right after creating directories.
 * All mutating operations report failure instead of silently skipping, so callers
 * never believe a page landed on disk when it did not. Callers run on Dispatchers.IO.
 */
class SafDownloadStorage(context: Context, val treeUri: Uri) : MangaDownloadStorage {

    private val resolver = context.contentResolver
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )
    private val dirCache = ConcurrentHashMap<String, Uri>()

    private fun children(dirUri: Uri): Map<String, ChildDoc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(dirUri)
        )
        val out = LinkedHashMap<String, ChildDoc>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: continue
                    out[name] = ChildDoc(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            } ?: Log.w(TAG, "children($dirUri): null cursor")
        } catch (e: Exception) {
            Log.w(TAG, "children($dirUri) failed", e)
        }
        return out
    }

    private data class ChildDoc(val uri: Uri, val isDir: Boolean)

    private fun dirAt(relativePath: String, create: Boolean): Uri? {
        var cur: Uri = dirCache[""] ?: rootUri
        var key = ""
        for (segment in relativePath.split('/').filter { it.isNotBlank() }) {
            key = if (key.isEmpty()) segment else "$key/$segment"
            val cached = dirCache[key]
            if (cached != null) {
                cur = cached
                continue
            }
            val existing = children(cur)[segment]
            if (existing != null && !existing.isDir) return null
            val next = when {
                existing != null -> existing.uri
                create -> DocumentsContract.createDocument(
                    resolver, cur, DocumentsContract.Document.MIME_TYPE_DIR, segment
                )?.also { Log.i(TAG, "created dir $segment -> $it") }
                    ?: throw IOException("SAF: cannot create directory '$segment'")
                else -> return null
            }
            dirCache[key] = next
            cur = next
        }
        return cur
    }

    override fun ensureDir(relativePath: String): Boolean = try {
        dirAt(relativePath, create = true) != null
    } catch (e: Exception) {
        Log.w(TAG, "ensureDir($relativePath) failed", e)
        false
    }

    override fun write(relativePath: String, fileName: String, bytes: ByteArray): Boolean = try {
        val dir = dirAt(relativePath, create = true) ?: return false
        children(dir)[fileName]?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
        val fileUri = DocumentsContract.createDocument(resolver, dir, "application/octet-stream", fileName)
            ?: throw IOException("SAF: cannot create file '$fileName'")
        resolver.openOutputStream(fileUri)?.use { it.write(bytes) }
            ?: throw IOException("SAF: cannot open output stream for '$fileName'")
        true
    } catch (e: Exception) {
        Log.w(TAG, "write($relativePath/$fileName) failed", e)
        false
    }

    override fun readFirst(relativePath: String, fileNamePrefix: String): ByteArray? {
        val dir = dirAt(relativePath, create = false) ?: return null
        val child = children(dir).entries.firstOrNull { (name, doc) ->
            !doc.isDir && name.startsWith(fileNamePrefix)
        } ?: return null
        return try {
            resolver.openInputStream(child.value.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "readFirst($relativePath/$fileNamePrefix) failed", e)
            null
        }
    }

    override fun listFiles(relativePath: String): List<String> {
        val dir = dirAt(relativePath, create = false) ?: return emptyList()
        return children(dir).filterValues { !it.isDir }.keys.toList()
    }

    override fun listSubDirs(relativePath: String): List<String> {
        val dir = dirAt(relativePath, create = false) ?: return emptyList()
        return children(dir).filterValues { it.isDir }.keys.toList()
    }

    override fun deleteDir(relativePath: String) {
        val dir = dirAt(relativePath, create = false) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, dir) }
        dirCache.keys.removeIf { it == relativePath || it.startsWith("$relativePath/") }
    }

    override fun describe(): String {
        val name = try {
            resolver.query(
                rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return name ?: treeUri.lastPathSegment ?: treeUri.toString()
    }

    private companion object {
        const val TAG = "SafDownloadStorage"
    }
}
