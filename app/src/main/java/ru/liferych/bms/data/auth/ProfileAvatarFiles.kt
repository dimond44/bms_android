package ru.liferych.bms.data.auth

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Resolves persistent avatar JPEG path (legacy `profile_avatars/`).
 * Allows JVM unit tests without Android Context / FileProvider.
 */
fun interface ProfileAvatarPathProvider {
    /**
     * Persistent avatar file for a phone.
     *
     * @param phoneE164 E.164 phone
     * @return target JPEG file (may not exist yet)
     */
    fun persistentFile(phoneE164: String): File
}

/**
 * Local avatar file locations matching legacy MainActivity.
 *
 * Persistent: `filesDir/profile_avatars/avatar_<digits>.jpg`
 * Capture temp: `filesDir/profile_avatars/avatar_capture.jpg` (FileProvider).
 *
 * @param appContext application context
 */
class ProfileAvatarFiles(
    appContext: Context,
) : ProfileAvatarPathProvider {
    private val app = appContext.applicationContext

    /**
     * Directory for per-user avatar JPEG cache.
     *
     * @return existing or newly created directory
     */
    fun directory(): File {
        return File(app.filesDir, DIR_NAME).apply { mkdirs() }
    }

    /**
     * Persistent avatar file for a phone (legacy profileAvatarFile).
     *
     * @param phoneE164 E.164 phone
     * @return target JPEG file (may not exist yet)
     */
    override fun persistentFile(phoneE164: String): File {
        val digits = phoneE164.filter { it.isDigit() }
        val name = if (digits.isNotBlank()) "avatar_$digits.jpg" else "avatar_pending.jpg"
        return File(directory(), name)
    }

    /**
     * Temporary camera capture file (overwritten each shoot).
     *
     * @return capture JPEG file
     */
    fun captureFile(): File {
        return File(directory(), CAPTURE_NAME)
    }

    /**
     * Content URI for [TakePicture] via existing FileProvider.
     *
     * @return content:// URI
     * @throws IllegalArgumentException if FileProvider rejects the path
     */
    fun createCaptureUri(): Uri {
        val file = captureFile()
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    companion object {
        const val DIR_NAME = "profile_avatars"
        const val CAPTURE_NAME = "avatar_capture.jpg"
    }
}
