package com.ds.localtaskmanager.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

class Dst1DecodeException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object Dst1Decoder {
    const val MAX_INPUT_CHARS = 128 * 1024
    const val MAX_COMPRESSED_BYTES = 96 * 1024
    const val MAX_JSON_BYTES = 256 * 1024

    fun decode(value: String): String {
        if (value.length > MAX_INPUT_CHARS) throw Dst1DecodeException("任务字符串过长")

        val parts = value.split('.')
        if (parts.size != 3 || parts[0] != "DST1") {
            throw Dst1DecodeException("不是有效的 DST1 字符串")
        }

        val expectedChecksum = parts[2]
        if (!expectedChecksum.matches(Regex("[0-9A-F]{8}"))) {
            throw Dst1DecodeException("CRC32 格式无效")
        }

        if (!parts[1].matches(Regex("[A-Za-z0-9_-]+"))) {
            throw Dst1DecodeException("payload 不是无填充 Base64URL")
        }

        val compressed = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (error: IllegalArgumentException) {
            throw Dst1DecodeException("Base64URL 解码失败", error)
        }
        if (compressed.size > MAX_COMPRESSED_BYTES) {
            throw Dst1DecodeException("压缩数据过大")
        }

        val actualChecksum = CRC32().apply { update(compressed) }
            .value
            .toString(16)
            .padStart(8, '0')
            .uppercase(Locale.ROOT)
        if (actualChecksum != expectedChecksum) {
            throw Dst1DecodeException("CRC32 校验失败")
        }

        val output = ByteArrayOutputStream()
        try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_JSON_BYTES) {
                        throw Dst1DecodeException("解压后的 JSON 过大")
                    }
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: Dst1DecodeException) {
            throw error
        } catch (error: Exception) {
            throw Dst1DecodeException("zlib 解压失败", error)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (error: Exception) {
            throw Dst1DecodeException("JSON 不是有效的 UTF-8", error)
        }
    }
}
