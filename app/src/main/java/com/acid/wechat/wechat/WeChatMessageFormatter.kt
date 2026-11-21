package com.acid.wechat.wechat

/**
 * Formats raw WeChat DB message payloads (plain text, media placeholders, VoIP calls,
 * location/contact cards, system notices, `<appmsg>` cards, and `<refermsg>` quotes)
 * into clean human- and Agent-readable text.
 */
object WeChatMessageFormatter {
    private val systemCompositeTypes = setOf(
        10000, 10002, 64, 268445456, 268445458, 285222674, 570425393, 603979825,
    )
    private val patCompositeTypes = setOf(889192497, 922746929)

    fun normalizeType(type: Int): Int {
        if ((type ushr 16) == 0) return type
        val low16 = type and 0xFFFF
        if (low16 == 10000 || low16 == 10002) return low16
        val low8 = type and 0xFF
        if (low8 == 49) return 49
        return if (low8 != 0 && low16 == low8) low8 else type
    }

    fun isTextOrAppMsgType(type: Int): Boolean {
        if (type in systemCompositeTypes || type in patCompositeTypes) return false
        val base = normalizeType(type)
        return base == 1 || base == 49
    }

    fun stripGroupPrefix(talker: String, isSend: Boolean, raw: String): String {
        if (!talker.endsWith("@chatroom") || isSend) return raw
        val idx = raw.indexOf(":\n")
        return if (idx in 1..64) raw.substring(idx + 2) else raw
    }

    fun extractAppMsgTitle(stripped: String): String? =
        WeChatSpecialMsgFormatter.extractTitle(stripped)

    /**
     * Extracts the newly typed reply text when checking wake-word triggers, ignoring wake words
     * that might appear inside the quoted `<refermsg>` body.
     */
    fun extractTriggerText(rawContent: String, formattedBody: String): String {
        if (!formattedBody.startsWith("[引用 ")) return formattedBody
        val beforeRefer = rawContent.substringBefore("<refermsg", rawContent)
        return extractAppMsgTitle(beforeRefer) ?: formattedBody.substringAfter("] ", "")
    }

    fun formatMessageBody(
        talker: String,
        isSend: Boolean,
        type: Int,
        raw: String,
        msgId: Long = 0L,
        imgPath: String = "",
        msgSvrId: Long = 0L,
        allowReferLookup: Boolean = true,
    ): String {
        val stripped = stripGroupPrefix(talker, isSend, raw)
        if (type in systemCompositeTypes || type in patCompositeTypes) {
            return WeChatSpecialMsgFormatter.formatSystemBody(stripped)
        }
        val base = normalizeType(type)
        if (base == 49 || stripped.contains("<appmsg", ignoreCase = true)) {
            if (stripped.contains("<sysmsg", ignoreCase = true) && !stripped.contains("<appmsg", ignoreCase = true)) {
                return WeChatSpecialMsgFormatter.formatSystemBody(stripped)
            }
            return WeChatSpecialMsgFormatter.formatAppMsgBody(talker, stripped, allowReferLookup)
        }
        if (stripped.contains("<sysmsg", ignoreCase = true) && base !in setOf(1, 3, 34, 43, 47)) {
            return WeChatSpecialMsgFormatter.formatSystemBody(stripped)
        }
        if (stripped.contains("<voipmsg", ignoreCase = true)) {
            return WeChatSpecialMsgFormatter.formatVoipBody(stripped)
        }
        return when (base) {
            1 -> stripped.trim()
            3 -> ChatImageRepository.resolveMessageImagePath(msgId, msgSvrId, imgPath)
                ?.let { "[图片: $it]" }
                ?: "[图片]"
            34 -> {
                val transcript = if (msgId > 0L || imgPath.isNotEmpty()) {
                    ChatRepository.queryVoiceTranscript(msgId, imgPath)
                } else {
                    null
                }
                if (!transcript.isNullOrBlank()) "[语音: ${transcript.trim()}]" else "[语音]"
            }
            37 -> WeChatSpecialMsgFormatter.formatFriendVerifyBody(stripped)
            40 -> "[好友推荐]"
            42, 66, 67 -> WeChatSpecialMsgFormatter.formatCardBody(stripped)
            43, 62 -> "[视频]"
            47 -> "[动画表情]"
            48 -> WeChatSpecialMsgFormatter.formatLocationBody(stripped)
            50, 52, 53 -> WeChatSpecialMsgFormatter.formatVoipBody(stripped)
            85, 86 -> "[来电铃声]"
            10000, 10002 -> WeChatSpecialMsgFormatter.formatSystemBody(stripped)
            else -> {
                val clean = if (stripped.contains('<') && stripped.contains('>')) {
                    WeChatSpecialMsgFormatter.cleanXmlText(stripped)
                } else {
                    stripped.replace('\n', ' ').trim()
                }
                val label = msgTypeLabel(type)
                if (clean.isNotEmpty()) "[$label] ${clean.take(160)}" else "[$label]"
            }
        }
    }

    /**
     * Cleans `rconversation.content` previews (which lack a reliable `type` column) for `list_chats`.
     */
    fun formatConversationPreview(talker: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val hasGroupPrefix = talker.endsWith("@chatroom") && trimmed.indexOf(":\n") in 1..64
        val senderPrefix = if (hasGroupPrefix) {
            ChatRepository.resolveSenderDisplayName(talker, trimmed, null) + ": "
        } else {
            ""
        }
        val stripped = stripGroupPrefix(talker, false, trimmed).trim()
        val body = when {
            stripped.equals("voip_content_voice", ignoreCase = true) ||
                stripped.equals("voip_content_video", ignoreCase = true) ||
                stripped.contains("<voipmsg", ignoreCase = true) ->
                WeChatSpecialMsgFormatter.formatVoipBody(stripped)
            stripped.contains("<appmsg", ignoreCase = true) ->
                WeChatSpecialMsgFormatter.formatAppMsgBody(talker, stripped, allowReferLookup = false)
            stripped.contains("<sysmsg", ignoreCase = true) ->
                WeChatSpecialMsgFormatter.formatSystemBody(stripped)
            stripped.contains("<location", ignoreCase = true) ->
                WeChatSpecialMsgFormatter.formatLocationBody(stripped)
            stripped.contains("<img", ignoreCase = true) -> "[图片]"
            stripped.contains("<videomsg", ignoreCase = true) -> "[视频]"
            stripped.contains("<emoji", ignoreCase = true) -> "[动画表情]"
            stripped.contains("<msg", ignoreCase = true) && stripped.contains("nickname=", ignoreCase = true) ->
                WeChatSpecialMsgFormatter.formatCardBody(stripped)
            stripped.startsWith("<") ->
                WeChatSpecialMsgFormatter.cleanXmlText(stripped).ifEmpty { "[消息]" }
            else -> stripped.replace('\n', ' ').trim()
        }
        return (senderPrefix + body).take(140)
    }

    fun msgTypeLabel(type: Int): String {
        if (type == 822083633) return "quote"
        if (type in patCompositeTypes) return "pat"
        if (type in systemCompositeTypes) return "system"
        return when (normalizeType(type)) {
            1 -> "text"
            3 -> "image"
            34 -> "voice"
            37 -> "friend_verify"
            40 -> "contact_recommend"
            42, 66, 67 -> "card"
            43, 62 -> "video"
            47 -> "emoji"
            48 -> "location"
            49 -> "appmsg"
            50, 52, 53 -> "voip"
            85, 86 -> "ringtone"
            10000, 10002 -> "system"
            else -> "type_$type"
        }
    }
}
