package com.ds.localtaskmanager.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppProfileEntity
import com.ds.localtaskmanager.data.ExecutionProgressEntity
import com.ds.localtaskmanager.data.ImportBatchEntity
import com.ds.localtaskmanager.data.InformationSubmissionEntity
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.PointsLedgerEntity
import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskGroupEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskNoteEntity
import com.ds.localtaskmanager.data.TaskStepDefinitionEntity

@Dao
interface BackupDao {
    @Query("SELECT * FROM app_profile ORDER BY id")
    suspend fun profiles(): List<AppProfileEntity>

    @Query("SELECT * FROM import_batch ORDER BY batchId")
    suspend fun importBatches(): List<ImportBatchEntity>

    @Query("SELECT * FROM task_group ORDER BY groupId")
    suspend fun groups(): List<TaskGroupEntity>

    @Query("SELECT * FROM task_definition ORDER BY taskId")
    suspend fun definitions(): List<TaskDefinitionEntity>

    @Query("SELECT * FROM task_step_definition ORDER BY taskId, position")
    suspend fun definitionSteps(): List<TaskStepDefinitionEntity>

    @Query("SELECT * FROM task_instance ORDER BY taskId, occurrenceKey")
    suspend fun instances(): List<TaskInstanceEntity>

    @Query("SELECT * FROM instance_step ORDER BY taskId, occurrenceKey, position")
    suspend fun instanceSteps(): List<InstanceStepEntity>

    @Query("SELECT * FROM execution_progress ORDER BY taskId, occurrenceKey")
    suspend fun progress(): List<ExecutionProgressEntity>

    @Query("SELECT * FROM information_submission ORDER BY taskId, occurrenceKey")
    suspend fun information(): List<InformationSubmissionEntity>

    @Query("SELECT * FROM task_note ORDER BY taskId, occurrenceKey")
    suspend fun notes(): List<TaskNoteEntity>

    @Query("SELECT * FROM points_ledger ORDER BY ledgerId")
    suspend fun ledger(): List<PointsLedgerEntity>

    @Query("SELECT * FROM action_log ORDER BY eventId")
    suspend fun actionLogs(): List<ActionLogEntity>

    @Query("SELECT * FROM result_revision ORDER BY revisionId")
    suspend fun resultRevisions(): List<ResultRevisionEntity>

    @Upsert suspend fun upsertProfiles(values: List<AppProfileEntity>)
    @Upsert suspend fun upsertImportBatches(values: List<ImportBatchEntity>)
    @Upsert suspend fun upsertGroups(values: List<TaskGroupEntity>)
    @Upsert suspend fun upsertDefinitions(values: List<TaskDefinitionEntity>)
    @Upsert suspend fun upsertDefinitionSteps(values: List<TaskStepDefinitionEntity>)
    @Upsert suspend fun upsertInstances(values: List<TaskInstanceEntity>)
    @Upsert suspend fun upsertInstanceSteps(values: List<InstanceStepEntity>)
    @Upsert suspend fun upsertProgress(values: List<ExecutionProgressEntity>)
    @Upsert suspend fun upsertInformation(values: List<InformationSubmissionEntity>)
    @Upsert suspend fun upsertNotes(values: List<TaskNoteEntity>)
    @Upsert suspend fun upsertLedger(values: List<PointsLedgerEntity>)
    @Upsert suspend fun upsertActionLogs(values: List<ActionLogEntity>)
    @Upsert suspend fun upsertResultRevisions(values: List<ResultRevisionEntity>)

    @Query("DELETE FROM reminder_record") suspend fun clearReminders()
    @Query("DELETE FROM result_revision") suspend fun clearResultRevisions()
    @Query("DELETE FROM action_log") suspend fun clearActionLogs()
    @Query("DELETE FROM points_ledger") suspend fun clearLedger()
    @Query("DELETE FROM task_note") suspend fun clearNotes()
    @Query("DELETE FROM information_submission") suspend fun clearInformation()
    @Query("DELETE FROM execution_progress") suspend fun clearProgress()
    @Query("DELETE FROM instance_step") suspend fun clearInstanceSteps()
    @Query("DELETE FROM task_instance") suspend fun clearInstances()
    @Query("DELETE FROM task_step_definition") suspend fun clearDefinitionSteps()
    @Query("DELETE FROM task_definition") suspend fun clearDefinitions()
    @Query("DELETE FROM task_group") suspend fun clearGroups()
    @Query("DELETE FROM import_batch") suspend fun clearImportBatches()
    @Query("DELETE FROM app_profile") suspend fun clearProfiles()
}
