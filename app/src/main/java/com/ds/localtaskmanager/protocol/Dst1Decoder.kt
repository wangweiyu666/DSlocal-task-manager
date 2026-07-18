package com.ds.localtaskmanager.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

object Dst1Decoder {
    const val MAX_INPUT_CHARS = 128 * 1024
    const val MAX_COMPRESSED_BYTES = 96 * 1024
    const val MAX_JSON_BYTES = 256 * 1024

    fun decode(value: String): String {
        if (value.length > MAX_INPUT_CHARS) {
            invalid(Dst1ErrorCode.INPUT_TOO_LARGE, "$", "任务字符串过长")
        }

        val parts = value.split('.')
        if (parts.size != 3 || parts[0] != "DST1") {
            invalid(Dst1ErrorCode.INVALID_ENVELOPE, "$", "不是有效的 DST1 字符串")
        }

        val expectedChecksum = parts[2]
        if (!expectedChecksum.matches(Regex("[0-9A-F]{8}"))) {
            invalid(Dst1ErrorCode.INVALID_CHECKSUM_FORMAT, "$.checksum", "CRC32 格式无效")
        }

        if (!parts[1].matches(Regex("[A-Za-z0-9_-]+"))) {
            invalid(Dst1ErrorCode.INVALID_BASE64URL, "$.payload", "payload 不是无填充 Base64URL")
        }

        val compressed = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (error: IllegalArgumentException) {
            invalid(Dst1ErrorCode.INVALID_BASE64URL, "$.payload", "Base64URL 解码失败", error)
        }
        if (compressed.size > MAX_COMPRESSED_BYTES) {
            invalid(Dst1ErrorCode.COMPRESSED_DATA_TOO_LARGE, "$.payload", "压缩数据过大")
        }

        val actualChecksum = CRC32().apply { update(compressed) }
            .value
            .toString(16)
            .padStart(8, '0')
            .uppercase(Locale.ROOT)
        if (actualChecksum != expectedChecksum) {
            invalid(Dst1ErrorCode.CHECKSUM_MISMATCH, "$.checksum", "CRC32 校验失败")
        }

        val output = ByteArrayOutputStream()
        try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_JSON_BYTES) {
                        invalid(Dst1ErrorCode.JSON_TOO_LARGE, "$.json", "解压后的 JSON 过大")
                    }
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: Dst1DecodeException) {
            throw error
        } catch (error: Exception) {
            invalid(Dst1ErrorCode.DECOMPRESSION_FAILED, "$.payload", "zlib 解压失败", error)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (error: Exception) {
            invalid(Dst1ErrorCode.INVALID_UTF8, "$.json", "JSON 不是有效的 UTF-8", error)
        }
    }

    private fun invalid(
        code: Dst1ErrorCode,
        path: String,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw Dst1DecodeException(code, path, message, cause)
}
