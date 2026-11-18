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

        fun from(spec: ConfigSchema.SettingSpec<*>): FeatureOption = when (spec) {
            is ConfigSchema.BoolSpec -> FeatureOption(
                title = spec.title,
                configKey = spec.key,
                group = spec.group,
                type = TYPE_SWITCH,
                defaultValue = spec.defaultValue,
                dependsOn = spec.dependsOn,
            )
            is ConfigSchema.StringSpec -> FeatureOption(
                title = spec.title,
                configKey = spec.key,
                group = spec.group,
                type = TYPE_TEXT,
                defaultText = spec.defaultValue,
                placeholder = spec.placeholder,
                dependsOn = spec.dependsOn,
            )
            is ConfigSchema.IntSpec -> FeatureOption(
                title = spec.title,
                configKey = spec.key,
                group = spec.group,
                type = TYPE_TEXT,
                defaultText = spec.defaultValue.toString(),
                placeholder = spec.defaultValue.toString(),
                dependsOn = spec.dependsOn,
            )
        }
    }
}

/**
 * Base contract for pluggable Acid-WeChat features.
 */
interface AcidFeature {
    val id: String
    val spec: ConfigSchema.BoolSpec? get() = null
    val name: String get() = spec?.title ?: id
    val category: FeatureCategory get() = spec?.category ?: FeatureCategory.CHAT
    val group: String get() = spec?.group ?: "通用"
    val description: String get() = spec?.description ?: ""
    val configKey: String get() = spec?.key ?: id
    val defaultEnabled: Boolean get() = spec?.defaultValue ?: true
    val dependsOn: String? get() = spec?.dependsOn

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
        val state = states.firstOrNull { it.feature.id == id }
        if (state != null) {
            ConfigStore.putBoolean(state.feature.configKey, enabled)
            AcidLogger.i(TAG, "toggled feature $id -> $enabled")
            return true
        }
        val opt = states.asSequence()
            .flatMap { it.feature.extraOptions().asSequence() }
            .firstOrNull { it.type == FeatureOption.TYPE_SWITCH && it.configKey == id }
            ?: return false
        ConfigStore.putBoolean(opt.configKey, enabled)
        AcidLogger.i(TAG, "toggled option $id -> $enabled")
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
        ConfigStore.reloadIfModified(force = true)
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
                put("options", JSONArray().apply {
                    for (opt in f.extraOptions()) {
                        put(JSONObject().apply {
                            put("title", opt.title)
                            put("configKey", opt.configKey)
                            put("group", opt.group)
                            put("type", opt.type)
                            put("dependsOn", opt.dependsOn ?: JSONObject.NULL)
                            if (opt.type == FeatureOption.TYPE_TEXT) {
                                put("value", ConfigStore.getString(opt.configKey, opt.defaultText))
                            } else {
                                put("enabled", ConfigStore.getBoolean(opt.configKey, opt.defaultValue))
                            }
                        })
                    }
                })
                put("extra", runCatching { f.statusJson() }.getOrElse { JSONObject() })
            })
        }
        return arr
    }
}
