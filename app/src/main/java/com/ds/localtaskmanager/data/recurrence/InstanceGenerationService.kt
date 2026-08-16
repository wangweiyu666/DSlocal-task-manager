package com.ds.localtaskmanager.data.recurrence

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.dao.GenerationSummary
import com.ds.localtaskmanager.data.result.ResultRecalculationService
import com.ds.localtaskmanager.data.result.ResultRevisionReason
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.execution.ExecutionSpec
import com.ds.localtaskmanager.domain.recurrence.EffectiveRecurrence
import com.ds.localtaskmanager.domain.recurrence.RecurrenceDeadline
import com.ds.localtaskmanager.domain.recurrence.RecurrenceFrequency
import com.ds.localtaskmanager.domain.recurrence.RecurrencePlanRequest
import com.ds.localtaskmanager.domain.recurrence.RecurrencePlanner
import com.ds.localtaskmanager.domain.recurrence.WeekdayMask
import com.ds.localtaskmanager.domain.recurrence.deadlineFor
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.protocol.Field
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class GenerationResult(
    val created: List<TaskInstanceKey>,
    val alreadyPresent: Int = 0,
)

interface InstanceGenerationService {
    suspend fun reconcileAll(throughDate: LocalDate): GenerationResult
    suspend fun reconcileTask(
        taskId: String,
        throughDate: LocalDate,
        batchId: String? = null,
    ): GenerationResult

    suspend fun previewDefinition(
        definition: TaskDefinitionEntity,
        throughDate: LocalDate,
    ): List<LocalDate>

    suspend fun previewDefinitions(
        definitions: List<TaskDefinitionEntity>,
        throughDate: LocalDate,
    ): Map<String, List<LocalDate>>
}

class RoomInstanceGenerationService(
    private val database: AppDatabase,
    private val clock: Clock,
    private val idGenerator: RecordIdGenerator,
) : InstanceGenerationService {
    private val resultService by lazy { ResultRecalculationService(database, clock, idGenerator) }
    private val exceptionParser = Dst1Parser()
    override suspend fun previewDefinition(
        definition: TaskDefinitionEntity,
        throughDate: LocalDate,
    ): List<LocalDate> = previewDefinitions(listOf(definition), throughDate)[definition.taskId].orEmpty()

    override suspend fun previewDefinitions(
        definitions: List<TaskDefinitionEntity>,
        throughDate: LocalDate,
    ): Map<String, List<LocalDate>> = database.withTransaction {
        val active = definitions.filter { !it.cancelled && it.recurrenceFrequency != null }
        if (active.isEmpty()) return@withTransaction emptyMap()
        val summaries = database.instanceDao().generationSummaries(active.map { it.taskId })
            .associateBy { it.taskId }
        active.associate { definition ->
            definition.taskId to plannedDates(definition, summaries[definition.taskId], throughDate)
        }
    }

    override suspend fun reconcileAll(throughDate: LocalDate): GenerationResult =
        database.withTransaction {
            reconcileExistingStatuses(throughDate)
            val definitions = database.definitionDao().getActiveRecurringDefinitions()
            if (definitions.isEmpty()) return@withTransaction GenerationResult(emptyList())
            val summaries = database.instanceDao().generationSummaries(definitions.map { it.taskId })
                .associateBy { it.taskId }
            val affectedDates = definitions.flatMap { definition ->
                plannedDates(definition, summaries[definition.taskId], throughDate)
            }.map(LocalDate::toString).distinct()
            val before = resultService.capture(affectedDates)
            val created = definitions.flatMap { definition ->
                generate(definition, summaries[definition.taskId], throughDate, null)
            }
            resultService.writeChanges(
                before,
                affectedDates,
                ResultRevisionReason.RECURRENCE_GENERATED,
                null,
                created.map { it.taskId },
            )
            GenerationResult(created)
        }

    private suspend fun reconcileExistingStatuses(throughDate: LocalDate) {
        val candidates = database.instanceDao().getReconcilableInstances(throughDate.toString())
        if (candidates.isEmpty()) return
        val now = clock.millis()
        val nowDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), clock.zone)
        val changed = candidates.mapNotNull { instance ->
            val expected = TaskStateMachine.statusAt(
                LocalDate.parse(instance.taskDate),
                instance.deadline?.let(LocalDateTime::parse),
                nowDateTime,
            )
            instance.takeIf { expected.name != it.status }?.copy(
                status = expected.name,
                updatedAtEpochMillis = now,
            )
        }
        if (changed.isEmpty()) return
        val before = resultService.capture(changed.map { it.taskDate })
        val oldByKey = candidates.associateBy { it.taskId to it.occurrenceKey }
        database.instanceDao().upsertInstances(changed)
        database.auditDao().insertLogs(changed.map { updated ->
            val old = oldByKey.getValue(updated.taskId to updated.occurrenceKey)
            ActionLogEntity(
                eventId = idGenerator.next(),
                taskId = updated.taskId,
                occurrenceKey = updated.occurrenceKey,
                batchId = null,
                action = "STATUS_RECONCILED",
                detail = "${old.status}->${updated.status}",
                createdAtEpochMillis = now,
            )
        })
        resultService.writeChanges(
            before,
            changed.map { it.taskDate },
            ResultRevisionReason.DEADLINE_RECONCILED,
            null,
            changed.map { it.taskId },
        )
    }

    override suspend fun reconcileTask(
        taskId: String,
        throughDate: LocalDate,
        batchId: String?,
    ): GenerationResult =
        database.withTransaction {
            val definition = database.definitionDao().getDefinition(taskId)
                ?.takeIf { !it.cancelled && it.recurrenceFrequency != null }
                ?: return@withTransaction GenerationResult(emptyList())
            val summary = database.instanceDao().generationSummaries(listOf(taskId)).singleOrNull()
            val affectedDates = plannedDates(definition, summary, throughDate).map(LocalDate::toString)
            val before = if (batchId == null) resultService.capture(affectedDates) else emptyMap()
            val created = generate(definition, summary, throughDate, batchId)
            if (batchId == null) {
                resultService.writeChanges(
                    before,
                    affectedDates,
                    ResultRevisionReason.RECURRENCE_GENERATED,
                    null,
                    listOf(taskId),
                )
            }
            GenerationResult(created)
        }

    private suspend fun generate(
        definition: TaskDefinitionEntity,
        summary: GenerationSummary?,
        throughDate: LocalDate,
        batchId: String?,
    ): List<TaskInstanceKey> {
        val recurrence = definition.toEffectiveRecurrence()
        val dates = plannedDates(definition, summary, throughDate)
        if (dates.isEmpty()) return emptyList()

        val stepDefinitions = database.definitionDao().getStepDefinitions(definition.taskId)
        val groupNameSnapshot = definition.groupId?.let { database.definitionDao().getGroup(it)?.name }
        val now = clock.millis()
        val nowDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), clock.zone)
        val created = mutableListOf<TaskInstanceKey>()
        dates.forEach { date ->
            val storedException = database.recurrenceExceptionDao().get(definition.taskId, date.toString())
            val exception = storedException?.let { exceptionParser.parseExceptionJson(it.patchJson) }
            val deadline = exception?.deadline.valueOr(deadlineFor(date, recurrence.deadline))
            val execution = exception?.execution.valueOr(definition.toExecutionSpec()) ?: definition.toExecutionSpec()
            val reminders = exception?.reminders.valueOr(definition.reminderMinutesJson.toReminderList())
                ?: definition.reminderMinutesJson.toReminderList()
            val exceptionSteps = (exception?.steps as? Field.Value)?.value
            val effectiveSteps = exceptionSteps?.mapIndexed { index, step ->
                InstanceStepEntity(
                    taskId = definition.taskId,
                    occurrenceKey = date.toString(),
                    position = index,
                    name = step.name,
                    required = step.required,
                    completed = false,
                    updatedAtEpochMillis = now,
                )
            } ?: stepDefinitions.map { step ->
                InstanceStepEntity(
                    taskId = definition.taskId,
                    occurrenceKey = date.toString(),
                    position = step.position,
                    name = step.name,
                    required = step.required,
                    completed = false,
                    updatedAtEpochMillis = now,
                )
            }
            val instance = TaskInstanceEntity(
                taskId = definition.taskId,
                occurrenceKey = date.toString(),
                name = exception?.name.valueOr(definition.name) ?: definition.name,
                description = exception?.description.valueOr(definition.description) ?: definition.description,
                taskDate = date.toString(),
                deadline = deadline?.toString(),
                groupId = definition.groupId,
                required = exception?.required.valueOr(definition.required) ?: definition.required,
                points = exception?.points.valueOr(definition.points) ?: definition.points,
                sortOrder = exception?.sortOrder.valueOr(definition.sortOrder) ?: definition.sortOrder,
                completionMessage = exception?.completionMessage.valueOr(definition.completionMessage)
                    ?: "任务已完成",
                status = if (exception?.cancelled == true) "CANCELLED" else TaskStateMachine.statusAt(date, deadline, nowDateTime).name,
                completedAtEpochMillis = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                category = recurrence.frequency.category,
                executionKind = execution.kindName(),
                executionAction = execution.actionValue(),
                executionTarget = execution.targetValue(),
                reminderMinutesJson = reminders.toStorageJson(),
                publishedAtEpochMillis = now,
                groupNameSnapshot = groupNameSnapshot,
                singleDayAdjusted = exception != null,
            )
            if (database.instanceDao().insertInstance(instance) == -1L) return@forEach
            database.instanceDao().insertInstanceSteps(
                effectiveSteps,
            )
            database.auditDao().insertLogs(
                listOf(
                    ActionLogEntity(
                        eventId = idGenerator.next(),
                        taskId = definition.taskId,
                        occurrenceKey = date.toString(),
                        batchId = batchId,
                        action = "INSTANCE_PUBLISHED",
                        detail = "{\"category\":\"${recurrence.frequency.category}\"}",
                        createdAtEpochMillis = now,
                    ),
                ),
            )
            created += TaskInstanceKey(definition.taskId, date.toString())
        }
        return created
    }

    private suspend fun plannedDates(
        definition: TaskDefinitionEntity,
        summary: GenerationSummary?,
        throughDate: LocalDate,
    ): List<LocalDate> {
        val recurrence = definition.toEffectiveRecurrence()
        val latest = summary?.latestDate?.let(LocalDate::parse)
        if (recurrence.maxOccurrences != null &&
            (summary?.generatedCount ?: 0) >= recurrence.maxOccurrences
        ) {
            return emptyList()
        }
        val firstCandidate = maxOf(recurrence.startDate, latest?.plusDays(1) ?: recurrence.startDate)
        if (firstCandidate > throughDate) return emptyList()
        val existing = database.instanceDao().occurrenceKeysInRange(
            definition.taskId,
            firstCandidate.toString(),
            throughDate.toString(),
        ).mapTo(hashSetOf(), LocalDate::parse)
        return RecurrencePlanner.plan(
            RecurrencePlanRequest(
                recurrence = recurrence,
                throughDate = throughDate,
                generatedCount = summary?.generatedCount ?: 0,
                latestOccurrenceDate = latest,
                existingDatesInRange = existing,
            ),
        )
    }

    private fun TaskDefinitionEntity.toEffectiveRecurrence(): EffectiveRecurrence {
        val frequency = when (recurrenceFrequency) {
            1 -> RecurrenceFrequency.DAILY
            2 -> RecurrenceFrequency.WEEKLY
            else -> error("Task $taskId has invalid recurrenceFrequency=$recurrenceFrequency")
        }
        val start = recurrenceStartDate?.let(LocalDate::parse)
            ?: error("Task $taskId has no effective recurrence start date")
        val deadline = recurrenceDeadlineTime?.let { RecurrenceDeadline.At(LocalTime.parse(it)) }
            ?: RecurrenceDeadline.None
        return EffectiveRecurrence(
            frequency = frequency,
            startDate = start,
            endDate = recurrenceEndDate?.let(LocalDate::parse),
            maxOccurrences = recurrenceCount,
            weekdays = WeekdayMask.decode(recurrenceWeekdaysMask),
            deadline = deadline,
        )
    }

    private fun TaskDefinitionEntity.toExecutionSpec(): ExecutionSpec = when (executionKind) {
        "COUNTER" -> ExecutionSpec.Counter(
            action = if (executionAction == 1) com.ds.localtaskmanager.domain.execution.CounterAction.SLIDER else com.ds.localtaskmanager.domain.execution.CounterAction.CLICK,
            target = checkNotNull(executionTarget),
        )
        "TIMER" -> ExecutionSpec.Timer(checkNotNull(executionTarget))
        "INFORMATION" -> ExecutionSpec.Information
        else -> ExecutionSpec.Normal
    }

    private fun ExecutionSpec.kindName(): String = when (this) {
        ExecutionSpec.Normal -> "NORMAL"
        is ExecutionSpec.Counter -> "COUNTER"
        is ExecutionSpec.Timer -> "TIMER"
        ExecutionSpec.Information -> "INFORMATION"
    }

    private fun ExecutionSpec.actionValue(): Int? = (this as? ExecutionSpec.Counter)?.action?.protocolValue

    private fun ExecutionSpec.targetValue(): Int? = when (this) {
        is ExecutionSpec.Counter -> target
        is ExecutionSpec.Timer -> targetSeconds
        else -> null
    }

    private fun String?.toReminderList(): List<Int> = this
        ?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf { it.isNotBlank() }
        ?.split(',')?.map(String::toInt)
        .orEmpty()

    private fun List<Int>.toStorageJson(): String? = takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun <T> Field<T>?.valueOr(fallback: T): T = when (this) {
        null, Field.Missing -> fallback
        is Field.Value -> value
    }
}
