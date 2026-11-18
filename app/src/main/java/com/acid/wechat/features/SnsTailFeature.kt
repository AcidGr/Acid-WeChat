package com.acid.wechat.features

import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.wechat.SnsProto
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moments Tail / Source Identifier feature (`朋友圈 -> 小尾巴`):
 * Hooks `UploadPackHelper.commit()` right before packing `TimeLineObject` into `SnsInfo`.
 * Supports attaching the custom device/source label as the Moments location/source line
 * (`TimeLineObject.Location.PoiName`) when no real GPS location was chosen, or as a
 * text suffix appended to `ContentDesc`.
 */
object SnsTailFeature : AcidFeature {
    private const val TAG = "SnsTail"
    private const val TIMELINE_OBJ_CLS = "com.tencent.mm.protocal.protobuf.TimeLineObject"

    private lateinit var ruleUploadPackCommit: DexKitLocator.DexRule
    private val appliedCount = AtomicInteger(0)

    @Volatile
    private var cachedTimelineField: Field? = null

    override val id = "sns_tail"
    override val name = "朋友圈小尾巴"
    override val category = FeatureCategory.MOMENTS
    override val group = "小尾巴"
    override val description = "发布朋友圈时自动附带自定义机型或来源尾巴"
    override val configKey = ConfigStore.KEY_SNS_TAIL_ENABLED
    override val defaultEnabled = false

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption(
            title = "尾巴内容",
            configKey = ConfigStore.KEY_SNS_TAIL_TEXT,
            group = group,
            type = FeatureOption.TYPE_TEXT,
            defaultText = ConfigStore.DEFAULT_SNS_TAIL_TEXT,
            placeholder = ConfigStore.DEFAULT_SNS_TAIL_TEXT,
            dependsOn = ConfigStore.KEY_SNS_TAIL_ENABLED,
        ),
        FeatureOption(
            title = "作为位置来源显示",
            configKey = ConfigStore.KEY_SNS_TAIL_AS_LOCATION,
            group = group,
            defaultValue = true,
            dependsOn = ConfigStore.KEY_SNS_TAIL_ENABLED,
        ),
    )

    override fun registerDexRules() {
        ruleUploadPackCommit = DexKitLocator.registerMethod(
            id = "sns_upload_pack_commit",
            description = "UploadPackHelper.commit()",
        ) {
            matcher {
                usingEqStrings("commit", "com.tencent.mm.plugin.sns.model.UploadPackHelper")
            }
        }
    }

    override fun onInit() {
        val commitMethod = ruleUploadPackCommit.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(commitMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@SnsTailFeature)) return
                    val packHelper = param.thisObject ?: return
                    applyTail(packHelper)
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hook UploadPackHelper.commit failed", it)
        }
    }

    private fun applyTail(packHelper: Any) {
        val rawTail = ConfigStore.getString(ConfigStore.KEY_SNS_TAIL_TEXT, ConfigStore.DEFAULT_SNS_TAIL_TEXT)
        val decodedTail = ConfigStore.decodeNewlines(rawTail).trim()
        if (decodedTail.isEmpty()) return

        runCatching {
            val tlField = resolveTimelineField(packHelper.javaClass) ?: return
            val tlObj = tlField.get(packHelper) ?: return
            val asLocation = ConfigStore.isEffective(
                ConfigStore.KEY_SNS_TAIL_AS_LOCATION,
                true,
                ConfigStore.KEY_SNS_TAIL_ENABLED,
                parentDefault = false,
            )

            var applied = false
            if (asLocation) {
                applied = tryApplyLocationTail(tlObj, decodedTail.replace('\n', ' '))
            }
            if (!applied) {
                applied = tryApplyDescTail(tlObj, decodedTail)
            }
            if (applied) {
                appliedCount.incrementAndGet()
                FeatureRegistry.recordHit(id)
                AcidLogger.i(TAG, "attached moments tail (asLocation=$asLocation)")
            }
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "applyTail failed", it)
        }
    }

    private fun tryApplyLocationTail(tlObj: Any, tailLabel: String): Boolean {
        val locField = ReflectHelper.findField(tlObj.javaClass, "Location") ?: return false
        val existingLoc = locField.get(tlObj)
        if (existingLoc != null) {
            val bytes = runCatching {
                ReflectHelper.findMethod(existingLoc.javaClass, "toByteArray", emptyList())?.invoke(existingLoc) as? ByteArray
            }.getOrNull()
            val existingPoi = SnsProto.readLocationPoi(bytes)?.trim()
            if (!existingPoi.isNullOrEmpty()) {
                return false
            }
        }
        val locInstance = existingLoc ?: runCatching { locField.type.getDeclaredConstructor().newInstance() }.getOrNull()
            ?: return false
        val parseMethod = ReflectHelper.findMethod(locInstance.javaClass, "parseFrom", listOf(ByteArray::class.java))
            ?: return false
        parseMethod.invoke(locInstance, SnsProto.encodeLocationPoi(tailLabel))
        locField.set(tlObj, locInstance)
        return true
    }

    private fun tryApplyDescTail(tlObj: Any, tail: String): Boolean {
        val descField = ReflectHelper.findField(tlObj.javaClass, "ContentDesc") ?: return false
        val oldDesc = (descField.get(tlObj) as? String).orEmpty()
        if (oldDesc.trimEnd().endsWith(tail)) return false
        val nextDesc = if (oldDesc.isBlank()) tail else "${oldDesc.trimEnd()}\n\n$tail"
        descField.set(tlObj, nextDesc)
        return true
    }

    private fun resolveTimelineField(helperCls: Class<*>): Field? {
        cachedTimelineField?.let { return it }
        var cur: Class<*>? = helperCls
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type.name == TIMELINE_OBJ_CLS) {
                    f.isAccessible = true
                    cachedTimelineField = f
                    return f
                }
            }
            cur = cur.superclass
        }
        return null
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("appliedCount", appliedCount.get())
        put("tailText", ConfigStore.getString(ConfigStore.KEY_SNS_TAIL_TEXT, ConfigStore.DEFAULT_SNS_TAIL_TEXT))
        put("asLocation", ConfigStore.getBoolean(ConfigStore.KEY_SNS_TAIL_AS_LOCATION, true))
    }
}
