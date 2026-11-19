package com.acid.wechat.agent

import com.acid.wechat.core.AcidLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class ToolPolicy(
    val isAdmin: Boolean = true,
    val currentTalker: String? = null,
    val allowSendMessage: Boolean = isAdmin && (currentTalker == null || currentTalker == AgentSessionModels.GLOBAL_TALKER),
    val allowedImagePaths: Set<String> = emptySet(),
    val enabledTools: Set<String> = AgentConfigStore.ALL_TOOL_NAMES.toSet(),
    val allowGuestDrawImage: Boolean = false,
    val drawImageUsedInTurn: AtomicBoolean = AtomicBoolean(false),
)

data class ImageAttachment(
    val dataUrl: String,
    val displayPath: String,
    val mimeType: String,
)

/**
 * Declarative contract and OpenAI-compatible schema registry for Agent tools.
 */
interface AgentTool {
    val name: String
    val title: String
    val summary: String
    fun buildOpenAiSchema(): JSONObject
    fun buildOpenAiSchema(policy: ToolPolicy): JSONObject = buildOpenAiSchema()
    fun execute(args: JSONObject): ToolResult
    fun execute(args: JSONObject, policy: ToolPolicy): ToolResult = execute(args)
}

data class ToolResult(
    val output: String,
    val isError: Boolean = output.startsWith("Error:"),
    val imageAttachment: ImageAttachment? = null,
)

object AgentToolRegistry {
    private const val TAG = "AgentToolRegistry"

    private val tools: Map<String, AgentTool> = linkedMapOf(
        WeChatAgentTool.name to WeChatAgentTool,
        SendFileTool.name to SendFileTool,
        WebSearchTool.name to WebSearchTool,
        WebFetchTool.name to WebFetchTool,
        DrawImageTool.name to DrawImageTool,
        ReadImageTool.name to ReadImageTool,
        SandboxFileTools.ReadTool.name to SandboxFileTools.ReadTool,
        SandboxFileTools.WriteTool.name to SandboxFileTools.WriteTool,
        SandboxFileTools.EditTool.name to SandboxFileTools.EditTool,
        SandboxFileTools.BashTool.name to SandboxFileTools.BashTool,
    )

    fun allToolNames(): List<String> = tools.keys.toList()

    fun catalogJson(): JSONArray = JSONArray().apply {
        for (t in tools.values) {
            put(JSONObject().apply {
                put("name", t.name)
                put("title", t.title)
                put("desc", t.summary)
            })
        }
    }

    fun buildToolsSchema(policy: ToolPolicy = ToolPolicy()): JSONArray = JSONArray().apply {
        for (tool in tools.values) {
            if (tool.name !in policy.enabledTools) continue
            if (!policy.isAdmin && tool.name == SandboxFileTools.BashTool.name) continue
            if (!policy.isAdmin && tool.name == DrawImageTool.name && !policy.allowGuestDrawImage) continue
            put(tool.buildOpenAiSchema(policy))
        }
    }

    fun execute(name: String, args: JSONObject, policy: ToolPolicy = ToolPolicy()): ToolResult {
        if (name !in policy.enabledTools) {
            return ToolResult("Error: Tool '$name' is disabled in AI settings.", true)
        }
        if (!policy.isAdmin && name == SandboxFileTools.BashTool.name) {
            return ToolResult("Error: Tool 'bash' is disabled in guest sandbox mode.", true)
        }
        if (!policy.isAdmin && name == DrawImageTool.name && !policy.allowGuestDrawImage) {
            return ToolResult("Error: Tool 'drawimage' is restricted to master only in AI settings.", true)
        }
        val tool = tools[name]
            ?: return ToolResult("Error: Unknown tool '$name'. Available: ${tools.keys.joinToString(", ")}", true)
        return runCatching {
            tool.execute(args, policy)
        }.onFailure {
            AcidLogger.w(TAG, "tool [$name] threw", it)
        }.getOrElse { err ->
            ToolResult("Error: tool '$name' failed: ${err.message ?: err.javaClass.simpleName}", true)
        }
    }

    fun prop(type: String, description: String): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
    }

    fun propEnum(type: String, description: String, values: List<String>): JSONObject =
        prop(type, description).apply {
            put("enum", JSONArray(values))
        }

    fun propStringArray(description: String): JSONObject = prop("array", description).apply {
        put("items", JSONObject().put("type", "string"))
    }

    fun makeTool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray(required))
            })
        })
    }
}
