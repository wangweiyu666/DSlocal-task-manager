package com.ds.localtaskmanager.protocol

import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.execution.CounterAction
import com.ds.localtaskmanager.domain.execution.ExecutionSpec
import com.ds.localtaskmanager.domain.recurrence.RecurrenceDeadline
import com.ds.localtaskmanager.domain.recurrence.RecurrenceSpec
import java.time.DayOfWeek
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

class Dst1Parser {
    private val json = Json { isLenient = false }
    private val idPattern = Regex("[A-Za-z0-9_-]{16}")
    private val dateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
        .withResolverStyle(ResolverStyle.STRICT)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(jsonText: String, importedAt: LocalDateTime, envelopeMinorVersion: Int? = null): DstBatch {
        val root = try {
            json.parseToJsonElement(jsonText) as? JsonObject
                ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, "$", "顶层 JSON 必须是对象")
        } catch (error: Dst1ValidationException) {
            throw error
        } catch (error: Exception) {
            throw Dst1ValidationException(
                Dst1ErrorCode.INVALID_JSON,
                "$",
                "JSON 解析失败：${error.message}",
            )
        }
        root.requireKeys(TOP_KEYS, "顶层")

        val version = root.requiredInt("v", "顶层")
        if (version != 1) {
            invalid(Dst1ErrorCode.INVALID_VALUE, "v", "不支持的 JSON 协议版本：$version")
        }
        val minorVersion = root.optionalInt("sv", "顶层") ?: 0
        if ((envelopeMinorVersion != null && minorVersion != envelopeMinorVersion) || minorVersion !in 0..1) {
            invalid(Dst1ErrorCode.INVALID_VALUE, "sv", "信封版本与 JSON 次版本不一致")
        }
        val batchId = root.requiredId("b", "顶层")
        val domName = root.optionalTextField("d", 50, allowEmpty = true, context = "顶层")
        val note = root.optionalText("m", 500, allowEmpty = false, context = "顶层")
        val groups = root.optionalNonEmptyArray("g", "顶层")
            ?.mapIndexed { index, element -> parseGroup(element, index, importedAt) }
            ?: emptyList()
        if (groups.size > 50) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "g", "积分组条目不能超过 50 个")
        }
        val ungrouped = root.optionalNonEmptyArray("t", "顶层")
            ?.mapIndexed { index, element -> parseTask(element, "t[$index]", null, importedAt) }
            ?: emptyList()
        val cancelled = root.optionalNonEmptyArray("z", "顶层")
            ?.mapIndexed { index, element -> element.asId("z[$index]") }
            ?: emptyList()
        val exceptions = root.optionalNonEmptyArray("e", "顶层")
            ?.mapIndexed { index, element -> parseException(element, "e[$index]") }
            ?: emptyList()
        if (minorVersion == 1 && exceptions.isEmpty()) {
            invalid(Dst1ErrorCode.REQUIRED_FIELD_MISSING, "e", "DST1.1 必须包含单日例外")
        }
        if (minorVersion == 0 && exceptions.isNotEmpty()) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, "e", "DST1 不能包含单日例外")
        }
        if (exceptions.size > 100) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "e", "单日例外不能超过 100 条")
        }
        if (cancelled.size > 100) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "z", "撤销任务 ID 不能超过 100 个")
        }

        if (groups.map { it.groupId }.toSet().size != groups.size) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, "g", "同一批次不能重复出现 groupId")
        }
        val tasks = groups.flatMap { it.tasks } + ungrouped
        if (tasks.size > 100) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "t", "任务总数不能超过 100 个")
        }
        if (tasks.map { it.taskId }.toSet().size != tasks.size) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, "t", "同一批次不能重复出现 taskId")
        }
        if (cancelled.toSet().size != cancelled.size) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, "z", "z 中不能包含重复 taskId")
        }
        if (tasks.any { it.taskId in cancelled }) {
            invalid(
                Dst1ErrorCode.CONFLICTING_FIELDS,
                "z",
                "同一 taskId 不能同时出现在任务列表和 z 中",
            )
        }
        if (exceptions.map { it.taskId to it.occurrenceDate }.toSet().size != exceptions.size) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, "e", "同一任务日期不能重复出现单日例外")
        }
        if (exceptions.any { it.taskId in cancelled }) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, "e", "任务不能同时整项撤销和设置单日例外")
        }
        val hasOperation = root.containsKey("d") || groups.isNotEmpty() ||
            ungrouped.isNotEmpty() || cancelled.isNotEmpty() || exceptions.isNotEmpty()
        if (!hasOperation) {
            invalid(Dst1ErrorCode.EMPTY_OPERATION, "$", "批次不包含实际操作")
        }

        return DstBatch(version, minorVersion, batchId, domName, note, groups, ungrouped, cancelled, exceptions)
    }

    fun parseExceptionJson(jsonText: String): DstOccurrenceException = try {
        parseException(json.parseToJsonElement(jsonText), "e")
    } catch (error: Dst1ValidationException) {
        throw error
    } catch (error: Exception) {
        throw Dst1ValidationException(Dst1ErrorCode.INVALID_JSON, "e", "单日例外 JSON 无效：${error.message}")
    }

    private fun parseException(element: JsonElement, context: String): DstOccurrenceException {
        val value = element.asObject(context)
        value.requireKeys(EXCEPTION_KEYS, context)
        val taskId = value.requiredId("i", context)
        val date = value.optionalDate("y", context)
            ?: invalid(Dst1ErrorCode.REQUIRED_FIELD_MISSING, "$context.y", "$context 缺少计划日期")
        val cancelled = value.optionalInt("c", context)?.also {
            if (it != 1) invalid(Dst1ErrorCode.INVALID_VALUE, "$context.c", "$context.c 只能是 1")
        } == 1
        val overrideKeys = EXCEPTION_KEYS - setOf("i", "y", "c")
        if (cancelled && value.keys.any { it in overrideKeys }) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, context, "撤销单日实例时不能同时包含覆盖字段")
        }
        val name = value.optionalTextField("n", 100, allowEmpty = false, context)
        val required = if (!value.containsKey("r")) Field.Missing else {
            val flag = value.requiredInt("r", context)
            if (flag !in 0..1) invalid(Dst1ErrorCode.INVALID_VALUE, "$context.r", "$context.r 只能是 0 或 1")
            Field.Value(flag == 1)
        }
        val description = value.optionalTextField("d", 2_000, allowEmpty = true, context)
        val deadline: Field<LocalDateTime?> = when (val raw = value["l"]) {
            null -> Field.Missing
            JsonNull -> Field.Value(null)
            else -> Field.Value(parseDateTime(raw.asString("$context.l"), "$context.l"))
        }
        val points: Field<Int> = if (!value.containsKey("p")) Field.Missing else {
            val parsed = value.requiredInt("p", context)
            if (parsed !in 0..9_999) invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.p", "$context.p 必须在 0..9999")
            Field.Value(parsed)
        }
        val sortOrder: Field<Int?> = when (val raw = value["o"]) {
            null -> Field.Missing
            JsonNull -> Field.Value(null)
            else -> Field.Value(raw.asInt("$context.o"))
        }
        val steps: Field<List<DstStep>> = if (!value.containsKey("s")) Field.Missing else {
            val parsed = value.optionalArray("s", context)!!.mapIndexed { index, step -> parseStep(step, "$context.s[$index]") }
            if (parsed.size > 50) invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.s", "$context.s 不能超过 50 个步骤")
            Field.Value(parsed)
        }
        val completionMessage: Field<String?> = when (val raw = value["m"]) {
            null -> Field.Missing
            JsonNull -> Field.Value(null)
            else -> Field.Value(normalizeText(raw.asString("$context.m"), 500, true, "$context.m"))
        }
        val reminders: Field<List<Int>> = if (!value.containsKey("h")) Field.Missing else {
            Field.Value(parseExceptionReminders(value.getValue("h"), "$context.h"))
        }
        if (deadline is Field.Value && deadline.value == null && reminders is Field.Value && reminders.value.isNotEmpty()) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, "$context.h", "永不截止的单日例外不能设置提醒")
        }
        val execution: Field<ExecutionSpec> = when (val raw = value["u"]) {
            null -> Field.Missing
            JsonNull -> Field.Value(ExecutionSpec.Normal)
            else -> Field.Value(parseExecution(raw, "$context.u"))
        }
        val canonical = JsonObject(EXCEPTION_KEYS.mapNotNull { key -> value[key]?.let { key to it } }.toMap()).toString()
        return DstOccurrenceException(
            taskId, date, cancelled, name, required, description, deadline, points, sortOrder,
            steps, completionMessage, reminders, execution, canonical,
        )
    }

    private fun parseExceptionReminders(element: JsonElement, context: String): List<Int> {
        val reminders = element as? JsonArray
            ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, context, "$context 必须是数组")
        if (reminders.size > 5) invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, context, "$context 不能超过 5 个提醒")
        val values = reminders.mapIndexed { index, value ->
            value.asInt("$context[$index]").also {
                if (it !in 0..10_080) invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context[$index]", "$context[$index] 必须在 0..10080")
            }
        }
        if (values.distinct() != values || values.sortedDescending() != values) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, context, "$context 必须唯一并降序")
        }
        return values
    }

    private fun parseGroup(
        element: JsonElement,
        index: Int,
        importedAt: LocalDateTime,
    ): DstGroupPatch {
        val context = "g[$index]"
        val objectValue = element.asObject(context)
        objectValue.requireKeys(GROUP_KEYS, context)
        val groupId = objectValue.requiredId("i", context)
        val name = objectValue.optionalTextField("n", 50, allowEmpty = false, context)
        val completeMessage = objectValue.optionalTextField("cm", 500, allowEmpty = true, context)
        val incompleteMessage = objectValue.optionalTextField("im", 500, allowEmpty = true, context)
        val tasks = objectValue.optionalNonEmptyArray("t", context)
            ?.mapIndexed { taskIndex, task ->
                parseTask(task, "$context.t[$taskIndex]", groupId, importedAt)
            }
            ?: emptyList()
        if (name is Field.Missing && completeMessage is Field.Missing &&
            incompleteMessage is Field.Missing && tasks.isEmpty()
        ) {
            invalid(Dst1ErrorCode.EMPTY_OPERATION, context, "$context 没有实际更新内容")
        }
        return DstGroupPatch(groupId, name, completeMessage, incompleteMessage, tasks)
    }

    private fun parseTask(
        element: JsonElement,
        context: String,
        groupId: String?,
        importedAt: LocalDateTime,
    ): DstTask {
        val task = element.asObject(context)
        task.requireKeys(TASK_KEYS, context)
        val taskId = task.requiredId("i", context)
        val name = task.requiredText("n", 100, context)
        val requiredFlag = task.requiredInt("r", context)
        if (requiredFlag !in 0..1) {
            invalid(Dst1ErrorCode.INVALID_VALUE, "$context.r", "$context.r 只能是 0 或 1")
        }
        val description = task.optionalText("d", 2_000, allowEmpty = true, context) ?: ""
        val explicitTaskDate = task.optionalDate("y", context)
        val deadline = parseDeadline(task, explicitTaskDate, importedAt, context)
        val taskDate = explicitTaskDate ?: deriveTaskDate(task["l"], deadline, importedAt)
        val taskDateDirective: Field<LocalDate> = explicitTaskDate?.let { Field.Value(it) } ?: Field.Missing
        val deadlineDirective: Field<LocalDateTime?> =
            if (task.containsKey("l")) Field.Value(deadline) else Field.Missing
        val points = task.optionalInt("p", context) ?: 0
        if (points !in 0..9_999) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.p", "$context.p 必须在 0..9999")
        }
        val sortOrder = task.optionalInt("o", context)
        val steps = task.optionalNonEmptyArray("s", context)
            ?.mapIndexed { stepIndex, step -> parseStep(step, "$context.s[$stepIndex]") }
            ?: emptyList()
        if (steps.size > 50) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.s", "$context.s 不能超过 50 个步骤")
        }
        val completionMessage = task.optionalText("m", 500, allowEmpty = true, context)
            ?: "任务已完成"

        val execution = parseExecution(task["u"], "$context.u")
        val recurrence = parseRecurrence(task["x"], "$context.x")
        val reminderMinutes = parseReminders(task["h"], task["l"], "$context.h")
        return DstTask(
            taskId = taskId,
            name = name,
            required = requiredFlag == 1,
            description = description,
            taskDateDirective = taskDateDirective,
            deadlineDirective = deadlineDirective,
            taskDate = taskDate,
            deadline = deadline,
            points = points,
            sortOrder = sortOrder,
            steps = steps,
            completionMessage = completionMessage,
            groupId = groupId,
            execution = execution,
            recurrence = recurrence,
            reminderMinutes = reminderMinutes,
        )
    }

    private fun parseStep(element: JsonElement, context: String): DstStep {
        val step = element.asObject(context)
        step.requireKeys(STEP_KEYS, context)
        val requiredFlag = step.requiredInt("r", context)
        if (requiredFlag !in 0..1) {
            invalid(Dst1ErrorCode.INVALID_VALUE, "$context.r", "$context.r 只能是 0 或 1")
        }
        return DstStep(step.requiredText("n", 100, context), requiredFlag == 1)
    }

    private fun parseDeadline(
        task: JsonObject,
        explicitDate: LocalDate?,
        importedAt: LocalDateTime,
        context: String,
    ): LocalDateTime? {
        if (!task.containsKey("l")) {
            val taskDate = explicitDate ?: TaskDay.from(importedAt)
            return taskDate.plusDays(1).atTime(4, 0)
        }
        val value = task.getValue("l")
        if (value == JsonNull) return null
        val text = value.asString("$context.l")
        return if (text.length == 10) {
            parseDate(text, "$context.l").plusDays(1).atTime(4, 0)
        } else {
            parseDateTime(text, "$context.l")
        }
    }

    private fun deriveTaskDate(
        rawDeadline: JsonElement?,
        deadline: LocalDateTime?,
        importedAt: LocalDateTime,
    ): LocalDate = when {
        rawDeadline is JsonPrimitive && rawDeadline.content.length == 10 ->
            parseDate(rawDeadline.content, "l")
        deadline != null -> TaskDay.from(deadline)
        else -> TaskDay.from(importedAt)
    }

    private fun parseRecurrence(element: JsonElement?, context: String): RecurrenceSpec {
        if (element == null) return RecurrenceSpec.None
        val recurrence = element.asObject(context)
        recurrence.requireKeys(RECURRENCE_KEYS, context)
        val frequency = recurrence.requiredInt("f", context)
        if (frequency !in 1..2) {
            invalid(Dst1ErrorCode.INVALID_VALUE, "$context.f", "$context.f 只能是 1 或 2")
        }
        val startDate = recurrence.optionalDate("s", context)
        val endDate = recurrence.optionalDate("e", context)
        val count = recurrence.optionalInt("c", context)
        if (count != null && count <= 0) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.c", "$context.c 必须是正整数")
        }
        if (recurrence.containsKey("e") && recurrence.containsKey("c")) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, context, "$context.e 和 $context.c 互斥")
        }
        val weekdays = recurrence.optionalArray("w", context)?.mapIndexed { index, value ->
            value.asInt("$context.w[$index]").also {
                if (it !in 1..7) {
                    invalid(
                        Dst1ErrorCode.VALUE_OUT_OF_RANGE,
                        "$context.w[$index]",
                        "$context.w[$index] 必须在 1..7",
                    )
                }
            }
        }
        if (frequency == 1 && weekdays != null) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, "$context.w", "每日重复不能包含 $context.w")
        }
        if (frequency == 2 && weekdays.isNullOrEmpty()) {
            invalid(Dst1ErrorCode.REQUIRED_FIELD_MISSING, "$context.w", "每周重复必须包含非空 $context.w")
        }
        if (weekdays != null && (weekdays.distinct() != weekdays || weekdays.sorted() != weekdays)) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, "$context.w", "$context.w 必须唯一并升序")
        }
        val deadline = when (val value = recurrence["t"]) {
            null -> RecurrenceDeadline.Default
            JsonNull -> RecurrenceDeadline.None
            else -> RecurrenceDeadline.At(parseTime(value.asString("$context.t"), "$context.t"))
        }
        return if (frequency == 1) {
            RecurrenceSpec.Daily(startDate, endDate, count, deadline)
        } else {
            RecurrenceSpec.Weekly(
                startDate = startDate,
                endDate = endDate,
                maxOccurrences = count,
                weekdays = weekdays.orEmpty().mapTo(linkedSetOf()) { DayOfWeek.of(it) },
                deadline = deadline,
            )
        }
    }

    private fun parseReminders(
        element: JsonElement?,
        rawDeadline: JsonElement?,
        context: String,
    ): List<Int> {
        if (element == null) return emptyList()
        val reminders = (element as? JsonArray)
            ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, context, "$context 必须是数组")
        if (reminders.isEmpty() || reminders.size > 5) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, context, "$context 必须包含 1..5 个提醒")
        }
        val values = reminders.mapIndexed { index, value ->
            value.asInt("$context[$index]").also {
                if (it !in 0..10_080) {
                    invalid(
                        Dst1ErrorCode.VALUE_OUT_OF_RANGE,
                        "$context[$index]",
                        "$context[$index] 必须在 0..10080",
                    )
                }
            }
        }
        if (values.distinct() != values || values.sortedDescending() != values) {
            invalid(Dst1ErrorCode.DUPLICATE_VALUE, context, "$context 必须唯一并降序")
        }
        if (rawDeadline == JsonNull) {
            invalid(Dst1ErrorCode.CONFLICTING_FIELDS, context, "永不截止的任务不能设置提醒")
        }
        return values
    }

    private fun parseExecution(element: JsonElement?, context: String): ExecutionSpec {
        if (element == null) return ExecutionSpec.Normal
        val execution = element.asObject(context)
        execution.requireKeys(EXECUTION_KEYS, context)
        return when (val kind = execution.requiredInt("k", context)) {
            1 -> {
                val action = execution.requiredInt("a", context)
                if (action !in 1..2) {
                    invalid(Dst1ErrorCode.INVALID_VALUE, "$context.a", "$context.a 只能是 1 或 2")
                }
                val target = execution.requiredInt("v", context)
                if (target !in 1..999) {
                    invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.v", "$context.v 必须在 1..999")
                }
                ExecutionSpec.Counter(
                    action = if (action == 1) CounterAction.SLIDER else CounterAction.CLICK,
                    target = target,
                )
            }
            2 -> {
                if (execution.containsKey("a")) {
                    invalid(Dst1ErrorCode.CONFLICTING_FIELDS, "$context.a", "计时任务不能包含 $context.a")
                }
                val target = execution.requiredInt("v", context)
                if (target !in 1..3_600) {
                    invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, "$context.v", "$context.v 必须在 1..3600")
                }
                ExecutionSpec.Timer(target)
            }
            3 -> {
                val forbidden = listOf("a", "v").firstOrNull(execution::containsKey)
                if (forbidden != null) {
                    invalid(
                        Dst1ErrorCode.CONFLICTING_FIELDS,
                        "$context.$forbidden",
                        "信息告知任务不能包含 $context.$forbidden",
                    )
                }
                ExecutionSpec.Information
            }
            else -> invalid(
                Dst1ErrorCode.INVALID_VALUE,
                "$context.k",
                "$context.k 是未知执行类型：$kind",
            )
        }
    }

    private fun JsonObject.requireKeys(allowed: Set<String>, context: String) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) {
            val key = unknown.sorted().first()
            val path = if (context == "顶层") key else "$context.$key"
            invalid(Dst1ErrorCode.UNKNOWN_FIELD, path, "$context 包含未知字段：$key")
        }
    }

    private fun JsonObject.requiredId(key: String, context: String): String =
        get(key)?.asId(path(context, key))
            ?: invalid(Dst1ErrorCode.REQUIRED_FIELD_MISSING, path(context, key), "$context 缺少必填字段 $key")

    private fun JsonElement.asId(context: String): String {
        val value = asString(context)
        if (!idPattern.matches(value)) {
            invalid(Dst1ErrorCode.INVALID_VALUE, context, "$context 必须是 16 位 Base64URL ID")
        }
        return value
    }

    private fun JsonObject.requiredText(key: String, max: Int, context: String): String =
        get(key)?.let { normalizeText(it.asString(path(context, key)), max, false, path(context, key)) }
            ?: invalid(Dst1ErrorCode.REQUIRED_FIELD_MISSING, path(context, key), "$context 缺少必填字段 $key")

    private fun JsonObject.optionalText(
        key: String,
        max: Int,
        allowEmpty: Boolean,
        context: String,
    ): String? = get(key)?.let {
        if (it == JsonNull) {
            invalid(Dst1ErrorCode.TYPE_MISMATCH, path(context, key), "${path(context, key)} 不允许为 null")
        }
        normalizeText(it.asString(path(context, key)), max, allowEmpty, path(context, key))
    }

    private fun JsonObject.optionalTextField(
        key: String,
        max: Int,
        allowEmpty: Boolean,
        context: String,
    ): Field<String> = if (!containsKey(key)) {
        Field.Missing
    } else {
        Field.Value(
            normalizeText(getValue(key).asString(path(context, key)), max, allowEmpty, path(context, key)),
        )
    }

    private fun normalizeText(value: String, max: Int, allowEmpty: Boolean, context: String): String {
        val trimmed = value.trim()
        if (!Normalizer.isNormalized(trimmed, Normalizer.Form.NFC)) {
            invalid(Dst1ErrorCode.NON_CANONICAL_TEXT, context, "$context 必须使用 Unicode NFC")
        }
        if (!allowEmpty && trimmed.isEmpty()) {
            invalid(Dst1ErrorCode.INVALID_VALUE, context, "$context 不允许为空")
        }
        if (trimmed.codePointCount(0, trimmed.length) > max) {
            invalid(Dst1ErrorCode.VALUE_OUT_OF_RANGE, context, "$context 超过 $max 个字符")
        }
        return trimmed
    }

    private fun JsonObject.requiredInt(key: String, context: String): Int =
        get(key)?.asInt(path(context, key))
            ?: invalid(Dst1ErrorCode.REQUIRED_FIELD_MISSING, path(context, key), "$context 缺少必填字段 $key")

    private fun JsonObject.optionalInt(key: String, context: String): Int? =
        get(key)?.asInt(path(context, key))

    private fun JsonElement.asInt(context: String): Int =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, context, "$context 必须是整数")

    private fun JsonElement.asString(context: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, context, "$context 必须是字符串")

    private fun JsonElement.asObject(context: String): JsonObject =
        this as? JsonObject ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, context, "$context 必须是对象")

    private fun JsonObject.optionalArray(key: String, context: String): JsonArray? =
        get(key)?.let {
            it as? JsonArray
                ?: invalid(Dst1ErrorCode.TYPE_MISMATCH, path(context, key), "${path(context, key)} 必须是数组")
        }

    private fun JsonObject.optionalNonEmptyArray(key: String, context: String): JsonArray? =
        optionalArray(key, context)?.also {
            if (it.isEmpty()) {
                invalid(
                    Dst1ErrorCode.VALUE_OUT_OF_RANGE,
                    path(context, key),
                    "${path(context, key)} 存在时不能为空数组",
                )
            }
        }

    private fun JsonObject.optionalDate(key: String, context: String): LocalDate? =
        get(key)?.let { parseDate(it.asString(path(context, key)), path(context, key)) }

    private fun parseDate(value: String, context: String): LocalDate = try {
        LocalDate.parse(value, dateFormatter)
    } catch (_: DateTimeException) {
        invalid(Dst1ErrorCode.INVALID_DATE, context, "$context 不是有效的 YYYY-MM-DD 日期")
    }

    private fun parseDateTime(value: String, context: String): LocalDateTime = try {
        val parsed = LocalDateTime.parse(value, dateTimeFormatter)
        if (parsed.toLocalTime() == LocalTime.MIDNIGHT && value.endsWith("24:00")) {
            invalid(Dst1ErrorCode.INVALID_DATE, context, "$context 不允许 24:00")
        }
        parsed
    } catch (_: DateTimeException) {
        invalid(Dst1ErrorCode.INVALID_DATE, context, "$context 不是有效的 YYYY-MM-DDTHH:mm 时间")
    }

    private fun parseTime(value: String, context: String): LocalTime = try {
        LocalTime.parse(value, timeFormatter)
    } catch (_: DateTimeException) {
        invalid(Dst1ErrorCode.INVALID_DATE, context, "$context 不是有效的 HH:mm 时间")
    }

    private fun path(context: String, key: String): String =
        if (context == "顶层") key else "$context.$key"

    private fun invalid(code: Dst1ErrorCode, path: String?, message: String): Nothing =
        throw Dst1ValidationException(code, path, message)

    private companion object {
        val TOP_KEYS = setOf("v", "sv", "b", "d", "m", "g", "t", "z", "e")
        val GROUP_KEYS = setOf("i", "n", "cm", "im", "t")
        val TASK_KEYS = setOf("i", "n", "r", "d", "y", "l", "p", "o", "s", "x", "m", "h", "u")
        val STEP_KEYS = setOf("n", "r")
        val RECURRENCE_KEYS = setOf("f", "s", "e", "c", "w", "t")
        val EXECUTION_KEYS = setOf("k", "a", "v")
        val EXCEPTION_KEYS = linkedSetOf("i", "y", "c", "n", "r", "d", "l", "p", "o", "s", "m", "h", "u")
    }
}
