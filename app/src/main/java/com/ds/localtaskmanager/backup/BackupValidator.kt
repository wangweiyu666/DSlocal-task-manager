package com.ds.localtaskmanager.backup

import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.data.branchState
import com.ds.localtaskmanager.domain.execution.*
import com.ds.localtaskmanager.protocol.*
import kotlinx.serialization.json.*
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
        if (payload.schemaVersion !in 1..5) throw DstbException("不支持的备份数据版本：${payload.schemaVersion}")
        if (payload.schemaVersion < 5 && (payload.definitions.any { it.executionConfigJson != null } || payload.instances.any { it.executionConfigJson != null } || payload.definitionSteps.any { it.executionConfigJson != null || it.conditionStepId != null || it.conditionOptionId != null } || payload.instanceSteps.any { it.executionConfigJson != null || it.conditionStepId != null || it.conditionOptionId != null || it.selectedOptionId != null } || payload.progress.any { it.selectedOptionId != null })) throw DstbException("通知、选项和条件需要备份数据 v5")
        if (payload.schemaVersion < 3 && (payload.moods.isNotEmpty() || payload.definitions.any { it.executionKind == "MOOD" } || payload.instances.any { it.executionKind == "MOOD" })) {
            throw DstbException("心情记录需要备份数据 v3")
        }
        if (payload.schemaVersion < 4 && (payload.definitions.any { it.executionKind == "STEPS" } || payload.instances.any { it.executionKind == "STEPS" })) {
            throw DstbException("分步骤任务需要备份数据 v4")
        }
        if (payload.schemaVersion == 1 &&
            (payload.recurrenceExceptions.isNotEmpty() || payload.instances.any { it.singleDayAdjusted })
        ) throw DstbException("备份数据 v1 不能包含单日例外")
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
        unique(payload.recurrenceExceptions, { "${it.taskId}|${it.occurrenceDate}" }, "单日例外")
        unique(payload.instanceSteps, { "${it.taskId}|${it.occurrenceKey}|${it.position}" }, "实例步骤")
        unique(payload.progress, { "${it.taskId}|${it.occurrenceKey}" }, "任务进度")
        unique(payload.information, { "${it.taskId}|${it.occurrenceKey}" }, "告知正文")
        unique(payload.moods, { "${it.taskId}|${it.occurrenceKey}" }, "心情记录")
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
            if (it.executionKind == "STEPS") {
                val steps = payload.definitionSteps.filter { step -> step.taskId == it.taskId }
                if (steps.isEmpty() || steps.size > 50 || steps.any { step -> step.stepId == null } ||
                    steps.mapNotNull { step -> step.stepId }.distinct().size != steps.size ||
                    steps.map { it.position }.distinct().size != steps.size ||
                    steps.map { it.position }.sorted() != (0 until steps.size).toList() ||
                    steps.any { step -> step.executionKind == "STEPS" || !validLeafConfig(step.executionKind, step.executionAction, step.executionTarget, step.executionConfigJson) }) {
                    throw DstbException("STEPS 任务“${it.name}”的步骤定义无效")
                }
            }
            if (it.points !in -1_000_000..1_000_000) throw DstbException("任务“${it.name}”的积分超出范围")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "任务“${it.name}”")
        }
        payload.definitionSteps.forEach {
            if (it.taskId !in taskIds) throw DstbException("任务步骤引用了不存在的任务")
            if (it.position < 0) throw DstbException("任务步骤位置无效")
            val definition = payload.definitions.first { definition -> definition.taskId == it.taskId }
            if (payload.schemaVersion >= 4 && definition.executionKind == "STEPS" && (it.stepId == null || !STEP_ID.matches(it.stepId))) {
                throw DstbException("任务步骤 ID 无效")
            }
        }
        payload.definitionSteps.filter { it.executionKind == "STEPS" }.forEach {
            throw DstbException("步骤不能嵌套 STEPS 执行方式")
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
        payload.recurrenceExceptions.forEach {
            if (it.taskId !in taskIds) throw DstbException("单日例外引用了不存在的任务")
            val definition = payload.definitions.first { definition -> definition.taskId == it.taskId }
            if (definition.recurrenceFrequency == null) throw DstbException("单日例外目标不是重复任务")
            parseDate(it.occurrenceDate, "单日例外日期")
            if (it.patchJson.isBlank() || it.patchJson.length > 16_384) throw DstbException("单日例外内容无效")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "单日例外")
        }
        payload.instanceSteps.forEach {
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "实例步骤")
            if (it.position < 0 || it.updatedAtEpochMillis > snapshotLimit) throw DstbException("实例步骤数据无效")
            val instance = payload.instances.first { instance -> instance.taskId == it.taskId && instance.occurrenceKey == it.occurrenceKey }
            if (payload.schemaVersion >= 4 && instance.executionKind == "STEPS" && !STEP_ID.matches(it.stepId)) throw DstbException("实例步骤 ID 无效")
            if (payload.schemaVersion >= 4 && instance.executionKind == "STEPS" && it.stepStatus !in STEP_STATUSES) throw DstbException("实例步骤状态无效")
            if (payload.schemaVersion >= 4 && instance.executionKind == "STEPS") {
                if (it.completed != (it.stepStatus == "CONFIRMED")) throw DstbException("实例步骤完成状态不一致")
                if (it.stepStatus == "SKIPPED" && it.required) throw DstbException("必需步骤不能跳过")
                if (!validLeafConfig(it.executionKind, it.executionAction, it.executionTarget, it.executionConfigJson)) throw DstbException("实例步骤执行配置无效")
                if (it.stepStatus == "CONFIRMED" && !validLeafAnswer(it)) throw DstbException("实例步骤答案未达成目标")
            }
            if (it.counterValue != null && it.counterValue < 0) throw DstbException("实例步骤计数无效")
            if (it.elapsedMillis != null && it.elapsedMillis < 0) throw DstbException("实例步骤计时无效")
            if (it.informationContent != null && it.informationContent.codePointCount(0, it.informationContent.length) > 2_000) throw DstbException("实例步骤正文过长")
            if (it.moodRating != null && it.moodRating !in 1..5) throw DstbException("实例步骤心情无效")
            if (it.moodText != null && it.moodText.codePointCount(0, it.moodText.length) > 2_000) throw DstbException("实例步骤感受过长")
        }
        if (payload.schemaVersion >= 4) {
            payload.instances.filter { it.executionKind == "STEPS" }.forEach { instance ->
                val steps = payload.instanceSteps.filter { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }
                if (steps.isEmpty() || steps.size > 50 || steps.map { it.position }.sorted() != (0 until steps.size).toList() ||
                    steps.map { it.stepId }.distinct().size != steps.size) throw DstbException("STEPS 实例步骤快照无效")
                val ordered = steps.sortedBy { it.position }
                val applicability = stepApplicability(ordered.map { it.toEntity().branchState() })
                var pendingSeen = false
                ordered.forEachIndexed { index, step ->
                    if (applicability[index] == StepApplicability.APPLICABLE) {
                        if (step.stepStatus == "NOT_APPLICABLE") throw DstbException("适用步骤不能标记不适用")
                        if (step.stepStatus == "PENDING") pendingSeen = true
                        else if (pendingSeen) throw DstbException("STEPS 实例步骤顺序无效")
                    } else if (step.stepStatus in setOf("CONFIRMED", "SKIPPED") || (instance.status == "COMPLETED" && step.stepStatus != "NOT_APPLICABLE")) throw DstbException("分支状态不一致")
                }
                if (instance.status == "COMPLETED" && (instance.completedAtEpochMillis == null || steps.any { it.stepStatus == "PENDING" })) {
                    throw DstbException("已完成 STEPS 实例缺少完整步骤")
                }
            }
            payload.instanceSteps.filter { step ->
                payload.instances.any { it.taskId == step.taskId && it.occurrenceKey == step.occurrenceKey && it.executionKind == "STEPS" }
            }.groupBy { Triple(it.taskId, it.occurrenceKey, it.stepId) }.values
                .firstOrNull { it.size > 1 }?.let { throw DstbException("实例步骤 ID 重复") }
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
        payload.moods.forEach {
            requireInstance(it.taskId, it.occurrenceKey, instanceKeys, "心情记录")
            requireTimes(it.createdAtEpochMillis, it.updatedAtEpochMillis, snapshotLimit, "心情记录")
            val instance = payload.instances.first { item -> item.taskId == it.taskId && item.occurrenceKey == it.occurrenceKey }
            if (instance.executionKind != "MOOD" || (it.rating != null && it.rating !in 1..5) || it.text.codePointCount(0, it.text.length) > 2000) throw DstbException("心情记录内容无效")
            if (it.submittedAtEpochMillis != null && (it.submittedAtEpochMillis !in it.createdAtEpochMillis..it.updatedAtEpochMillis || it.rating == null)) throw DstbException("心情提交时间或答案无效")
            if ((instance.status == "COMPLETED") != (it.submittedAtEpochMillis != null)) throw DstbException("心情记录与完成状态不一致")
            if (instance.status == "COMPLETED" && it.submittedAtEpochMillis != instance.completedAtEpochMillis) throw DstbException("心情答案与实例完成时间不一致")
        }
        payload.instances.filter { it.executionKind == "MOOD" && it.status == "COMPLETED" }.forEach { instance ->
            if (payload.moods.none { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }) throw DstbException("已完成的心情任务缺少答案")
        }
        validateExtendedSnapshots(payload)
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

    internal fun validateExtendedSnapshots(payload: BackupPayload) {
        fun spec(kind: String, action: Int?, target: Int?, config: String?): ExecutionSpec = when (kind) {
            "STEPS" -> ExecutionSpec.Steps
            "NOTICE", "CHOICE" -> extendedExecution(kind, config)
            "COUNTER" -> ExecutionSpec.Counter(if (action == 1) CounterAction.SLIDER else CounterAction.CLICK, requireNotNull(target))
            "TIMER" -> ExecutionSpec.Timer(requireNotNull(target))
            "INFORMATION" -> ExecutionSpec.Information
            "MOOD" -> ExecutionSpec.Mood
            else -> ExecutionSpec.Normal
        }
        fun condition(source: String?, option: String?): StepCondition? {
            require((source == null) == (option == null))
            return source?.let { require(STEP_ID.matches(it) && STEP_ID.matches(option!!)); StepCondition(it, option) }
        }
        try {
            payload.definitions.forEach { definition ->
                val execution = spec(definition.executionKind, definition.executionAction, definition.executionTarget, definition.executionConfigJson)
                val steps = payload.definitionSteps.filter { it.taskId == definition.taskId }.sortedBy { it.position }.map {
                    DstStep(it.name, it.required, it.stepId, spec(it.executionKind, it.executionAction, it.executionTarget, it.executionConfigJson), condition(it.conditionStepId, it.conditionOptionId))
                }
                validateConditionalDefinition(execution, steps)
                payload.recurrenceExceptions.filter { it.taskId == definition.taskId }.forEach { exception ->
                    val raw = Json.parseToJsonElement(exception.patchJson).jsonObject
                    require(raw["i"]?.jsonPrimitive?.content == definition.taskId && raw["y"]?.jsonPrimitive?.content == exception.occurrenceDate)
                    // Early backups allowed local IDs longer/shorter than transport IDs.
                    val portable = JsonObject(raw + ("i" to JsonPrimitive("BackupTask000001")))
                    val patch = Dst1Parser().parseExceptionJson(portable.toString())
                    val effectiveExecution = (patch.execution as? Field.Value)?.value ?: execution
                    val effectiveSteps = (patch.steps as? Field.Value)?.value ?: steps
                    validateConditionalDefinition(effectiveExecution, effectiveSteps)
                    if (effectiveExecution is ExecutionSpec.Steps) require(effectiveSteps.size in 1..50 && effectiveSteps.all { it.id != null } && effectiveSteps.map { it.id }.distinct().size == effectiveSteps.size)
                }
            }
            payload.instances.forEach { instance ->
                val execution = spec(instance.executionKind, instance.executionAction, instance.executionTarget, instance.executionConfigJson)
                val steps = payload.instanceSteps.filter { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }.sortedBy { it.position }
                validateConditionalDefinition(execution, steps.map { DstStep(it.name, it.required, it.stepId, spec(it.executionKind, it.executionAction, it.executionTarget, it.executionConfigJson), condition(it.conditionStepId, it.conditionOptionId)) })
                val progress = payload.progress.firstOrNull { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }
                progress?.selectedOptionId?.let { id -> require(execution is ExecutionSpec.Choice && execution.options.any { it.id == id }) }
                steps.forEach { step -> step.selectedOptionId?.let { id -> require(step.executionKind == "CHOICE" && choiceOptions(step.executionConfigJson).any { it.id == id }) } }
                val hasExtended = instance.executionKind in setOf("CHOICE", "NOTICE") || steps.any { it.executionConfigJson != null || it.conditionStepId != null }
                if (hasExtended && execution is ExecutionSpec.Steps) {
                    val states = steps.map { it.toEntity().branchState() }
                    val branches = stepApplicability(states)
                    steps.forEachIndexed { index, step ->
                        require(step.completed == (step.stepStatus == "CONFIRMED"))
                        if (branches[index] == StepApplicability.APPLICABLE) {
                            require(step.stepStatus in setOf("PENDING", "CONFIRMED", "SKIPPED"))
                            require(step.stepStatus != "SKIPPED" || !step.required)
                            if (step.stepStatus == "CONFIRMED") require(validLeafAnswer(step))
                        } else require(step.stepStatus in setOf("PENDING", "NOT_APPLICABLE"))
                    }
                    if (instance.status == "COMPLETED") require(finalizedSteps(states) == states)
                }
                if (instance.status == "COMPLETED") {
                    val extra = when (execution) {
                        is ExecutionSpec.Choice -> execution.options.single { it.id == progress?.selectedOptionId }.points
                        is ExecutionSpec.Steps -> confirmedChoicePoints(steps.map { it.toEntity().branchState() })
                        else -> 0
                    }
                    if (payload.schemaVersion >= 5) require(instance.awardedPoints == instance.points + extra)
                } else require(instance.awardedPoints == null)
                if (hasExtended) require(payload.ledger.filter { it.taskId == instance.taskId && it.occurrenceKey == instance.occurrenceKey }.sumOf { it.delta } == (instance.awardedPoints ?: 0))
            }
        } catch (error: Exception) {
            throw DstbException("备份执行配置、分支或积分快照无效：${error.message}")
        }
    }

    private fun validLeafConfig(kind: String, action: Int?, target: Int?, config: String? = null): Boolean = when (kind) {
        "NOTICE", "CHOICE" -> action == null && target == null && runCatching { extendedExecution(kind, config) }.isSuccess
        "NORMAL" -> action == null && target == null
        "COUNTER" -> action in 1..2 && target in 1..999
        "TIMER" -> action == null && target in 1..3_600
        "INFORMATION", "MOOD" -> action == null && target == null
        else -> false
    }

    private fun validLeafAnswer(step: InstanceStepBackup): Boolean = when (step.executionKind) {
        "NOTICE" -> true
        "CHOICE" -> choiceOptions(step.executionConfigJson).any { it.id == step.selectedOptionId }
        "NORMAL" -> true
        "COUNTER" -> step.counterValue == step.executionTarget
        "TIMER" -> step.elapsedMillis == (step.executionTarget ?: 0) * 1_000L
        "INFORMATION" -> step.informationContent?.let { raw -> raw.isNotBlank() && raw.trim().codePointCount(0, raw.trim().length) <= 2_000 } == true
        "MOOD" -> step.moodRating in 1..5 && (step.moodText ?: "").codePointCount(0, (step.moodText ?: "").length) <= 2_000
        else -> false
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
    private val EXECUTION_KINDS = setOf("NORMAL", "COUNTER", "TIMER", "INFORMATION", "MOOD", "STEPS", "NOTICE", "CHOICE")
    private val STEP_ID = Regex("[A-Za-z0-9_-]{16}")
    private val STEP_STATUSES = setOf("PENDING", "CONFIRMED", "SKIPPED", "NOT_APPLICABLE")
    private val INSTANCE_CATEGORIES = setOf("DAILY", "WEEKLY", "TEMPORARY")
    private val RESULT_SCOPES = setOf("GLOBAL", "GROUP")
}
