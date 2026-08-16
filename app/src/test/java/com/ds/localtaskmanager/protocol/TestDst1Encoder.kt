package com.ds.localtaskmanager.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

fun encodeDst1ForTest(json: String): String {
    val compressed = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    val checksum = CRC32().apply { update(compressed) }
        .value.toString(16).padStart(8, '0').uppercase(Locale.ROOT)
    return "${if (Regex("\\\"sv\\\"\\s*:\\s*1").containsMatchIn(json)) "DST1.1" else "DST1"}.$payload.$checksum"
}
