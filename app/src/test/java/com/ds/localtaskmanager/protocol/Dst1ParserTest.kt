package com.ds.localtaskmanager.protocol

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Dst1ParserTest {
    private val parser = Dst1Parser()
    private val importedAt = LocalDateTime.of(2026, 7, 18, 10, 0)
    private val json = Json { isLenient = false }

    @Test
    fun `shared manifest drives every protocol vector`() {
        val manifest = json.parseToJsonElement(resource("manifest.json")).jsonObject
        assertEquals(1, manifest.requiredInt("formatVersion"))

        manifest.getValue("cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val id = case.requiredString("id")
            assertExpectationLayers(id, case)

            val actual = runCatching { execute(case.getValue("source").jsonObject) }
                .exceptionOrNull()
            val expected = case.getValue("android").jsonObject
            when (expected.requiredString("result")) {
                "VALID" -> assertNull("$id should be accepted, but failed with $actual", actual)
                "ERROR" -> {
                    assertNotNull("$id should be rejected", actual)
                    assertTrue("$id threw ${actual!!::class.java.name}", actual is Dst1ProtocolException)
                    actual as Dst1ProtocolException
                    assertEquals("$id error code", expected.requiredString("code"), actual.code.name)
                    assertEquals("$id error path", expected.requiredString("path"), actual.path)
                }
                else -> error("Unknown result in $id")
            }
        }
    }

    @Test
    fun `manifest covers every committed fixture`() {
        val manifest = json.parseToJsonElement(resource("manifest.json")).jsonObject
        val referenced = manifest.getValue("cases").jsonArray.flatMap { element ->
            val source = element.jsonObject.getValue("source").jsonObject
            listOfNotNull(source.stringOrNull("path"), source.stringOrNull("decodedJson"))
        }.toSet()
        val root = repositoryFile("protocol-test-vectors")
        val committed = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("json", "dst1") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it != "manifest.json" }
            .toSet()

        assertEquals("Every fixture must be listed in manifest.json", committed, referenced)
    }

    @Test
    fun `schema is draft 2020-12 and closes every protocol object`() {
        val schemaFile = repositoryFile("docs/dst1-schema.json")
        val schema = json.parseToJsonElement(schemaFile.readText()).jsonObject

        assertEquals(
            "https://json-schema.org/draft/2020-12/schema",
            schema.requiredString("\$schema"),
        )
        assertEquals("false", schema.getValue("additionalProperties").toString())
        val definitions = schema.getValue("\$defs").jsonObject
        listOf("group", "step", "recurrence", "task").forEach { name ->
            assertEquals("false", definitions.getValue(name).jsonObject.getValue("additionalProperties").toString())
        }
    }

    private fun execute(source: JsonObject) {
        when (source.requiredString("kind")) {
            "json" -> parser.parse(resource(source.requiredString("path")), importedAt)
            "dst1" -> {
                val decoded = Dst1Decoder.decode(resource(source.requiredString("path")).trim())
                source.stringOrNull("decodedJson")?.let { expectedPath ->
                    assertEquals(resource(expectedPath).trim(), decoded)
                }
                parser.parse(decoded, importedAt)
            }
            "generator" -> executeGenerated(source.requiredString("type"))
            else -> error("Unknown vector source kind")
        }
    }

    private fun executeGenerated(type: String) {
        when (type) {
            "invalid-envelope" -> Dst1Decoder.decode("DST2.eA.00000000")
            "invalid-checksum-format" -> Dst1Decoder.decode("DST1.eA.abcdef12")
            "invalid-base64url" -> Dst1Decoder.decode("DST1.abc=.00000000")
            "decompression-failed" -> Dst1Decoder.decode(envelopeFromCompressed(byteArrayOf(1, 2, 3)))
            "invalid-json" -> parser.parse(Dst1Decoder.decode(envelope("{]".toByteArray())), importedAt)
            "top-level-type" -> parser.parse("[]", importedAt)
            "unsupported-version" -> parser.parse("{\"v\":2,\"b\":\"GeneratedBatch03\",\"d\":\"\"}", importedAt)
            "input-over-limit" -> Dst1Decoder.decode("X".repeat(Dst1Decoder.MAX_INPUT_CHARS + 1))
            "compressed-over-limit" -> {
                val compressed = ByteArray(Dst1Decoder.MAX_COMPRESSED_BYTES + 1) { index -> index.toByte() }
                Dst1Decoder.decode(envelopeFromCompressed(compressed))
            }
            "json-at-limit" -> parser.parse(
                Dst1Decoder.decode(envelope(jsonAtSize(Dst1Decoder.MAX_JSON_BYTES))),
                importedAt,
            )
            "json-over-limit" -> Dst1Decoder.decode(envelope(jsonAtSize(Dst1Decoder.MAX_JSON_BYTES + 1)))
            "invalid-utf8" -> Dst1Decoder.decode(envelope(byteArrayOf(0xC3.toByte(), 0x28)))
            "too-many-steps" -> {
                val steps = (1..51).joinToString(",") { "{\"n\":\"S$it\",\"r\":1}" }
                parser.parse(
                    "{\"v\":1,\"b\":\"GeneratedBatch01\",\"t\":[{\"i\":\"GeneratedTask001\",\"n\":\"Steps\",\"r\":1,\"s\":[$steps]}]}",
                    importedAt,
                )
            }
            "task-name-over-limit" -> parser.parse(
                "{\"v\":1,\"b\":\"GeneratedBatch02\",\"t\":[{\"i\":\"GeneratedTask002\",\"n\":\"${"N".repeat(101)}\",\"r\":1}]}",
                importedAt,
            )
            else -> error("Unknown generator $type")
        }
    }

    private fun assertExpectationLayers(id: String, case: JsonObject) {
        val spec = case.getValue("spec").jsonObject
        val android = case.getValue("android").jsonObject
        if (spec.requiredString("result") == "VALID" && android.requiredString("result") == "ERROR") {
            assertEquals("$id may differ only for deferred capabilities", "CAPABILITY_NOT_IMPLEMENTED", android.requiredString("code"))
        }
        if (spec.requiredString("result") == "ERROR") {
            assertEquals("$id must preserve the protocol error in Android", spec.requiredString("code"), android.requiredString("code"))
            assertEquals("$id must preserve the protocol path in Android", spec.requiredString("path"), android.requiredString("path"))
        }
    }

    private fun jsonAtSize(size: Int): ByteArray {
        val base = "{\"v\":1,\"b\":\"LimitBatch000001\",\"d\":\"\"}".toByteArray(Charsets.UTF_8)
        check(base.size <= size)
        return base + ByteArray(size - base.size) { ' '.code.toByte() }
    }

    private fun envelope(jsonBytes: ByteArray): String {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(jsonBytes) }
        }.toByteArray()
        return envelopeFromCompressed(compressed)
    }

    private fun envelopeFromCompressed(compressed: ByteArray): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        val checksum = CRC32().apply { update(compressed) }
            .value
            .toString(16)
            .padStart(8, '0')
            .uppercase(Locale.ROOT)
        return "DST1.$payload.$checksum"
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing resource $path" }.readText()

    private fun repositoryFile(relativePath: String): File {
        var directory: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile
        }
        error("Cannot locate repository file $relativePath")
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive.content.toInt()
    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.jsonPrimitive?.content
}
