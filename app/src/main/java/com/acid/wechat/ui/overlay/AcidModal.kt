package com.acid.wechat.ui.overlay

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.wechat.ChatRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream

/**
 * Standard centered floating modal host backed by `assets/acid_modal.html`.
 * Any feature can pop up a consistent pure-white floating dialog via [AcidModalSpec].
 */
object AcidModal {
    private const val TAG = "AcidModal"

    fun show(activity: Activity, spec: AcidModalSpec): Dialog? {
        return FloatingSheetDialog.show(
            activity = activity,
            assetPath = "assets/acid_modal.html",
            bridgeName = "AcidModalBridge",
            widthRatio = spec.widthRatio,
            heightRatio = spec.heightRatio,
            requestInterceptor = ::interceptAvatarRequest,
            bridgeFactory = { dialog, _ -> ModalBridge(activity, dialog, spec) },
        )
    }

    private fun interceptAvatarRequest(url: Uri): WebResourceResponse? {
        if (url.host != "acid.local") return null
        val path = url.path ?: return null
        if (!path.startsWith("/avatar/")) return null
        val username = Uri.decode(path.removePrefix("/avatar/")).trim()
        val (file, mime) = ChatRepository.findCachedAvatar(username) ?: return null
        return WebResourceResponse(
            mime,
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "max-age=300"),
            FileInputStream(file),
        )
    }

    private fun serializeSpec(spec: AcidModalSpec): String {
        val blockArr = JSONArray()
        for (block in spec.blocks) {
            val obj = JSONObject()
            when (block) {
                is ModalBlock.Preview -> {
                    obj.put("type", "preview")
                    obj.put("title", block.title)
                    obj.put("body", block.body)
                }
                is ModalBlock.RandomCountBar -> {
                    obj.put("type", "random_bar")
                    obj.put("checklistKey", block.checklistKey)
                    obj.put("defaultCount", block.defaultCount)
                    obj.put("placeholder", block.placeholder)
                    obj.put("pickLabel", block.pickLabel)
                    obj.put("clearLabel", block.clearLabel)
                }
                is ModalBlock.Checklist -> {
                    obj.put("type", "checklist")
                    obj.put("key", block.key)
                    obj.put("multiSelect", block.multiSelect)
                    obj.put("showSearch", block.showSearch)
                    obj.put("searchPlaceholder", block.searchPlaceholder)
                    obj.put("filterSelectedLabel", block.filterSelectedLabel)
                    obj.put("emptyText", block.emptyText)
                    obj.put("lockedCountOffset", block.lockedCountOffset)
                    obj.put("headerTemplate", block.headerTemplate ?: JSONObject.NULL)
                    obj.put("footerTemplate", block.footerTemplate)
                    val itemsArr = JSONArray()
                    for (it in block.items) {
                        itemsArr.put(JSONObject().apply {
                            put("id", it.id)
                            put("title", it.title)
                            put("subtitle", it.subtitle)
                            put("avatar", it.avatarUsername ?: JSONObject.NULL)
                            put("checked", it.checked)
                            put("lockedBadge", it.lockedBadge ?: JSONObject.NULL)
                        })
                    }
                    obj.put("items", itemsArr)
                }
                is ModalBlock.TextInput -> {
                    obj.put("type", "text_input")
                    obj.put("key", block.key)
                    obj.put("label", block.label)
                    obj.put("value", block.value)
                    obj.put("placeholder", block.placeholder)
                }
                is ModalBlock.NumberInput -> {
                    obj.put("type", "number_input")
                    obj.put("key", block.key)
                    obj.put("label", block.label)
                    obj.put("value", block.value)
                    obj.put("min", block.min)
                    obj.put("max", block.max)
                }
                is ModalBlock.Toggle -> {
                    obj.put("type", "toggle")
                    obj.put("key", block.key)
                    obj.put("label", block.label)
                    obj.put("checked", block.checked)
                }
            }
            blockArr.put(obj)
        }

        return JSONObject().apply {
            put("title", spec.title)
            put("subtitle", spec.subtitle)
            put("primaryButtonText", spec.primaryButtonText)
            put("blocks", blockArr)
        }.toString()
    }

    private fun parseResult(rawJson: String): AcidModalResult {
        val root = JSONObject(rawJson)
        val values = HashMap<String, String>()
        val valuesObj = root.optJSONObject("values")
        if (valuesObj != null) {
            val keys = valuesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                values[k] = valuesObj.optString(k, "")
            }
        }

        val lists = HashMap<String, List<String>>()
        val listsObj = root.optJSONObject("lists")
        if (listsObj != null) {
            val keys = listsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = listsObj.optJSONArray(k) ?: continue
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(arr.optString(i))
                }
                lists[k] = list
            }
        }
        return AcidModalResult(values, lists)
    }

    private class ModalBridge(
        private val activity: Activity,
        private val dialog: Dialog,
        private val spec: AcidModalSpec,
    ) {
        @JavascriptInterface
        fun getSpec(): String = serializeSpec(spec)

        @JavascriptInterface
        fun submit(rawResultJson: String) {
            val result = runCatching { parseResult(rawResultJson) }.getOrElse {
                AcidLogger.w(TAG, "parseResult failed", it)
                return
            }
            runCatching {
                val shouldClose = spec.onSubmit(result)
                if (shouldClose) {
                    activity.runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                    }
                }
            }.onFailure {
                AcidLogger.w(TAG, "modal onSubmit failed", it)
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
