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

    fun resolveFileUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            decodeExternalStorageDocId(docId)?.let { path ->
                if (File(path).exists()) return path
            }
        }
        // Fallback: copy stream into cacheDir so native Rust core can read it
        val displayName = queryDisplayName(context, uri) ?: "picked_${System.currentTimeMillis()}"
        val dst = File(File(context.cacheDir, "picked").apply { mkdirs() }, displayName)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            dst.absolutePath
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
