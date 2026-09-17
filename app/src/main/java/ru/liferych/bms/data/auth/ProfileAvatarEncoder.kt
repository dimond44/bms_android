package ru.liferych.bms.data.auth

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import ru.liferych.bms.image.OrientedBitmapLoader
import java.io.ByteArrayOutputStream

/**
 * Encodes a gallery/camera URI into a JPEG matching legacy avatar processing.
 *
 * Legacy: OrientedBitmapLoader maxSide=1024, JPEG quality 88.
 *
 * @param appContext application context
 */
class ProfileAvatarEncoder(
    appContext: Context,
) {
    private val app = appContext.applicationContext

    /**
     * Loads [uri] with EXIF correction, scales, compresses to JPEG.
     *
     * @param uri content/file URI from camera or picker
     * @return JPEG bytes or null if unreadable
     *
     * Side effects: may allocate/recycle Bitmaps; does not upload.
     * Security: does not log image bytes.
     */
    fun encodeJpeg(uri: Uri): ByteArray? {
        return try {
            val bitmap = OrientedBitmapLoader.loadOrientedBitmap(
                context = app,
                uri = uri,
                maxSide = MAX_SIDE,
            ) ?: return null
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                bitmap.recycle()
                baos.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "encodeJpeg failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        /** Legacy persistProfileAvatarFromUri maxSide. */
        const val MAX_SIDE = 1024

        /** Legacy Bitmap.compress quality. */
        const val JPEG_QUALITY = 88

        private const val LOG_TAG = "ProfileAvatar"
    }
}
