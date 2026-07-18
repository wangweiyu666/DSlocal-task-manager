package com.ds.localtaskmanager.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Dst1DecoderTest {
    @Test
    fun `valid envelope is verified and inflated`() {
        val json = "{\"v\":1,\"b\":\"0123456789ABCDEF\",\"t\":[]}"

        assertEquals(json, Dst1Decoder.decode(envelope(json)))
    }

    @Test
    fun `checksum mismatch is rejected`() {
        val encoded = envelope("{\"v\":1}").replaceAfterLast('.', "00000000")

        assertThrows(Dst1DecodeException::class.java) {
            Dst1Decoder.decode(encoded)
        }
    }

    private fun envelope(json: String): String {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        val checksum = CRC32().apply { update(compressed) }
            .value
            .toString(16)
            .padStart(8, '0')
            .uppercase(Locale.ROOT)
        return "DST1.$payload.$checksum"
    }
}
