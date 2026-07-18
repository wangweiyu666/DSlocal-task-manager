package com.ds.localtaskmanager.protocol

import com.ds.localtaskmanager.domain.TaskDay
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

class Dst1ValidationException(message: String) : IllegalArgumentException(message)

class Dst1Parser {
    private val json = Json { isLenient = false }
    private val idPattern = Regex("[A-Za-z0-9_-]{16}")
    private val dateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(jsonText: String, importedAt: LocalDateTime): DstBatch {
        val root = try {
            json.parseToJsonElement(jsonText) as? JsonObject
                ?: invalid("顶层 JSON 必须是对象")
        } catch (error: Dst1ValidationException) {
            throw error
        } catch (error: Exception) {
            throw Dst1ValidationException("JSON 解析失败：${error.message}")
        }
        root.requireKeys(TOP_KEYS, "顶层")

        val version = root.requiredInt("v", "顶层")
        if (version != 1) invalid("不支持的 JSON 协议版本：$version")
        val batchId = root.requiredId("b", "顶层")
        val domName = root.optionalTextField("d", 50, allowEmpty = true, context = "顶层")
        val note = root.optionalText("m", 500, allowEmpty = false, context = "顶层")
        val groups = root.optionalArray("g", "顶层")
            ?.mapIndexed { index, element -> parseGroup(element, index, importedAt) }
            ?: emptyList()
        if (groups.size > 50) invalid("积分组条目不能超过 50 个")
        val ungrouped = root.optionalArray("t", "顶层")
            ?.mapIndexed { index, element -> parseTask(element, "t[$index]", null, importedAt) }
            ?: emptyList()
        val cancelled = root.optionalArray("z", "顶层")
            ?.mapIndexed { index, element -> element.asId("z[$index]") }
            ?: emptyList()
        if (cancelled.size > 100) invalid("撤销任务 ID 不能超过 100 个")

        if (groups.map { it.groupId }.toSet().size != groups.size) {
            invalid("同一批次不能重复出现 groupId")
        }
        val tasks = groups.flatMap { it.tasks } + ungrouped
        if (tasks.size > 100) invalid("任务总数不能超过 100 个")
        if (tasks.map { it.taskId }.toSet().size != tasks.size) {
            invalid("同一批次不能重复出现 taskId")
        }
        if (cancelled.toSet().size != cancelled.size) invalid("z 中不能包含重复 taskId")
        if (tasks.any { it.taskId in cancelled }) {
            invalid("同一 taskId 不能同时出现在任务列表和 z 中")
        }
        val hasOperation = root.containsKey("d") || groups.isNotEmpty() ||
            ungrouped.isNotEmpty() || cancelled.isNotEmpty()
        if (!hasOperation) invalid("批次不包含实际操作")

        return DstBatch(version, batchId, domName, note, groups, ungrouped, cancelled)
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
        val tasks = objectValue.optionalArray("t", context)
            ?.mapIndexed { taskIndex, task ->
                parseTask(task, "$context.t[$taskIndex]", groupId, importedAt)
            }
            ?: emptyList()
        if (name is Field.Missing && completeMessage is Field.Missing &&
            incompleteMessage is Field.Missing && tasks.isEmpty()
        ) {
            invalid("$context 没有实际更新内容")
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
        listOf("x", "h", "u").firstOrNull(task::containsKey)?.let {
            invalid("$context.$it 属于当前测试版本尚未实现的功能")
        }
        val taskId = task.requiredId("i", context)
        val name = task.requiredText("n", 100, context)
        val requiredFlag = task.requiredInt("r", context)
        if (requiredFlag !in 0..1) invalid("$context.r 只能是 0 或 1")
        val description = task.optionalText("d", 2_000, allowEmpty = true, context) ?: ""
        val explicitTaskDate = task.optionalDate("y", context)
        val deadline = parseDeadline(task, explicitTaskDate, importedAt, context)
        val taskDate = explicitTaskDate ?: deriveTaskDate(task["l"], deadline, importedAt)
        val points = task.optionalInt("p", context) ?: 0
        if (points !in 0..999) invalid("$context.p 必须在 0..999")
        val sortOrder = task.optionalInt("o", context)
        val steps = task.optionalArray("s", context)
            ?.mapIndexed { stepIndex, step -> parseStep(step, "$context.s[$stepIndex]") }
            ?: emptyList()
        if (steps.size > 50) invalid("$context.s 不能超过 50 个步骤")
        val completionMessage = task.optionalText("m", 500, allowEmpty = true, context)
            ?: "任务已完成"
        return DstTask(
            taskId = taskId,
            name = name,
            required = requiredFlag == 1,
            description = description,
            taskDate = taskDate,
            deadline = deadline,
            points = points,
            sortOrder = sortOrder,
            steps = steps,
            completionMessage = completionMessage,
            groupId = groupId,
        )
    }

    private fun parseStep(element: JsonElement, context: String): DstStep {
        val step = element.asObject(context)
        step.requireKeys(STEP_KEYS, context)
        val requiredFlag = step.requiredInt("r", context)
        if (requiredFlag !in 0..1) invalid("$context.r 只能是 0 或 1")
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

    private fun JsonObject.requireKeys(allowed: Set<String>, context: String) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) invalid("$context 包含未知字段：${unknown.sorted().joinToString()}")
    }

    private fun JsonObject.requiredId(key: String, context: String): String =
        get(key)?.asId("$context.$key") ?: invalid("$context 缺少必填字段 $key")

    private fun JsonElement.asId(context: String): String {
        val value = asString(context)
        if (!idPattern.matches(value)) invalid("$context 必须是 16 位 Base64URL ID")
        return value
    }

    private fun JsonObject.requiredText(key: String, max: Int, context: String): String =
        get(key)?.let { normalizeText(it.asString("$context.$key"), max, false, "$context.$key") }
            ?: invalid("$context 缺少必填字段 $key")

    private fun JsonObject.optionalText(
        key: String,
        max: Int,
        allowEmpty: Boolean,
        context: String,
    ): String? = get(key)?.let {
        if (it == JsonNull) invalid("$context.$key 不允许为 null")
        normalizeText(it.asString("$context.$key"), max, allowEmpty, "$context.$key")
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
            normalizeText(getValue(key).asString("$context.$key"), max, allowEmpty, "$context.$key"),
        )
    }

    private fun normalizeText(value: String, max: Int, allowEmpty: Boolean, context: String): String {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        if (!allowEmpty && normalized.isEmpty()) invalid("$context 不允许为空")
        if (normalized.codePointCount(0, normalized.length) > max) {
            invalid("$context 超过 $max 个字符")
        }
        return normalized
    }

    private fun JsonObject.requiredInt(key: String, context: String): Int =
        get(key)?.asInt("$context.$key") ?: invalid("$context 缺少必填字段 $key")

    private fun JsonObject.optionalInt(key: String, context: String): Int? =
        get(key)?.asInt("$context.$key")

    private fun JsonElement.asInt(context: String): Int =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: invalid("$context 必须是整数")

    private fun JsonElement.asString(context: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid("$context 必须是字符串")

    private fun JsonElement.asObject(context: String): JsonObject =
        this as? JsonObject ?: invalid("$context 必须是对象")

    private fun JsonObject.optionalArray(key: String, context: String): JsonArray? =
        get(key)?.let { it as? JsonArray ?: invalid("$context.$key 必须是数组") }

    private fun JsonObject.optionalDate(key: String, context: String): LocalDate? =
        get(key)?.let { parseDate(it.asString("$context.$key"), "$context.$key") }

    private fun parseDate(value: String, context: String): LocalDate = try {
        LocalDate.parse(value, dateFormatter)
    } catch (_: DateTimeException) {
        invalid("$context 不是有效的 YYYY-MM-DD 日期")
    }

    private fun parseDateTime(value: String, context: String): LocalDateTime = try {
        val parsed = LocalDateTime.parse(value, dateTimeFormatter)
        if (parsed.toLocalTime() == LocalTime.MIDNIGHT && value.endsWith("24:00")) {
            invalid("$context 不允许 24:00")
        }
        parsed
    } catch (_: DateTimeException) {
        invalid("$context 不是有效的 YYYY-MM-DDTHH:mm 时间")
    }

    private fun invalid(message: String): Nothing = throw Dst1ValidationException(message)

    private companion object {
        val TOP_KEYS = setOf("v", "b", "d", "m", "g", "t", "z")
        val GROUP_KEYS = setOf("i", "n", "cm", "im", "t")
        val TASK_KEYS = setOf("i", "n", "r", "d", "y", "l", "p", "o", "s", "x", "m", "h", "u")
        val STEP_KEYS = setOf("n", "r")
    }
}
