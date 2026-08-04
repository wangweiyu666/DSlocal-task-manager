package com.ds.localtaskmanager.protocol

import com.ds.localtaskmanager.backup.DstbCodec
import java.time.LocalDateTime
import java.util.Random
import org.junit.Assert.assertFalse
import org.junit.Test

class W32MaliciousInputTest {
    private val importedAt = LocalDateTime.of(2026, 8, 5, 12, 0)

    @Test
    fun `seeded random bytes never escape as virtual machine failure`() {
        val random = Random(SEED)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(8_192)) { random.nextInt(256).toByte() }
            assertSafeFailure { DstbCodec.decode(bytes) }
            assertSafeFailure { Dst1Decoder.decode(bytes.toString(Charsets.ISO_8859_1)) }
        }
    }

    @Test
    fun `deep and oversized json is rejected without stack overflow`() {
        val deep = "[".repeat(2_000) + "0" + "]".repeat(2_000)
        assertSafeFailure { Dst1Parser().parse(deep, importedAt) }
        val oversizedName = "N".repeat(200_000)
        assertSafeFailure {
            Dst1Parser().parse(
                """{"v":1,"b":"MaliciousBatch01","t":[{"i":"MaliciousTask01","n":"$oversizedName","r":1}]}""",
                importedAt,
            )
        }
    }

    private fun assertSafeFailure(block: () -> Any?) {
        val failure = runCatching(block).exceptionOrNull()
        assertFalse("OutOfMemoryError must never escape", failure is OutOfMemoryError)
        assertFalse("StackOverflowError must never escape", failure is StackOverflowError)
    }

    private companion object {
        const val SEED = 0x4453544154494F4EL
    }
}
