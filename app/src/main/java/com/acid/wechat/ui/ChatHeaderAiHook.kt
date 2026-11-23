package com.acid.wechat.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import com.acid.wechat.agent.AgentConfigStore
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
import java.util.concurrent.atomic.AtomicBoolean

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

    private val rulesRegistered = AtomicBoolean(false)
    private val hookInstalled = AtomicBoolean(false)

    @Volatile private var aiEnabledProvider: () -> Boolean = { false }

    @Volatile private var ctxFieldOnHeader: Field? = null
    @Volatile private var fragmentFieldOnCtx: Field? = null
    @Volatile private var findMenuInfoMethod: Method? = null
    @Volatile private var addTextOptionMenuMethod: Method? = null
    @Volatile private var addTextOptionMenuStyledMethod: Method? = null
    @Volatile private var removeOptionMenuMethod: Method? = null
    @Volatile private var updateOptionMenuTextMethod: Method? = null
    @Volatile private var menuStyleEnumClass: Class<*>? = null
    @Volatile private var menuStyleNormal: Any? = null
    @Volatile private var menuStyleTakeover: Any? = null
    @Volatile private var menuInfoStyleField: Field? = null
    @Volatile private var getTalkerMethodRef: Method? = null

    @Volatile private var getNickMethodRef: Method? = null

    private val latestCtxByFragment = ConcurrentHashMap<Int, WeakReference<Any>>()
    private val latestFragments = ConcurrentHashMap<Int, WeakReference<Any>>()

    fun registerDexRules() {
        if (!rulesRegistered.compareAndSet(false, true)) return
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
        aiEnabledProvider = isFeatureEnabled
        ensureHookInstalled()
    }

    fun ensureContextTracking() {
        ensureHookInstalled()
    }

    fun resolveLiveTalker(activity: Activity): Pair<String, String>? {
        val getTalker = getTalkerMethodRef ?: return null
        val getNick = getNickMethodRef
        for ((fragKey, fragRef) in latestFragments.entries) {
            val fragment = fragRef.get() ?: continue
            if (resolveFragmentActivity(fragment) !== activity) continue
            val ctx = latestCtxByFragment[fragKey]?.get() ?: continue
            val talker = runCatching { (getTalker.invoke(ctx) as? String)?.trim().orEmpty() }.getOrDefault("")
            if (talker.isNotEmpty()) {
                val nick = runCatching { (getNick?.invoke(ctx) as? String)?.trim() }.getOrNull()
                val title = nick?.takeIf { it.isNotEmpty() } ?: ChatRepository.resolveChatTitle(talker)
                return talker to title
            }
        }
        return null
    }

    private fun ensureHookInstalled() {
        if (!hookInstalled.compareAndSet(false, true)) return
        val updateMenuMethod = ruleHeaderUpdateMenu.resolveMethod()
        val getTalkerMethod = ruleChattingCtxGetTalker.resolveMethod()
        val getNickMethod = ruleChattingCtxGetNick.resolveMethod()
        if (updateMenuMethod == null || getTalkerMethod == null) {
            AcidLogger.w(TAG, "ChatHeaderAiHook skipped: DexKit resolution incomplete")
            return
        }
        getTalkerMethodRef = getTalkerMethod
        getNickMethodRef = getNickMethod

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
                        val fragKey = System.identityHashCode(fragment)
                        latestCtxByFragment[fragKey] = WeakReference(ctx)
                        latestFragments[fragKey] = WeakReference(fragment)
                        pruneDeadFragmentsIfNeeded()
                        val talker = (getTalkerMethod.invoke(ctx) as? String)?.trim().orEmpty()
                        if (talker.isEmpty()) return

                        ensureFragmentMenuMethods(fragment.javaClass)
                        val hasMoreMenu = findMenuInfoMethod?.invoke(fragment, 0) != null
                        if (!aiEnabledProvider()) {
                            removeMenuIfPresent(fragment, AI_MENU_ID)
                        } else if (!hasMoreMenu && findMenuInfoMethod?.invoke(fragment, AI_MENU_ID) == null) {
                            addAiMenu(fragment, ctx, talker, getTalkerMethod, getNickMethod)
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val header = param.thisObject ?: return
                        val ctx = ctxFieldOnHeader?.get(header) ?: return
                        val fragment = fragmentFieldOnCtx?.get(ctx) ?: return
                        val fragKey = System.identityHashCode(fragment)
                        latestCtxByFragment[fragKey] = WeakReference(ctx)
                        latestFragments[fragKey] = WeakReference(fragment)
                        pruneDeadFragmentsIfNeeded()
                        val talker = (getTalkerMethod.invoke(ctx) as? String)?.trim().orEmpty()
                        if (talker.isEmpty()) return

                        ensureFragmentMenuMethods(fragment.javaClass)
                        if (aiEnabledProvider()) {
                            if (findMenuInfoMethod?.invoke(fragment, AI_MENU_ID) == null) {
                                addAiMenu(fragment, ctx, talker, getTalkerMethod, getNickMethod)
                            } else {
                                applyMenuVisual(fragment, talker, forceInvalidate = false)
                            }
                        } else {
                            removeMenuIfPresent(fragment, AI_MENU_ID)
                        }
                    }
                }
            })
            AcidLogger.i(TAG, "hooked ${updateMenuMethod.declaringClass.name}.${updateMenuMethod.name} for Chat header buttons")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking ChattingUI header updateMenuItem", it)
        }
    }

    private fun pruneDeadFragmentsIfNeeded() {
        if (latestFragments.size <= 16) return
        val iter = latestFragments.entries.iterator()
        while (iter.hasNext()) {
            val (fragKey, fragRef) = iter.next()
            if (fragRef.get() == null) {
                iter.remove()
                latestCtxByFragment.remove(fragKey)
            }
        }
    }

    fun refreshTakeoverBadge(talker: String) {
        if (talker.isBlank()) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val getTalker = getTalkerMethodRef ?: return@runCatching
                val iter = latestFragments.entries.iterator()
                while (iter.hasNext()) {
                    val (fragKey, fragRef) = iter.next()
                    val fragment = fragRef.get()
                    if (fragment == null) {
                        iter.remove()
                        latestCtxByFragment.remove(fragKey)
                        continue
                    }
                    val ctx = latestCtxByFragment[fragKey]?.get() ?: continue
                    val curTalker = (runCatching { getTalker.invoke(ctx) as? String }.getOrNull())?.trim().orEmpty()
                    if (curTalker == talker) {
                        ensureFragmentMenuMethods(fragment.javaClass)
                        applyMenuVisual(fragment, curTalker, forceInvalidate = true)
                    }
                }
            }.onFailure {
                AcidLogger.w(TAG, "refreshTakeoverBadge failed for $talker", it)
            }
        }
    }

    private fun addAiMenu(
        fragment: Any,
        fallbackCtx: Any,
        talker: String,
        getTalkerMethod: Method,
        getNickMethod: Method?,
    ) {
        val fragKey = System.identityHashCode(fragment)
        val listener = MenuItem.OnMenuItemClickListener {
            val liveCtx = latestCtxByFragment[fragKey]?.get() ?: fallbackCtx
            openChatAgentDialog(fragment, liveCtx, getTalkerMethod, getNickMethod)
            true
        }
        val style = resolveMenuStyle(AgentConfigStore.isTakeoverEnabled(talker))
        val styledMethod = addTextOptionMenuStyledMethod
        if (styledMethod != null && style != null) {
            styledMethod.invoke(fragment, AI_MENU_ID, "AI", listener, null, style)
        } else {
            addTextOptionMenuMethod?.invoke(fragment, AI_MENU_ID, "AI", listener)
        }
    }

    private fun applyMenuVisual(fragment: Any, talker: String, forceInvalidate: Boolean) {
        val menuInfo = findMenuInfoMethod?.invoke(fragment, AI_MENU_ID) ?: return
        val styleCls = menuStyleEnumClass
        var styleChanged = false
        if (styleCls != null) {
            val field = resolveMenuInfoStyleField(menuInfo.javaClass, styleCls)
            val targetStyle = resolveMenuStyle(AgentConfigStore.isTakeoverEnabled(talker))
            if (field != null && targetStyle != null) {
                val prev = runCatching { field.get(menuInfo) }.getOrNull()
                if (prev != targetStyle) {
                    field.set(menuInfo, targetStyle)
                    styleChanged = true
                }
            }
        }
        updateOptionMenuTextMethod?.invoke(fragment, AI_MENU_ID, "AI")
        if (styleChanged || forceInvalidate) {
            resolveFragmentActivity(fragment)?.invalidateOptionsMenu()
        }
    }

    private fun resolveMenuStyle(takeoverEnabled: Boolean): Any? =
        if (takeoverEnabled) menuStyleTakeover ?: menuStyleNormal else menuStyleNormal

    private fun resolveMenuInfoStyleField(infoCls: Class<*>, styleCls: Class<*>): Field? {
        menuInfoStyleField?.let { if (it.declaringClass.isAssignableFrom(infoCls)) return it }
        var cur: Class<*>? = infoCls
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type == styleCls) {
                    f.isAccessible = true
                    menuInfoStyleField = f
                    return f
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun removeMenuIfPresent(fragment: Any, menuId: Int) {
        if (findMenuInfoMethod?.invoke(fragment, menuId) != null) {
            removeOptionMenuMethod?.invoke(fragment, menuId)
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
        if (addTextOptionMenuStyledMethod == null) {
            var cur: Class<*>? = cls
            while (cur != null && cur != Any::class.java && addTextOptionMenuStyledMethod == null) {
                for (m in cur.declaredMethods) {
                    val p = m.parameterTypes
                    if (m.name == "addTextOptionMenu" && p.size == 5 &&
                        p[0] == Int::class.javaPrimitiveType &&
                        p[1] == String::class.java &&
                        p[2] == MenuItem.OnMenuItemClickListener::class.java &&
                        p[3] == View.OnLongClickListener::class.java &&
                        p[4].isEnum
                    ) {
                        m.isAccessible = true
                        addTextOptionMenuStyledMethod = m
                        val enumCls = p[4]
                        menuStyleEnumClass = enumCls
                        val constants = enumCls.enumConstants
                        menuStyleNormal = constants?.firstOrNull { (it as? Enum<*>)?.name == "NORMAL" }
                        menuStyleTakeover = constants?.firstOrNull { (it as? Enum<*>)?.name == "TRANSPARENT_GREEN_TEXT" }
                            ?: constants?.firstOrNull { (it as? Enum<*>)?.name == "GREEN" }
                        break
                    }
                }
                cur = cur.superclass
            }
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
