package com.ds.localtaskmanager.protocol

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Dst1ParserTest {
    private val parser = Dst1Parser()
    private val importedAt = LocalDateTime.of(2026, 7, 18, 10, 0)

    @Test
    fun `shared golden vector decodes and parses`() {
        val encoded = resource("valid/minimal-step.dst1").trim()
        val expectedJson = resource("valid/minimal-step.json").trim()

        assertEquals(expectedJson, Dst1Decoder.decode(encoded))
        val batch = parser.parse(expectedJson, importedAt)

        assertEquals("BatchId123456789", batch.batchId)
        assertEquals("TaskId1234567890", batch.allTasks().single().taskId)
        assertEquals(1, batch.allTasks().single().steps.size)
    }

    @Test
    fun `duplicate task id is rejected`() {
        assertThrows(Dst1ValidationException::class.java) {
            parser.parse(resource("invalid/duplicate-task-id.json"), importedAt)
        }
    }

    @Test
    fun `unknown field is rejected`() {
        assertThrows(Dst1ValidationException::class.java) {
            parser.parse(resource("invalid/unknown-field.json"), importedAt)
        }
    }

    @Test
    fun `recognized but unsupported execution type is rejected`() {
        assertThrows(Dst1ValidationException::class.java) {
            parser.parse(resource("invalid/unsupported-counter.json"), importedAt)
        }
    }

    @Test
    fun `bad crc fixture is rejected before json parsing`() {
        assertThrows(Dst1DecodeException::class.java) {
            Dst1Decoder.decode(resource("invalid/bad-crc.dst1").trim())
        }
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing resource $path" }.readText()
}
