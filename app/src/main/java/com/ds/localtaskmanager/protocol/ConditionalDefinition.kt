package com.ds.localtaskmanager.protocol

import com.ds.localtaskmanager.domain.execution.ExecutionSpec

fun validateConditionalDefinition(execution: ExecutionSpec, steps: List<DstStep>) {
    if (execution !is ExecutionSpec.Steps && steps.any { it.condition != null || it.execution is ExecutionSpec.Notice || it.execution is ExecutionSpec.Choice }) {
        throw Dst1ValidationException(Dst1ErrorCode.CONFLICTING_FIELDS, "s", "通知、选项和条件步骤需要 STEPS 执行模式")
    }
    val previous = mutableMapOf<String, DstStep>()
    steps.forEach { step ->
        step.condition?.let { condition ->
            val source = previous[condition.stepId]?.execution as? ExecutionSpec.Choice
            if (source == null || source.options.none { it.id == condition.optionId }) {
                throw Dst1ValidationException(Dst1ErrorCode.INVALID_VALUE, "s.c", "条件必须引用本任务前面的单选步骤及其有效选项")
            }
        }
        step.id?.let { previous[it] = step }
    }
}
