package com.acid.wechat.ui

import android.app.Activity
import android.app.Dialog
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.acid.wechat.agent.AcidAgentLoop
import com.acid.wechat.agent.AgentConfigStore
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.ui.overlay.FloatingSheetDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Centered floating dialog hosting the ephemeral Acid Agent chat UI.
 */
object AgentDialog {
    private const val TAG = "AgentDialog"

    fun show(activity: Activity) {
        runCatching {
            var activeBridge: AgentBridge? = null
            val dialog = FloatingSheetDialog.show(
                activity = activity,
                assetPath = "assets/agent_dialog.html",
                bridgeName = "AcidAgentBridge",
                widthRatio = 0.94f,
                heightRatio = 0.82f,
                bridgeFactory = { dlg, webView ->
                    AgentBridge(activity, dlg, webView).also { activeBridge = it }
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
    ) : AcidAgentLoop.Listener {

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
            val arr = JSONArray()
            for (it in AcidAgentLoop.snapshotUiItems()) arr.put(it.toJson())
            return JSONObject().apply {
                put("running", AcidAgentLoop.isRunning)
                put("step", AcidAgentLoop.currentStep)
                put("totalTokens", AcidAgentLoop.lastTotalTokens)
                put("items", arr)
                put("config", JSONObject().apply {
                    put("baseUrl", cfg.baseUrl)
                    put("apiKey", cfg.apiKey)
                    put("model", cfg.model)
                    put("hasApiKey", cfg.hasApiKey)
                    put("workspace", AgentConfigStore.workspaceDir().absolutePath)
                    put("modelsFile", AgentConfigStore.modelsFile().absolutePath)
                })
            }.toString()
        }

        @JavascriptInterface
        fun sendPrompt(prompt: String) {
            AcidAgentLoop.submitPrompt(prompt)
        }

        @JavascriptInterface
        fun stopRun() {
            AcidAgentLoop.stop()
        }

        @JavascriptInterface
        fun clearChat() {
            AcidAgentLoop.clearSession()
        }

        @JavascriptInterface
        fun saveModelConfig(baseUrl: String, apiKey: String, model: String): String {
            val saved = AgentConfigStore.updateConfig(baseUrl, apiKey, model)
            return JSONObject().apply {
                put("baseUrl", saved.baseUrl)
                put("apiKey", saved.apiKey)
                put("model", saved.model)
                put("hasApiKey", saved.hasApiKey)
            }.toString()
        }

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        override fun onStateChanged(running: Boolean, step: Int, totalTokens: Int) {
            val payload = JSONObject().apply {
                put("running", running)
                put("step", step)
                put("totalTokens", totalTokens)
            }.toString()
            activity.runOnUiThread {
                runCatching { webView.evaluateJavascript("window.onAgentState && window.onAgentState($payload)", null) }
            }
        }

        override fun onItemsChanged(items: List<AcidAgentLoop.UiItem>) {
            val arr = JSONArray()
            for (it in items) arr.put(it.toJson())
            val payload = arr.toString()
            activity.runOnUiThread {
                runCatching { webView.evaluateJavascript("window.onAgentItems && window.onAgentItems($payload)", null) }
            }
        }
    }
}
