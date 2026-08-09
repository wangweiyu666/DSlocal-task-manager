package com.ds.localtaskmanager.sharing

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.ui.result.toPresentation
import com.ds.localtaskmanager.settings.AppSettingsRepository
import com.ds.localtaskmanager.diagnostics.DiagnosticEventStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShareImageService(
    private val appContext: Context,
    private val database: AppDatabase,
    private val renderer: ShareImageRenderer = ShareImageRenderer(),
    private val settingsRepository: AppSettingsRepository = AppSettingsRepository(appContext),
    private val diagnosticEvents: DiagnosticEventStore? = null,
) {
    suspend fun generateResult(snapshot: DailyResultSnapshot): GeneratedShareImage = withContext(Dispatchers.Default) {
        renderer.renderResult(snapshot.toPresentation(), settingsRepository.settings.value.uiPalette)
    }

    suspend fun generateInformation(
        taskName: String,
        taskDate: String,
        body: String,
    ): GeneratedShareImage = withContext(Dispatchers.Default) {
        val domName = database.profileDao().getProfile()?.domName
        renderer.renderInformation(taskName, taskDate, domName, body, settingsRepository.settings.value.uiPalette)
    }

    suspend fun cache(image: GeneratedShareImage): Uri = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, "shared-images").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
        val file = File(directory, image.fileName)
        file.outputStream().use { image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
    }

    fun send(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "发送图片"))
    }

    suspend fun saveToGallery(image: GeneratedShareImage): Uri = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DStationery")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            appContext.contentResolver.openOutputStream(uri, "w")!!.use {
                check(image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } catch (error: Throwable) {
            appContext.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    suspend fun writeToUri(image: GeneratedShareImage, uri: Uri) = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri, "w")!!.use {
            check(image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    fun informationPrivacyConfirmed(): Boolean = settingsRepository.settings.value.informationPrivacyConfirmed
    fun confirmInformationPrivacy() = settingsRepository.confirmInformationPrivacy()

    fun cleanupTemporaryFiles(nowEpochMillis: Long = System.currentTimeMillis()) {
        val cutoff = nowEpochMillis - 24L * 60 * 60 * 1000
        File(appContext.cacheDir, "shared-images").listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff && !file.delete()) {
                diagnosticEvents?.record("CLEANUP", "SHARED_IMAGE_DELETE_FAILED", false)
            }
        }
    }
}
