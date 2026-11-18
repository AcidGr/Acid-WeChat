package com.acid.wechat.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.debug.DebugServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipFile

/**
 * Clean, lightweight white WebView settings panel opened from WeChat Settings top-right "Acid".
 */
object SettingsDialog {
    private const val TAG = "SettingsDialog"

    @Volatile
    private var cachedHtml: String? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching {
            val html = loadSettingsHtml() ?: run {
                Toast.makeText(activity, "settings.html 加载失败", Toast.LENGTH_SHORT).show()
                return
            }

            val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                @Suppress("DEPRECATION")
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
                statusBarColor = Color.WHITE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }

            val webView = WebView(activity).apply {
                setBackgroundColor(Color.WHITE)
                overScrollMode = View.OVER_SCROLL_NEVER
                isFocusable = true
                isFocusableInTouchMode = true
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
            }

            val bridge = AcidBridge(activity, dialog, webView)
            webView.addJavascriptInterface(bridge, "AcidBridge")
            webView.loadDataWithBaseURL("https://acid.local/", html, "text/html", "UTF-8", null)

            dialog.setOnDismissListener {
                runCatching {
                    webView.removeJavascriptInterface("AcidBridge")
                    webView.stopLoading()
                    webView.destroy()
                }
            }

            dialog.setContentView(webView)
            dialog.show()
        }.onFailure {
            AcidLogger.e(TAG, "show SettingsDialog failed", it)
        }
    }

    private fun loadSettingsHtml(): String? {
        cachedHtml?.let { return it }
        val modPath = HostContext.modulePath
        if (modPath.isBlank()) return null
        return runCatching {
            ZipFile(modPath).use { zip ->
                val entry = zip.getEntry("assets/settings.html")
                    ?: error("assets/settings.html not found in $modPath")
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.also { cachedHtml = it }
        }.onFailure {
            AcidLogger.e(TAG, "loadSettingsHtml failed", it)
        }.getOrNull()
    }

    private class AcidBridge(
        private val activity: Activity,
        private val dialog: Dialog,
        private val webView: WebView,
    ) {
        @JavascriptInterface
        fun getState(): String {
            val dk = DexKitLocator.snapshotJson()
            val cats = JSONArray()
            for (cat in FeatureCategory.entries) {
                cats.put(JSONObject().apply {
                    put("id", cat.id)
                    put("label", cat.label)
                })
            }

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
                    items.put(JSONObject().apply {
                        put("type", opt.type)
                        put("category", f.category.id)
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
            }

            items.put(JSONObject().apply {
                put("type", FeatureOption.TYPE_SWITCH)
                put("category", FeatureCategory.ME.id)
                put("group", "系统调试")
                put("title", "本地调试接口")
                put("configKey", ConfigStore.KEY_DEBUG_SERVER)
                put("enabled", ConfigStore.getBoolean(ConfigStore.KEY_DEBUG_SERVER, true))
                put("dependsOn", JSONObject.NULL)
            })

            return JSONObject().apply {
                put("hostVersion", HostContext.hostVersionName)
                put("dexkitOk", dk.optInt("okCount", 0))
                put("dexkitTotal", dk.optInt("total", 0))
                put("categories", cats)
                put("items", items)
            }.toString()
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
        fun close() {
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }
}
