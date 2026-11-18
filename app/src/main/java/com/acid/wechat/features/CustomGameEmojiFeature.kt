package com.acid.wechat.features

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Toast
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.ui.overlay.AcidModal
import com.acid.wechat.ui.overlay.AcidModalSpec
import com.acid.wechat.ui.overlay.ChecklistItem
import com.acid.wechat.ui.overlay.ModalBlock
import com.acid.wechat.wechat.EmojiRepository
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom animated game emojis (Rock-Paper-Scissors and Dice):
 * - Registers two independent toggles under `聊天 -> 动画表情`.
 * - Intercepts EmojiPanel clicks when Dice/JSB is tapped, pops the standard [AcidModal]
 *   picker, and forces WeChat's `getRandomEmoji` result to the user's chosen `EmojiInfo`.
 */
object CustomGameEmojiFeature : AcidFeature {
    private const val TAG = "CustomGameEmoji"
    private const val KEY_CHOICE = "choice"
    private const val IEMOJI_INFO_CLS = "com.tencent.mm.api.IEmojiInfo"
    private const val EMOJI_INFO_CLS = "com.tencent.mm.storage.emotion.EmojiInfo"

    private lateinit var rulePanelClick: DexKitLocator.DexRule
    private lateinit var ruleEmojiMgrSend: DexKitLocator.DexRule

    private val bypassPrompt = ThreadLocal<Boolean>()
    private val forcedEmojiMd5 = ThreadLocal<String>()
    private val customSentCount = AtomicInteger(0)

    override val id = "custom_jsb"
    override val name = "自定义剪刀石头布"
    override val category = FeatureCategory.CHAT
    override val group = "动画表情"
    override val description = "发送猜拳与骰子前弹出统一选择窗"
    override val configKey = ConfigStore.KEY_CUSTOM_JSB

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption(
            title = "自定义骰子",
            configKey = ConfigStore.KEY_CUSTOM_DICE,
            group = group,
            defaultValue = true,
            dependsOn = null,
        ),
    )

    override fun registerDexRules() {
        rulePanelClick = DexKitLocator.registerMethod(
            id = "emoji_panel_item_click",
            description = "EmojiPanelClickListener.onClick(View, Context, int, item)",
        ) {
            matcher {
                usingEqStrings("MicroMsg.EmojiPanelClickListener", "getRandomEmoji(...)")
            }
        }

        ruleEmojiMgrSend = DexKitLocator.registerMethod(
            id = "emoji_mgr_send_emoji",
            description = "EmojiMgrImpl.sendEmoji(Context, talker, md5, ...)",
        ) {
            matcher {
                usingEqStrings("MicroMsg.emoji.EmojiMgrImpl", "sendEmoji: use SendEmojiTask, user %s")
            }
        }
    }

    override fun onInit() {
        hookPanelClick()
        hookRandomEmojiOverride()
    }

    private fun isJsbEnabled(): Boolean = ConfigStore.getBoolean(ConfigStore.KEY_CUSTOM_JSB, true)
    private fun isDiceEnabled(): Boolean = ConfigStore.getBoolean(ConfigStore.KEY_CUSTOM_DICE, true)

    private fun hookPanelClick() {
        val clickMethod = rulePanelClick.resolveMethod() ?: return
        val iEmojiCls = runCatching { Class.forName(IEMOJI_INFO_CLS, false, HostContext.classLoader) }.getOrNull()
            ?: return

        runCatching {
            XposedBridge.hookMethod(clickMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (bypassPrompt.get() == true) return
                    val args = param.args ?: return
                    val context = args.getOrNull(1) as? Context ?: return
                    val item = args.getOrNull(3) ?: return

                    val itemType = (ReflectHelper.findField(item.javaClass, "a")?.get(item) as? Number)?.toInt()
                    if (itemType != null && itemType != 0) return

                    val emoji = findIEmojiField(item, iEmojiCls) ?: return
                    val md5 = runCatching { emoji.javaClass.getMethod("getMd5").invoke(emoji) as? String }.getOrNull()
                    val catalog = (ReflectHelper.findField(emoji.javaClass, "field_catalog")?.get(emoji) as? Number)?.toInt() ?: 0
                    val content = ReflectHelper.findField(emoji.javaClass, "field_content")?.get(emoji) as? String

                    val kind = EmojiRepository.classifyTemplate(md5, catalog, content) ?: return
                    val enabled = when (kind) {
                        EmojiRepository.GameKind.JSB -> isJsbEnabled()
                        EmojiRepository.GameKind.DICE -> isDiceEnabled()
                    }
                    if (!enabled) return

                    val activity = ReflectHelper.unwrapActivity(context) ?: return
                    val choices = EmojiRepository.queryChoices(kind)
                    if (choices.isEmpty()) {
                        AcidLogger.w(TAG, "no EmojiInfo choices found for catalog=${kind.catalog}")
                        return
                    }

                    param.result = null
                    val savedArgs = args.clone()
                    val targetObj = param.thisObject
                    openChoiceModal(activity, kind, choices) { chosenMd5 ->
                        dispatchChosenEmoji(activity, clickMethod, targetObj, savedArgs, chosenMd5)
                    }
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookPanelClick failed", it)
        }
    }

    private fun hookRandomEmojiOverride() {
        val sendMethod = ruleEmojiMgrSend.resolveMethod() ?: return
        val mgrCls = sendMethod.declaringClass
        val emojiCls = runCatching { Class.forName(EMOJI_INFO_CLS, false, HostContext.classLoader) }.getOrNull()
            ?: return

        val getByMd5 = mgrCls.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                m.returnType == emojiCls
        }?.apply { isAccessible = true }

        val getRandom = mgrCls.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == emojiCls &&
                m.returnType == emojiCls
        }?.apply { isAccessible = true }

        if (getByMd5 == null || getRandom == null) {
            AcidLogger.w(TAG, "failed locating getByMd5/getRandom on ${mgrCls.name}")
            return
        }

        runCatching {
            XposedBridge.hookMethod(getRandom, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val chosenMd5 = forcedEmojiMd5.get() ?: return
                    val resolved = runCatching { getByMd5.invoke(param.thisObject, chosenMd5) }.getOrNull()
                    if (resolved != null) {
                        param.result = resolved
                    }
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookRandomEmojiOverride failed", it)
        }
    }

    private fun openChoiceModal(
        activity: Activity,
        kind: EmojiRepository.GameKind,
        choices: List<EmojiRepository.GameEmojiChoice>,
        onChosen: (String) -> Unit,
    ) {
        val title = if (kind == EmojiRepository.GameKind.DICE) "自定义骰子" else "自定义剪刀石头布"
        val items = choices.mapIndexed { idx, c ->
            ChecklistItem(
                id = c.md5,
                title = c.label,
                checked = idx == 0,
            )
        }

        AcidModal.show(
            activity = activity,
            spec = AcidModalSpec(
                title = title,
                primaryButtonText = "发送",
                widthRatio = 0.82f,
                heightRatio = if (kind == EmojiRepository.GameKind.DICE) 0.56f else 0.40f,
                blocks = listOf(
                    ModalBlock.Checklist(
                        key = KEY_CHOICE,
                        items = items,
                        multiSelect = false,
                        showSearch = false,
                        emptyText = "未找到表情资源",
                        footerTemplate = "",
                    ),
                ),
                onSubmit = { result ->
                    val chosenMd5 = result.selectedIds(KEY_CHOICE).firstOrNull()
                    if (chosenMd5.isNullOrEmpty()) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "请先选择一项", Toast.LENGTH_SHORT).show()
                        }
                        false
                    } else {
                        onChosen(chosenMd5)
                        true
                    }
                },
            ),
        )
    }

    private fun dispatchChosenEmoji(
        activity: Activity,
        clickMethod: Method,
        targetObj: Any?,
        args: Array<Any?>,
        chosenMd5: String,
    ) {
        activity.runOnUiThread {
            runCatching {
                bypassPrompt.set(true)
                forcedEmojiMd5.set(chosenMd5)
                clickMethod.invoke(targetObj, *args)
                customSentCount.incrementAndGet()
                FeatureRegistry.recordHit(id)
                AcidLogger.i(TAG, "dispatched custom game emoji md5=$chosenMd5")
            }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "dispatchChosenEmoji failed", it)
            }.also {
                bypassPrompt.remove()
                forcedEmojiMd5.remove()
            }
        }
    }

    private fun findIEmojiField(item: Any, iEmojiCls: Class<*>): Any? {
        var cur: Class<*>? = item.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (iEmojiCls.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    f.get(item)?.let { return it }
                }
            }
            cur = cur.superclass
        }
        return null
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("customSentCount", customSentCount.get())
    }
}
