package com.acid.wechat.agent

import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import com.acid.wechat.agent.AgentToolRegistry.propEnum
import com.acid.wechat.agent.AgentToolRegistry.propStringArray
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.wechat.ChatQueryRepository
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.SnsFakeCommentStore
import com.acid.wechat.wechat.SnsFakeLikeStore
import com.acid.wechat.wechat.SnsProto
import com.acid.wechat.wechat.SnsRepository
import com.acid.wechat.wechat.WeChatSender
import org.json.JSONArray
import org.json.JSONObject

/**
 * 5th core tool: `wechat` — gives the Agent unified inspection & control over
 * contacts, recent chats, messages, sending messages, Moments actions (by explicit sns_id), and config.
 */
object WeChatAgentTool : AgentTool {
    override val name = "wechat"
    override val title = "微信综合工具 (wechat)"
    override val summary = "查状态、搜联系人、读消息、全局发消息、伪赞评与改配置"

    private val actions = listOf(
        "status",
        "search_contacts",
        "list_chats",
        "read_messages",
        "send_message",
        "moment_fake_like",
        "moment_fake_comment",
        "config_patch",
    )

    override fun buildOpenAiSchema(): JSONObject = buildOpenAiSchema(ToolPolicy())

    override fun buildOpenAiSchema(policy: ToolPolicy): JSONObject {
        val allowedActions = when {
            !policy.isAdmin -> listOf("read_messages")
            !policy.allowSendMessage -> actions.filter { it != "send_message" }
            else -> actions
        }
        val desc = when {
            !policy.isAdmin -> "Read history messages from the CURRENT chat conversation only (`action='read_messages'`)."
            !policy.allowSendMessage -> "Inspect status, search contacts/chats, read chat messages, apply fake likes/comments (with explicit sns_id), or patch config.json. (`send_message` is disabled in per-chat sessions.)"
            else -> "Interact with hooked WeChat: inspect status, search contacts/chats, read chat messages, send real WeChat text messages, apply fake likes/comments (with explicit sns_id), or patch config.json."
        }
        val props = JSONObject().apply {
            put("action", propEnum("string", "WeChat action to execute.", allowedActions))
            if (policy.isAdmin) {
                put("query", prop("string", "Search query for 'search_contacts'."))
                put("talker", prop("string", "Target contact/group username (`wxid_...`, `...@chatroom`, `filehelper`) or remark/nickname."))
                if (policy.allowSendMessage) {
                    put("text", prop("string", "Message text content for 'send_message' or comment text for 'moment_fake_comment'."))
                } else {
                    put("text", prop("string", "Comment text for 'moment_fake_comment'."))
                }
                put("include_chatrooms", prop("boolean", "Include @chatroom groups in 'search_contacts' (default true)."))
                put("sns_id", prop("string", "64-bit Moments snsId string for 'moment_fake_like'/'moment_fake_comment'."))
                put("usernames", propStringArray("Friend usernames for 'moment_fake_like'."))
                put("count", prop("integer", "Random friend count for 'moment_fake_like' when 'usernames' is omitted."))
                put("username", prop("string", "Single friend username or name for 'moment_fake_comment'."))
                put("clear", prop("boolean", "When true, clear fake likes/comments instead of adding."))
                put("patch", prop("object", "Key-value JSON object to merge into config.json for 'config_patch'."))
            }
            put("keyword", prop("string", "Optional keyword filter for 'read_messages'."))
            put("limit", prop("integer", "Max items to return (default 25)."))
            put("offset", prop("integer", "Pagination offset (default 0)."))
        }
        return makeTool(
            name = name,
            description = desc,
            properties = props,
            required = listOf("action"),
        )
    }

    override fun execute(args: JSONObject): ToolResult = execute(args, ToolPolicy())

    override fun execute(args: JSONObject, policy: ToolPolicy): ToolResult {
        val action = args.optString("action", "").trim()
        if (!policy.isAdmin) {
            if (action != "read_messages") {
                return ToolResult("Error: Action '$action' is denied in guest mode (only current-chat 'read_messages' is permitted).", true)
            }
            val currentTalker = policy.currentTalker?.takeIf { it.isNotBlank() }
                ?: return ToolResult("Error: Missing current conversation scope in guest mode.", true)
            val scopedArgs = JSONObject(args.toString()).put("talker", currentTalker)
            return handleReadMessages(scopedArgs)
        }
        if (action == "send_message" && !policy.allowSendMessage) {
            return ToolResult("Error: 'send_message' is disabled inside per-chat Agent sessions; output the reply text directly or use the Global Agent.", true)
        }
        return when (action) {
            "status" -> ToolResult(buildStatus().toString(2))
            "search_contacts" -> handleSearchContacts(args)
            "list_chats" -> handleListChats(args)
            "read_messages" -> handleReadMessages(args)
            "send_message" -> handleSendMessage(args)
            "moment_fake_like" -> handleFakeLike(args)
            "moment_fake_comment" -> handleFakeComment(args)
            "config_patch" -> handleConfigPatch(args)
            else -> ToolResult("Error: Unknown wechat action '$action'. Valid: ${actions.joinToString(", ")}", true)
        }
    }

    private fun buildStatus(): JSONObject = JSONObject().apply {
        put("hostVersion", "${HostContext.hostVersionName} (${HostContext.hostVersionCode})")
        put("moduleVersion", HostContext.MODULE_VERSION_NAME)
        put("hasMainDb", WcdbBridge.mainDb != null)
        put("sender", WeChatSender.statusJson())
        put("dexkitOk", DexKitLocator.snapshotJson().optInt("okCount", 0))
        put("dexkitTotal", DexKitLocator.snapshotJson().optInt("total", 0))
        put("config", ConfigStore.snapshot())
    }

    private fun handleSearchContacts(args: JSONObject): ToolResult {
        val q = args.optString("query", "")
        val includeRooms = args.optBoolean("include_chatrooms", true)
        val limit = args.optInt("limit", 25)
        val list = ChatQueryRepository.searchContacts(q, includeRooms, limit)
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject().apply {
                put("username", c.username)
                put("displayName", c.displayName)
                put("nickname", c.nickname)
                put("remark", c.remark)
                put("alias", c.alias)
            })
        }
        return ToolResult(JSONObject().put("count", list.size).put("contacts", arr).toString(2))
    }

    private fun handleListChats(args: JSONObject): ToolResult {
        val limit = args.optInt("limit", 25)
        val chats = ChatQueryRepository.queryRecentConversations(limit)
        val arr = JSONArray()
        for (c in chats) {
            arr.put(JSONObject().apply {
                put("username", c.username)
                put("displayName", c.displayName)
                put("isChatroom", c.isChatroom)
                put("unreadCount", c.unreadCount)
                put("lastTime", c.lastTimeText)
                put("lastContent", c.lastContent)
            })
        }
        return ToolResult(JSONObject().put("count", chats.size).put("chats", arr).toString(2))
    }

    private fun handleReadMessages(args: JSONObject): ToolResult {
        val talkerInput = args.optString("talker", "")
        if (talkerInput.isBlank()) return ToolResult("Error: Missing 'talker' for read_messages.", true)
        val resolved = resolveOrReturnError(talkerInput) ?: return ToolResult(
            ambiguousOrMissingError(talkerInput),
            true,
        )
        val msgs = ChatQueryRepository.queryMessages(
            talker = resolved.username,
            limit = args.optInt("limit", 30),
            offset = args.optInt("offset", 0),
            keyword = args.optString("keyword", "").ifBlank { null },
        )
        val arr = JSONArray()
        for (m in msgs) {
            arr.put(JSONObject().apply {
                put("time", m.timeText)
                put("sender", m.senderName)
                put("isSend", m.isSend)
                put("type", m.typeLabel)
                put("content", m.content)
            })
        }
        return ToolResult(
            JSONObject().apply {
                put("username", resolved.username)
                put("displayName", resolved.displayName)
                put("count", msgs.size)
                put("messages", arr)
            }.toString(2),
        )
    }

    private fun handleSendMessage(args: JSONObject): ToolResult {
        val talkerInput = args.optString("talker", "")
        val text = args.optString("text", "")
        if (talkerInput.isBlank() || text.isEmpty()) {
            return ToolResult("Error: 'talker' and non-empty 'text' are required for send_message.", true)
        }
        val resolved = resolveOrReturnError(talkerInput) ?: return ToolResult(
            ambiguousOrMissingError(talkerInput),
            true,
        )
        val res = WeChatSender.sendText(resolved.username, text)
        val payload = JSONObject().apply {
            put("ok", res.ok)
            put("username", resolved.username)
            put("displayName", resolved.displayName)
            put("text", text)
            put("detail", res.detail)
        }
        return if (res.ok) ToolResult(payload.toString(2)) else ToolResult("Error: ${res.detail}", true)
    }

    private fun handleFakeLike(args: JSONObject): ToolResult {
        val snsId = parseSnsId(args) ?: return ToolResult("Error: Invalid or missing 'sns_id'.", true)
        if (args.optBoolean("clear", false)) {
            val prev = SnsFakeLikeStore.getUsernames(snsId)
            SnsFakeLikeStore.set(snsId, emptyList())
            val (ok, total) = SnsRepository.applyLikesToPost(snsId, emptyList(), prev, null)
            return if (ok) {
                ToolResult(JSONObject().put("ok", true).put("snsId", snsId.toString()).put("totalLikes", total).toString(2))
            } else {
                ToolResult("Error: Failed clearing fake likes for sns_id=$snsId.", true)
            }
        }

        val chosen = mutableListOf<String>()
        val rawArr = args.optJSONArray("usernames")
        if (rawArr != null && rawArr.length() > 0) {
            for (i in 0 until rawArr.length()) {
                val item = rawArr.optString(i).trim()
                resolveOrReturnError(item)?.let { chosen += it.username }
            }
        } else {
            val cnt = args.optInt("count", 5).coerceIn(1, 50)
            chosen += ChatRepository.queryFriends(limit = 400).shuffled().take(cnt).map { it.username }
        }
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val entries = chosen.distinct().mapIndexed { idx, u ->
            SnsProto.LikeEntry(u, ChatRepository.resolveSenderDisplayName(u, null, null), nowSec - idx)
        }
        val removed = SnsFakeLikeStore.getUsernames(snsId).filterTo(HashSet()) { u -> entries.none { it.username == u } }
        SnsFakeLikeStore.set(snsId, entries)
        val (ok, total) = SnsRepository.applyLikesToPost(snsId, entries, removed, null)
        return if (ok) {
            ToolResult(
                JSONObject().put("ok", true).put("snsId", snsId.toString())
                    .put("fakeLikesSet", entries.size).put("totalLikes", total).toString(2),
            )
        } else {
            ToolResult("Error: Failed applying fake likes (ensure Moments was visited once).", true)
        }
    }

    private fun handleFakeComment(args: JSONObject): ToolResult {
        val snsId = parseSnsId(args) ?: return ToolResult("Error: Invalid or missing 'sns_id'.", true)
        if (args.optBoolean("clear", false)) {
            val removed = SnsFakeCommentStore.clear(snsId).mapTo(HashSet()) { it.commentId }
            val (ok, total) = SnsRepository.applyCommentsToPost(snsId, emptyList(), removed, null)
            return if (ok) {
                ToolResult(JSONObject().put("ok", true).put("snsId", snsId.toString()).put("totalComments", total).toString(2))
            } else {
                ToolResult("Error: Failed clearing fake comments.", true)
            }
        }
        val userInput = args.optString("username", "").ifBlank { args.optString("talker", "") }
        val content = args.optString("text", "").ifBlank { args.optString("content", "") }
        if (userInput.isBlank() || content.isBlank()) {
            return ToolResult("Error: 'username' and 'text' are required for moment_fake_comment.", true)
        }
        val resolved = resolveOrReturnError(userInput) ?: return ToolResult(ambiguousOrMissingError(userInput), true)
        val entry = SnsProto.CommentEntry(
            commentId = SnsFakeCommentStore.nextCommentId(),
            username = resolved.username,
            nickname = resolved.displayName,
            content = content,
            createTime = (System.currentTimeMillis() / 1000L).toInt(),
        )
        SnsFakeCommentStore.add(snsId, entry)
        val (ok, total) = SnsRepository.applyCommentsToPost(snsId, listOf(entry), emptySet(), null)
        return if (ok) {
            ToolResult(
                JSONObject().put("ok", true).put("snsId", snsId.toString())
                    .put("commentBy", resolved.displayName).put("totalComments", total).toString(2),
            )
        } else {
            ToolResult("Error: Failed adding fake comment.", true)
        }
    }

    private fun handleConfigPatch(args: JSONObject): ToolResult {
        val patch = args.optJSONObject("patch") ?: return ToolResult("Error: Missing 'patch' JSON object.", true)
        return runCatching {
            val changed = ConfigStore.applyPatch(patch)
            ToolResult(
                JSONObject().put("ok", true).put("changedKeys", JSONArray(changed.toList()))
                    .put("config", ConfigStore.snapshot()).toString(2),
            )
        }.getOrElse { err ->
            ToolResult("Error: ${err.message}", true)
        }
    }

    private fun resolveOrReturnError(input: String): ChatQueryRepository.TalkerResolution.Resolved? =
        ChatQueryRepository.resolveTalker(input) as? ChatQueryRepository.TalkerResolution.Resolved

    private fun ambiguousOrMissingError(input: String): String = when (val res = ChatQueryRepository.resolveTalker(input)) {
        is ChatQueryRepository.TalkerResolution.Ambiguous -> {
            val cand = res.candidates.joinToString("; ") { "${it.displayName} (${it.username})" }
            "Error: Contact '$input' is ambiguous. Specify exact username from: $cand"
        }
        else -> "Error: Contact '$input' not found."
    }

    private fun parseSnsId(args: JSONObject): Long? {
        val asStr = args.optString("sns_id", "").trim()
        if (asStr.isNotEmpty()) return asStr.toLongOrNull()?.takeIf { it != 0L }
        val asLong = args.optLong("sns_id", 0L)
        return asLong.takeIf { it != 0L }
    }
}
