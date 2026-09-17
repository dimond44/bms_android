package ru.liferych.bms.data.support

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import ru.liferych.bms.image.OrientedBitmapLoader
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encodes warranty attachments for JSON upload (legacy encodeWarrantyMediaForUpload).
 */
interface SupportMediaEncoding {
    /**
     * @param uri attachment uri
     * @return Pair(base64, mime) or null on failure
     */
    fun encode(uri: Uri): Pair<String, String>?

    /**
     * Display name for UI (no content:// URI).
     *
     * @param uri attachment
     * @param index 0-based index fallback
     * @return human name
     */
    fun displayName(uri: Uri, index: Int): String
}

/**
 * Default encoder using OrientedBitmapLoader + ContentResolver.
 */
class SupportMediaEncoder(
    private val appContext: Context,
) : SupportMediaEncoding {
    override fun encode(uri: Uri): Pair<String, String>? {
        return try {
            val resolver = appContext.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            if (mime.startsWith("image/")) {
                val oriented = OrientedBitmapLoader.loadOrientedBitmap(
                    context = appContext,
                    uri = uri,
                    maxSide = 1600,
                )
                if (oriented != null) {
                    val out = ByteArrayOutputStream()
                    oriented.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    oriented.recycle()
                    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
                }
                val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                val compressed = OrientedBitmapLoader.compressOrientedJpeg(
                    bytes = original,
                    maxSide = 1600,
                    quality = 82,
                )
                val bytes = compressed ?: original.take(4 * 1024 * 1024).toByteArray()
                val outMime = if (compressed != null) "image/jpeg" else mime
                Base64.encodeToString(bytes, Base64.NO_WRAP) to outMime
            } else {
                val bytes = resolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = stream.read(tmp)
                        if (n <= 0) break
                        total += n
                        if (total > 6 * 1024 * 1024) return null
                        buf.write(tmp, 0, n)
                    }
                    buf.toByteArray()
                } ?: return null
                Base64.encodeToString(bytes, Base64.NO_WRAP) to mime
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "encode failed: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun displayName(uri: Uri, index: Int): String {
        try {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
        }
        val last = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !it.contains(':') }
        if (last != null) return last
        return fallbackFileName(uri, index)
    }

    private fun fallbackFileName(uri: Uri, index: Int): String {
        val mime = appContext.contentResolver.getType(uri) ?: ""
        val ext = when {
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("mp4") -> "mp4"
            mime.contains("quicktime") -> "mov"
            else -> "jpg"
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return "Фото_${stamp}_${index + 1}.$ext"
    }

    companion object {
        private const val LOG_TAG = "SupportMedia"
        const val MAX_ATTACHMENTS = 5
    }
}
