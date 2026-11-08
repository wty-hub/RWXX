package io.github.rwx.net.timeline

import java.io.IOException

object TimelineProtocol {
    const val VERSION = 7
    const val MAGIC = 0x52575854
    const val MAX_FRAME_PAYLOAD = 1_048_576
    const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    const val MAX_CHUNK_BYTES = 65_536
    const val MAX_FIELDS = 256
    const val MAX_DEPTH = 8
    const val MAX_RECORDS = 65_536
    const val MAX_OPERATIONS = 2_097_152
    const val MAX_OPERATION_BYTES = 1_048_576
    const val MAX_SPLICE_RECORDS = 4096
    const val MAX_OBJECTS = 262_144
    const val MAX_UNITS = 65_536
    const val MAX_CURVE_RECORDS = 2_000_000
    const val MAX_ATTRIBUTES = 128
    const val MAX_ROOT_ATTRIBUTES = 8192
    const val SUBDIVISIONS = 1024
    const val FOREVER = Long.MAX_VALUE
    const val MAX_TIME = Long.MAX_VALUE - 1
    const val MIN_SINT = -(1L shl 62)
    const val MAX_SINT = (1L shl 62) - 1
    const val MAX_U32 = 0xffff_ffffL
}

/** Malformed wire data or an invalid invariant. This is never a restore request. */
open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A structurally bounded operation needs a previously declared curve. */
class MissingBase(message: String = "Missing timeline context") : IOException(message)

internal fun protocolRequire(condition: Boolean, message: String) {
    if (!condition) throw ProtocolException(message)
}
