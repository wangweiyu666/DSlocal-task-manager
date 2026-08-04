package com.ds.localtaskmanager.backup

import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.settings.AppThemeMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object BackupValidator {
    private const val CLOCK_TOLERANCE_MILLIS = 5 * 60 * 1000L

    fun validate(decoded: DecodedBackup) {
        if (decoded.metadata.createdAtEpochMillis < 0) throw DstbException("备份创建时间无效")
        val payload = decoded.payload
        if (payload.schemaVersion != 1) throw DstbException("不支持的备份数据版本：${payload.schemaVersion}")
        runCatching { AppThemeMode.valueOf(payload.settings.themeMode) }
            .getOrElse { throw DstbException("备份中的主题设置无效") }
        runCatching { StatisticsPeriod.valueOf(payload.settings.lastStatisticsPeriod) }
            .getOrElse { throw DstbException("备份中的统计周期无效") }

        unique(payload.profiles, { it.id }, "个人资料")
        unique(payload.importBatches, { it.batchId }, "导入批次")
        unique(payload.groups, { it.groupId }, "积分组")
        unique(payload.definitions, { it.taskId }, "任务")
        unique(payload.definitionSteps, { "${it.taskId}|${it.position}" }, "任务步骤")
        unique(payload.instances, { "${it.taskId}|${it.occurrenceKey}" }, "任务实例")
        unique(payload.instanceSteps, { "${it.taskId}|${it.occurrenceKey}|${it.position}" }, "实例步骤")
        unique(payload.progress, { "${it.taskId}|${it.occurrenceKey}" }, "任务进度")
        unique(payload.information, { "${it.taskId}|${it.occurrenceKey}" }, "告知正文")
        unique(payload.notes, { "${it.taskId}|${it.occurrenceKey}" }, "任务备注")
        unique(payload.ledger, { it.ledgerId }, "积分流水")
        unique(payload.actionLogs, { it.eventId }, "操作记录")
        unique(payload.resultRevisions, { it.revisionId }, "结果版本")

        val groupIds = payload.groups.mapTo(hashSetOf()) { it.groupId }
        val taskIds = payload.definitions.mapTo(hashSetOf()) { it.taskId }
        val batchIds = payload.importBatches.mapTo(hashSetOf()) { it.batchId }
        val instanceKeys = payload.instances.mapTo(hashSetOf()) { it.taskId to it.occurrenceKey }
        val snapshotLimit = decoded.metadata.createdAtEpochMillis + CLOCK_TOLERANCE_MILLIS

        if (decoded.metadata.counts != BackupCounts(
                groups = payload.groups.size,
                tasks = payload.definitions.size,
                instances = payload.instances.size,
                ledgerEntries = payload.ledger.size,
                actionLogs = payload.actionLogs.size,
                resultRevisions = payload.resultRevisions.size,
            )
        ) throw DstbException("备份摘要与实际内容不一致")

        payload.profiles.forEach {
            if (it.id != 1 || it.updatedAtEpochMillis !in 0..snapshotLimit) throw DstbException("个人资料数据无效")
        }
        payload.importBatches.forEach {
            requireId(it.batchId, "导入批次 ID")
            requireEventTime(it.importedAtEpochMillis, snapshotLimit, "导入批次")
        }

        payload.groups.forEach {
            requireId(it.groupId, "积分组 ID")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "积分组 ${it.name}")
        }
        payload.definitions.forEach {
            requireId(it.taskId, "任务 ID")
            if (it.groupId != null && it.groupId !in groupIds) throw DstbException("任务“${it.name}”引用了不存在的积分组")
            parseDate(it.taskDate, "任务“${it.name}”的日期")
            it.deadline?.let { value -> parseDateTime(value, "任务“${it.name}”的截止时间") }
            it.recurrenceStartDate?.let { value -> parseDate(value, "任务“${it.name}”的重复开始日期") }
            it.recurrenceEndDate?.let { value -> parseDate(value, "任务“${it.name}”的重复结束日期") }
            it.recurrenceDeadlineTime?.let { value -> parseTime(value, "任务“${it.name}”的重复截止时间") }
            if (it.recurrenceCount != null && it.recurrenceCount <= 0) throw DstbException("任务“${it.name}”的重复次数无效")
            if (it.recurrenceFrequency != null && it.recurrenceFrequency !in 1..2) throw DstbException("任务“${it.name}”的重复频率无效")
            if (it.executionKind !in EXECUTION_KINDS) throw DstbException("任务“${it.name}”的执行方式无效")
            if (it.points !in -1_000_000..1_000_000) throw DstbException("任务“${it.name}”的积分超出范围")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "任务“${it.name}”")
        }
        payload.definitionSteps.forEach {
            if (it.taskId !in taskIds) throw DstbException("任务步骤引用了不存在的任务")
            if (it.position < 0) throw DstbException("任务步骤位置无效")
        }
        payload.instances.forEach {
            val label = "任务实例“${it.name}”"
            if (it.taskId !in taskIds) throw DstbException("$label 引用了不存在的任务")
            if (it.groupId != null && it.groupId !in groupIds) throw DstbException("$label 引用了不存在的积分组")
            requireId(it.occurrenceKey, "任务实例键")
            parseDate(it.taskDate, "$label 的日期")
            it.deadline?.let { value -> parseDateTime(value, "$label 的截止时间") }
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, label)
            if (it.status !in TASK_STATUSES || it.category !in INSTANCE_CATEGORIES || it.executionKind !in EXECUTION_KINDS) {
                throw DstbException("$label 的状态或执行方式无效")
            }
            if (it.completedAtEpochMillis != null && it.completedAtEpochMillis > snapshotLimit) throw DstbException("$label 的完成时间晚于备份时间")
        }
        payload.instanceSteps.forEach {
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "实例步骤")
            if (it.position < 0 || it.updatedAtEpochMillis > snapshotLimit) throw DstbException("实例步骤数据无效")
        }
        payload.progress.forEach {
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "任务进度")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "任务进度")
            if ((it.counterValue ?: 0) < 0 || (it.elapsedMillis ?: 0) < 0) throw DstbException("任务进度数值无效")
            if (it.executionKind !in EXECUTION_KINDS) throw DstbException("任务进度执行方式无效")
        }
        payload.information.forEach {
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "告知正文")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "告知正文")
            if (it.submittedAtEpochMillis != null && it.submittedAtEpochMillis > snapshotLimit) throw DstbException("告知提交时间晚于备份时间")
        }
        payload.notes.forEach {
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "任务备注")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "任务备注")
        }
        payload.ledger.forEach {
            requireId(it.ledgerId, "积分流水 ID")
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "积分流水")
            if (it.groupId != null && it.groupId !in groupIds) throw DstbException("积分流水引用了不存在的积分组")
            requireEventTime(it.createdAtEpochMillis, snapshotLimit, "积分流水")
            if (it.delta !in -1_000_000..1_000_000) throw DstbException("积分流水数值超出范围")
        }
        payload.actionLogs.forEach {
            requireId(it.eventId, "操作记录 ID")
            when {
                it.taskId != null && it.occurrenceKey != null -> requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "操作记录")
                it.taskId != null && it.taskId !in taskIds -> throw DstbException("操作记录引用了不存在的任务")
                it.taskId == null && it.occurrenceKey != null -> throw DstbException("操作记录的任务引用不完整")
            }
            if (it.batchId != null && it.batchId !in batchIds) throw DstbException("操作记录引用了不存在的导入批次")
            requireEventTime(it.createdAtEpochMillis, snapshotLimit, "操作记录")
        }
        payload.resultRevisions.forEach {
            requireId(it.revisionId, "结果版本 ID")
            parseDate(it.taskDate, "结果版本日期")
            if (it.groupId != null && it.groupId !in groupIds) throw DstbException("结果版本引用了不存在的积分组")
            if (it.batchId != null && it.batchId !in batchIds) throw DstbException("结果版本引用了不存在的导入批次")
            requireEventTime(it.createdAtEpochMillis, snapshotLimit, "结果版本")
            if (it.scope !in RESULT_SCOPES) throw DstbException("结果版本范围无效")
        }
    }

    private fun <T, K> unique(values: List<T>, key: (T) -> K, label: String) {
        val seen = HashSet<K>()
        values.forEach { if (!seen.add(key(it))) throw DstbException("备份中存在重复的$label") }
    }

    private fun requireId(value: String, label: String) {
        if (value.isBlank() || value.length > 256) throw DstbException("$label 无效")
    }

    private fun requireTimes(created: Long, updated: Long, snapshotLimit: Long, label: String) {
        if (created < 0 || updated < created || updated > snapshotLimit) throw DstbException("$label 的时间关系无效")
    }

    private fun requireEventTime(value: Long, snapshotLimit: Long, label: String) {
        if (value !in 0..snapshotLimit) throw DstbException("$label 时间晚于备份时间或无效")
    }

    private fun requireInstance(taskId: String, occurrenceKey: String, keys: Set<Pair<String, String>>, label: String) {
        if ((taskId to occurrenceKey) !in keys) throw DstbException("$label 引用了不存在的任务实例")
    }

    private fun parseDate(value: String, label: String) {
        runCatching { LocalDate.parse(value) }.getOrElse { throw DstbException("$label 无效") }
    }

    private fun parseTime(value: String, label: String) {
        runCatching { LocalTime.parse(value) }.getOrElse { throw DstbException("$label 无效") }
    }

    private fun parseDateTime(value: String, label: String) {
        runCatching { LocalDateTime.parse(value) }.getOrElse { throw DstbException("$label 无效") }
    }

    private val TASK_STATUSES = TaskStatus.entries.mapTo(hashSetOf()) { it.name }
    private val EXECUTION_KINDS = setOf("NORMAL", "COUNTER", "TIMER", "INFORMATION")
    private val INSTANCE_CATEGORIES = setOf("DAILY", "WEEKLY", "TEMPORARY")
    private val RESULT_SCOPES = setOf("GLOBAL", "GROUP")
}
