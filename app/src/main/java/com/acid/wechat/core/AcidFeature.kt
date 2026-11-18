package com.acid.wechat.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Primary top-level UI categories for Acid-WeChat settings.
 */
enum class FeatureCategory(val id: String, val label: String) {
    HOME("home", "主页"),
    CONTACTS("contacts", "通讯录"),
    DISCOVER("discover", "发现"),
    ME("me", "我的"),
    MOMENTS("moments", "朋友圈"),
    CHAT("chat", "聊天"),
}

/**
 * Sub-option attached to a feature, placed in a secondary group and optionally linked to a parent toggle.
 */
data class FeatureOption(
    val title: String,
    val configKey: String,
    val group: String,
    val type: String = TYPE_SWITCH,
    val defaultValue: Boolean = true,
    val defaultText: String = "",
    val placeholder: String = "",
    val dependsOn: String? = null,
) {
    companion object {
        const val TYPE_SWITCH = "switch"
        const val TYPE_TEXT = "text"
    }
}

/**
 * Base contract for pluggable Acid-WeChat features.
 */
interface AcidFeature {
    val id: String
    val name: String
    val category: FeatureCategory
    val group: String
    val description: String
    val configKey: String
    val defaultEnabled: Boolean get() = true
    val dependsOn: String? get() = null

    fun extraOptions(): List<FeatureOption> = emptyList()
    fun registerDexRules() {}
    fun onInit() {}
    fun statusJson(): JSONObject = JSONObject()
}

/**
 * Central registry managing feature lifecycle and diagnostics.
 */
object FeatureRegistry {
    private const val TAG = "FeatureRegistry"

    data class FeatureState(
        val feature: AcidFeature,
        @Volatile var initialized: Boolean = false,
        @Volatile var lastError: String? = null,
        val hitCount: AtomicInteger = AtomicInteger(0),
    )

    private val states = CopyOnWriteArrayList<FeatureState>()

    fun register(feature: AcidFeature) {
        states.add(FeatureState(feature))
    }

    fun registerAllDexRules() {
        for (state in states) {
            runCatching {
                state.feature.registerDexRules()
            }.onFailure {
                state.lastError = "registerDexRules failed: ${it.message}"
                AcidLogger.e(TAG, "registerDexRules error for ${state.feature.id}", it)
            }
        }
    }

    fun initAll() {
        for (state in states) {
            runCatching {
                state.feature.onInit()
                state.initialized = true
                AcidLogger.i(TAG, "initialized feature: ${state.feature.id}")
            }.onFailure {
                state.lastError = "onInit failed: ${it.message}"
                AcidLogger.e(TAG, "onInit failed for ${state.feature.id}", it)
            }
        }
    }

    fun isEnabled(feature: AcidFeature): Boolean =
        ConfigStore.isEffective(feature.configKey, feature.defaultEnabled, feature.dependsOn)

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val state = states.firstOrNull { it.feature.id == id } ?: return false
        ConfigStore.putBoolean(state.feature.configKey, enabled)
        AcidLogger.i(TAG, "toggled feature $id -> $enabled")
        return true
    }

    fun recordHit(id: String) {
        states.firstOrNull { it.feature.id == id }?.hitCount?.incrementAndGet()
    }

    fun recordError(id: String, err: Throwable) {
        states.firstOrNull { it.feature.id == id }?.lastError = "${err.javaClass.simpleName}: ${err.message}"
    }

    fun allStates(): List<FeatureState> = states.toList()

    fun snapshotJson(): JSONArray {
        val arr = JSONArray()
        for (st in states) {
            val f = st.feature
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("category", f.category.id)
                put("group", f.group)
                put("description", f.description)
                put("configKey", f.configKey)
                put("enabled", isEnabled(f))
                put("initialized", st.initialized)
                put("hitCount", st.hitCount.get())
                put("lastError", st.lastError ?: JSONObject.NULL)
                put("extra", runCatching { f.statusJson() }.getOrElse { JSONObject() })
            })
        }
        return arr
    }
}
