package com.ds.localtaskmanager.backup

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.ds.localtaskmanager.DstApplication
import java.io.File

class BackupExportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uri = Uri.parse(requireNotNull(inputData.getString(KEY_URI)))
        return runCatching {
            setProgress(Data.Builder().putString(KEY_STAGE, "正在生成快照").build())
            val (_, bytes) = application.backupManager.createArchive()
            setProgress(Data.Builder().putString(KEY_STAGE, "正在写入并校验").build())
            applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw DstbException("无法写入所选位置")
            val written = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw DstbException("无法复核导出的文件")
            application.backupManager.inspect(written)
            if (!written.contentEquals(bytes)) throw DstbException("导出文件复核失败")
            application.settingsRepository.recordBackupExported(System.currentTimeMillis())
            Result.success(Data.Builder().putInt(KEY_SIZE, bytes.size).build())
        }.getOrElse { error ->
            runCatching { applicationContext.contentResolver.delete(uri, null, null) }
            Result.failure(Data.Builder().putString(KEY_ERROR, error.message ?: "导出失败").build())
        }
    }

    private val application get() = applicationContext as DstApplication

    companion object {
        const val KEY_URI = "uri"
        const val KEY_STAGE = "stage"
        const val KEY_SIZE = "size"
        const val KEY_ERROR = "error"
    }
}

class BackupRestoreWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val staged = File(requireNotNull(inputData.getString(KEY_FILE)))
        val choices = inputData.getString(KEY_CHOICES)?.let(::File)
        return runCatching {
            setProgress(Data.Builder().putString(BackupExportWorker.KEY_STAGE, "正在校验备份").build())
            val decoded = application.backupManager.inspect(staged.readBytes())
            val mode = RestoreMode.valueOf(requireNotNull(inputData.getString(KEY_MODE)))
            val selected = choices?.takeIf(File::exists)?.readLines()?.filter(String::isNotBlank)?.toSet().orEmpty()
            setProgress(Data.Builder().putString(BackupExportWorker.KEY_STAGE, "正在恢复数据").build())
            val result = application.backupManager.performRestore(decoded, mode, selected)
            Result.success(
                Data.Builder()
                    .putInt(KEY_ADDED, result.added)
                    .putInt(KEY_UPDATED, result.updated)
                    .putInt(KEY_KEPT, result.keptLocal)
                    .putBoolean(KEY_REMINDER_PENDING, result.reminderRebuildPending)
                    .build(),
            )
        }.getOrElse { error ->
            Result.failure(Data.Builder().putString(BackupExportWorker.KEY_ERROR, error.message ?: "恢复失败").build())
        }.also {
            staged.delete()
            choices?.delete()
        }
    }

    private val application get() = applicationContext as DstApplication

    companion object {
        const val KEY_FILE = "file"
        const val KEY_MODE = "mode"
        const val KEY_CHOICES = "choices"
        const val KEY_ADDED = "added"
        const val KEY_UPDATED = "updated"
        const val KEY_KEPT = "kept"
        const val KEY_REMINDER_PENDING = "reminder_pending"
    }
}

class BackupRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        application.backupManager.recoverIfNeeded()
        Result.success()
    }.getOrElse { Result.retry() }

    private val application get() = applicationContext as DstApplication
}
