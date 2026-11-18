package com.acid.wechat.core

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple JSON-backed persistent configuration store with parent-child dependency helper.
 */
object ConfigStore {
    private const val TAG = "ConfigStore"
    private const val FILE_NAME = "config.json"

    private val lock = Any()
    private val map = ConcurrentHashMap<String, Any>()

    @Volatile
    private var lastLoadedMtime: Long = 0L

    const val KEY_ANTI_RECALL = "anti_recall"
    const val KEY_ANTI_RECALL_NOTICE = "anti_recall_notice"
    const val KEY_ANTI_RECALL_NOTICE_TEXT = "anti_recall_notice_text"
    const val KEY_CUSTOM_JSB = "custom_jsb"
    const val KEY_CUSTOM_DICE = "custom_dice"
    const val KEY_ANTI_MOMENTS_DELETE = "anti_moments_delete"
    const val KEY_MOMENTS_DELETE_PREFIX = "moments_delete_prefix"
    const val KEY_FAKE_LIKE_ENABLED = "fake_like_enabled"
    const val KEY_FAKE_COMMENT_ENABLED = "fake_comment_enabled"
    const val KEY_SNS_TAIL_ENABLED = "sns_tail_enabled"
    const val KEY_SNS_TAIL_TEXT = "sns_tail_text"
    const val KEY_SNS_TAIL_AS_LOCATION = "sns_tail_as_location"
    const val KEY_CUSTOM_SAVE_PATH_ENABLED = "custom_save_path_enabled"
    const val KEY_SAVE_FILE_DIR = "save_file_dir"
    const val KEY_SAVE_MEDIA_DIR = "save_media_dir"
    const val KEY_DEBUG_SERVER = "debug_server"
    const val KEY_DEBUG_PORT = "debug_port"

    const val DEFAULT_MOMENTS_DELETE_PREFIX = "❌\\n"
    const val DEFAULT_RECALL_NOTICE_TEXT = "「{name}」尝试撤回一条消息（已拦截）"
    const val DEFAULT_SNS_TAIL_TEXT = "来自 iPhone 16 Pro Max"
    const val DEFAULT_SAVE_FILE_DIR = "Download/AcidWeChat"
    const val DEFAULT_SAVE_MEDIA_DIR = "Pictures/AcidWeChat"

    private val retiredKeys = setOf(
        "anti_recall_self",
        "anti_moments_comment",
        "fake_like_float_button",
        "float_btn_y_ratio",
        "float_btn_right_side",
    )

    fun load() {
        synchronized(lock) {
            setDefaultsIfMissing()
            runCatching {
                val f = File(HostContext.getDataDir(), FILE_NAME)
                if (f.exists() && f.length() > 0) {
                    val obj = JSONObject(f.readText(Charsets.UTF_8))
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k in retiredKeys) continue
                        map[k] = obj.get(k)
                    }
                    retiredKeys.forEach { map.remove(it) }
                    lastLoadedMtime = f.lastModified()
                } else {
                    saveLocked()
                }
            }.onFailure {
                AcidLogger.w(TAG, "load config failed, using defaults", it)
            }
        }
    }

    fun reloadIfModified() {
        runCatching {
            val f = File(HostContext.getDataDir(), FILE_NAME)
            val m = f.lastModified()
            if (m > 0L && m != lastLoadedMtime) {
                load()
            }
        }
    }

    private fun setDefaultsIfMissing() {
        map.putIfAbsent(KEY_ANTI_RECALL, true)
        map.putIfAbsent(KEY_ANTI_RECALL_NOTICE, true)
        map.putIfAbsent(KEY_ANTI_RECALL_NOTICE_TEXT, DEFAULT_RECALL_NOTICE_TEXT)
        map.putIfAbsent(KEY_CUSTOM_JSB, true)
        map.putIfAbsent(KEY_CUSTOM_DICE, true)
        map.putIfAbsent(KEY_ANTI_MOMENTS_DELETE, true)
        map.putIfAbsent(KEY_MOMENTS_DELETE_PREFIX, DEFAULT_MOMENTS_DELETE_PREFIX)
        map.putIfAbsent(KEY_FAKE_LIKE_ENABLED, true)
        map.putIfAbsent(KEY_FAKE_COMMENT_ENABLED, true)
        map.putIfAbsent(KEY_SNS_TAIL_ENABLED, false)
        map.putIfAbsent(KEY_SNS_TAIL_TEXT, DEFAULT_SNS_TAIL_TEXT)
        map.putIfAbsent(KEY_SNS_TAIL_AS_LOCATION, true)
        map.putIfAbsent(KEY_CUSTOM_SAVE_PATH_ENABLED, false)
        map.putIfAbsent(KEY_SAVE_FILE_DIR, DEFAULT_SAVE_FILE_DIR)
        map.putIfAbsent(KEY_SAVE_MEDIA_DIR, DEFAULT_SAVE_MEDIA_DIR)
        map.putIfAbsent(KEY_DEBUG_SERVER, true)
        map.putIfAbsent(KEY_DEBUG_PORT, 19898)
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            is Number -> v.toInt() != 0
            else -> defaultValue
        }
    }

    /**
     * Checks both the option itself and its parent [dependsOn] key (if any).
     */
    fun isEffective(
        key: String,
        defaultValue: Boolean,
        dependsOn: String? = null,
        parentDefault: Boolean = true,
    ): Boolean {
        if (dependsOn != null && !getBoolean(dependsOn, parentDefault)) {
            return false
        }
        return getBoolean(key, defaultValue)
    }

    fun getInt(key: String, defaultValue: Int): Int {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    fun getString(key: String, defaultValue: String): String {
        val v = map[key] ?: return defaultValue
        return v as? String ?: v.toString()
    }

    fun decodeNewlines(raw: String): String = raw.replace("\\n", "\n")

    fun putBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            map[key] = value
            saveLocked()
        }
    }

    fun putInt(key: String, value: Int) {
        synchronized(lock) {
            map[key] = value
            saveLocked()
        }
    }

    fun putString(key: String, value: String) {
        synchronized(lock) {
            map[key] = value
            saveLocked()
        }
    }

    fun snapshot(): JSONObject {
        synchronized(lock) {
            val obj = JSONObject()
            for ((k, v) in map) {
                obj.put(k, v)
            }
            return obj
        }
    }

    private fun saveLocked() {
        runCatching {
            val f = File(HostContext.getDataDir(), FILE_NAME)
            val obj = JSONObject()
            for ((k, v) in map) {
                obj.put(k, v)
            }
            f.writeText(obj.toString(2), Charsets.UTF_8)
            lastLoadedMtime = f.lastModified()
        }.onFailure {
            AcidLogger.w(TAG, "save config failed", it)
        }
    }
}
