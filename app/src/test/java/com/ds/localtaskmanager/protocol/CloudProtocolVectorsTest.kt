package com.ds.localtaskmanager.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CloudProtocolVectorsTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun sharedCloudManifestReferencesReadableJsonVectors() {
        val loader = requireNotNull(javaClass.classLoader)
        val manifest = requireNotNull(loader.getResourceAsStream("cloud-manifest.json"))
            .bufferedReader()
            .use { json.parseToJsonElement(it.readText()).jsonObject }
        val vectors = requireNotNull(manifest["vectors"]).jsonArray
        assertTrue(vectors.size >= 7)
        vectors.forEach { entry ->
            val path = requireNotNull(entry.jsonObject["file"]).jsonPrimitive.content
            val resource = loader.getResourceAsStream(path)
            assertNotNull("Missing shared vector: $path", resource)
            resource!!.bufferedReader().use { json.parseToJsonElement(it.readText()) }
        }
    }

    @Test
    fun occurrenceKeyMatchesSharedVector() {
        val loader = requireNotNull(javaClass.classLoader)
        val vector = requireNotNull(loader.getResourceAsStream("valid/occurrence-key.json"))
            .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        val actual = cloudOccurrenceKey(
            taskId = requireNotNull(vector["taskId"]).jsonPrimitive.content,
            taskRevision = requireNotNull(vector["taskRevision"]).jsonPrimitive.content.toInt(),
            timeZoneVersion = requireNotNull(vector["timeZoneVersion"]).jsonPrimitive.content.toInt(),
            scheduledLocalTime = LocalDateTime.parse(requireNotNull(vector["scheduledLocalTime"]).jsonPrimitive.content),
        )
        assertTrue(actual == requireNotNull(vector["occurrenceKey"]).jsonPrimitive.content)
    }
}
