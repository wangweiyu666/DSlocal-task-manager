package com.ds.localtaskmanager.backup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.recurrence.InstanceGenerationService
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.reminder.ReminderReconciler
import com.ds.localtaskmanager.settings.AppSettingsRepository
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.diagnostics.DiagnosticEventStore
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class RestoreResult(
    val added: Int,
    val updated: Int,
    val keptLocal: Int,
    val reminderRebuildPending: Boolean,
)

@SuppressLint("ApplySharedPref") // Restore markers must be durable before database replacement begins.
class BackupManager(
    context: Context,
    private val database: AppDatabase,
    private val repository: RoomBackupRepository,
    private val settingsRepository: AppSettingsRepository,
    private val instanceGenerationService: InstanceGenerationService,
    private val reminderReconciler: ReminderReconciler,
    private val idGenerator: RecordIdGenerator,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val diagnosticEvents: DiagnosticEventStore? = null,
) {
    private val appContext = context.applicationContext
    private val workManager by lazy { WorkManager.getInstance(appContext) }
    private val operationDirectory = File(appContext.filesDir, "w31-backup")
    private val preferences = appContext.getSharedPreferences("backup_operation", Context.MODE_PRIVATE)

    suspend fun createArchive(): Pair<BackupMetadata, ByteArray> {
        val payload = repository.snapshot()
        val metadata = metadataFor(payload)
        return metadata to DstbCodec.encode(metadata, payload)
    }

    fun inspect(bytes: ByteArray): DecodedBackup = DstbCodec.decode(bytes).also(BackupValidator::validate)

    fun stageSelectedBackup(uri: Uri): Pair<File, DecodedBackup> {
        operationDirectory.mkdirs()
        val target = File(operationDirectory, "selected-${clock.millis()}.dstb")
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > DstbCodec.MAX_FILE_BYTES) throw DstbException("备份文件超过 100 MB")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw DstbException("无法读取所选文件")
            target to inspect(target.readBytes())
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun enqueueExport(uri: Uri): UUID {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val request = OneTimeWorkRequestBuilder<BackupExportWorker>()
            .setInputData(Data.Builder().putString(BackupExportWorker.KEY_URI, uri.toString()).build())
            .build()
        workManager.enqueueUniqueWork(UNIQUE_OPERATION, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun enqueueRestore(stagedFile: File, mode: RestoreMode, backupChoices: Set<String>): UUID {
        val choiceFile = File(operationDirectory, "choices-${clock.millis()}.txt")
        choiceFile.writeText(backupChoices.sorted().joinToString("\n"))
        val request = OneTimeWorkRequestBuilder<BackupRestoreWorker>()
            .addTag(TAG_RESTORE)
            .setInputData(
                Data.Builder()
                    .putString(BackupRestoreWorker.KEY_FILE, stagedFile.absolutePath)
                    .putString(BackupRestoreWorker.KEY_MODE, mode.name)
                    .putString(BackupRestoreWorker.KEY_CHOICES, choiceFile.absolutePath)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(UNIQUE_OPERATION, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun observeWork(id: UUID): Flow<WorkInfo?> = workManager.getWorkInfoByIdFlow(id)

    val operationActive: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_OPERATION)
            .map { values -> values.any { !it.state.isFinished } }
            .distinctUntilChanged()

    val restoreActive: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_OPERATION)
            .map { values -> values.any { !it.state.isFinished && TAG_RESTORE in it.tags } }
            .distinctUntilChanged()

    suspend fun performRestore(decoded: DecodedBackup, mode: RestoreMode, backupChoices: Set<String>): RestoreResult {
        operationDirectory.mkdirs()
        val rollbackFile = File(operationDirectory, ROLLBACK_FILE)
        val oldSettings = settingsRepository.settings.value
        val (rollbackMetadata, rollbackBytes) = createArchive()
        rollbackFile.writeBytes(rollbackBytes)
        preferences.edit().putBoolean(KEY_RESTORE_PENDING, true).commit()
        return try {
            val merge = if (mode == RestoreMode.MERGE) repository.previewMerge(decoded.payload, backupChoices) else null
            if (merge == null) repository.replace(decoded.payload) else repository.applyMerge(merge)
            if (mode == RestoreMode.REPLACE) restorePortableSettings(decoded.payload.settings)
            instanceGenerationService.reconcileAll(LocalDate.now(clock))
            database.auditDao().insertLogs(
                listOf(
                    ActionLogEntity(
                        eventId = idGenerator.next(),
                        taskId = null,
                        occurrenceKey = null,
                        batchId = null,
                        action = "BACKUP_RESTORED",
                        detail = "{\"mode\":\"${mode.name}\",\"sourceCreatedAt\":${decoded.metadata.createdAtEpochMillis}}",
                        createdAtEpochMillis = clock.millis(),
                    ),
                ),
            )
            val reminderPending = runCatching { reminderReconciler.reconcileAll("backup-restored") }.isFailure
            preferences.edit().putBoolean(KEY_RESTORE_PENDING, false).commit()
            rollbackFile.delete()
            settingsRepository.recordBackupRestored(clock.millis())
            RestoreResult(
                added = merge?.added ?: decoded.payload.definitions.size + decoded.payload.instances.size,
                updated = merge?.updated ?: 0,
                keptLocal = merge?.keptLocal ?: 0,
                reminderRebuildPending = reminderPending,
            )
        } catch (error: Exception) {
            val rollbackResult = runCatching {
                val rollback = inspect(rollbackFile.readBytes())
                repository.replace(rollback.payload)
                settingsRepository.restorePortableSettings(
                    oldSettings.themeMode,
                    oldSettings.reduceMotion,
                    oldSettings.lastStatisticsPeriod,
                )
            }
            if (rollbackResult.isSuccess) {
                diagnosticEvents?.record("BACKUP", "RESTORE_ROLLED_BACK", true)
                preferences.edit().putBoolean(KEY_RESTORE_PENDING, false).commit()
                rollbackFile.delete()
                throw DstbException("恢复失败，已还原原有数据", error)
            }
            diagnosticEvents?.record("BACKUP", "ROLLBACK_PENDING", false)
            throw DstbException("恢复失败，将在下次启动时继续还原原有数据", error)
        }
    }

    suspend fun recoverIfNeeded(): Boolean {
        if (!preferences.getBoolean(KEY_RESTORE_PENDING, false)) return false
        val rollbackFile = File(operationDirectory, ROLLBACK_FILE)
        if (!rollbackFile.exists()) {
            preferences.edit().putBoolean(KEY_RESTORE_PENDING, false).commit()
            return false
        }
        val rollback = inspect(rollbackFile.readBytes())
        repository.replace(rollback.payload)
        restorePortableSettings(rollback.payload.settings)
        runCatching { reminderReconciler.reconcileAll("backup-rollback-recovered") }
        preferences.edit().putBoolean(KEY_RESTORE_PENDING, false).commit()
        rollbackFile.delete()
        diagnosticEvents?.record("BACKUP", "ROLLBACK_RECOVERED", true)
        return true
    }

    fun enqueueRecoveryIfNeeded() {
        if (!preferences.getBoolean(KEY_RESTORE_PENDING, false)) return
        workManager.enqueueUniqueWork(
            RECOVERY_OPERATION,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupRecoveryWorker>().build(),
        )
    }

    fun cleanupTemporaryFiles() {
        val cutoff = clock.millis() - 24 * 60 * 60 * 1000L
        operationDirectory.listFiles().orEmpty().forEach { file ->
            if (file.name != ROLLBACK_FILE && file.lastModified() < cutoff && !file.delete()) {
                diagnosticEvents?.record("CLEANUP", "BACKUP_TEMP_DELETE_FAILED", false)
            }
        }
    }

    private fun metadataFor(payload: BackupPayload): BackupMetadata = BackupMetadata(
        createdAtEpochMillis = clock.millis(),
        appVersion = appContext.appVersionName(),
        sourceTimeZone = ZoneId.systemDefault().id,
        counts = BackupCounts(
            groups = payload.groups.size,
            tasks = payload.definitions.size,
            instances = payload.instances.size,
            ledgerEntries = payload.ledger.size,
            actionLogs = payload.actionLogs.size,
            resultRevisions = payload.resultRevisions.size,
        ),
    )

    private fun restorePortableSettings(settings: PortableSettings) {
        settingsRepository.restorePortableSettings(
            AppThemeMode.valueOf(settings.themeMode),
            settings.reduceMotion,
            StatisticsPeriod.valueOf(settings.lastStatisticsPeriod),
        )
    }

    @Suppress("DEPRECATION")
    private fun Context.appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    companion object {
        const val UNIQUE_OPERATION = "w31-backup-operation"
        private const val RECOVERY_OPERATION = "w31-backup-recovery"
        private const val ROLLBACK_FILE = "restore-rollback.dstb"
        private const val KEY_RESTORE_PENDING = "restore_pending"
        private const val TAG_RESTORE = "w31-restore"
    }
}
