package com.acid.wechat.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Shared WebView asset loader and dialog lifecycle helper used by both [SettingsDialog]
 * and floating overlay sheets ([com.acid.wechat.ui.overlay.FloatingSheetDialog]).
 */
object WebViewHost {
    private const val TAG = "WebViewHost"
    private val assetCache = ConcurrentHashMap<String, String>()

    fun loadAsset(assetPath: String): String? {
        assetCache[assetPath]?.let { return it }
        val modPath = HostContext.modulePath
        if (modPath.isBlank()) return null
        return runCatching {
            ZipFile(modPath).use { zip ->
                val entry = zip.getEntry(assetPath) ?: error("$assetPath not found in $modPath")
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.also { assetCache[assetPath] = it }
        }.onFailure {
            AcidLogger.e(TAG, "loadAsset($assetPath) failed", it)
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun showHtmlDialog(
        activity: Activity,
        assetPath: String,
        bridgeName: String,
        styleResId: Int,
        softInputMode: Int = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        windowSetup: (Dialog) -> Unit,
        bridgeFactory: (Dialog, WebView) -> Any,
        containerFactory: (WebView, Dialog) -> View = { wv, _ -> wv },
    ): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val html = loadAsset(assetPath) ?: run {
            Toast.makeText(activity, "$assetPath 加载失败", Toast.LENGTH_SHORT).show()
            return null
        }

        val dialog = Dialog(activity, styleResId)
        windowSetup(dialog)
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            @Suppress("DEPRECATION")
            setSoftInputMode(softInputMode)
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

        val bridge = bridgeFactory(dialog, webView)
        webView.addJavascriptInterface(bridge, bridgeName)
        webView.loadDataWithBaseURL("https://acid.local/", html, "text/html", "UTF-8", null)

        dialog.setOnDismissListener {
            runCatching {
                webView.removeJavascriptInterface(bridgeName)
                webView.stopLoading()
                webView.destroy()
            }
        }

        dialog.setContentView(containerFactory(webView, dialog))
        dialog.show()
        return dialog
    }
}
