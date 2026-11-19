package com.acid.wechat.wechat

/**
 * Formats raw WeChat DB message payloads (plain text, media placeholders, `<appmsg>` cards,
 * and `<refermsg>` quote replies) into clean human- and Agent-readable text.
 */
object WeChatMessageFormatter {
    private val tagOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private fun tagRegex(tag: String): Regex =
        Regex("<$tag>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</$tag>", tagOptions)

    private val titleTagRegex = tagRegex("title")
    private val referMsgBlockRegex = tagRegex("refermsg")
    private val typeTagRegex = tagRegex("type")
    private val svrIdTagRegex = tagRegex("svrid")
    private val displayNameTagRegex = tagRegex("displayname")
    private val chatUsrTagRegex = tagRegex("chatusr")
    private val fromUsrTagRegex = tagRegex("fromusr")
    private val contentTagRegex = tagRegex("content")

    fun isTextOrAppMsgType(type: Int): Boolean =
        type == 1 || type == 49 || type == 822083633 || (type > 0 && (type and 0xFF) == 49)

    fun stripGroupPrefix(talker: String, isSend: Boolean, raw: String): String {
        if (!talker.endsWith("@chatroom") || isSend) return raw
        val idx = raw.indexOf(":\n")
        return if (idx in 1..64) raw.substring(idx + 2) else raw
    }

    fun extractAppMsgTitle(stripped: String): String? =
        extractGroup(titleTagRegex, stripped)?.takeIf { it.isNotEmpty() }

    /**
     * Extracts the newly typed reply text when checking `@ai` triggers, ignoring `@ai`
     * that might appear inside the quoted `<refermsg>` body.
     */
    fun extractTriggerText(rawContent: String, formattedBody: String): String {
        if (!formattedBody.startsWith("[引用 ")) return formattedBody
        val beforeRefer = rawContent.substringBefore("<refermsg", rawContent)
        return extractAppMsgTitle(beforeRefer)?.let(::unescapeXml)?.trim()?.takeIf { it.isNotEmpty() }
            ?: formattedBody.substringAfter("] ", "")
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
        if (type == 49 || type == 822083633 || (type > 0 && (type and 0xFF) == 49) || stripped.contains("<appmsg")) {
            return formatAppMsgBody(talker, stripped, allowReferLookup)
        }
        return when (type) {
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
            43 -> "[视频]"
            47 -> "[动画表情]"
            10000 -> "[系统提示] " + stripped.trim()
            else -> "[${msgTypeLabel(type)}] " + stripped.replace('\n', ' ').take(160)
        }
    }

    fun msgTypeLabel(type: Int): String = when {
        type == 1 -> "text"
        type == 3 -> "image"
        type == 34 -> "voice"
        type == 42 -> "card"
        type == 43 -> "video"
        type == 47 -> "emoji"
        type == 48 -> "location"
        type == 822083633 -> "quote"
        type == 49 || (type > 0 && (type and 0xFF) == 49) -> "appmsg"
        type == 10000 -> "system"
        else -> "type_$type"
    }

    private fun formatAppMsgBody(talker: String, stripped: String, allowReferLookup: Boolean): String {
        val beforeRefer = stripped.substringBefore("<refermsg", stripped)
        val title = extractAppMsgTitle(beforeRefer)?.let(::unescapeXml)?.trim().orEmpty()
        val referXml = extractGroup(referMsgBlockRegex, stripped)
        if (referXml != null) {
            val referType = extractGroup(typeTagRegex, referXml)?.toIntOrNull() ?: 1
            val referSvrId = extractGroup(svrIdTagRegex, referXml)?.toLongOrNull() ?: 0L
            val rawDisplayName = extractGroup(displayNameTagRegex, referXml)?.let(::unescapeXml).orEmpty()
            val chatusr = extractGroup(chatUsrTagRegex, referXml).orEmpty()
            val fromusr = extractGroup(fromUsrTagRegex, referXml).orEmpty()
            val referContent = extractGroup(contentTagRegex, referXml)?.let(::unescapeXml).orEmpty()

            val referSender = resolveReferSender(chatusr.ifEmpty { fromusr }, rawDisplayName)
            val fromDb = if (allowReferLookup && referSvrId > 0L) {
                ChatRepository.queryMessageBySvrId(referSvrId)?.let { refRow ->
                    formatMessageBody(
                        talker = refRow.getAsString("talker") ?: talker,
                        isSend = (refRow.getAsInteger("isSend") ?: 0) != 0,
                        type = refRow.getAsInteger("type") ?: referType,
                        raw = refRow.getAsString("content").orEmpty(),
                        msgId = refRow.getAsLong("msgId") ?: 0L,
                        imgPath = refRow.getAsString("imgPath").orEmpty(),
                        msgSvrId = referSvrId,
                        allowReferLookup = false,
                    ).trim()
                }?.takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val referBody = fromDb ?: when (referType) {
                1 -> stripGroupPrefix(talker, false, referContent).trim()
                3 -> ChatImageRepository.resolveMessageImagePath(0L, referSvrId, "")
                    ?.let { "[图片: $it]" }
                    ?: "[图片]"
                34 -> "[语音]"
                43 -> "[视频]"
                47 -> "[动画表情]"
                else -> extractAppMsgTitle(referContent)?.let(::unescapeXml)?.trim()
                    ?: referContent.replace('\n', ' ').take(80).ifEmpty { "[消息]" }
            }
            val cleanRefer = referBody.replace('\n', ' ').trim().ifEmpty { "[消息]" }
            return if (title.isNotEmpty()) "[引用 $referSender: $cleanRefer] $title" else "[引用 $referSender: $cleanRefer]"
        }

        val appType = extractGroup(typeTagRegex, beforeRefer)?.toIntOrNull() ?: 0
        return when {
            appType == 6 && title.isNotEmpty() -> "[文件: $title]"
            title.isNotEmpty() -> title
            else -> "[卡片/链接消息]"
        }
    }

    private fun resolveReferSender(targetWxid: String, rawDisplayName: String): String {
        if (targetWxid.isNotEmpty() && targetWxid == ChatRepository.querySelfWxid()) return "master"
        val names = targetWxid.takeIf { it.isNotEmpty() && !it.endsWith("@chatroom") }
            ?.let { ChatRepository.queryContactNames(it) }
        return names?.first?.takeIf { it.isNotBlank() }
            ?: rawDisplayName.ifEmpty { null }
            ?: names?.second?.takeIf { it.isNotBlank() }
            ?: names?.third?.takeIf { it.isNotBlank() }
            ?: "对方"
    }

    private fun extractGroup(regex: Regex, input: String): String? =
        regex.find(input)?.groupValues?.getOrNull(1)?.trim()

    private fun unescapeXml(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#x20;", " ")
        .replace("&#x0A;", "\n")
        .replace("&#x0a;", "\n")
        .replace("&amp;", "&")
}
