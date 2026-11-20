package com.acid.wechat.features

import android.view.View
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Forces WeChat into tablet device mode (`我的 -> 平板模式`) and reveals the
 * "login as other device" button on the login screen.
 */
object ForceTabletModeFeature : AcidFeature {
    private const val TAG = "ForceTabletMode"

    private lateinit var ruleIsP8Pad: DexKitLocator.DexRule
    private lateinit var ruleInTabletEnvImpl: DexKitLocator.DexRule
    private lateinit var ruleInTabletEnvEntry: DexKitLocator.DexRule
    private lateinit var ruleOtherDeviceLoginBtn: DexKitLocator.DexRule

    private val hookedCount = AtomicInteger(0)
    private val tabletHits = AtomicInteger(0)
    private val loginBtnHits = AtomicInteger(0)

    override val id = "force_tablet_mode"
    override val spec = ConfigSchema.FORCE_TABLET_MODE

    override fun registerDexRules() {
        ruleIsP8Pad = DexKitLocator.registerMethod(
            id = "tablet_is_p8_pad_v1",
            description = "Pad device model check (Lenovo TB-9707F)",
        ) {
            matcher {
                usingEqStrings("Lenovo TB-9707F")
                returnType = "boolean"
                paramCount(0)
            }
        }
        ruleInTabletEnvImpl = DexKitLocator.registerMethod(
            id = "tablet_in_env_impl_v1",
            description = "WeChat UIUtils inTabletEnv implementation",
        ) {
            matcher {
                usingEqStrings(
                    "MicroMsg.UIUtils",
                    "inTabletEnv, isP8Pad, return true",
                    "inTabletEnv, isFoldableDevice, return false",
                )
                returnType = "boolean"
            }
        }
        ruleInTabletEnvEntry = DexKitLocator.registerMethod(
            id = "tablet_in_env_entry_v1",
            description = "WeChat UIUtils inTabletEnv() static entry",
        ) {
            matcher {
                modifiers(Modifier.STATIC)
                returnType = "boolean"
                paramCount(0)
                addInvoke {
                    usingEqStrings("MicroMsg.UIUtils", "inTabletEnv, isP8Pad, return true")
                }
            }
        }
        ruleOtherDeviceLoginBtn = DexKitLocator.registerMethod(
            id = "tablet_other_device_login_btn_v1",
            description = "LoginUtil.updateLoginAsExdevice(View, LifecycleOwner)",
        ) {
            matcher {
                usingEqStrings("loginAsOtherDeviceBtn")
                paramCount(2)
            }
        }
    }

    override fun onInit() {
        hookBooleanTrue(ruleIsP8Pad)
        hookBooleanTrue(ruleInTabletEnvImpl)
        hookBooleanTrue(ruleInTabletEnvEntry)
        hookOtherDeviceLoginButton()
    }

    private fun hookBooleanTrue(rule: DexKitLocator.DexRule) {
        val m = rule.resolveMethod() ?: return
        if (m.returnType != Boolean::class.javaPrimitiveType) {
            AcidLogger.w(TAG, "skip non-boolean method for ${rule.id}: ${m.declaringClass.name}.${m.name} -> ${m.returnType}")
            return
        }
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@ForceTabletModeFeature)) return
                    param.result = true
                    tabletHits.incrementAndGet()
                    FeatureRegistry.recordHit(id)
                }
            })
            hookedCount.incrementAndGet()
            AcidLogger.i(TAG, "hooked ${rule.id}: ${m.declaringClass.name}.${m.name}")
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookBooleanTrue failed for ${rule.id}", it)
        }
    }

    private fun hookOtherDeviceLoginButton() {
        val m = ruleOtherDeviceLoginBtn.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@ForceTabletModeFeature)) return
                    val btn = param.args?.getOrNull(0) as? View ?: return
                    if (btn.visibility != View.VISIBLE) {
                        btn.visibility = View.VISIBLE
                        loginBtnHits.incrementAndGet()
                        FeatureRegistry.recordHit(id)
                        AcidLogger.i(TAG, "forced loginAsOtherDeviceBtn VISIBLE")
                    }
                }
            })
            hookedCount.incrementAndGet()
            AcidLogger.i(TAG, "hooked ${ruleOtherDeviceLoginBtn.id}: ${m.declaringClass.name}.${m.name}")
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookOtherDeviceLoginButton failed", it)
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("hookedCount", hookedCount.get())
        put("tabletHits", tabletHits.get())
        put("loginBtnHits", loginBtnHits.get())
    }
}
