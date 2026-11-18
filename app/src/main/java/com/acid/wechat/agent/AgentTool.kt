package com.acid.wechat.agent

import com.acid.wechat.core.AcidLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative contract and OpenAI-compatible schema registry for Agent tools.
 */
interface AgentTool {
    val name: String
    fun buildOpenAiSchema(): JSONObject
    fun execute(args: JSONObject): ToolResult
}

data class ToolResult(
    val output: String,
    val isError: Boolean = output.startsWith("Error:"),
)

object AgentToolRegistry {
    private const val TAG = "AgentToolRegistry"

    private val tools: Map<String, AgentTool> = linkedMapOf(
        WeChatAgentTool.name to WeChatAgentTool,
        SandboxFileTools.ReadTool.name to SandboxFileTools.ReadTool,
        SandboxFileTools.WriteTool.name to SandboxFileTools.WriteTool,
        SandboxFileTools.EditTool.name to SandboxFileTools.EditTool,
        SandboxFileTools.BashTool.name to SandboxFileTools.BashTool,
    )

    fun buildToolsSchema(): JSONArray = JSONArray().apply {
        for (tool in tools.values) {
            put(tool.buildOpenAiSchema())
        }
    }

    fun execute(name: String, args: JSONObject): ToolResult {
        val tool = tools[name]
            ?: return ToolResult("Error: Unknown tool '$name'. Available: ${tools.keys.joinToString(", ")}", true)
        return runCatching {
            tool.execute(args)
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
