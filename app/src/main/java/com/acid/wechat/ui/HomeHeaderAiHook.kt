package com.acid.wechat.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.HostContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Injects a Global Agent "AI" button to the left of the search (magnifying glass) and "+" buttons
 * in WeChat's main `LauncherUI` ActionBar.
 */
object HomeHeaderAiHook {
    private const val TAG = "HomeHeaderAiHook"
    private const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
    private const val AI_HOME_MENU_ID = 0x414948

    fun init() {
        val launcherCls = runCatching {
            Class.forName(LAUNCHER_UI_CLASS, false, HostContext.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                launcherCls,
                "onCreateOptionsMenu",
                Menu::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!ConfigStore.getBoolean(ConfigSchema.GLOBAL_AGENT_ENTRY)) return
                            val activity = param.thisObject as? Activity ?: return
                            val menu = param.args?.firstOrNull() as? Menu ?: return
                            if (menu.findItem(AI_HOME_MENU_ID) != null) return
                            // Insert at order=0 before LauncherUI adds search (order=0) and plus (order=0)
                            // so AI sits to the left of the magnifying glass.
                            val item = menu.add(0, AI_HOME_MENU_ID, 0, "AI")
                            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            item.actionView = buildAiActionView(activity)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val menu = param.args?.firstOrNull() as? Menu ?: return
                            val enabled = ConfigStore.getBoolean(ConfigSchema.GLOBAL_AGENT_ENTRY)
                            val shown = (param.result as? Boolean) == true
                            // Remove if HomeUI skipped adding main-tab icons (e.g. ChattingUI foreground)
                            // or if user toggled off the Global AI button.
                            if (!enabled || !shown || menu.size() < 2) {
                                menu.removeItem(AI_HOME_MENU_ID)
                            }
                        }
                    }
                },
            )
            AcidLogger.i(TAG, "hooked $LAUNCHER_UI_CLASS.onCreateOptionsMenu for Global AI button")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking LauncherUI.onCreateOptionsMenu", it)
        }
    }

    private fun buildAiActionView(activity: Activity): TextView {
        val density = activity.resources.displayMetrics.density
        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val fgColor = if (isDark) Color.parseColor("#E6FFFFFF") else Color.parseColor("#E6181818")
        val tv = TextView(activity).apply {
            text = "AI"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(fgColor)
            val hPad = (10f * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            minWidth = (40f * density).toInt()
            minHeight = (44f * density).toInt()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            val rippleOut = TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleOut, true)) {
                setBackgroundResource(rippleOut.resourceId)
            }
            contentDescription = "全局 AI 助手"
            setOnClickListener {
                AgentDialog.show(activity)
            }
        }
        return tv
    }
}
