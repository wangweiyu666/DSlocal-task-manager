package com.ds.localtaskmanager.domain.execution

import kotlinx.serialization.json.*

fun ExecutionSpec.configJson(): String? = when (this) {
    is ExecutionSpec.Notice -> buildJsonObject { put("t", text) }.toString()
    is ExecutionSpec.Choice -> buildJsonObject { putJsonArray("o") { options.forEach { option -> add(buildJsonObject {
        put("i", option.id); put("n", option.name); put("p", option.points)
    }) } } }.toString()
    else -> null
}

fun extendedExecution(kind: String, config: String?): ExecutionSpec {
    val value = Json.parseToJsonElement(requireNotNull(config) { "执行配置缺失" }).jsonObject
    return when (kind) {
        "NOTICE" -> ExecutionSpec.Notice(value.getValue("t").jsonPrimitive.let {
            require(value.keys == setOf("t") && it.isString)
            it.content.also { text -> require(text.isNotBlank() && text.codePointCount(0, text.length) <= 2000) }
        })
        "CHOICE" -> ExecutionSpec.Choice(value.getValue("o").jsonArray.map { item ->
            val option = item.jsonObject
            require(value.keys == setOf("o") && option.keys == setOf("i", "n", "p"))
            require(option.getValue("i").jsonPrimitive.isString && option.getValue("n").jsonPrimitive.isString && !option.getValue("p").jsonPrimitive.isString)
            ChoiceOption(option.getValue("i").jsonPrimitive.content, option.getValue("n").jsonPrimitive.content, option.getValue("p").jsonPrimitive.int)
        }.also { options ->
            require(options.size in 2..50 && options.map { it.id }.distinct().size == options.size)
            require(options.all { Regex("[A-Za-z0-9_-]{16}").matches(it.id) && it.name.isNotBlank() && it.name.codePointCount(0, it.name.length) <= 100 && it.points in 0..9999 })
        })
        else -> error("不是通知或选项类型")
    }
}

fun choiceOptions(config: String?): List<ChoiceOption> = (extendedExecution("CHOICE", config) as ExecutionSpec.Choice).options

enum class StepApplicability { APPLICABLE, WAITING, NOT_APPLICABLE }

fun stepApplicability(steps: List<StepState>): List<StepApplicability> {
    val previous = mutableMapOf<String, Pair<StepState, StepApplicability>>()
    return steps.map { step ->
        val source = step.conditionStepId?.let(previous::get)
        val state = if (step.conditionStepId == null) StepApplicability.APPLICABLE else when {
            source == null -> StepApplicability.WAITING
            source.second == StepApplicability.NOT_APPLICABLE || source.first.status in setOf("SKIPPED", "NOT_APPLICABLE") -> StepApplicability.NOT_APPLICABLE
            source.second != StepApplicability.APPLICABLE || source.first.status != "CONFIRMED" -> StepApplicability.WAITING
            source.first.selectedOptionId == step.conditionOptionId -> StepApplicability.APPLICABLE
            else -> StepApplicability.NOT_APPLICABLE
        }
        step.stepId?.let { previous[it] = step to state }
        state
    }
}

/** Simulates final optional skips. Caller commits these states and the ledger in one transaction. */
fun finalizedSteps(steps: List<StepState>): List<StepState>? {
    val result = steps.toMutableList()
    for (index in result.indices) {
        val step = result[index]
        when (stepApplicability(result)[index]) {
            StepApplicability.NOT_APPLICABLE -> result[index] = step.copy(status = "NOT_APPLICABLE", completed = false)
            StepApplicability.WAITING -> return null
            StepApplicability.APPLICABLE -> {
                if (step.status != "CONFIRMED") {
                    if (step.required) return null
                    result[index] = step.copy(status = "SKIPPED", completed = false)
                }
            }
        }
    }
    return result
}

fun confirmedChoicePoints(steps: List<StepState>): Int = steps.filter { it.status == "CONFIRMED" && it.executionKind == "CHOICE" }
    .sumOf { step -> choiceOptions(step.executionConfigJson).single { it.id == step.selectedOptionId }.points }
