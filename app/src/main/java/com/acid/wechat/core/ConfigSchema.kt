package com.acid.wechat.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Authoritative declarative schema for `config.json` (Single Source of Truth).
 * Every persistent setting's key, type, default value, validation/sanitization rule,
 * UI placement, and Agent prompt metadata is defined here once.
 */
object ConfigSchema {
    sealed class SettingSpec<T : Any>(
        val key: String,
        open val defaultValue: T,
        val category: FeatureCategory,
        val group: String,
        val title: String,
        val description: String,
        val dependsOn: String? = null,
        val showInUi: Boolean = true,
        val isSystem: Boolean = false,
    ) {
        abstract val typeName: String
        abstract fun parseRaw(raw: Any?): T?
        abstract fun sanitize(value: T): T
        fun resolve(raw: Any?): T = parseRaw(raw)?.let(::sanitize) ?: defaultValue
        abstract fun schemaTypeDescription(): String

        open fun toSchemaJson(): JSONObject = JSONObject().apply {
            put("key", key)
            put("type", typeName)
            put("default", defaultValue)
            put("title", title)
            put("description", description)
            put("category", category.id)
            put("group", group)
            put("dependsOn", dependsOn ?: JSONObject.NULL)
            put("showInUi", showInUi)
        }
    }

    class BoolSpec(
        key: String,
        override val defaultValue: Boolean,
        category: FeatureCategory,
        group: String,
        title: String,
        description: String,
        dependsOn: String? = null,
        showInUi: Boolean = true,
        isSystem: Boolean = false,
    ) : SettingSpec<Boolean>(key, defaultValue, category, group, title, description, dependsOn, showInUi, isSystem) {
        override val typeName = "boolean"
        override fun parseRaw(raw: Any?): Boolean? = when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }

        override fun sanitize(value: Boolean): Boolean = value
        override fun schemaTypeDescription(): String = "boolean (default $defaultValue)"
    }

    class IntSpec(
        key: String,
        override val defaultValue: Int,
        category: FeatureCategory,
        group: String,
        title: String,
        description: String,
        val min: Int = Int.MIN_VALUE,
        val max: Int = Int.MAX_VALUE,
        dependsOn: String? = null,
        showInUi: Boolean = false,
        isSystem: Boolean = false,
    ) : SettingSpec<Int>(key, defaultValue, category, group, title, description, dependsOn, showInUi, isSystem) {
        override val typeName = "integer"
        override fun parseRaw(raw: Any?): Int? = when (raw) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Number -> raw.toDouble()
                .takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }
                ?.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }

        override fun sanitize(value: Int): Int = value.coerceIn(min, max)
        override fun schemaTypeDescription(): String = "integer ($min..$max, default $defaultValue)"
        override fun toSchemaJson(): JSONObject = super.toSchemaJson().apply {
            put("min", min)
            put("max", max)
        }
    }

    class StringSpec(
        key: String,
        override val defaultValue: String,
        category: FeatureCategory,
        group: String,
        title: String,
        description: String,
        val placeholder: String = defaultValue,
        val allowEmpty: Boolean = false,
        dependsOn: String? = null,
        showInUi: Boolean = true,
        isSystem: Boolean = false,
    ) : SettingSpec<String>(key, defaultValue, category, group, title, description, dependsOn, showInUi, isSystem) {
        override val typeName = "string"
        override fun parseRaw(raw: Any?): String? = when (raw) {
            is String -> raw
            is Number, is Boolean -> raw.toString()
            else -> null
        }

        override fun sanitize(value: String): String =
            if (!allowEmpty && value.isBlank()) defaultValue else value

        override fun schemaTypeDescription(): String = "string (default \"$defaultValue\")"
        override fun toSchemaJson(): JSONObject = super.toSchemaJson().apply {
            put("allowEmpty", allowEmpty)
        }
    }

    private val registry = ArrayList<SettingSpec<*>>()
    private fun <T : Any, S : SettingSpec<T>> register(spec: S): S = spec.also { registry.add(it) }

    val ANTI_RECALL = register(BoolSpec(
        "anti_recall", true, FeatureCategory.CHAT, "防止撤回", "消息防撤回",
        "拦截对方撤回消息并保留原内容",
    ))
    val ANTI_RECALL_NOTICE = register(BoolSpec(
        "anti_recall_notice", true, FeatureCategory.CHAT, "防止撤回", "撤回本地提示",
        "拦截撤回后在聊天内插入本地提示", dependsOn = ANTI_RECALL.key,
    ))
    val ANTI_RECALL_NOTICE_TEXT = register(StringSpec(
        "anti_recall_notice_text", "「{name}」尝试撤回一条消息（已拦截）", FeatureCategory.CHAT, "防止撤回", "撤回提示文案",
        "撤回提示模板，支持 {name} 与 \\n", dependsOn = ANTI_RECALL_NOTICE.key,
    ))
    val CUSTOM_JSB = register(BoolSpec(
        "custom_jsb", true, FeatureCategory.CHAT, "动画表情", "自定义剪刀石头布",
        "发送猜拳前弹出自定义选择窗",
    ))
    val CUSTOM_DICE = register(BoolSpec(
        "custom_dice", true, FeatureCategory.CHAT, "动画表情", "自定义骰子",
        "发送骰子前弹出自定义选择窗",
    ))
    val ANTI_MOMENTS_DELETE = register(BoolSpec(
        "anti_moments_delete", true, FeatureCategory.MOMENTS, "防删除", "朋友圈防删除",
        "拦截好友删除朋友圈并添加自定义前缀",
    ))
    val MOMENTS_DELETE_PREFIX = register(StringSpec(
        "moments_delete_prefix", "❌\\n", FeatureCategory.MOMENTS, "防删除", "删除前缀",
        "防删除朋友圈前缀文案，支持 \\n", dependsOn = ANTI_MOMENTS_DELETE.key,
    ))
    val FAKE_LIKE_ENABLED = register(BoolSpec(
        "fake_like_enabled", true, FeatureCategory.MOMENTS, "伪集赞", "朋友圈伪集赞",
        "长按朋友圈菜单增加「伪集赞」，支持自选好友或随机指定人数",
    ))
    val FAKE_COMMENT_ENABLED = register(BoolSpec(
        "fake_comment_enabled", true, FeatureCategory.MOMENTS, "伪评论", "朋友圈伪评论",
        "长按朋友圈菜单增加「伪评论」，指定好友与内容添加到本地评论区",
    ))
    val SNS_TAIL_ENABLED = register(BoolSpec(
        "sns_tail_enabled", false, FeatureCategory.MOMENTS, "小尾巴", "朋友圈小尾巴",
        "发布朋友圈时自动附带自定义机型或来源尾巴",
    ))
    val SNS_TAIL_TEXT = register(StringSpec(
        "sns_tail_text", "来自 iPhone 16 Pro Max", FeatureCategory.MOMENTS, "小尾巴", "尾巴内容",
        "朋友圈小尾巴显示文案", dependsOn = SNS_TAIL_ENABLED.key,
    ))
    val SNS_TAIL_AS_LOCATION = register(BoolSpec(
        "sns_tail_as_location", true, FeatureCategory.MOMENTS, "小尾巴", "作为位置来源显示",
        "优先作为位置来源行显示，未启用时附加到正文末尾", dependsOn = SNS_TAIL_ENABLED.key,
    ))
    val CUSTOM_SAVE_PATH_ENABLED = register(BoolSpec(
        "custom_save_path_enabled", false, FeatureCategory.ME, "保存路径", "自定义保存路径",
        "调整微信保存文件、图片和视频的导出目录",
    ))
    val SAVE_FILE_DIR = register(StringSpec(
        "save_file_dir", "Download/AcidWeChat", FeatureCategory.ME, "保存路径", "文件保存目录",
        "文件导出目录，如 Download/AcidWeChat",
        dependsOn = CUSTOM_SAVE_PATH_ENABLED.key,
    ))
    val SAVE_MEDIA_DIR = register(StringSpec(
        "save_media_dir", "Pictures/AcidWeChat", FeatureCategory.ME, "保存路径", "图片视频目录",
        "图片视频导出目录，如 Pictures/AcidWeChat",
        dependsOn = CUSTOM_SAVE_PATH_ENABLED.key,
    ))
    val DEBUG_SERVER = register(BoolSpec(
        "debug_server", true, FeatureCategory.ME, "系统调试", "本地调试接口",
        "开启 127.0.0.1 本地调试与配置接口（重启微信生效）", isSystem = true,
    ))
    val DEBUG_PORT = register(IntSpec(
        "debug_port", 19898, FeatureCategory.ME, "系统调试", "调试服务端口",
        "本地调试接口监听端口（重启微信生效）", min = 1024, max = 65535,
        dependsOn = DEBUG_SERVER.key, showInUi = false, isSystem = true,
    ))

    val ALL_SPECS: List<SettingSpec<*>> = registry.toList()
    val byKey: Map<String, SettingSpec<*>> = ALL_SPECS.associateBy { it.key }
    val retiredKeys: Set<String> = setOf(
        "anti_recall_self",
        "anti_moments_comment",
        "fake_like_float_button",
        "float_btn_y_ratio",
        "float_btn_right_side",
    )

    fun systemUiSpecs(): List<SettingSpec<*>> = ALL_SPECS.filter { it.isSystem && it.showInUi }

    fun describeJsonSchemaForPrompt(configPath: String): String = buildString {
        append("Settings SSOT file: ").append(configPath).append(". ")
        append("Direct edits to this JSON live-reload automatically into running hooks without restarting WeChat ")
        append("(except debug_server/debug_port). Schema: ")
        ALL_SPECS.forEachIndexed { idx, spec ->
            if (idx > 0) append(", ")
            append('`').append(spec.key).append("`: ").append(spec.schemaTypeDescription())
            spec.dependsOn?.let { append(" [dependsOn `").append(it).append("`]") }
        }
        append('.')
    }

    fun schemaSnapshotJson(configFile: File): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("configFile", configFile.absolutePath)
        put("hotReload", true)
        put("promptSummary", describeJsonSchemaForPrompt(configFile.absolutePath))
        put("fields", JSONArray().apply { ALL_SPECS.forEach { put(it.toSchemaJson()) } })
    }
}
