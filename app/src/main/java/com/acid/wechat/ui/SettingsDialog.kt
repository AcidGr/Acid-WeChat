package com.acid.wechat.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.acid.wechat.agent.AcidLlmClient
import com.acid.wechat.agent.AgentConfigStore
import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.debug.DebugServer
import com.acid.wechat.wechat.WeChatAbTestStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clean, lightweight white WebView settings panel opened from WeChat Settings top-right "Acid".
 */
object SettingsDialog {
    private const val TAG = "SettingsDialog"

    fun show(activity: Activity) {
        runCatching {
            WebViewHost.showHtmlDialog(
                activity = activity,
                assetPath = "assets/settings.html",
                bridgeName = "AcidBridge",
                styleResId = android.R.style.Theme_DeviceDefault_Light_NoActionBar,
                windowSetup = { dialog ->
                    dialog.window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.WHITE))
                        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                        statusBarColor = Color.WHITE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            @Suppress("DEPRECATION")
                            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        }
                    }
                },
                bridgeFactory = { dialog, webView -> AcidBridge(activity, dialog, webView) },
            )
        }.onFailure {
            AcidLogger.e(TAG, "show SettingsDialog failed", it)
        }
    }

    private class AcidBridge(
        private val activity: Activity,
        private val dialog: Dialog,
        private val webView: WebView,
    ) {
        @JavascriptInterface
        fun getState(): String {
            ConfigStore.reloadIfModified(force = true)
            val dk = DexKitLocator.snapshotJson()
            val cats = JSONArray()
            for (cat in FeatureCategory.entries) {
                cats.put(JSONObject().apply {
                    put("id", cat.id)
                    put("label", cat.label)
                })
            }
            cats.put(JSONObject().apply {
                put("id", "abtest")
                put("label", "灰度")
            })

            val items = JSONArray()
            for (st in FeatureRegistry.allStates()) {
                val f = st.feature
                items.put(JSONObject().apply {
                    put("type", FeatureOption.TYPE_SWITCH)
                    put("category", f.category.id)
                    put("group", f.group)
                    put("title", f.name)
                    put("configKey", f.configKey)
                    put("enabled", ConfigStore.getBoolean(f.configKey, f.defaultEnabled))
                    put("dependsOn", f.dependsOn ?: JSONObject.NULL)
                })
                for (opt in f.extraOptions()) {
                    appendOptionItem(items, f.category.id, opt)
                }
            }

            for (spec in ConfigSchema.systemUiSpecs()) {
                appendOptionItem(items, spec.category.id, FeatureOption.from(spec))
            }

            AgentConfigStore.ensureInitialized()
            return JSONObject().apply {
                put("hostVersion", HostContext.hostVersionName)
                put("dexkitOk", dk.optInt("okCount", 0))
                put("dexkitTotal", dk.optInt("total", 0))
                put("categories", cats)
                put("items", items)
                put("agent", AgentConfigStore.toUiJson(AgentConfigStore.getConfig(forceReload = true)))
            }.toString()
        }

        @JavascriptInterface
        fun updateAgentConfig(patchJson: String): String {
            return runCatching {
                val patch = JSONObject(patchJson)
                val updated = AgentConfigStore.updateFromPatch(patch)
                AcidLogger.i(
                    TAG,
                    "UI updated agent config: model=${updated.model}, effort=${updated.reasoningEffort}, tools=${updated.enabledTools.size}",
                )
                AgentConfigStore.toUiJson(updated).toString()
            }.getOrElse { err ->
                AcidLogger.w(TAG, "updateAgentConfig failed", err)
                AgentConfigStore.toUiJson().toString()
            }
        }

        @JavascriptInterface
        fun testAgentConnection() {
            Thread({
                val cfg = AgentConfigStore.getConfig(forceReload = true)
                val res = AcidLlmClient.testConnection(cfg)
                val payload = JSONObject().apply {
                    put("ok", res.ok)
                    put("latencyMs", res.latencyMs)
                    put("model", cfg.model)
                    put("reply", res.replyPreview)
                    put("error", res.error)
                }.toString()
                activity.runOnUiThread {
                    runCatching {
                        webView.evaluateJavascript("window.onAgentTestResult && window.onAgentTestResult($payload)", null)
                    }
                }
            }, "AcidModelTest").apply { isDaemon = true; start() }
        }

        @JavascriptInterface
        fun cleanAgentWorkspace(): String {
            val res = AgentConfigStore.cleanWorkspace()
            val freedText = AcidIo.formatBytes(res.freedBytes)
            AcidLogger.i(TAG, "cleaned agent workspace: ${res.deletedFiles} files, $freedText")
            val stats = AcidIo.computeDirStats(AgentConfigStore.workspaceDir())
            val remainSize = AcidIo.formatBytes(stats.totalBytes)
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "已清理工作区（删除 ${res.deletedFiles} 个文件，释放 $freedText）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return JSONObject().apply {
                put("ok", true)
                put("deletedFiles", res.deletedFiles)
                put("freedSize", freedText)
                put("workspaceFiles", stats.fileCount)
                put("workspaceSize", remainSize)
            }.toString()
        }

        @JavascriptInterface
        fun openAgent() {
            activity.runOnUiThread {
                AgentDialog.show(activity)
            }
        }

        private fun appendOptionItem(items: JSONArray, categoryId: String, opt: FeatureOption) {
            items.put(JSONObject().apply {
                put("type", opt.type)
                put("category", categoryId)
                put("group", opt.group)
                put("title", opt.title)
                put("configKey", opt.configKey)
                put("dependsOn", opt.dependsOn ?: JSONObject.NULL)
                if (opt.type == FeatureOption.TYPE_TEXT) {
                    put("value", ConfigStore.getString(opt.configKey, opt.defaultText))
                    put("placeholder", opt.placeholder)
                } else {
                    put("enabled", ConfigStore.getBoolean(opt.configKey, opt.defaultValue))
                }
            })
        }

        @JavascriptInterface
        fun toggleConfig(key: String, enabled: Boolean) {
            ConfigStore.putBoolean(key, enabled)
            AcidLogger.i(TAG, "UI toggle $key -> $enabled")
        }

        @JavascriptInterface
        fun setStringConfig(key: String, value: String) {
            ConfigStore.putString(key, value)
            AcidLogger.i(TAG, "UI text $key -> ${value.length} chars")
        }

        @JavascriptInterface
        fun rescanDexKit() {
            Thread {
                DexKitLocator.clearCache()
                DexKitLocator.resolveAll(forceRescan = true)
                val dk = DexKitLocator.snapshotJson()
                val ok = dk.optInt("okCount", 0)
                val total = dk.optInt("total", 0)
                activity.runOnUiThread {
                    webView.evaluateJavascript("onRescanDone($ok, $total)", null)
                    Toast.makeText(activity, "DexKit 扫描完成 ($ok/$total)", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        @JavascriptInterface
        fun copyDiag() {
            activity.runOnUiThread {
                val diag = JSONObject().apply {
                    put("hostVersion", "${HostContext.hostVersionName} (${HostContext.hostVersionCode})")
                    put("moduleVersion", HostContext.MODULE_VERSION_NAME)
                    put("debugPort", DebugServer.boundPort)
                    put("dexkit", DexKitLocator.snapshotJson())
                    put("features", FeatureRegistry.snapshotJson())
                }.toString(2)
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("AcidWeChatDiag", diag))
                Toast.makeText(activity, "已复制诊断信息", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getAbTestState(includeIncompatible: Boolean): String =
            WeChatAbTestStore.buildUiSnapshot(includeIncompatible).toString()

        @JavascriptInterface
        fun setAbTestValue(type: String, key: String, value: String): String =
            WeChatAbTestStore.setValue(type, key, value).toString()

        @JavascriptInterface
        fun queryAbTestKey(type: String, key: String): String =
            WeChatAbTestStore.queryKey(type, key).toString()

        @JavascriptInterface
        fun resetAbTest(): String =
            WeChatAbTestStore.resetAll().toString()

        @JavascriptInterface
        fun restartHost() {
            activity.runOnUiThread {
                runCatching { if (dialog.isShowing) dialog.dismiss() }
                runCatching {
                    val launchIntent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        activity.startActivity(launchIntent)
                    }
                    activity.finishAffinity()
                }.onFailure {
                    AcidLogger.w(TAG, "restartHost failed", it)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    Process.killProcess(Process.myPid())
                }, 150L)
            }
        }

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }
}
