package com.ds.localtaskmanager.protocol

enum class Dst1ErrorCode {
    INPUT_TOO_LARGE,
    INVALID_ENVELOPE,
    INVALID_CHECKSUM_FORMAT,
    INVALID_BASE64URL,
    COMPRESSED_DATA_TOO_LARGE,
    CHECKSUM_MISMATCH,
    DECOMPRESSION_FAILED,
    JSON_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    TYPE_MISMATCH,
    UNKNOWN_FIELD,
    REQUIRED_FIELD_MISSING,
    INVALID_VALUE,
    VALUE_OUT_OF_RANGE,
    INVALID_DATE,
    NON_CANONICAL_TEXT,
    DUPLICATE_VALUE,
    CONFLICTING_FIELDS,
    EMPTY_OPERATION,
    CAPABILITY_NOT_IMPLEMENTED,
}

sealed class Dst1ProtocolException(
    val code: Dst1ErrorCode,
    val path: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class Dst1DecodeException(
    code: Dst1ErrorCode,
    path: String?,
    message: String,
    cause: Throwable? = null,
) : Dst1ProtocolException(code, path, message, cause)

class Dst1ValidationException(
    code: Dst1ErrorCode,
    path: String?,
    message: String,
) : Dst1ProtocolException(code, path, message)
