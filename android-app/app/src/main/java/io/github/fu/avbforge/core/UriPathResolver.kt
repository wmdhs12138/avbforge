package io.github.fu.avbforge.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Resolves Android SAF document / tree URIs into direct POSIX paths under `/storage/emulated/0/...`
 * when possible, falling back to copying openable documents into app cache for external providers.
 */
object UriPathResolver {

    fun resolveTreeUri(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return decodeExternalStorageDocId(docId)
    }

    private const val MAX_CACHE_FALLBACK_BYTES = 128L * 1024L * 1024L

    fun resolveFileUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        var decodedPath: String? = null
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            decodeExternalStorageDocId(docId)?.let { path ->
                decodedPath = path
                if (isDirectlyReadable(path)) return path
            }
        }
        // Fallback: copy openable SAF stream into app cacheDir so both JVM & non-root Rust core
        // can read it even before MANAGE_EXTERNAL_STORAGE is granted. Avoid copying huge multi-GB
        // GSI images into cacheDir if we already have a decoded POSIX path.
        val sizeBytes = queryFileSize(context, uri)
        if (decodedPath != null && sizeBytes != null && sizeBytes > MAX_CACHE_FALLBACK_BYTES) {
            return decodedPath
        }
        val displayName = queryDisplayName(context, uri) ?: "picked_${System.currentTimeMillis()}"
        val dst = File(File(context.cacheDir, "picked").apply { mkdirs() }, displayName)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching decodedPath
            dst.absolutePath
        }.getOrNull() ?: decodedPath
    }

    private fun isDirectlyReadable(path: String): Boolean {
        val f = File(path)
        if (!f.isFile || !f.canRead()) return false
        return runCatching { f.inputStream().use { true } }.getOrDefault(false)
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun decodeExternalStorageDocId(docId: String): String? {
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, rel) = parts
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
