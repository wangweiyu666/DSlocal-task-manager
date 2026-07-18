package com.ds.localtaskmanager.domain

import java.security.SecureRandom
import java.util.Base64

fun interface RecordIdGenerator {
    fun next(): String
}

class SecureRecordIdGenerator(
    private val random: SecureRandom = SecureRandom(),
) : RecordIdGenerator {
    override fun next(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
