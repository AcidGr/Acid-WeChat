package com.acid.wechat.wechat

import android.app.Activity
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared dispatcher for WeChat's native Moment long-press context menu
 * (`TimelineOnCreateContextMenuListener` / `TimeLineMMMenuItemSelectedListener`).
 */
object SnsContextMenuHook {
    private const val TAG = "SnsContextMenuHook"

    data class MenuSpec(
        val itemId: Int,
        val title: String,
    )

    interface Provider {
        fun menuItemsFor(snsId: Long): List<MenuSpec>
        fun onMenuSelected(itemId: Int, activity: Activity, snsId: Long): Boolean
    }

    private data class SelectedPostContext(
        val snsId: Long,
        val activity: Activity?,
    )

    private lateinit var ruleContextMenuCreate: DexKitLocator.DexRule
    private lateinit var ruleMenuBindSelected: DexKitLocator.DexRule
    private lateinit var ruleMenuItemSelected: DexKitLocator.DexRule

    private val dexRegistered = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val providers = CopyOnWriteArrayList<Provider>()
    private val selectedPosts = Collections.synchronizedMap(WeakHashMap<Any, SelectedPostContext>())
    private val viewSnsIds = Collections.synchronizedMap(WeakHashMap<View, Long>())

    @Volatile
    private var latestMenuSnsId: Long = 0L

    fun registerDexRules() {
        if (!dexRegistered.compareAndSet(false, true)) return
        SnsRepository.registerDexRules()

        ruleContextMenuCreate = DexKitLocator.registerMethod(
            id = "sns_context_menu_create",
            description = "TimelineOnCreateContextMenuListener.onCreateContextMenu",
        ) {
            matcher {
                usingEqStrings(
                    "onCreateContextMenu",
                    "com.tencent.mm.plugin.sns.ui.listener.TimelineOnCreateContextMenuListener",
                )
            }
        }

        ruleMenuBindSelected = DexKitLocator.registerMethod(
            id = "sns_menu_bind_selected",
            description = "TimeLineMMMenuItemSelectedListener.bindOnSelectedView",
        ) {
            matcher {
                usingEqStrings(
                    "bindOnSelectedView",
                    "com.tencent.mm.plugin.sns.ui.listener.TimeLineMMMenuItemSelectedListener",
                )
            }
        }

        ruleMenuItemSelected = DexKitLocator.registerMethod(
            id = "sns_menu_item_selected",
            description = "TimeLineMMMenuItemSelectedListener.onMMMenuItemSelected",
        ) {
            matcher {
                usingEqStrings(
                    "onMMMenuItemSelected",
                    "com.tencent.mm.plugin.sns.ui.listener.TimeLineMMMenuItemSelectedListener",
                )
            }
        }
    }

    fun registerProvider(provider: Provider) {
        if (!providers.contains(provider)) {
            providers.add(provider)
        }
        initIfNeeded()
    }

    private fun initIfNeeded() {
        if (!initialized.compareAndSet(false, true)) return
        hookContextMenuCreate()
        hookMenuBindSelected()
        hookMenuItemSelected()
    }

    private fun hookContextMenuCreate() {
        val m = ruleContextMenuCreate.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    latestMenuSnsId = 0L
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
                    val view = param.args?.getOrNull(1) as? View
                    val snsId = (latestMenuSnsId.takeIf { it != 0L } ?: view?.let { viewSnsIds[it] })
                        ?: ReflectHelper.unwrapActivity(view?.context)?.let(SnsRepository::extractSnsIdFromIntent)
                        ?: 0L
                    for (provider in providers) {
                        for (spec in provider.menuItemsFor(snsId)) {
                            if (menu.findItem(spec.itemId) == null) {
                                menu.add(0, spec.itemId, 0, spec.title)
                            }
                        }
                    }
                }
            })
        }.onFailure {
            AcidLogger.w(TAG, "hookContextMenuCreate failed", it)
        }
    }

    private fun hookMenuBindSelected() {
        val m = ruleMenuBindSelected.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.thisObject ?: return
                    val view = param.args?.getOrNull(0) as? View
                    val snsTableId = param.args?.getOrNull(1) as? String
                    val tlObj = param.args?.getOrNull(2)
                    val activity = ReflectHelper.unwrapActivity(view?.context) ?: ReflectHelper.findActivityField(listener)
                    val snsId = SnsRepository.resolveSnsId(tlObj, snsTableId, activity) ?: return
                    selectedPosts[listener] = SelectedPostContext(snsId, activity)
                    if (view != null) viewSnsIds[view] = snsId
                    latestMenuSnsId = snsId
                }
            })
        }.onFailure {
            AcidLogger.w(TAG, "hookMenuBindSelected failed", it)
        }
    }

    private fun hookMenuItemSelected() {
        val m = ruleMenuItemSelected.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val menuItem = param.args?.getOrNull(0) as? MenuItem ?: return
                    val itemId = menuItem.itemId
                    val listener = param.thisObject ?: return
                    val ctx = selectedPosts[listener]
                    val activity = ctx?.activity ?: ReflectHelper.findActivityField(listener)
                    val snsId = ctx?.snsId ?: activity?.let(SnsRepository::extractSnsIdFromIntent)

                    for (provider in providers) {
                        if (activity != null && snsId != null && snsId != 0L && provider.onMenuSelected(itemId, activity, snsId)) {
                            param.result = null
                            return
                        }
                    }
                }
            })
        }.onFailure {
            AcidLogger.w(TAG, "hookMenuItemSelected failed", it)
        }
    }
}
