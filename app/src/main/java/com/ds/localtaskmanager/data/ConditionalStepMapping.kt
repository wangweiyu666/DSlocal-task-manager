package com.ds.localtaskmanager.data

import com.ds.localtaskmanager.domain.execution.*

fun InstanceStepEntity.branchState() = StepState(stepId, position, name, required, completed, executionKind,
    executionAction, executionTarget, stepStatus, counterValue, elapsedMillis, informationContent, moodRating, moodText,
    executionConfigJson, conditionStepId, conditionOptionId, selectedOptionId)

fun List<InstanceStepEntity>.applicableSteps(): List<InstanceStepEntity> {
    val states = stepApplicability(map { it.branchState() })
    return filterIndexed { index, _ -> states[index] == StepApplicability.APPLICABLE }
}

fun List<InstanceStepEntity>.completionSteps(): List<InstanceStepEntity>? = finalizedSteps(map { it.branchState() })?.let { finalized ->
    mapIndexed { index, step -> step.copy(stepStatus = finalized[index].status, completed = finalized[index].status == "CONFIRMED") }
}
