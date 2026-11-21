package com.acid.wechat.wechat

/**
 * Formats non-plain-text WeChat XML/structured payloads (AppMsg cards, quotes, VoIP calls,
 * locations, contact cards, friend verifications, and system/pat/revoke notices) into clean text.
 */
internal object WeChatSpecialMsgFormatter {
    private val tagOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private fun tagRegex(tag: String): Regex =
        Regex("<$tag>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</$tag>", tagOptions)

    private val titleTagRegex = tagRegex("title")
    private val desTagRegex = tagRegex("des")
    private val descTagRegex = tagRegex("desc")
    private val referMsgBlockRegex = tagRegex("refermsg")
    private val typeTagRegex = tagRegex("type")
    private val svrIdTagRegex = tagRegex("svrid")
    private val displayNameTagRegex = tagRegex("displayname")
    private val chatUsrTagRegex = tagRegex("chatusr")
    private val fromUsrTagRegex = tagRegex("fromusr")
    private val contentTagRegex = tagRegex("content")
    private val urlTagRegex = tagRegex("url")
    private val sourceDisplayNameRegex = tagRegex("sourcedisplayname")
    private val feeDescRegex = tagRegex("feedesc")
    private val payMemoRegex = tagRegex("pay_memo")
    private val senderTitleRegex = tagRegex("sendertitle")
    private val msgTagRegex = tagRegex("msg")
    private val roomTypeRegex = tagRegex("room_type")
    private val replaceMsgRegex = tagRegex("replacemsg")
    private val templateTagRegex = tagRegex("template")
    private val fromUsernameRegex = tagRegex("fromusername")
    private val pattedUsernameRegex = tagRegex("pattedusername")
    private val patSuffixRegex = tagRegex("patsuffix")
    private val plainTagRegex = tagRegex("plain")
    private val sysMsgTypeAttrRegex = Regex("<sysmsg\\b[^>]*\\btype\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val patPlaceholderRegex = Regex("\\$\\{([^}]+)\\}")
    private val xmlTagStripRegex = Regex("<[^>]+>")

    fun extractTitle(xml: String): String? =
        extractGroup(titleTagRegex, xml)?.let(::unescapeXml)?.trim()?.takeIf { it.isNotEmpty() }

    fun formatVoipBody(stripped: String): String {
        val trimmed = stripped.trim()
        if (trimmed.equals("voip_content_voice", ignoreCase = true)) return "[语音通话]"
        if (trimmed.equals("voip_content_video", ignoreCase = true)) return "[视频通话]"
        val kind = when (extractGroup(roomTypeRegex, trimmed)) {
            "1" -> "语音通话"
            "0" -> "视频通话"
            else -> "音视频通话"
        }
        val detail = extractText(msgTagRegex, trimmed)
        return if (detail.isNotEmpty()) "[$kind: $detail]" else "[$kind]"
    }

    fun formatLocationBody(stripped: String): String {
        val poi = extractAttr(stripped, "poiname").orEmpty()
        val label = extractAttr(stripped, "label").orEmpty()
        return when {
            poi.isNotEmpty() && label.isNotEmpty() && poi != label -> "[位置: $poi ($label)]"
            poi.isNotEmpty() -> "[位置: $poi]"
            label.isNotEmpty() -> "[位置: $label]"
            else -> "[位置]"
        }
    }

    fun formatCardBody(stripped: String): String {
        val nick = extractAttr(stripped, "nickname").orEmpty()
        val alias = extractAttr(stripped, "alias").orEmpty()
        val username = extractAttr(stripped, "username").orEmpty()
        val name = nick.ifEmpty { alias }.ifEmpty { username }
        val certFlag = extractAttr(stripped, "certflag")?.toIntOrNull() ?: 0
        val prefix = if (certFlag != 0) "公众号名片" else "名片"
        return if (name.isNotEmpty()) "[$prefix: $name]" else "[$prefix]"
    }

    fun formatFriendVerifyBody(stripped: String): String {
        val nick = extractAttr(stripped, "fromnickname").orEmpty()
        val content = extractAttr(stripped, "content").orEmpty()
        return when {
            nick.isNotEmpty() && content.isNotEmpty() -> "[好友申请: $nick（$content）]"
            nick.isNotEmpty() -> "[好友申请: $nick]"
            content.isNotEmpty() -> "[好友申请: $content]"
            else -> "[好友申请]"
        }
    }

    fun formatSystemBody(stripped: String): String {
        val trimmed = stripped.trim()
        if (!trimmed.contains('<')) {
            return if (trimmed.isNotEmpty()) "[系统提示] $trimmed" else "[系统提示]"
        }
        val sysType = sysMsgTypeAttrRegex.find(trimmed)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
        if (sysType == "revokemsg") {
            val replace = extractGroup(replaceMsgRegex, trimmed)?.let(::cleanXmlText).orEmpty()
            return if (replace.isNotEmpty()) "[系统提示] $replace" else "[系统提示] 撤回了一条消息"
        }
        if (sysType == "pat" || sysType == "patmsg") {
            return formatPatBody(trimmed)
        }
        val plains = plainTagRegex.findAll(trimmed)
            .map { cleanXmlText(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .toList()
        val fallbackText = if (plains.isNotEmpty()) {
            plains.joinToString("")
        } else {
            extractGroup(replaceMsgRegex, trimmed)?.let(::cleanXmlText)
                ?: extractGroup(titleTagRegex, trimmed)?.let(::cleanXmlText)
                ?: cleanXmlText(trimmed)
        }
        return if (fallbackText.isNotEmpty()) "[系统提示] ${fallbackText.take(180)}" else "[系统提示]"
    }

    private fun formatPatBody(xml: String): String {
        val template = extractText(templateTagRegex, xml)
        if (template.isNotEmpty()) {
            val resolved = patPlaceholderRegex.replace(template) { m ->
                resolveContactLabel(m.groupValues[1].trim())
            }.trim()
            if (resolved.isNotEmpty()) return "[拍一拍] $resolved"
        }
        val from = extractGroup(fromUsernameRegex, xml).orEmpty()
        val patted = extractGroup(pattedUsernameRegex, xml).orEmpty()
        val suffix = extractText(patSuffixRegex, xml)
        if (from.isNotEmpty() && patted.isNotEmpty()) {
            val fromName = resolveContactLabel(from)
            val pattedName = resolveContactLabel(patted)
            val tail = if (suffix.isNotEmpty()) " $suffix" else ""
            return "[拍一拍] $fromName 拍了拍 $pattedName$tail"
        }
        return "[拍一拍]"
    }

    fun formatAppMsgBody(talker: String, stripped: String, allowReferLookup: Boolean): String {
        val beforeRefer = stripped.substringBefore("<refermsg", stripped)
        val title = extractTitle(beforeRefer).orEmpty()
        val referXml = extractGroup(referMsgBlockRegex, stripped)
        if (referXml != null) {
            return formatReferBody(talker, title, referXml, allowReferLookup)
        }

        val appType = extractGroup(typeTagRegex, beforeRefer)?.toIntOrNull() ?: 0
        val des = extractText(desTagRegex, beforeRefer)
        val url = extractText(urlTagRegex, beforeRefer)
        return when (appType) {
            2 -> "[图片]"
            8 -> "[动画表情]"
            3, 76, 92 -> formatTaggedItem("音乐", listOf(title, des).filter { it.isNotEmpty() }.distinct().joinToString(" - "))
            6, 74, 130, 131 -> formatTaggedItem("文件", title)
            17 -> formatTaggedItem("位置共享", title)
            19 -> formatTaggedItem("聊天记录", title)
            24 -> formatTaggedItem("收藏笔记", title)
            33, 36 -> {
                val src = extractText(sourceDisplayNameRegex, beforeRefer)
                val label = if (src.isNotEmpty()) "小程序($src)" else "小程序"
                formatTaggedItem(label, title)
            }
            50 -> formatTaggedItem("视频号名片", title)
            51, 106, 129 -> formatTaggedItem("视频号", extractText(descTagRegex, beforeRefer).ifEmpty { title }.take(120))
            53 -> if (title.isNotEmpty()) "[接龙] $title" else "[接龙]"
            62 -> if (title.isNotEmpty()) "[拍一拍] $title" else "[拍一拍]"
            63, 65, 88 -> formatTaggedItem("视频号直播", title)
            115, 124 -> formatTaggedItem("礼物", title)
            2000, 2011 -> {
                val fee = extractText(feeDescRegex, beforeRefer)
                val memo = extractText(payMemoRegex, beforeRefer)
                formatTaggedItem("转账", listOf(fee, memo).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            2001 -> formatTaggedItem("红包", extractText(senderTitleRegex, beforeRefer).ifEmpty { title })
            2002, 2003 -> formatTaggedItem("红包封面", title)
            else -> formatGenericAppMsg(title, des, url)
        }
    }

    private fun formatGenericAppMsg(title: String, des: String, url: String): String {
        val cleanDes = des.replace('\n', ' ').trim().take(120)
        return when {
            title.isNotEmpty() && (url.startsWith("http://", true) || url.startsWith("https://", true)) ->
                "[链接卡片: $title | $url]"
            title.isNotEmpty() && cleanDes.isNotEmpty() && !title.contains(cleanDes) ->
                "[卡片: $title | $cleanDes]"
            title.isNotEmpty() -> title
            cleanDes.isNotEmpty() -> "[卡片: $cleanDes]"
            else -> "[卡片/链接消息]"
        }
    }

    private fun formatTaggedItem(label: String, detail: String): String =
        if (detail.isNotEmpty()) "[$label: $detail]" else "[$label]"

    private fun formatReferBody(talker: String, title: String, referXml: String, allowReferLookup: Boolean): String {
        val referType = extractGroup(typeTagRegex, referXml)?.toIntOrNull() ?: 1
        val referSvrId = extractGroup(svrIdTagRegex, referXml)?.toLongOrNull() ?: 0L
        val rawDisplayName = extractGroup(displayNameTagRegex, referXml)?.let(::unescapeXml).orEmpty()
        val chatusr = extractGroup(chatUsrTagRegex, referXml).orEmpty()
        val fromusr = extractGroup(fromUsrTagRegex, referXml).orEmpty()
        val referContent = extractGroup(contentTagRegex, referXml)?.let(::unescapeXml).orEmpty()

        val referSender = resolveReferSender(chatusr.ifEmpty { fromusr }, rawDisplayName)
        val fromDb = if (allowReferLookup && referSvrId > 0L) {
            ChatRepository.queryMessageBySvrId(referSvrId)?.let { refRow ->
                WeChatMessageFormatter.formatMessageBody(
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

        val referBody = fromDb ?: when (WeChatMessageFormatter.normalizeType(referType)) {
            1 -> WeChatMessageFormatter.stripGroupPrefix(talker, false, referContent).trim()
            3 -> ChatImageRepository.resolveMessageImagePath(0L, referSvrId, "")
                ?.let { "[图片: $it]" }
                ?: "[图片]"
            34 -> "[语音]"
            42, 66, 67 -> formatCardBody(referContent)
            43, 62 -> "[视频]"
            47 -> "[动画表情]"
            48 -> formatLocationBody(referContent)
            50, 52, 53 -> formatVoipBody(referContent)
            else -> extractTitle(referContent)
                ?: referContent.replace('\n', ' ').take(80).ifEmpty { "[消息]" }
        }
        val cleanRefer = referBody.replace('\n', ' ').trim().ifEmpty { "[消息]" }
        return if (title.isNotEmpty()) "[引用 $referSender: $cleanRefer] $title" else "[引用 $referSender: $cleanRefer]"
    }

    private fun resolveContactLabel(wxid: String): String {
        if (wxid.isEmpty()) return "对方"
        if (wxid == ChatRepository.querySelfWxid()) return "master"
        val names = ChatRepository.queryContactNames(wxid)
        return names?.first?.takeIf { it.isNotBlank() }
            ?: names?.second?.takeIf { it.isNotBlank() }
            ?: names?.third?.takeIf { it.isNotBlank() }
            ?: wxid
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

    private fun extractText(regex: Regex, input: String): String =
        extractGroup(regex, input)?.let(::unescapeXml)?.trim().orEmpty()

    private fun extractAttr(xml: String, attrName: String): String? {
        val rx = Regex("\\b${Regex.escape(attrName)}\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        val raw = rx.find(xml)?.groupValues?.getOrNull(1) ?: return null
        return unescapeXml(raw).trim().takeIf { it.isNotEmpty() }
    }

    fun cleanXmlText(raw: String): String =
        unescapeXml(xmlTagStripRegex.replace(raw, " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    fun unescapeXml(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#x20;", " ")
        .replace("&#x0A;", "\n")
        .replace("&#x0a;", "\n")
        .replace("&amp;", "&")
}
