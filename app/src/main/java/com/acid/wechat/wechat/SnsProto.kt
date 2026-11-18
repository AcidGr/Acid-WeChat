package com.acid.wechat.wechat

import com.acid.wechat.core.ProtoLite
import com.acid.wechat.core.ProtoLite.WIRE_LEN
import com.acid.wechat.core.ProtoLite.WIRE_VARINT
import com.acid.wechat.core.ProtoLite.WireField

/**
 * WeChat Moments (`SnsObject`, `mg6`, `Location` / `ml4`) protobuf wire adapters.
 */
object SnsProto {
    private const val SNS_LIKE_COUNT = 7
    private const val SNS_LIKE_LIST_COUNT = 8
    private const val SNS_LIKE_LIST = 9
    private const val SNS_COMMENT_COUNT = 10
    private const val SNS_COMMENT_LIST_COUNT = 11
    private const val SNS_COMMENT_LIST = 12

    private const val COMMENT_USERNAME = 1
    private const val COMMENT_NICKNAME = 2
    private const val COMMENT_SOURCE = 3
    private const val COMMENT_TYPE = 4
    private const val COMMENT_CONTENT = 5
    private const val COMMENT_CREATE_TIME = 6
    private const val COMMENT_ID = 7
    private const val COMMENT_REPLY_ID = 8
    private const val COMMENT_REPLY_USERNAME = 9
    private const val COMMENT_IS_NOT_RICH_TEXT = 10
    private const val COMMENT_REPLY_ID2 = 11
    private const val COMMENT_ID2 = 12
    private const val COMMENT_DELETE_FLAG = 13

    private const val LOC_POI_NAME = 4
    private const val LOC_POI_CLASSIFY_TYPE = 7
    private const val LOC_SHOW_FLAG = 10

    data class LikeEntry(
        val username: String,
        val nickname: String,
        val createTime: Int,
    )

    data class CommentEntry(
        val commentId: Int,
        val username: String,
        val nickname: String,
        val content: String,
        val createTime: Int,
        val replyUsername: String = "",
        val replyCommentId: Int = 0,
    )

    fun readLikes(attrBuf: ByteArray?): List<LikeEntry> {
        if (attrBuf == null || attrBuf.isEmpty()) return emptyList()
        val fields = ProtoLite.parseFields(attrBuf) ?: return emptyList()
        return fields.mapNotNull { wf ->
            if (wf.number == SNS_LIKE_LIST && wf.wireType == WIRE_LEN) parseLikeEntry(wf.bytes) else null
        }
    }

    fun applyLikes(
        attrBuf: ByteArray,
        removeUsernames: Set<String>,
        addLikes: List<LikeEntry>,
    ): ByteArray? {
        val fields = ProtoLite.parseFields(attrBuf) ?: return null
        var changed = false
        val keptFields = mutableListOf<WireField>()
        val presentUsers = LinkedHashSet<String>()

        for (wf in fields) {
            if (wf.number == SNS_LIKE_LIST && wf.wireType == WIRE_LEN) {
                val entry = parseLikeEntry(wf.bytes)
                if (entry != null && entry.username in removeUsernames) {
                    changed = true
                    continue
                }
                if (entry != null) presentUsers.add(entry.username)
            }
            keptFields += wf
        }

        val additions = mutableListOf<WireField>()
        for (like in addLikes) {
            if (like.username.isBlank() || !presentUsers.add(like.username)) continue
            additions += WireField(SNS_LIKE_LIST, WIRE_LEN, bytes = encodeLikeEntry(like))
            changed = true
        }
        if (!changed) return null

        val insertIdx = keptFields.indexOfLast {
            it.number == SNS_LIKE_LIST || it.number == SNS_LIKE_LIST_COUNT
        }.let { if (it >= 0) it + 1 else keptFields.size }
        keptFields.addAll(insertIdx, additions)

        val totalLikes = presentUsers.size.toLong()
        ProtoLite.setVarintField(keptFields, SNS_LIKE_COUNT, totalLikes)
        ProtoLite.setVarintField(keptFields, SNS_LIKE_LIST_COUNT, totalLikes)
        return ProtoLite.encodeFields(keptFields)
    }

    fun readComments(attrBuf: ByteArray?): List<CommentEntry> {
        if (attrBuf == null || attrBuf.isEmpty()) return emptyList()
        val fields = ProtoLite.parseFields(attrBuf) ?: return emptyList()
        return fields.mapNotNull { wf ->
            if (wf.number == SNS_COMMENT_LIST && wf.wireType == WIRE_LEN) parseCommentEntry(wf.bytes) else null
        }
    }

    fun applyComments(
        attrBuf: ByteArray,
        removeCommentIds: Set<Int>,
        addComments: List<CommentEntry>,
    ): ByteArray? {
        val fields = ProtoLite.parseFields(attrBuf) ?: return null
        var changed = false
        val keptFields = mutableListOf<WireField>()
        val presentIds = HashSet<Int>()
        var activeCount = 0

        for (wf in fields) {
            if (wf.number == SNS_COMMENT_LIST && wf.wireType == WIRE_LEN) {
                val entry = parseCommentEntry(wf.bytes)
                if (entry != null && entry.commentId in removeCommentIds) {
                    changed = true
                    continue
                }
                if (entry != null) {
                    if (entry.commentId != 0) presentIds += entry.commentId
                    activeCount++
                }
            }
            keptFields += wf
        }

        val additions = mutableListOf<WireField>()
        for (comment in addComments) {
            if (comment.username.isBlank() || comment.content.isBlank()) continue
            if (comment.commentId != 0 && !presentIds.add(comment.commentId)) continue
            additions += WireField(SNS_COMMENT_LIST, WIRE_LEN, bytes = encodeCommentEntry(comment))
            activeCount++
            changed = true
        }
        if (!changed) return null

        val insertIdx = keptFields.indexOfLast {
            it.number == SNS_COMMENT_LIST || it.number == SNS_COMMENT_LIST_COUNT
        }.let { if (it >= 0) it + 1 else keptFields.size }
        keptFields.addAll(insertIdx, additions)

        ProtoLite.setVarintField(keptFields, SNS_COMMENT_COUNT, activeCount.toLong())
        ProtoLite.setVarintField(keptFields, SNS_COMMENT_LIST_COUNT, activeCount.toLong())
        return ProtoLite.encodeFields(keptFields)
    }

    fun readLocationPoi(locationBytes: ByteArray?): String? =
        ProtoLite.readUtf8Field(locationBytes, LOC_POI_NAME)

    fun encodeLocationPoi(poiName: String): ByteArray = ProtoLite.encodeFields(
        listOf(
            WireField(LOC_POI_NAME, WIRE_LEN, bytes = poiName.toByteArray(Charsets.UTF_8)),
            WireField(LOC_POI_CLASSIFY_TYPE, WIRE_VARINT, varint = 1L),
            WireField(LOC_SHOW_FLAG, WIRE_VARINT, varint = 0L),
        ),
    )

    private fun parseLikeEntry(bytes: ByteArray?): LikeEntry? {
        val fields = parseMg6Fields(bytes) ?: return null
        val username = readStr(fields, COMMENT_USERNAME)?.takeIf { it.isNotBlank() } ?: return null
        val nickname = readStr(fields, COMMENT_NICKNAME).orEmpty()
        val createTime = readInt(fields, COMMENT_CREATE_TIME)
        return LikeEntry(username, nickname, createTime)
    }

    private fun parseCommentEntry(bytes: ByteArray?): CommentEntry? {
        val fields = parseMg6Fields(bytes) ?: return null
        val username = readStr(fields, COMMENT_USERNAME)?.takeIf { it.isNotBlank() } ?: return null
        val nickname = readStr(fields, COMMENT_NICKNAME).orEmpty()
        val content = readStr(fields, COMMENT_CONTENT).orEmpty()
        val createTime = readInt(fields, COMMENT_CREATE_TIME)
        val commentId = readInt(fields, COMMENT_ID).takeIf { it != 0 } ?: readInt(fields, COMMENT_ID2)
        val replyUsername = readStr(fields, COMMENT_REPLY_USERNAME).orEmpty()
        val replyCommentId = readInt(fields, COMMENT_REPLY_ID).takeIf { it != 0 } ?: readInt(fields, COMMENT_REPLY_ID2)
        return CommentEntry(commentId, username, nickname, content, createTime, replyUsername, replyCommentId)
    }

    private fun parseMg6Fields(bytes: ByteArray?): List<WireField>? {
        if (bytes == null || bytes.isEmpty()) return null
        val fields = ProtoLite.parseFields(bytes) ?: return null
        val delFlag = fields.firstOrNull { it.number == COMMENT_DELETE_FLAG && it.wireType == WIRE_VARINT }?.varint ?: 0L
        return if (delFlag != 0L) null else fields
    }

    private fun encodeLikeEntry(like: LikeEntry): ByteArray = ProtoLite.encodeFields(
        listOf(
            WireField(COMMENT_USERNAME, WIRE_LEN, bytes = like.username.toByteArray(Charsets.UTF_8)),
            WireField(COMMENT_NICKNAME, WIRE_LEN, bytes = like.nickname.ifEmpty { like.username }.toByteArray(Charsets.UTF_8)),
            WireField(COMMENT_SOURCE, WIRE_VARINT, varint = 0L),
            WireField(COMMENT_TYPE, WIRE_VARINT, varint = 1L),
            WireField(COMMENT_CREATE_TIME, WIRE_VARINT, varint = like.createTime.toLong()),
        ),
    )

    private fun encodeCommentEntry(c: CommentEntry): ByteArray {
        val fields = mutableListOf(
            WireField(COMMENT_USERNAME, WIRE_LEN, bytes = c.username.toByteArray(Charsets.UTF_8)),
            WireField(COMMENT_NICKNAME, WIRE_LEN, bytes = c.nickname.ifEmpty { c.username }.toByteArray(Charsets.UTF_8)),
            WireField(COMMENT_SOURCE, WIRE_VARINT, varint = 0L),
            WireField(COMMENT_TYPE, WIRE_VARINT, varint = 2L),
            WireField(COMMENT_CONTENT, WIRE_LEN, bytes = c.content.toByteArray(Charsets.UTF_8)),
            WireField(COMMENT_CREATE_TIME, WIRE_VARINT, varint = c.createTime.toLong()),
            WireField(COMMENT_ID, WIRE_VARINT, varint = c.commentId.toLong()),
            WireField(COMMENT_IS_NOT_RICH_TEXT, WIRE_VARINT, varint = 0L),
            WireField(COMMENT_ID2, WIRE_VARINT, varint = c.commentId.toLong()),
        )
        if (c.replyCommentId != 0) {
            fields += WireField(COMMENT_REPLY_ID, WIRE_VARINT, varint = c.replyCommentId.toLong())
            fields += WireField(COMMENT_REPLY_ID2, WIRE_VARINT, varint = c.replyCommentId.toLong())
        }
        if (c.replyUsername.isNotBlank()) {
            fields += WireField(COMMENT_REPLY_USERNAME, WIRE_LEN, bytes = c.replyUsername.toByteArray(Charsets.UTF_8))
        }
        return ProtoLite.encodeFields(fields)
    }

    private fun readStr(fields: List<WireField>, number: Int): String? =
        fields.firstOrNull { it.number == number && it.wireType == WIRE_LEN }?.bytes?.let { String(it, Charsets.UTF_8) }

    private fun readInt(fields: List<WireField>, number: Int): Int =
        fields.firstOrNull { it.number == number && it.wireType == WIRE_VARINT }?.varint?.toInt() ?: 0
}
