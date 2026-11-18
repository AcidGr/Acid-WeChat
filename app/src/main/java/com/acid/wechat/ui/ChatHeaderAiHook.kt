package com.acid.wechat.ui

import android.app.Activity
import android.view.MenuItem
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.wechat.ChatRepository
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Injects an "AI" button to the left of the top-right "..." (chat info) menu button
 * inside WeChat's `ChattingUI` header (`HeaderComponent`).
 * Dynamically resolves the active `talker` on click because WeChat reuses `ChattingUIFragment` across chats.
 */
object ChatHeaderAiHook {
    private const val TAG = "ChatHeaderAiHook"
    private const val AI_MENU_ID = 0x4149 // "AI"
    private const val FRAGMENT_BASE_CLASS = "com.tencent.mm.ui.chatting.BaseChattingUIFragment"

    private lateinit var ruleHeaderUpdateMenu: DexKitLocator.DexRule
    private lateinit var ruleChattingCtxGetTalker: DexKitLocator.DexRule
    private lateinit var ruleChattingCtxGetNick: DexKitLocator.DexRule

    @Volatile private var ctxFieldOnHeader: Field? = null
    @Volatile private var fragmentFieldOnCtx: Field? = null
    @Volatile private var findMenuInfoMethod: Method? = null
    @Volatile private var addTextOptionMenuMethod: Method? = null
    @Volatile private var removeOptionMenuMethod: Method? = null
    @Volatile private var updateOptionMenuTextMethod: Method? = null

    private val latestCtxByFragment = ConcurrentHashMap<Int, WeakReference<Any>>()

    fun registerDexRules() {
        ruleHeaderUpdateMenu = DexKitLocator.registerMethod(
            id = "chat_header_update_menu_v1",
            description = "HeaderComponent.updateMenuItem() in ChattingUI",
        ) {
            searchPackages("com.tencent.mm.ui.chatting.component")
            matcher {
                declaredClass {
                    usingStrings("MicroMsg.ChattingUI.HeaderComponent", "clicfg_android_open_wxpay_search is open2")
                }
                usingStrings("[setRightTopBtn] isEntranceShowEnabled true need open")
                returnType = "void"
                params { count(0) }
            }
        }
        ruleChattingCtxGetTalker = DexKitLocator.registerMethod(
            id = "chatting_ctx_get_talker_v1",
            description = "ChattingContext.getTalkerUserName()",
        ) {
            matcher {
                declaredClass { usingStrings("MicroMsg.ChattingContext") }
                usingStrings("MicroMsg.ChattingContext", "getTalker returns null.")
                returnType = "java.lang.String"
                params { count(0) }
            }
        }
        ruleChattingCtxGetNick = DexKitLocator.registerMethod(
            id = "chatting_ctx_get_nick_v1",
            description = "ChattingContext.getTalkerNickName()",
        ) {
            matcher {
                declaredClass { usingStrings("MicroMsg.ChattingContext") }
                usingStrings("getTalkerNickName")
                returnType = "java.lang.String"
                params { count(0) }
            }
        }
    }

    fun init(isFeatureEnabled: () -> Boolean) {
        val updateMenuMethod = ruleHeaderUpdateMenu.resolveMethod()
        val getTalkerMethod = ruleChattingCtxGetTalker.resolveMethod()
        val getNickMethod = ruleChattingCtxGetNick.resolveMethod()
        if (updateMenuMethod == null || getTalkerMethod == null) {
            AcidLogger.w(TAG, "ChatHeaderAiHook skipped: DexKit resolution incomplete")
            return
        }

        val ctxCls = getTalkerMethod.declaringClass
        ctxFieldOnHeader = findFieldByType(updateMenuMethod.declaringClass, ctxCls)
        val baseFragCls = runCatching { Class.forName(FRAGMENT_BASE_CLASS, false, HostContext.classLoader) }.getOrNull()
        if (baseFragCls != null) {
            fragmentFieldOnCtx = findFieldByType(ctxCls, baseFragCls)
        }
        if (ctxFieldOnHeader == null || fragmentFieldOnCtx == null) {
            AcidLogger.w(TAG, "ChatHeaderAiHook skipped: context/fragment fields not resolved")
            return
        }

        runCatching {
            XposedBridge.hookMethod(updateMenuMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val header = param.thisObject ?: return
                        val ctx = ctxFieldOnHeader?.get(header) ?: return
                        val fragment = fragmentFieldOnCtx?.get(ctx) ?: return
                        latestCtxByFragment[System.identityHashCode(fragment)] = WeakReference(ctx)
                        val talker = (getTalkerMethod.invoke(ctx) as? String)?.trim().orEmpty()
                        if (talker.isEmpty()) return

                        ensureFragmentMenuMethods(fragment.javaClass)
                        if (!isFeatureEnabled()) {
                            removeAiMenuIfPresent(fragment)
                            return
                        }
                        val hasMoreMenu = findMenuInfoMethod?.invoke(fragment, 0) != null
                        val hasAiMenu = findMenuInfoMethod?.invoke(fragment, AI_MENU_ID) != null
                        // Register AI before WeChat inserts menuId=0 ("...") so AI renders to its left.
                        if (!hasMoreMenu && !hasAiMenu) {
                            addAiMenu(fragment, ctx, getTalkerMethod, getNickMethod)
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!isFeatureEnabled()) return
                        val header = param.thisObject ?: return
                        val ctx = ctxFieldOnHeader?.get(header) ?: return
                        val fragment = fragmentFieldOnCtx?.get(ctx) ?: return
                        latestCtxByFragment[System.identityHashCode(fragment)] = WeakReference(ctx)
                        val talker = (getTalkerMethod.invoke(ctx) as? String)?.trim().orEmpty()
                        if (talker.isEmpty()) return

                        ensureFragmentMenuMethods(fragment.javaClass)
                        if (findMenuInfoMethod?.invoke(fragment, AI_MENU_ID) == null) {
                            addAiMenu(fragment, ctx, getTalkerMethod, getNickMethod)
                        } else {
                            updateOptionMenuTextMethod?.invoke(fragment, AI_MENU_ID, "AI")
                        }
                    }
                }
            })
            AcidLogger.i(TAG, "hooked ${updateMenuMethod.declaringClass.name}.${updateMenuMethod.name} for Chat AI button")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking ChattingUI header updateMenuItem", it)
        }
    }

    private fun addAiMenu(
        fragment: Any,
        fallbackCtx: Any,
        getTalkerMethod: Method,
        getNickMethod: Method?,
    ) {
        val fragKey = System.identityHashCode(fragment)
        val listener = MenuItem.OnMenuItemClickListener {
            val liveCtx = latestCtxByFragment[fragKey]?.get() ?: fallbackCtx
            openChatAgentDialog(fragment, liveCtx, getTalkerMethod, getNickMethod)
            true
        }
        addTextOptionMenuMethod?.invoke(fragment, AI_MENU_ID, "AI", listener)
    }

    private fun removeAiMenuIfPresent(fragment: Any) {
        if (findMenuInfoMethod?.invoke(fragment, AI_MENU_ID) != null) {
            removeOptionMenuMethod?.invoke(fragment, AI_MENU_ID)
        }
    }

    private fun openChatAgentDialog(
        fragment: Any,
        ctx: Any,
        getTalkerMethod: Method,
        getNickMethod: Method?,
    ) {
        val talker = runCatching { (getTalkerMethod.invoke(ctx) as? String)?.trim().orEmpty() }.getOrDefault("")
        if (talker.isEmpty()) {
            AcidLogger.w(TAG, "skip opening AI dialog: live talker is empty")
            return
        }
        val activity = resolveFragmentActivity(fragment) ?: run {
            AcidLogger.w(TAG, "unable to resolve Activity for chat AI dialog")
            return
        }
        val nick = runCatching { (getNickMethod?.invoke(ctx) as? String)?.trim() }.getOrNull()
        val title = nick?.takeIf { it.isNotEmpty() } ?: ChatRepository.resolveChatTitle(talker)
        AcidLogger.i(TAG, "opening per-chat AgentDialog: talker=$talker, title=$title")
        AgentDialog.show(activity, talker = talker, title = title)
    }

    private fun resolveFragmentActivity(fragment: Any): Activity? = runCatching {
        val m = ReflectHelper.findMethod(fragment.javaClass, "getActivity", emptyList())
            ?: ReflectHelper.findMethod(fragment.javaClass, "thisActivity", emptyList())
        (m?.invoke(fragment) as? Activity)
    }.getOrNull() ?: ReflectHelper.findActivityField(fragment)

    private fun ensureFragmentMenuMethods(cls: Class<*>) {
        if (addTextOptionMenuMethod == null) {
            addTextOptionMenuMethod = ReflectHelper.findMethod(
                cls,
                "addTextOptionMenu",
                listOf(Int::class.javaPrimitiveType!!, String::class.java, MenuItem.OnMenuItemClickListener::class.java),
            )
        }
        if (findMenuInfoMethod == null) {
            findMenuInfoMethod = ReflectHelper.findMethod(
                cls,
                "findMenuInfo",
                listOf(Int::class.javaPrimitiveType!!),
            )
        }
        if (removeOptionMenuMethod == null) {
            removeOptionMenuMethod = ReflectHelper.findMethod(
                cls,
                "removeOptionMenu",
                listOf(Int::class.javaPrimitiveType!!),
            )
        }
        if (updateOptionMenuTextMethod == null) {
            updateOptionMenuTextMethod = ReflectHelper.findMethod(
                cls,
                "updateOptionMenuText",
                listOf(Int::class.javaPrimitiveType!!, String::class.java),
            )
        }
    }

    private fun findFieldByType(startCls: Class<*>, targetType: Class<*>): Field? {
        var cur: Class<*>? = startCls
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (targetType.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return f
                }
            }
            cur = cur.superclass
        }
        return null
    }
}
