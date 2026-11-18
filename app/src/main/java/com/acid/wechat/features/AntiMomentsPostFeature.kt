package com.acid.wechat.features

import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.ProtoLite
import com.acid.wechat.core.WcdbBridge
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Intercepts moments deletion via WCDB updateWithOnConflict on SnsInfo:
 * drops sourceType=0 (WeChat soft-delete marker) and prepends customizable prefix (default "❌\n") to protobuf field 5.
 */
object AntiMomentsPostFeature : AcidFeature {
    private const val TAG = "AntiMomentsPost"
    private const val TBL_SNS_INFO = "SnsInfo"

    private val validSnsTypes = setOf(1, 2, 3, 4, 5, 9, 10, 12, 13, 14, 15, 18, 19, 26, 28, 30, 34, 36, 41, 42, 47, 54)
    private val interceptedCount = AtomicInteger(0)

    override val id = "anti_moments_delete"
    override val name = "朋友圈防删除"
    override val category = FeatureCategory.MOMENTS
    override val group = "防删除"
    override val description = "拦截好友删除朋友圈并添加自定义前缀"
    override val configKey = ConfigStore.KEY_ANTI_MOMENTS_DELETE

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption(
            title = "删除前缀",
            configKey = ConfigStore.KEY_MOMENTS_DELETE_PREFIX,
            group = group,
            type = FeatureOption.TYPE_TEXT,
            defaultText = ConfigStore.DEFAULT_MOMENTS_DELETE_PREFIX,
            placeholder = ConfigStore.DEFAULT_MOMENTS_DELETE_PREFIX,
            dependsOn = ConfigStore.KEY_ANTI_MOMENTS_DELETE,
        ),
    )

    override fun onInit() {
        WcdbBridge.addUpdateListener { table, values, _, _, _ ->
            if (table != TBL_SNS_INFO) return@addUpdateListener
            if (!FeatureRegistry.isEnabled(this@AntiMomentsPostFeature)) return@addUpdateListener

            runCatching {
                val typeVal = values.get("type") as? Int ?: return@runCatching
                val sourceVal = values.get("sourceType") as? Int ?: return@runCatching
                if (typeVal !in validSnsTypes || sourceVal != 0) return@runCatching

                values.remove("sourceType")

                val rawPrefix = ConfigStore.getString(
                    ConfigStore.KEY_MOMENTS_DELETE_PREFIX,
                    ConfigStore.DEFAULT_MOMENTS_DELETE_PREFIX,
                ).ifEmpty { ConfigStore.DEFAULT_MOMENTS_DELETE_PREFIX }
                val prefix = ConfigStore.decodeNewlines(rawPrefix)
                val emptyFallback = prefix.trimEnd('\n', '\r').ifEmpty { prefix }

                val contentBytes = values.getAsByteArray("content")
                if (contentBytes != null && contentBytes.isNotEmpty()) {
                    val patched = ProtoLite.rewriteUtf8Field(contentBytes, 5) { text ->
                        if (text.startsWith(prefix) || text == emptyFallback || text.contains("[拦截删除]")) {
                            null
                        } else if (text.isEmpty()) {
                            emptyFallback
                        } else {
                            "$prefix$text"
                        }
                    }
                    if (patched != null) {
                        values.put("content", patched)
                    }
                }

                interceptedCount.incrementAndGet()
                FeatureRegistry.recordHit(id)
                AcidLogger.i(TAG, "intercepted SnsInfo delete (type=$typeVal)")
            }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "failed handling SnsInfo update", it)
            }
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("interceptedCount", interceptedCount.get())
    }
}
