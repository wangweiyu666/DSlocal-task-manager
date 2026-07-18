package com.ds.localtaskmanager.domain

import com.ds.localtaskmanager.protocol.DstStep
import java.security.MessageDigest

object StepFingerprint {
    fun of(steps: List<DstStep>): String {
        val canonical = steps.joinToString(separator = "\u001e") {
            "${if (it.required) 1 else 0}\u001f${it.name}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
