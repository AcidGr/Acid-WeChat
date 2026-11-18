package com.acid.wechat.core

import java.io.ByteArrayOutputStream

/**
 * Lightweight zero-dependency Protobuf wire-format reader/rewriter.
 */
object ProtoLite {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_START_GROUP = 3
    const val WIRE_END_GROUP = 4
    const val WIRE_FIXED32 = 5

    class WireField(
        val number: Int,
        val wireType: Int,
        var varint: Long = 0L,
        var bytes: ByteArray? = null,
    )

    fun readUtf8Field(data: ByteArray?, targetFieldNumber: Int): String? {
        if (data == null || data.isEmpty()) return null
        val fields = parseFields(data) ?: return null
        val bytes = fields.firstOrNull { it.number == targetFieldNumber && it.wireType == WIRE_LEN }?.bytes
            ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    fun rewriteUtf8Field(
        data: ByteArray,
        targetFieldNumber: Int,
        transform: (String) -> String?,
    ): ByteArray? {
        val fields = parseFields(data) ?: return null
        val target = fields.firstOrNull { it.number == targetFieldNumber && it.wireType == WIRE_LEN }
            ?: return null
        val oldStr = String(target.bytes ?: return null, Charsets.UTF_8)
        val newStr = transform(oldStr) ?: return null
        if (newStr == oldStr) return null
        target.bytes = newStr.toByteArray(Charsets.UTF_8)
        return encodeFields(fields)
    }

    fun setVarintField(fields: MutableList<WireField>, number: Int, value: Long) {
        val existing = fields.firstOrNull { it.number == number && it.wireType == WIRE_VARINT }
        if (existing != null) {
            existing.varint = value
        } else {
            fields.add(WireField(number, WIRE_VARINT, varint = value))
        }
    }

    fun parseFields(data: ByteArray): MutableList<WireField>? {
        val out = mutableListOf<WireField>()
        var pos = 0
        while (pos < data.size) {
            val tagRes = readVarint(data, pos) ?: return null
            val tag = tagRes.first.toInt()
            pos = tagRes.second
            val fieldNumber = tag ushr 3
            if (fieldNumber <= 0) return null
            when (val wireType = tag and 0x07) {
                WIRE_VARINT -> {
                    val v = readVarint(data, pos) ?: return null
                    pos = v.second
                    out += WireField(fieldNumber, WIRE_VARINT, varint = v.first)
                }
                WIRE_FIXED64 -> {
                    if (pos + 8 > data.size) return null
                    out += WireField(fieldNumber, WIRE_FIXED64, bytes = data.copyOfRange(pos, pos + 8))
                    pos += 8
                }
                WIRE_FIXED32 -> {
                    if (pos + 4 > data.size) return null
                    out += WireField(fieldNumber, WIRE_FIXED32, bytes = data.copyOfRange(pos, pos + 4))
                    pos += 4
                }
                WIRE_LEN -> {
                    val lenRes = readVarint(data, pos) ?: return null
                    val len = lenRes.first.toInt()
                    val payloadStart = lenRes.second
                    if (len < 0 || payloadStart + len > data.size) return null
                    out += WireField(fieldNumber, WIRE_LEN, bytes = data.copyOfRange(payloadStart, payloadStart + len))
                    pos = payloadStart + len
                }
                WIRE_START_GROUP, WIRE_END_GROUP -> return null
                else -> return null
            }
        }
        return out
    }

    fun encodeFields(fields: List<WireField>): ByteArray {
        val out = ByteArrayOutputStream(fields.size * 16)
        for (wf in fields) {
            writeVarint(out, ((wf.number shl 3) or wf.wireType).toLong())
            when (wf.wireType) {
                WIRE_VARINT -> writeVarint(out, wf.varint)
                WIRE_FIXED64, WIRE_FIXED32 -> wf.bytes?.let { out.write(it) }
                WIRE_LEN -> {
                    val b = wf.bytes ?: ByteArray(0)
                    writeVarint(out, b.size.toLong())
                    out.write(b)
                }
            }
        }
        return out.toByteArray()
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var idx = start
        while (idx < data.size && shift < 64) {
            val b = data[idx++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to idx
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
