package com.ds.localtaskmanager.data.recurrence

import androidx.room.withTransaction
import com.ds.localtaskmanager.data.ActionLogEntity
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.InstanceStepEntity
import com.ds.localtaskmanager.data.TaskDefinitionEntity
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.dao.GenerationSummary
import com.ds.localtaskmanager.domain.RecordIdGenerator
import com.ds.localtaskmanager.domain.TaskStateMachine
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.recurrence.EffectiveRecurrence
import com.ds.localtaskmanager.domain.recurrence.RecurrenceDeadline
import com.ds.localtaskmanager.domain.recurrence.RecurrenceFrequency
import com.ds.localtaskmanager.domain.recurrence.RecurrencePlanRequest
import com.ds.localtaskmanager.domain.recurrence.RecurrencePlanner
import com.ds.localtaskmanager.domain.recurrence.WeekdayMask
import com.ds.localtaskmanager.domain.recurrence.deadlineFor
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
            val definitions = database.definitionDao().getActiveRecurringDefinitions()
            if (definitions.isEmpty()) return@withTransaction GenerationResult(emptyList())
            val summaries = database.instanceDao().generationSummaries(definitions.map { it.taskId })
                .associateBy { it.taskId }
            val created = definitions.flatMap { definition ->
                generate(definition, summaries[definition.taskId], throughDate, null)
            }
            GenerationResult(created)
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
            GenerationResult(generate(definition, summary, throughDate, batchId))
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
        val now = clock.millis()
        val nowDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), clock.zone)
        val created = mutableListOf<TaskInstanceKey>()
        dates.forEach { date ->
            val deadline = deadlineFor(date, recurrence.deadline)
            val instance = TaskInstanceEntity(
                taskId = definition.taskId,
                occurrenceKey = date.toString(),
                name = definition.name,
                description = definition.description,
                taskDate = date.toString(),
                deadline = deadline?.toString(),
                groupId = definition.groupId,
                required = definition.required,
                points = definition.points,
                sortOrder = definition.sortOrder,
                completionMessage = definition.completionMessage,
                status = TaskStateMachine.statusAt(date, deadline, nowDateTime).name,
                completedAtEpochMillis = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                category = recurrence.frequency.category,
                executionKind = definition.executionKind,
                executionAction = definition.executionAction,
                executionTarget = definition.executionTarget,
                reminderMinutesJson = definition.reminderMinutesJson,
                publishedAtEpochMillis = now,
            )
            if (database.instanceDao().insertInstance(instance) == -1L) return@forEach
            database.instanceDao().insertInstanceSteps(
                stepDefinitions.map { step ->
                    InstanceStepEntity(
                        taskId = definition.taskId,
                        occurrenceKey = date.toString(),
                        position = step.position,
                        name = step.name,
                        required = step.required,
                        completed = false,
                        updatedAtEpochMillis = now,
                    )
                },
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
}
