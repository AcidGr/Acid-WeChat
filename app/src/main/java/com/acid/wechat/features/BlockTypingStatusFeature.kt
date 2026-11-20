package com.acid.wechat.features

import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Blocks outgoing typing-status reporting at `SignallingComponent.doDirectSend(int)`,
 * covering both legacy `NetSceneDirectSend` and new `MMTypingSend` protocols without
 * affecting incoming typing indicators.
 */
object BlockTypingStatusFeature : AcidFeature {
    private const val TAG = "BlockTypingStatus"

    override val id = ConfigSchema.BLOCK_TYPING_STATUS.key
    override val spec = ConfigSchema.BLOCK_TYPING_STATUS

    private lateinit var ruleDoDirectSend: DexKitLocator.DexRule
    @Volatile private var hookedTarget: String? = null

    override fun registerDexRules() {
        ruleDoDirectSend = DexKitLocator.registerMethod(
            id = "block_typing_do_direct_send_v1",
            description = "SignallingComponent.doDirectSend(int)",
        ) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                usingEqStrings(
                    "MicroMsg.SignallingComponent",
                    "[doDirectSend] mChattingContext is null!",
                )
                returnType = "void"
                paramTypes("int")
            }
        }
    }

    override fun onInit() {
        val method = ruleDoDirectSend.resolveMethod()
        if (method == null) {
            AcidLogger.w(TAG, "SignallingComponent.doDirectSend not resolved")
            return
        }
        if (!isExpectedMethod(method)) {
            AcidLogger.w(TAG, "unexpected method signature for ${ruleDoDirectSend.id}: ${method.toGenericString()}")
            return
        }
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!FeatureRegistry.isEnabled(this@BlockTypingStatusFeature)) return
                        param.result = null
                        FeatureRegistry.recordHit(id)
                    }.onFailure {
                        FeatureRegistry.recordError(id, it)
                        AcidLogger.w(TAG, "doDirectSend hook callback failed", it)
                    }
                }
            })
            val targetName = "${method.declaringClass.name}.${method.name}"
            hookedTarget = targetName
            AcidLogger.i(TAG, "hooked ${ruleDoDirectSend.id}: $targetName")
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.e(TAG, "failed hooking SignallingComponent.doDirectSend", it)
        }
    }

    private fun isExpectedMethod(method: Method): Boolean {
        val params = method.parameterTypes
        return method.returnType == Void.TYPE &&
            params.size == 1 &&
            params[0] == Int::class.javaPrimitiveType &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("hooked", hookedTarget != null)
        put("target", hookedTarget ?: JSONObject.NULL)
    }
}
