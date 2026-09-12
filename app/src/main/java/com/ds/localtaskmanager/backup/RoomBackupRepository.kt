package com.ds.localtaskmanager.backup

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.settings.AppSettingsRepository

enum class RestoreMode { MERGE, REPLACE }

data class MergeConflict(
    val id: String,
    val category: String,
    val title: String,
    val localValue: String,
    val backupValue: String,
)

data class MergePreview(
    val merged: BackupPayload,
    val conflicts: List<MergeConflict>,
    val added: Int,
    val updated: Int,
    val keptLocal: Int,
)

class RoomBackupRepository(
    private val database: AppDatabase,
    private val settingsRepository: AppSettingsRepository,
) {
    suspend fun snapshot(): BackupPayload = database.withTransaction {
        val dao = database.backupDao()
        val settings = settingsRepository.settings.value
        BackupPayload(
            settings = PortableSettings(
                themeMode = settings.themeMode.name,
                reduceMotion = settings.reduceMotion,
                lastStatisticsPeriod = settings.lastStatisticsPeriod.name,
            ),
            profiles = dao.profiles().map { it.toBackup() },
            importBatches = dao.importBatches().map { it.toBackup() },
            groups = dao.groups().map { it.toBackup() },
            definitions = dao.definitions().map { it.toBackup() },
            definitionSteps = dao.definitionSteps().map { it.toBackup() },
            recurrenceExceptions = dao.recurrenceExceptions().map { it.toBackup() },
            instances = dao.instances().map { it.toBackup() },
            instanceSteps = dao.instanceSteps().map { it.toBackup() },
            progress = dao.progress().map { it.toBackup() },
            information = dao.information().map { it.toBackup() },
            moods = dao.moods().map { it.toBackup() },
            notes = dao.notes().map { it.toBackup() },
            ledger = dao.ledger().map { it.toBackup() },
            actionLogs = dao.actionLogs().map { it.toBackup() },
            resultRevisions = dao.resultRevisions().map { it.toBackup() },
        )
    }

    suspend fun replace(payload: BackupPayload) = database.withTransaction {
        BackupValidator.validateExtendedSnapshots(payload)
        writePayload(payload)
    }

    suspend fun previewMerge(backup: BackupPayload, backupChoices: Set<String> = emptySet()): MergePreview {
        val local = snapshot()
        return BackupMerger.merge(local, backup, backupChoices).also { BackupValidator.validateExtendedSnapshots(it.merged) }
    }

    suspend fun applyMerge(preview: MergePreview) = database.withTransaction {
        BackupValidator.validateExtendedSnapshots(preview.merged)
        writePayload(preview.merged)
    }

    private suspend fun writePayload(payload: BackupPayload) {
        val dao = database.backupDao()
        dao.clearReminders()
        dao.clearResultRevisions()
        dao.clearActionLogs()
        dao.clearLedger()
        dao.clearNotes()
        dao.clearInformation()
        dao.clearMoods()
        dao.clearProgress()
        dao.clearInstanceSteps()
        dao.clearInstances()
        dao.clearRecurrenceExceptions()
        dao.clearDefinitionSteps()
        dao.clearDefinitions()
        dao.clearGroups()
        dao.clearImportBatches()
        dao.clearProfiles()

        dao.upsertProfiles(payload.profiles.map { it.toEntity() })
        dao.upsertImportBatches(payload.importBatches.map { it.toEntity() })
        dao.upsertGroups(payload.groups.map { it.toEntity() })
        dao.upsertDefinitions(payload.definitions.map { it.toEntity() })
        dao.upsertDefinitionSteps(payload.definitionSteps.map { it.toEntity() })
        dao.upsertRecurrenceExceptions(payload.recurrenceExceptions.map { it.toEntity() })
        dao.upsertInstances(payload.instances.map { it.toEntity() })
        dao.upsertInstanceSteps(payload.instanceSteps.map { it.toEntity() })
        dao.upsertProgress(payload.progress.map { it.toEntity() })
        dao.upsertInformation(payload.information.map { it.toEntity() })
        dao.upsertMoods(payload.moods.map { it.toEntity() })
        dao.upsertNotes(payload.notes.map { it.toEntity() })
        dao.upsertLedger(payload.ledger.map { it.toEntity() })
        dao.upsertActionLogs(payload.actionLogs.map { it.toEntity() })
        dao.upsertResultRevisions(payload.resultRevisions.map { it.toEntity() })
    }
}

internal object BackupMerger {
    fun merge(local: BackupPayload, backup: BackupPayload, backupChoices: Set<String>): MergePreview {
        val state = MergeState(backupChoices)
        val profiles = when {
            local.profiles.isEmpty() -> backup.profiles.also { state.added += it.size }
            backup.profiles.isEmpty() -> local.profiles
            local.profiles.first().domName.isBlank() && backup.profiles.first().domName.isNotBlank() ->
                backup.profiles.also { state.updated++ }
            else -> local.profiles.also { state.keptLocal++ }
        }
        val importBatches = immutable(
            local.importBatches, backup.importBatches, ImportBatchBackup::batchId, "导入批次", state,
        )
        val groups = updated(
            local.groups, backup.groups, GroupBackup::groupId, GroupBackup::updatedAtEpochMillis,
            "group", "积分组", GroupBackup::name, { it.toString() }, state,
        )
        val definitions = updated(
            local.definitions, backup.definitions, DefinitionBackup::taskId, DefinitionBackup::updatedAtEpochMillis,
            "task", "任务", DefinitionBackup::name, { summarizeDefinition(it) }, state,
        )
        // Definition steps are also a snapshot: use the same source as the
        // selected definition so a reorder cannot combine two revisions.
        val definitionSteps = definitions.flatMap { definition ->
            val taskId = definition.taskId
            val localDefinition = local.definitions.firstOrNull { it.taskId == taskId }
            val backupDefinition = backup.definitions.firstOrNull { it.taskId == taskId }
            val localSteps = local.definitionSteps.filter { it.taskId == taskId }
            val backupSteps = backup.definitionSteps.filter { it.taskId == taskId }
            val source = when {
                localDefinition == null -> backupSteps
                backupDefinition == null -> localSteps
                localDefinition != backupDefinition -> if (definition == backupDefinition) backupSteps else localSteps
                localSteps == backupSteps -> localSteps
                else -> {
                    val conflictId = "definition-steps:$taskId"
                    state.conflicts += MergeConflict(conflictId, "任务步骤", definition.name, localSteps.toString(), backupSteps.toString())
                    if (conflictId in state.backupChoices) backupSteps.also { state.updated++ }
                    else localSteps.also { state.keptLocal++ }
                }
            }
            source
        }
        val recurrenceExceptions = updated(
            local.recurrenceExceptions, backup.recurrenceExceptions,
            { "${it.taskId}|${it.occurrenceDate}" }, RecurrenceExceptionBackup::updatedAtEpochMillis,
            "recurrence-exception", "单日例外", { "${it.taskId} / ${it.occurrenceDate}" },
            { if (it.cancelled) "已撤销" else it.patchJson }, state,
        )
        val instances = updated(
            local.instances, backup.instances, { "${it.taskId}|${it.occurrenceKey}" }, InstanceBackup::updatedAtEpochMillis,
            "instance", "任务实例", InstanceBackup::name, { summarizeInstance(it) }, state,
        )
        // Step state is an instance snapshot. Select the complete source set with its
        // parent instance; never merge individual positions from different revisions.
        val instanceSteps = instances.flatMap { instance ->
            val key = instance.taskId to instance.occurrenceKey
            val localInstance = local.instances.firstOrNull { it.taskId == key.first && it.occurrenceKey == key.second }
            val backupInstance = backup.instances.firstOrNull { it.taskId == key.first && it.occurrenceKey == key.second }
            val localSteps = local.instanceSteps.filter { it.taskId == key.first && it.occurrenceKey == key.second }
            val backupSteps = backup.instanceSteps.filter { it.taskId == key.first && it.occurrenceKey == key.second }
            when {
                localInstance == null -> backupSteps
                backupInstance == null -> localSteps
                localInstance != backupInstance -> if (instance == backupInstance) backupSteps else localSteps
                localSteps == backupSteps -> localSteps
                else -> {
                    val conflictId = "instance-steps:${key.first}|${key.second}"
                    state.conflicts += MergeConflict(conflictId, "实例步骤", instance.name, localSteps.toString(), backupSteps.toString())
                    if (conflictId in state.backupChoices) backupSteps.also { state.updated++ } else localSteps.also { state.keptLocal++ }
                }
            }
        }
        val choiceKeys = instances.filter { it.executionKind == "CHOICE" }.mapTo(hashSetOf()) { it.taskId to it.occurrenceKey }
        val progress = updated(
            local.progress.filter { (it.taskId to it.occurrenceKey) !in choiceKeys }, backup.progress.filter { (it.taskId to it.occurrenceKey) !in choiceKeys }, { "${it.taskId}|${it.occurrenceKey}" }, ProgressBackup::updatedAtEpochMillis,
            "progress", "任务进度", { it.taskId }, { "计数 ${it.counterValue ?: 0}，计时 ${it.elapsedMillis ?: 0} 毫秒" }, state,
        ) + instances.filter { it.executionKind == "CHOICE" }.mapNotNull { instance ->
            val source = if (instance in local.instances) local else backup
            source.progress.firstOrNull { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }
        }
        val information = updated(
            local.information, backup.information, { "${it.taskId}|${it.occurrenceKey}" }, InformationBackup::updatedAtEpochMillis,
            "information", "告知正文", { it.taskId }, InformationBackup::content, state,
        )
        val draftMoodKeys = instances.filter { it.executionKind == "MOOD" && it.status != "COMPLETED" }
            .mapTo(hashSetOf()) { it.taskId to it.occurrenceKey }
        val moodCandidates = updated(
            local.moods.filter { (it.taskId to it.occurrenceKey) in draftMoodKeys },
            backup.moods.filter { (it.taskId to it.occurrenceKey) in draftMoodKeys },
            { "${it.taskId}|${it.occurrenceKey}" }, MoodBackup::updatedAtEpochMillis,
            "mood", "心情记录", { it.taskId }, { "${it.rating ?: "未选择"}：${it.text}" }, state,
        )
        // Completed answers belong to the selected instance snapshot, not to a newer draft.
        val moods = instances.filter { it.executionKind == "MOOD" }.mapNotNull { instance ->
            val key = instance.taskId to instance.occurrenceKey
            val source = if (instance.status == "COMPLETED") {
                if (local.instances.any { it == instance }) local.moods else backup.moods
            } else moodCandidates
            source.firstOrNull { (it.taskId to it.occurrenceKey) == key }?.let {
                if (instance.status == "COMPLETED") it else it.copy(submittedAtEpochMillis = null)
            }
        }
        val notes = updated(
            local.notes, backup.notes, { "${it.taskId}|${it.occurrenceKey}" }, NoteBackup::updatedAtEpochMillis,
            "note", "任务备注", { it.taskId }, NoteBackup::content, state,
        )
        val scoredKeys = instances.filter { it.executionKind in setOf("CHOICE", "NOTICE", "STEPS") }.mapTo(hashSetOf()) { it.taskId to it.occurrenceKey }
        val ledger = immutable(local.ledger.filter { (it.taskId to it.occurrenceKey) !in scoredKeys }, backup.ledger.filter { (it.taskId to it.occurrenceKey) !in scoredKeys }, LedgerBackup::ledgerId, "积分流水", state) +
            instances.filter { (it.taskId to it.occurrenceKey) in scoredKeys }.flatMap { instance ->
                val fromBackup = instance !in local.instances ||
                    (instance in backup.instances && "instance-steps:${instance.taskId}|${instance.occurrenceKey}" in state.backupChoices)
                val source = if (fromBackup) backup else local
                source.ledger.filter { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }
            }
        val actionLogs = immutable(local.actionLogs, backup.actionLogs, ActionLogBackup::eventId, "操作记录", state)
        val resultRevisions = immutable(
            local.resultRevisions, backup.resultRevisions, ResultRevisionBackup::revisionId, "结果版本", state,
        )
        return MergePreview(
            merged = BackupPayload(
                settings = local.settings,
                profiles = profiles.sortedBy { it.id },
                importBatches = importBatches.sortedBy { it.batchId },
                groups = groups.sortedBy { it.groupId },
                definitions = definitions.sortedBy { it.taskId },
                definitionSteps = definitionSteps.sortedWith(compareBy({ it.taskId }, { it.position })),
                recurrenceExceptions = recurrenceExceptions.sortedWith(compareBy({ it.taskId }, { it.occurrenceDate })),
                instances = instances.map { it.copy(awardedPoints = it.awardedPoints ?: it.points.takeIf { _ -> it.status == "COMPLETED" }) }.sortedWith(compareBy({ it.taskId }, { it.occurrenceKey })),
                instanceSteps = instanceSteps.sortedWith(compareBy({ it.taskId }, { it.occurrenceKey }, { it.position })),
                progress = progress.sortedWith(compareBy({ it.taskId }, { it.occurrenceKey })),
                information = information.sortedWith(compareBy({ it.taskId }, { it.occurrenceKey })),
                moods = moods.sortedWith(compareBy({ it.taskId }, { it.occurrenceKey })),
                notes = notes.sortedWith(compareBy({ it.taskId }, { it.occurrenceKey })),
                ledger = ledger.sortedBy { it.ledgerId },
                actionLogs = actionLogs.sortedBy { it.eventId },
                resultRevisions = resultRevisions.sortedBy { it.revisionId },
            ),
            conflicts = state.conflicts,
            added = state.added,
            updated = state.updated,
            keptLocal = state.keptLocal,
        )
    }

    private fun <T, K> updated(
        local: List<T>, backup: List<T>, key: (T) -> K, timestamp: (T) -> Long,
        idPrefix: String, category: String, title: (T) -> String, summary: (T) -> String, state: MergeState,
    ): List<T> {
        val localByKey = local.associateBy(key)
        val backupByKey = backup.associateBy(key)
        return (localByKey.keys + backupByKey.keys).map { itemKey ->
            val localItem = localByKey[itemKey]
            val backupItem = backupByKey[itemKey]
            when {
                localItem == null -> backupItem!!.also { state.added++ }
                backupItem == null || localItem == backupItem -> localItem
                timestamp(backupItem) > timestamp(localItem) -> backupItem.also { state.updated++ }
                timestamp(backupItem) < timestamp(localItem) -> localItem.also { state.keptLocal++ }
                else -> {
                    val id = "$idPrefix:$itemKey"
                    state.conflicts += MergeConflict(id, category, title(localItem), summary(localItem), summary(backupItem))
                    if (id in state.backupChoices) backupItem.also { state.updated++ }
                    else localItem.also { state.keptLocal++ }
                }
            }
        }
    }

    private fun <T, K> selectable(
        local: List<T>, backup: List<T>, key: (T) -> K, idPrefix: String, category: String,
        title: (T) -> String, summary: (T) -> String, state: MergeState,
    ): List<T> {
        val localByKey = local.associateBy(key)
        val backupByKey = backup.associateBy(key)
        return (localByKey.keys + backupByKey.keys).map { itemKey ->
            val localItem = localByKey[itemKey]
            val backupItem = backupByKey[itemKey]
            when {
                localItem == null -> backupItem!!.also { state.added++ }
                backupItem == null || localItem == backupItem -> localItem
                else -> {
                    val id = "$idPrefix:$itemKey"
                    state.conflicts += MergeConflict(id, category, title(localItem), summary(localItem), summary(backupItem))
                    if (id in state.backupChoices) backupItem.also { state.updated++ }
                    else localItem.also { state.keptLocal++ }
                }
            }
        }
    }

    private fun <T, K> immutable(
        local: List<T>, backup: List<T>, key: (T) -> K, label: String, state: MergeState,
    ): List<T> {
        val localByKey = local.associateBy(key)
        val result = local.toMutableList()
        backup.forEach { item ->
            val existing = localByKey[key(item)]
            when {
                existing == null -> { result += item; state.added++ }
                existing != item -> throw DstbException("相同 ID 的${label}内容不一致，无法安全合并")
            }
        }
        return result
    }

    private fun summarizeDefinition(value: DefinitionBackup) =
        "${value.name}；${value.points} 分；${value.deadline ?: "无截止时间"}；${if (value.cancelled) "已取消" else "有效"}"

    private fun summarizeInstance(value: InstanceBackup) =
        "${value.name}；${value.taskDate}；${value.deadline ?: "无截止时间"}；${value.status}"

    private class MergeState(val backupChoices: Set<String>) {
        val conflicts = mutableListOf<MergeConflict>()
        var added = 0
        var updated = 0
        var keptLocal = 0
    }
}
