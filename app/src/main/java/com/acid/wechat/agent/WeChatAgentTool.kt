package com.acid.wechat.agent

import com.acid.wechat.agent.AgentToolRegistry.makeTool
import com.acid.wechat.agent.AgentToolRegistry.prop
import com.acid.wechat.agent.AgentToolRegistry.propEnum
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.wechat.ChatQueryRepository
import com.acid.wechat.wechat.WeChatSender
import org.json.JSONArray
import org.json.JSONObject

/**
 * Core `wechat` tool: gives the Agent unified inspection & control over
 * status, contacts, recent chats, messages, and sending messages.
 */
object WeChatAgentTool : AgentTool {
    override val name = "wechat"
    override val title = "微信综合工具 (wechat)"
    override val summary = "查状态、搜联系人、读消息与全局发消息"

    private val actions = listOf(
        "status",
        "search_contacts",
        "list_chats",
        "read_messages",
        "send_message",
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
            !policy.allowSendMessage -> "Inspect status, search contacts/chats, or read chat messages. (`send_message` is disabled in per-chat sessions.)"
            else -> "Interact with hooked WeChat: inspect status, search contacts/chats, read chat messages, or send real WeChat text messages."
        }
        val props = JSONObject().apply {
            put("action", propEnum("string", "WeChat action to execute.", allowedActions))
            if (policy.isAdmin) {
                put("query", prop("string", "Search query for 'search_contacts'."))
                put("talker", prop("string", "Target contact/group username (`wxid_...`, `...@chatroom`, `filehelper`) or remark/nickname."))
                if (policy.allowSendMessage) {
                    put("text", prop("string", "Message text content for 'send_message'."))
                }
                put("include_chatrooms", prop("boolean", "Include @chatroom groups in 'search_contacts' (default true)."))
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
        val agentCfg = AgentConfigStore.getConfig()
        val arr = JSONArray()
        for (m in msgs) {
            val content = if (agentCfg.wakeWordEnabled && AgentSessionModels.containsWakeWord(m.content, agentCfg.wakeWord)) {
                AgentSessionModels.stripWakeWordForModel(m.content, agentCfg).ifEmpty { "(呼叫了 AI)" }
            } else {
                m.content
            }
            arr.put(JSONObject().apply {
                put("time", m.timeText)
                put("sender", m.senderName)
                put("isSend", m.isSend)
                put("type", m.typeLabel)
                put("content", content)
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

    private fun resolveOrReturnError(input: String): ChatQueryRepository.TalkerResolution.Resolved? =
        ChatQueryRepository.resolveTalker(input) as? ChatQueryRepository.TalkerResolution.Resolved

    private fun ambiguousOrMissingError(input: String): String = when (val res = ChatQueryRepository.resolveTalker(input)) {
        is ChatQueryRepository.TalkerResolution.Ambiguous -> {
            val cand = res.candidates.joinToString("; ") { "${it.displayName} (${it.username})" }
            "Error: Contact '$input' is ambiguous. Specify exact username from: $cand"
        }
        else -> "Error: Contact '$input' not found."
    }
}
