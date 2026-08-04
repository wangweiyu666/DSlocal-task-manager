package com.ds.localtaskmanager.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

class DstbException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class DecodedBackup(val metadata: BackupMetadata, val payload: BackupPayload)

object DstbCodec {
    const val FORMAT_VERSION = 1
    const val MAX_FILE_BYTES = 100 * 1024 * 1024
    const val MAX_JSON_BYTES = 500 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 1024 * 1024
    private val magic = "DSTB1".encodeToByteArray()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(metadata: BackupMetadata, payload: BackupPayload): ByteArray {
        val metadataBytes = json.encodeToString(metadata).encodeToByteArray()
        if (metadataBytes.size > MAX_METADATA_BYTES) throw DstbException("备份元数据过大")
        val compressedOutput = ByteArrayOutputStream()
        val counter = CountingOutputStream(
            DeflaterOutputStream(compressedOutput, Deflater(Deflater.DEFAULT_COMPRESSION, false)),
            MAX_JSON_BYTES.toLong(),
        )
        try {
            json.encodeToStream(payload, counter)
        } finally {
            counter.close()
        }
        val rawLength = counter.count
        val compressed = compressedOutput.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(magic)
            writeShortLe(FORMAT_VERSION)
            writeIntLe(metadataBytes.size)
            write(metadataBytes)
            writeLongLe(compressed.size.toLong())
            writeLongLe(rawLength)
            write(compressed)
        }.toByteArray()
        if (body.size + 4 > MAX_FILE_BYTES) throw DstbException("备份文件超过 100 MB")
        val crc = CRC32().apply { update(body) }.value
        return ByteArrayOutputStream(body.size + 4).apply {
            write(body)
            writeIntLe(crc.toInt())
        }.toByteArray()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun decode(file: ByteArray): DecodedBackup {
        if (file.size > MAX_FILE_BYTES) throw DstbException("备份文件超过 100 MB")
        if (file.size < magic.size + 2 + 4 + 8 + 8 + 4) throw DstbException("备份文件已截断")
        val expectedCrc = file.readUIntLe(file.size - 4)
        val actualCrc = CRC32().apply { update(file, 0, file.size - 4) }.value
        if (expectedCrc != actualCrc) throw DstbException("备份文件校验失败，文件可能已损坏")

        val cursor = Cursor(file, file.size - 4)
        if (!cursor.readBytes(magic.size).contentEquals(magic)) throw DstbException("不是 DSTB1 备份文件")
        val version = cursor.readUShortLe()
        if (version > FORMAT_VERSION) throw DstbException("备份版本较新，请升级应用后重试")
        if (version != FORMAT_VERSION) throw DstbException("不支持的备份版本：$version")
        val metadataLength = cursor.readIntLe()
        if (metadataLength !in 1..MAX_METADATA_BYTES) throw DstbException("备份元数据长度无效")
        val metadataBytes = cursor.readBytes(metadataLength)
        val compressedLength = cursor.readLongLe()
        val rawLength = cursor.readLongLe()
        if (compressedLength < 0 || compressedLength > cursor.remaining.toLong()) throw DstbException("压缩数据长度无效")
        if (rawLength !in 1..MAX_JSON_BYTES.toLong()) throw DstbException("解压数据长度无效")
        if (compressedLength != cursor.remaining.toLong()) throw DstbException("备份文件长度与文件头不一致")
        val compressed = cursor.readBytes(compressedLength.toInt())
        val metadata = parse("备份元数据") { json.decodeFromString<BackupMetadata>(metadataBytes.decodeToString()) }
        val payload = try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
                val bounded = BoundedInputStream(inflater, rawLength)
                val value = parse("备份内容") { json.decodeFromStream<BackupPayload>(bounded) }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (bounded.read(buffer) >= 0) Unit
                if (bounded.count != rawLength) throw DstbException("解压内容长度与文件头不一致")
                value
            }
        } catch (error: DstbException) {
            throw error
        } catch (error: Exception) {
            throw DstbException("无法解压备份文件", error)
        }
        if (metadata.payloadSchemaVersion != payload.schemaVersion) throw DstbException("备份数据版本不一致")
        return DecodedBackup(metadata, payload)
    }

    private inline fun <T> parse(label: String, block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw DstbException("$label 无效", error)
    }

    private class Cursor(private val source: ByteArray, private val limit: Int) {
        private var position = 0
        val remaining: Int get() = limit - position
        fun readBytes(count: Int): ByteArray {
            if (count < 0 || count > remaining) throw DstbException("备份文件已截断")
            return source.copyOfRange(position, position + count).also { position += count }
        }
        fun readUShortLe(): Int = readBytes(2).let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) }
        fun readIntLe(): Int = readBytes(4).let { bytes ->
            (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8) or
                ((bytes[2].toInt() and 0xff) shl 16) or ((bytes[3].toInt() and 0xff) shl 24)
        }
        fun readLongLe(): Long = readBytes(8).foldIndexed(0L) { index, value, byte ->
            value or ((byte.toLong() and 0xffL) shl (index * 8))
        }
    }

    private class CountingOutputStream(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        var count: Long = 0
            private set
        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            count++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            out.write(buffer, offset, length)
            count += length
        }
        private fun ensureCapacity(next: Int) {
            if (count + next > limit) throw DstbException("备份内容超过 500 MB")
        }
    }

    private class BoundedInputStream(input: InputStream, private val expected: Long) : FilterInputStream(input) {
        var count: Long = 0
            private set
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) add(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val result = super.read(buffer, offset, length)
            if (result > 0) add(result)
            return result
        }
        private fun add(value: Int) {
            count += value
            if (count > expected || count > MAX_JSON_BYTES) throw DstbException("解压内容超过文件头声明长度")
        }
    }

    private fun ByteArray.readUIntLe(offset: Int): Long =
        (this[offset].toLong() and 0xffL) or ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff)
    }
    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        repeat(4) { write((value ushr (it * 8)) and 0xff) }
    }
    private fun ByteArrayOutputStream.writeLongLe(value: Long) {
        repeat(8) { write(((value ushr (it * 8)) and 0xff).toInt()) }
    }
}
