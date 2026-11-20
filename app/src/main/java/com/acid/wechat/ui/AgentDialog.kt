package com.acid.wechat.ui

import android.app.Activity
import android.app.Dialog
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.acid.wechat.agent.AcidAgentLoop
import com.acid.wechat.agent.AgentConfigStore
import com.acid.wechat.agent.AgentSessionModels
import com.acid.wechat.agent.AgentSessionStore
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.ui.overlay.FloatingSheetDialog
import com.acid.wechat.wechat.ChatRepository
import org.json.JSONObject

/**
 * Centered floating dialog hosting a per-chat Acid Agent timeline bound to a specific `talker`.
 */
object AgentDialog {
    private const val TAG = "AgentDialog"

    fun show(
        activity: Activity,
        talker: String = AgentSessionModels.GLOBAL_TALKER,
        title: String? = null,
    ) {
        runCatching {
            val resolvedTitle = title?.takeIf { it.isNotBlank() }
                ?: if (talker == AgentSessionModels.GLOBAL_TALKER) "全局助手" else ChatRepository.resolveChatTitle(talker)
            var activeBridge: AgentBridge? = null
            val dialog = FloatingSheetDialog.show(
                activity = activity,
                assetPath = "assets/agent_dialog.html",
                bridgeName = "AcidAgentBridge",
                widthRatio = 0.94f,
                heightRatio = 0.84f,
                bridgeFactory = { dlg, webView ->
                    AgentBridge(activity, dlg, webView, talker, resolvedTitle).also { activeBridge = it }
                },
            )
            dialog?.setOnDismissListener {
                activeBridge?.detach()
            }
        }.onFailure {
            AcidLogger.e(TAG, "show AgentDialog failed", it)
        }
    }

    private class AgentBridge(
        private val activity: Activity,
        private val dialog: Dialog,
        private val webView: WebView,
        private val talker: String,
        private val chatTitle: String,
    ) : AcidAgentLoop.Listener {

        @Volatile
        private var visibleStartIndex: Int? = null

        init {
            AcidAgentLoop.addListener(this)
        }

        fun detach() {
            AcidAgentLoop.removeListener(this)
            runCatching {
                webView.removeJavascriptInterface("AcidAgentBridge")
                webView.stopLoading()
                webView.destroy()
            }
        }

        @JavascriptInterface
        fun getInitialState(): String {
            AgentConfigStore.ensureInitialized()
            val cfg = AgentConfigStore.getConfig()
            val page = AgentSessionStore.snapshotWindow(talker, startIndexHint = null, titleHint = chatTitle)
            visibleStartIndex = page.startIndex
            val (running, step, totalTokens) = AcidAgentLoop.stateSnapshot(talker)
            return JSONObject().apply {
                put("talker", talker)
                put("title", page.title)
                put("isGlobal", talker == AgentSessionModels.GLOBAL_TALKER)
                put("isGroup", talker.endsWith("@chatroom"))
                put("takeoverEnabled", AgentConfigStore.isTakeoverEnabled(talker))
                put("running", running)
                put("step", step)
                put("totalTokens", totalTokens)
                put("compactCount", page.compactCount)
                put("startIndex", page.startIndex)
                put("totalCount", page.totalCount)
                put("hasMore", page.hasMore)
                put("items", page.itemsJsonArray())
                put("config", AgentConfigStore.toUiJson(cfg))
            }.toString()
        }

        @JavascriptInterface
        fun loadOlderItems(beforeIndex: Int): String {
            val page = AgentSessionStore.loadOlderPage(talker, beforeIndex, AgentSessionModels.PAGE_SIZE)
            visibleStartIndex = page.startIndex
            return JSONObject().apply {
                put("items", page.itemsJsonArray())
                put("startIndex", page.startIndex)
                put("totalCount", page.totalCount)
                put("hasMore", page.hasMore)
                put("compactCount", page.compactCount)
            }.toString()
        }

        @JavascriptInterface
        fun sendPrompt(prompt: String) {
            AcidAgentLoop.submitPanelPrompt(talker, chatTitle, prompt)
        }

        @JavascriptInterface
        fun stopRun() {
            AcidAgentLoop.stop(talker)
        }

        @JavascriptInterface
        fun clearChat() {
            visibleStartIndex = 0
            AcidAgentLoop.clearSession(talker)
        }

        @JavascriptInterface
        fun compactSession() {
            Thread({
                val outcome = AcidAgentLoop.triggerManualCompact(talker)
                val payload = JSONObject().apply {
                    put("ok", outcome.ok)
                    put("message", if (outcome.ok) "会话已压缩并生成金色长期记忆快照" else outcome.error)
                }.toString()
                activity.runOnUiThread {
                    runCatching { webView.evaluateJavascript("window.onCompactResult && window.onCompactResult($payload)", null) }
                }
            }, "AcidManualCompact").apply { isDaemon = true; start() }
        }

        @JavascriptInterface
        fun sendToWeChat(itemId: String, text: String): String {
            if (talker == AgentSessionModels.GLOBAL_TALKER) {
                return JSONObject().put("ok", false).put("detail", "全局会话未绑定聊天对象").toString()
            }
            val res = AcidAgentLoop.sendTextToChat(talker, text, itemId)
            return JSONObject().put("ok", res.ok).put("detail", res.detail).toString()
        }

        @JavascriptInterface
        fun setTakeover(enabled: Boolean): String {
            if (talker == AgentSessionModels.GLOBAL_TALKER) {
                return JSONObject().put("ok", false).put("takeoverEnabled", false).toString()
            }
            val saved = AgentConfigStore.setTakeoverEnabled(talker, enabled)
            ChatHeaderAiHook.refreshTakeoverBadge(talker)
            AcidLogger.i(TAG, "setTakeover($talker) -> $saved")
            return JSONObject().put("ok", true).put("takeoverEnabled", saved).toString()
        }

        @JavascriptInterface
        fun saveModelConfig(baseUrl: String, apiKey: String, model: String): String {
            val saved = AgentConfigStore.updateConfig(baseUrl, apiKey, model)
            return AgentConfigStore.toUiJson(saved).toString()
        }

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        override fun onStateChanged(talker: String, running: Boolean, step: Int, totalTokens: Int, compactCount: Int) {
            if (talker != this.talker) return
            val payload = JSONObject().apply {
                put("running", running)
                put("step", step)
                put("totalTokens", totalTokens)
                put("compactCount", compactCount)
            }.toString()
            activity.runOnUiThread {
                runCatching { webView.evaluateJavascript("window.onAgentState && window.onAgentState($payload)", null) }
            }
        }

        override fun onTimelineChanged(talker: String) {
            if (talker != this.talker) return
            val page = AgentSessionStore.snapshotWindow(talker, startIndexHint = visibleStartIndex, titleHint = chatTitle)
            visibleStartIndex = page.startIndex
            val payload = JSONObject().apply {
                put("items", page.itemsJsonArray())
                put("startIndex", page.startIndex)
                put("totalCount", page.totalCount)
                put("hasMore", page.hasMore)
                put("compactCount", page.compactCount)
            }.toString()
            activity.runOnUiThread {
                runCatching { webView.evaluateJavascript("window.onAgentWindow && window.onAgentWindow($payload)", null) }
            }
        }
    }
}
