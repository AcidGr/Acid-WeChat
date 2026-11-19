package com.acid.wechat.wechat

import android.content.Context
import android.util.Base64
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import com.acid.wechat.ui.WebViewHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method

/**
 * Zero-hook WeChat ABTest (`Expt`) & `Repairer` switch store backed directly by WeChat's own
 * `com.tencent.mmkv.MMKV` stores (`${uin}_WxExptAppKeyMmkv`, `${uin}_WxExptAppIdMmkv`, and `Repairer`).
 */
object WeChatAbTestStore {
    private const val TAG = "WeChatAbTest"
    private const val CATALOG_ASSET_PATH = "assets/abtest_catalog.json"
    private const val FAKE_EXPT_ID = 99999
    private const val REPAIRER_MMAP_ID = "Repairer"
    private const val SYSTEM_CONFIG_PREFS = "system_config_prefs"
    private const val KEY_DEFAULT_UIN = "default_uin"

    private class MmkvApi(
        val mmkvWithIdMethod: Method,
        val containsKeyMethod: Method,
        val getIntMethod: Method,
        val putIntMethod: Method,
        val getStringMethod: Method,
        val putStringMethod: Method,
        val removeValueForKeyMethod: Method,
        val clearAllMethod: Method,
        val syncMethod: Method?,
    )

    private class MmkvHandle(
        private val api: MmkvApi,
        private val instance: Any,
    ) {
        fun containsKey(key: String): Boolean =
            runCatching { api.containsKeyMethod.invoke(instance, key) == true }.getOrDefault(false)

        fun getInt(key: String, defValue: Int): Int =
            runCatching { (api.getIntMethod.invoke(instance, key, defValue) as? Int) ?: defValue }.getOrDefault(defValue)

        fun putInt(key: String, value: Int) {
            api.putIntMethod.invoke(instance, key, value)
        }

        fun getString(key: String, defValue: String): String =
            runCatching { (api.getStringMethod.invoke(instance, key, defValue) as? String) ?: defValue }
                .getOrDefault(defValue)

        fun putString(key: String, value: String) {
            api.putStringMethod.invoke(instance, key, value)
        }

        fun removeValueForKey(key: String) {
            runCatching { api.removeValueForKeyMethod.invoke(instance, key) }
        }

        fun clearAll() {
            runCatching { api.clearAllMethod.invoke(instance) }
        }

        fun sync() {
            runCatching { api.syncMethod?.invoke(instance) }
        }
    }

    @Volatile
    private var cachedMmkvApi: MmkvApi? = null

    @Volatile
    private var cachedCatalog: List<JSONObject>? = null

    private fun resolveMmkvApi(): MmkvApi? {
        cachedMmkvApi?.let { return it }
        return runCatching {
            val cls = Class.forName("com.tencent.mmkv.MMKV", false, HostContext.classLoader)
            MmkvApi(
                mmkvWithIdMethod = cls.getMethod("mmkvWithID", String::class.java).apply { isAccessible = true },
                containsKeyMethod = cls.getMethod("containsKey", String::class.java).apply { isAccessible = true },
                getIntMethod = cls.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                },
                putIntMethod = cls.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                },
                getStringMethod = cls.getMethod("getString", String::class.java, String::class.java).apply {
                    isAccessible = true
                },
                putStringMethod = cls.getMethod("putString", String::class.java, String::class.java).apply {
                    isAccessible = true
                },
                removeValueForKeyMethod = cls.getMethod("removeValueForKey", String::class.java).apply {
                    isAccessible = true
                },
                clearAllMethod = cls.getMethod("clearAll").apply { isAccessible = true },
                syncMethod = runCatching { cls.getMethod("sync").apply { isAccessible = true } }.getOrNull(),
            ).also { cachedMmkvApi = it }
        }.onFailure {
            AcidLogger.w(TAG, "failed resolving com.tencent.mmkv.MMKV", it)
        }.getOrNull()
    }

    private fun openMmkv(mmapId: String): MmkvHandle? {
        val api = resolveMmkvApi() ?: return null
        return runCatching {
            val inst = api.mmkvWithIdMethod.invoke(null, mmapId) ?: return null
            MmkvHandle(api, inst)
        }.onFailure {
            AcidLogger.w(TAG, "mmkvWithID($mmapId) failed", it)
        }.getOrNull()
    }

    private fun resolveUin(): Int = runCatching {
        val pref = HostContext.app.getSharedPreferences(SYSTEM_CONFIG_PREFS, Context.MODE_PRIVATE)
        runCatching { pref.getInt(KEY_DEFAULT_UIN, 0) }.getOrElse {
            pref.all[KEY_DEFAULT_UIN]?.toString()?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    fun getValue(type: String, key: String): String? {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return null
        return if (type.equals("repairer", ignoreCase = true)) {
            getRepairerValue(cleanKey)
        } else {
            getExptValue(cleanKey)
        }
    }

    fun queryKey(type: String, key: String): JSONObject {
        val cleanType = normalizeType(type)
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("type", cleanType)
                .put("key", "")
                .put("exists", false)
                .put("value", JSONObject.NULL)
                .put("error", "Key 不能为空")
        }
        val uin = resolveUin()
        if (cleanType == "expt" && uin == 0) {
            return JSONObject()
                .put("ok", false)
                .put("type", cleanType)
                .put("key", cleanKey)
                .put("exists", false)
                .put("value", JSONObject.NULL)
                .put("error", "未获取到微信账号 UIN，请确认已登录")
        }
        val value = getValue(cleanType, cleanKey)
        return JSONObject()
            .put("ok", true)
            .put("type", cleanType)
            .put("key", cleanKey)
            .put("exists", value != null)
            .put("value", value ?: JSONObject.NULL)
            .put("error", "")
    }

    fun setValue(type: String, key: String, value: String): JSONObject {
        val cleanType = normalizeType(type)
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("type", cleanType)
                .put("key", "")
                .put("value", value)
                .put("error", "Key 不能为空")
        }
        val result = if (cleanType == "repairer") {
            putRepairerValue(cleanKey, value)
        } else {
            putExptValue(cleanKey, value)
        }
        return result.fold(
            onSuccess = {
                AcidLogger.i(TAG, "set $cleanType [$cleanKey] = '$value'")
                JSONObject()
                    .put("ok", true)
                    .put("type", cleanType)
                    .put("key", cleanKey)
                    .put("value", value)
                    .put("error", "")
            },
            onFailure = { err ->
                val msg = err.message ?: "写入失败"
                AcidLogger.w(TAG, "set $cleanType [$cleanKey] failed: $msg")
                JSONObject()
                    .put("ok", false)
                    .put("type", cleanType)
                    .put("key", cleanKey)
                    .put("value", value)
                    .put("error", msg)
            },
        )
    }

    fun resetAll(): JSONObject = runCatching {
        val uin = resolveUin()
        var deletedFiles = 0
        if (uin != 0) {
            val appKeyName = "${uin}_WxExptAppKeyMmkv"
            val appIdName = "${uin}_WxExptAppIdMmkv"
            openMmkv(appKeyName)?.apply { clearAll(); sync() }
            openMmkv(appIdName)?.apply { clearAll(); sync() }
            val mmkvDir = File(HostContext.app.filesDir, "mmkv")
            for (name in listOf(appKeyName, "$appKeyName.crc", appIdName, "$appIdName.crc")) {
                val f = File(mmkvDir, name)
                if (f.exists() && f.delete()) deletedFiles++
            }
        }

        var clearedRepairerKeys = 0
        val repairerMmkv = openMmkv(REPAIRER_MMAP_ID)
        if (repairerMmkv != null) {
            for (groupObj in loadCatalog()) {
                val configs = groupObj.optJSONArray("configs") ?: continue
                for (i in 0 until configs.length()) {
                    val cfg = configs.optJSONObject(i) ?: continue
                    val type = normalizeType(cfg.optString("type", "expt"))
                    val key = cfg.optString("key", "").trim()
                    if (type == "repairer" && key.isNotEmpty() && repairerMmkv.containsKey(key)) {
                        repairerMmkv.removeValueForKey(key)
                        clearedRepairerKeys++
                    }
                }
            }
            if (clearedRepairerKeys > 0) repairerMmkv.sync()
        }

        AcidLogger.i(TAG, "reset ABTest stores: uin=$uin, deletedFiles=$deletedFiles, clearedRepairerKeys=$clearedRepairerKeys")
        JSONObject()
            .put("ok", true)
            .put("deletedFiles", deletedFiles)
            .put("clearedRepairerKeys", clearedRepairerKeys)
            .put("message", "已恢复默认（清理 $deletedFiles 个 Expt 缓存文件，$clearedRepairerKeys 项 Repairer）")
    }.getOrElse { err ->
        AcidLogger.w(TAG, "resetAll failed", err)
        JSONObject()
            .put("ok", false)
            .put("deletedFiles", 0)
            .put("clearedRepairerKeys", 0)
            .put("message", err.message ?: "恢复默认失败")
    }

    fun buildUiSnapshot(includeIncompatible: Boolean = false): JSONObject {
        val uin = resolveUin()
        val catalog = loadCatalog()
        val mmkvReady = resolveMmkvApi() != null
        val errorMsg = when {
            !mmkvReady -> "当前进程未找到微信 MMKV 组件"
            uin == 0 -> "未获取到微信账号 UIN，Expt 实验项暂不可读写"
            catalog.isEmpty() -> "未加载到内置灰度实验目录"
            else -> ""
        }

        var totalCount = 0
        var activeCount = 0
        var hiddenIncompatibleCount = 0
        val groupsOut = JSONArray()

        for (groupObj in catalog) {
            val groupName = groupObj.optString("group", "其他").trim().ifEmpty { "其他" }
            val configsIn = groupObj.optJSONArray("configs") ?: continue
            val configsOut = JSONArray()

            for (i in 0 until configsIn.length()) {
                val item = configsIn.optJSONObject(i) ?: continue
                val key = item.optString("key", "").trim()
                if (key.isEmpty()) continue
                val type = normalizeType(item.optString("type", "expt"))
                val title = item.optString("title", key).trim().ifEmpty { key }
                val desc = item.optString("desc", "").trim()

                val verObj = item.optJSONObject("version")
                val minVer = verObj?.optString("min", "")?.trim().orEmpty()
                val maxVer = verObj?.optString("max", "")?.trim().orEmpty()
                val compatible = isVersionCompatible(minVer, maxVer)
                if (!compatible && !includeIncompatible) {
                    hiddenIncompatibleCount++
                    continue
                }

                val uiObj = item.optJSONObject("ui")
                val rawUiType = uiObj?.optString("type", "switch")?.trim().orEmpty()
                val uiType = when (rawUiType) {
                    "single_choice", "input", "switch" -> rawUiType
                    else -> "switch"
                }
                val valuesObj = uiObj?.optJSONObject("values")
                val offValue = if (valuesObj != null && valuesObj.has("off") && !valuesObj.isNull("off")) {
                    valuesObj.optString("off")
                } else {
                    "0"
                }
                val onValue = if (valuesObj != null && valuesObj.has("on") && !valuesObj.isNull("on")) {
                    valuesObj.optString("on")
                } else {
                    "1"
                }
                val optionsOut = JSONArray()
                val rawOpts = uiObj?.optJSONArray("options")
                if (rawOpts != null) {
                    for (j in 0 until rawOpts.length()) {
                        val o = rawOpts.optJSONObject(j) ?: continue
                        optionsOut.put(
                            JSONObject()
                                .put("alias", o.optString("alias", o.optString("value", "")))
                                .put("value", o.optString("value", "")),
                        )
                    }
                }

                val currentValue = if (mmkvReady && (type == "repairer" || uin != 0)) {
                    getValue(type, key)
                } else {
                    null
                }
                val active = isEntryActive(uiType, currentValue, onValue)
                totalCount++
                if (active) activeCount++

                configsOut.put(
                    JSONObject().apply {
                        put("type", type)
                        put("key", key)
                        put("title", title)
                        put("desc", desc)
                        put("uiType", uiType)
                        put("onValue", onValue)
                        put("offValue", offValue)
                        put("options", optionsOut)
                        put("currentValue", currentValue ?: JSONObject.NULL)
                        put("active", active)
                        put("compatible", compatible)
                        put("minVersion", minVer)
                        put("maxVersion", maxVer)
                    },
                )
            }

            if (configsOut.length() > 0) {
                groupsOut.put(
                    JSONObject()
                        .put("group", groupName)
                        .put("configs", configsOut),
                )
            }
        }

        return JSONObject().apply {
            put("ok", mmkvReady && catalog.isNotEmpty())
            put("error", errorMsg)
            put("uin", uin)
            put("hostVersion", HostContext.hostVersionName)
            put("hostVersionCode", HostContext.hostVersionCode)
            put("totalCount", totalCount)
            put("activeCount", activeCount)
            put("hiddenIncompatibleCount", hiddenIncompatibleCount)
            put("groups", groupsOut)
        }
    }

    private fun getExptValue(exptKey: String): String? {
        val uin = resolveUin()
        if (uin == 0) return null
        val appKeyMmkv = openMmkv("${uin}_WxExptAppKeyMmkv") ?: return null
        val appIdMmkv = openMmkv("${uin}_WxExptAppIdMmkv") ?: return null
        if (!appKeyMmkv.containsKey(exptKey)) return null

        val exptId = appKeyMmkv.getInt(exptKey, FAKE_EXPT_ID)
        val itemStr = appIdMmkv.getString(exptId.toString(), "")
        if (itemStr.isBlank()) return null

        val itemObj = runCatching { JSONObject(itemStr) }.getOrNull() ?: return null
        val args = itemObj.optJSONArray("Args") ?: return null
        for (i in 0 until args.length()) {
            val arg = args.optJSONObject(i) ?: continue
            if (arg.optString("Key") == exptKey) {
                val rawVal = arg.optString("Val", "")
                return runCatching {
                    val decoded = Base64.decode(rawVal, Base64.DEFAULT)
                    String(decoded, Charsets.UTF_8)
                }.getOrNull()
            }
        }
        return null
    }

    private fun putExptValue(exptKey: String, value: String): Result<Unit> {
        val uin = resolveUin()
        if (uin == 0) {
            return Result.failure(IllegalStateException("未获取到微信账号 UIN，请确认已登录"))
        }
        val appKeyMmkv = openMmkv("${uin}_WxExptAppKeyMmkv")
            ?: return Result.failure(IllegalStateException("无法打开 ExptAppKey MMKV"))
        val appIdMmkv = openMmkv("${uin}_WxExptAppIdMmkv")
            ?: return Result.failure(IllegalStateException("无法打开 ExptAppId MMKV"))

        return runCatching {
            val exptId = appKeyMmkv.getInt(exptKey, FAKE_EXPT_ID)
            val existingStr = appIdMmkv.getString(exptId.toString(), "")
            val itemObj = existingStr.takeIf { it.isNotBlank() }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            } ?: defaultExptItemJson(exptId)

            val args = itemObj.optJSONArray("Args") ?: JSONArray().also { itemObj.put("Args", it) }
            val encoded = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            var updated = false
            for (i in 0 until args.length()) {
                val arg = args.optJSONObject(i) ?: continue
                if (arg.optString("Key") == exptKey) {
                    arg.put("Val", encoded)
                    updated = true
                    break
                }
            }
            if (!updated) {
                args.put(JSONObject().put("Key", exptKey).put("Val", encoded))
            }

            appIdMmkv.putString(exptId.toString(), itemObj.toString())
            appIdMmkv.sync()
            if (exptId == FAKE_EXPT_ID && !appKeyMmkv.containsKey(exptKey)) {
                appKeyMmkv.putInt(exptKey, FAKE_EXPT_ID)
                appKeyMmkv.sync()
            }
        }
    }

    private fun defaultExptItemJson(exptId: Int): JSONObject = JSONObject().apply {
        put("ExptId", exptId)
        put("GroupId", 0)
        put("ExptSequence", 1)
        put("Priority", 1)
        put("NeedReport", 0)
        put("StartTime", 0L)
        put("EndTime", 0L)
        put("ExptType", 4)
        put("SvrType", 1)
        put("ExptCheckSum", "")
        put("Args", JSONArray())
    }

    private fun getRepairerValue(key: String): String? {
        val mmkv = openMmkv(REPAIRER_MMAP_ID) ?: return null
        if (!mmkv.containsKey(key)) return null
        val typeSuffix = key.substringAfterLast('_', "")
        return when (typeSuffix) {
            "Int" -> mmkv.getInt(key, 0).toString()
            else -> mmkv.getString(key, "")
        }
    }

    private fun putRepairerValue(key: String, value: String): Result<Unit> {
        val mmkv = openMmkv(REPAIRER_MMAP_ID)
            ?: return Result.failure(IllegalStateException("无法打开 Repairer MMKV"))
        val typeSuffix = key.substringAfterLast('_', "")
        return when (typeSuffix) {
            "Int" -> {
                val intVal = value.trim().toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("Repairer Int 参数必须为整数"))
                runCatching {
                    mmkv.putInt(key, intVal)
                    mmkv.sync()
                }
            }
            "String" -> runCatching {
                mmkv.putString(key, value)
                mmkv.sync()
            }
            else -> runCatching {
                val intVal = value.trim().toIntOrNull()
                if (intVal != null) mmkv.putInt(key, intVal) else mmkv.putString(key, value)
                mmkv.sync()
            }
        }
    }

    private fun loadCatalog(): List<JSONObject> {
        cachedCatalog?.let { return it }
        val raw = WebViewHost.loadAsset(CATALOG_ASSET_PATH) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list += it }
            }
            list.also { cachedCatalog = it }
        }.onFailure {
            AcidLogger.w(TAG, "failed parsing $CATALOG_ASSET_PATH", it)
        }.getOrDefault(emptyList())
    }

    private fun normalizeType(raw: String?): String {
        val clean = raw?.trim()?.lowercase().orEmpty()
        return if (clean == "repairer") "repairer" else "expt"
    }

    private fun isEntryActive(uiType: String, currentValue: String?, onValue: String): Boolean = when (uiType) {
        "switch" -> currentValue == onValue
        "single_choice" -> currentValue != null && currentValue.isNotEmpty() && currentValue != "0"
        else -> !currentValue.isNullOrEmpty()
    }

    private data class ParsedVer(val major: Int, val minor: Int, val patch: Int, val code: Long?)

    private fun parseVersionSpec(raw: String): ParsedVer? {
        val parts = raw.trim().split('.')
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        val code = parts.getOrNull(3)?.toLongOrNull()
        return ParsedVer(major, minor, patch, code)
    }

    private fun compareHostToSpec(hostName: ParsedVer, hostCode: Long, spec: ParsedVer): Int {
        if (hostName.major != spec.major) return hostName.major.compareTo(spec.major)
        if (hostName.minor != spec.minor) return hostName.minor.compareTo(spec.minor)
        if (hostName.patch != spec.patch) return hostName.patch.compareTo(spec.patch)
        if (hostCode > 0L && spec.code != null && spec.code > 0L) {
            return hostCode.compareTo(spec.code)
        }
        return 0
    }

    private fun isVersionCompatible(minVer: String, maxVer: String): Boolean {
        val hostVer = parseVersionSpec(HostContext.hostVersionName) ?: return true
        val hostCode = HostContext.hostVersionCode
        if (minVer.isNotEmpty()) {
            val minParsed = parseVersionSpec(minVer)
            if (minParsed != null && compareHostToSpec(hostVer, hostCode, minParsed) < 0) {
                return false
            }
        }
        if (maxVer.isNotEmpty()) {
            val maxParsed = parseVersionSpec(maxVer)
            if (maxParsed != null && compareHostToSpec(hostVer, hostCode, maxParsed) > 0) {
                return false
            }
        }
        return true
    }
}
