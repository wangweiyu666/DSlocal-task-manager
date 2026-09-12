package com.ds.localtaskmanager.domain

import com.ds.localtaskmanager.protocol.DstStep
import com.ds.localtaskmanager.domain.execution.configJson
import java.security.MessageDigest

object StepFingerprint {
    fun of(steps: List<DstStep>): String {
        val canonical = steps.mapIndexed { index, step ->
            val stableId = step.id ?: "legacy-$index"
            val execution = when (val value = step.execution) {
                com.ds.localtaskmanager.domain.execution.ExecutionSpec.Normal -> "NORMAL"
                is com.ds.localtaskmanager.domain.execution.ExecutionSpec.Counter -> "COUNTER:${value.action.protocolValue}:${value.target}"
                is com.ds.localtaskmanager.domain.execution.ExecutionSpec.Timer -> "TIMER:${value.targetSeconds}"
                com.ds.localtaskmanager.domain.execution.ExecutionSpec.Information -> "INFORMATION"
                com.ds.localtaskmanager.domain.execution.ExecutionSpec.Mood -> "MOOD"
                com.ds.localtaskmanager.domain.execution.ExecutionSpec.Steps -> "STEPS"
                is com.ds.localtaskmanager.domain.execution.ExecutionSpec.Notice -> "NOTICE:${value.configJson()}"
                is com.ds.localtaskmanager.domain.execution.ExecutionSpec.Choice -> "CHOICE:${value.configJson()}"
            }
            "${stableId}\u001f${if (step.required) 1 else 0}\u001f${step.name}\u001f$execution${step.condition?.let { "\u001f${it.stepId}:${it.optionId}" }.orEmpty()}"
        }.joinToString(separator = "\u001e")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
