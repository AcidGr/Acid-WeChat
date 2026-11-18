package com.acid.wechat.ui

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Injects the "Acid" settings entry into the top-right action bar of WeChat's Settings page
 * (Me -> Settings -> Top-right "Acid"). Supports both 8.0.80+ MainSettingsUI and legacy SettingsUI.
 */
object SettingsHook {
    private const val TAG = "SettingsHook"
    private const val NEW_BASE_SETTING_CLASS = "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
    private const val NEW_SETTING_CLASS = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
    private const val OLD_SETTING_CLASS = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
    private const val MENU_ITEM_ID = 0x41434944

    fun init() {
        hookClass(NEW_BASE_SETTING_CLASS, filterClassName = NEW_SETTING_CLASS)
        hookClass(OLD_SETTING_CLASS, filterClassName = null)
    }

    private fun hookClass(className: String, filterClassName: String?) {
        val cls = runCatching {
            Class.forName(className, false, HostContext.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (filterClassName != null && activity.javaClass.name != filterClassName) {
                            return
                        }
                        injectMenuOption(activity)
                    }
                },
            )
            AcidLogger.i(TAG, "hooked Settings entry on $className")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking $className.onCreate", it)
        }
    }

    private fun injectMenuOption(activity: Activity) {
        runCatching {
            val addMethod = ReflectHelper.findMethod(
                activity.javaClass,
                "addTextOptionMenu",
                listOf(
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    MenuItem.OnMenuItemClickListener::class.java,
                ),
            ) ?: error("addTextOptionMenu(int, String, OnMenuItemClickListener) not found on ${activity.javaClass.name}")
            val listener = MenuItem.OnMenuItemClickListener {
                SettingsDialog.show(activity)
                true
            }
            addMethod.invoke(activity, MENU_ITEM_ID, "Acid", listener)
            AcidLogger.i(TAG, "injected top-right 'Acid' button on ${activity.javaClass.simpleName}")
        }.onFailure {
            AcidLogger.w(TAG, "injectMenuOption failed", it)
        }
    }
}
