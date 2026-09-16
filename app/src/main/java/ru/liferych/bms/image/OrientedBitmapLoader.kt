package ru.liferych.bms.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Загрузка изображений с учётом EXIF orientation.
 *
 * Многие камеры сохраняют пиксели «как есть», а нужный поворот кладут в EXIF.
 * [BitmapFactory] EXIF игнорирует — без нормализации вертикальные фото
 * выглядят повёрнутыми боком после decode → JPEG → отображения.
 *
 * После нормализации пиксели уже «стоят» правильно; сохранённый JPEG
 * не должен повторно применять EXIF rotate (compress не пишет TAG_ORIENTATION).
 */
object OrientedBitmapLoader {
    private const val LOG_TAG = "PhotoOrientation"

    /**
     * Декодирует [uri] с применением EXIF и опциональным уменьшением до [maxSide].
     *
     * @param context Context для ContentResolver
     * @param uri источник (галерея / FileProvider / камера)
     * @param maxSide максимальная длинная сторона; 0 — без финального scale
     * @return Bitmap в корректной ориентации или null
     *
     * Side effects: читает поток(и) из ContentResolver; может recycle промежуточный Bitmap.
     * Security: только чтение URI, переданного приложением.
     */
    fun loadOrientedBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val orientation = readOrientation(context, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null

            logOrientation(uri.toString(), orientation, decoded.width, decoded.height)
            val oriented = applyExifOrientation(decoded, orientation)
            scaleToMaxSide(oriented, maxSide)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "loadOrientedBitmap(uri) failed: ${e.message}")
            null
        }
    }

    /**
     * Декодирует JPEG/PNG байты с EXIF (если есть) и уменьшает до [maxSide].
     *
     * @param bytes исходные байты файла
     * @param maxSide максимальная длинная сторона
     * @return нормализованный Bitmap или null
     */
    fun loadOrientedBitmap(bytes: ByteArray, maxSide: Int): Bitmap? {
        return try {
            val orientation = readOrientation(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

            logOrientation("bytes[${bytes.size}]", orientation, decoded.width, decoded.height)
            val oriented = applyExifOrientation(decoded, orientation)
            scaleToMaxSide(oriented, maxSide)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "loadOrientedBitmap(bytes) failed: ${e.message}")
            null
        }
    }

    /**
     * Нормализует изображение в JPEG: EXIF → upright pixels → compress.
     * Итоговый файл без «висящего» TAG_ORIENTATION rotate — повторного поворота не будет.
     *
     * @param bytes исходные байты
     * @param maxSide длинная сторона
     * @param quality JPEG quality 1..100
     * @return JPEG bytes или null
     */
    fun compressOrientedJpeg(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? {
        val bitmap = loadOrientedBitmap(bytes, maxSide) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Читает EXIF orientation из URI (отдельный InputStream).
     */
    fun readOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /** Читает EXIF orientation из байтов файла (JPEG и др.). */
    fun readOrientation(bytes: ByteArray): Int {
        return try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Физически поворачивает/отражает [source] по EXIF orientation.
     * Если поворот не нужен — возвращает тот же [source].
     * Иначе recycles исходный Bitmap.
     */
    fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_UNDEFINED,
            -> return source

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
            }

            else -> return source
        }
        val transformed = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (transformed != source) {
            source.recycle()
        }
        return transformed
    }

    private fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        if (maxSide <= 0) return 1
        val longest = max(width, height).coerceAtLeast(1)
        var sample = 1
        // Декодируем с запасом ×2 — финальный scale после EXIF точнее уложит в maxSide.
        while (longest / sample > maxSide * 2) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        if (maxSide <= 0) return bitmap
        val longest = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = min(1f, maxSide.toFloat() / longest.toFloat())
        if (scale >= 0.999f) return bitmap
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true,
        )
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun logOrientation(source: String, orientation: Int, width: Int, height: Int) {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return
        }
        Log.d(
            LOG_TAG,
            "source=$source orientation=$orientation decoded=${width}x$height",
        )
    }
}
