package com.acid.wechat.core

import java.io.ByteArrayOutputStream

/**
 * Lightweight zero-dependency Protobuf wire-format reader/rewriter for patching UTF-8 string fields
 * (e.g., SnsInfo.content field 5) without pulling protobuf-lite.
 */
object ProtoLite {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_START_GROUP = 3
    private const val WIRE_END_GROUP = 4
    private const val WIRE_FIXED32 = 5

    /**
     * Rewrites the first length-delimited UTF-8 field matching [targetFieldNumber] using [transform].
     * Returns null if field is missing, unchanged, or wire format is invalid.
     */
    fun rewriteUtf8Field(
        data: ByteArray,
        targetFieldNumber: Int,
        transform: (String) -> String?,
    ): ByteArray? {
        var pos = 0
        while (pos < data.size) {
            val tagRes = readVarint(data, pos) ?: return null
            val tag = tagRes.first.toInt()
            pos = tagRes.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                WIRE_VARINT -> {
                    val v = readVarint(data, pos) ?: return null
                    pos = v.second
                }
                WIRE_FIXED64 -> pos += 8
                WIRE_FIXED32 -> pos += 4
                WIRE_LEN -> {
                    val lenStart = pos
                    val lenRes = readVarint(data, pos) ?: return null
                    val len = lenRes.first.toInt()
                    val payloadStart = lenRes.second
                    if (len < 0 || payloadStart + len > data.size) return null
                    if (fieldNumber == targetFieldNumber) {
                        val oldStr = String(data, payloadStart, len, Charsets.UTF_8)
                        val newStr = transform(oldStr) ?: return null
                        if (newStr == oldStr) return null
                        val newBytes = newStr.toByteArray(Charsets.UTF_8)
                        val out = ByteArrayOutputStream(data.size - len + newBytes.size + 4)
                        out.write(data, 0, lenStart)
                        writeVarint(out, newBytes.size.toLong())
                        out.write(newBytes)
                        val tailStart = payloadStart + len
                        if (tailStart < data.size) {
                            out.write(data, tailStart, data.size - tailStart)
                        }
                        return out.toByteArray()
                    }
                    pos = payloadStart + len
                }
                WIRE_START_GROUP, WIRE_END_GROUP -> return null
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var idx = start
        while (idx < data.size && shift < 64) {
            val b = data[idx++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return result to idx
            }
            shift += 7
        }
        return null
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }
}
