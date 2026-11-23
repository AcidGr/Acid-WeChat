package com.acid.wechat.features

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.ui.AudioFilePicker
import com.acid.wechat.ui.ChatHeaderAiHook
import com.acid.wechat.ui.ChatPlusPanelHook
import com.acid.wechat.ui.overlay.AcidModal
import com.acid.wechat.ui.overlay.AcidModalSpec
import com.acid.wechat.ui.overlay.ModalBlock
import com.acid.wechat.wechat.SilkAudioConverter
import com.acid.wechat.wechat.WeChatVoiceSender
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adds an "MP3语音" tile inside WeChat's chat "+" panel (plus a long-press shortcut on the
 * bottom-left voice toggle icon) to pick an MP3/audio file from the system file manager,
 * convert it to 24kHz SILK v3, and send it as a native WeChat voice message.
 */
object VoiceFileSendFeature : AcidFeature {
    private const val TAG = "VoiceFileSend"
    private const val KEY_DURATION_SEC = "duration_sec"

    private lateinit var ruleChatFooterSmiley: DexKitLocator.DexRule
    private val sentVoiceCount = AtomicInteger(0)

    override val id = "voice_file_send"
    override val spec = ConfigSchema.VOICE_FILE_SEND_ENABLED

    override fun registerDexRules() {
        ChatHeaderAiHook.registerDexRules()
        ChatPlusPanelHook.registerDexRules()
        ruleChatFooterSmiley = DexKitLocator.registerMethod(
            id = "voice_chat_footer_smiley_v1",
            description = "ChatFooter.initSmileyBtn() for voice-button long-press shortcut",
        ) {
            searchPackages("com.tencent.mm.pluginsdk.ui.chat")
            matcher {
                usingEqStrings("initSmileyBtn")
            }
        }
    }

    override fun onInit() {
        ChatHeaderAiHook.ensureContextTracking()
        ChatPlusPanelHook.init(
            isEnabled = { FeatureRegistry.isEnabled(this@VoiceFileSendFeature) },
            onClick = { activity -> launchForLiveChat(activity) },
        )
        hookChatFooterVoiceLongClick()
    }

    private fun launchForLiveChat(activity: Activity) {
        val (talker, title) = ChatHeaderAiHook.resolveLiveTalker(activity) ?: run {
            Toast.makeText(activity, "未能识别当前聊天对象", Toast.LENGTH_SHORT).show()
            return
        }
        startPickAndSendFlow(activity, talker, title)
    }

    private fun hookChatFooterVoiceLongClick() {
        val method = ruleChatFooterSmiley.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val footer = param.thisObject as? ViewGroup ?: return
                        val voiceBtn = findLeftVoiceButton(footer) ?: return
                        voiceBtn.setOnLongClickListener { v ->
                            if (!FeatureRegistry.isEnabled(this@VoiceFileSendFeature)) {
                                return@setOnLongClickListener false
                            }
                            val activity = ReflectHelper.unwrapActivity(v.context) ?: return@setOnLongClickListener false
                            launchForLiveChat(activity)
                            true
                        }
                    }
                }
            })
            AcidLogger.i(TAG, "hooked ${method.declaringClass.name}.${method.name} for voice long-press")
        }.onFailure { err ->
            FeatureRegistry.recordError(id, err)
            AcidLogger.w(TAG, "hookChatFooterVoiceLongClick failed", err)
        }
    }

    private fun findLeftVoiceButton(root: ViewGroup): ImageButton? {
        val buttons = ArrayList<ImageButton>(4)
        fun walk(v: View) {
            if (v is ImageButton && v.javaClass.simpleName == "WeImageButton") {
                buttons.add(v)
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return buttons.firstOrNull()
    }

    private fun startPickAndSendFlow(activity: Activity, talker: String, title: String) {
        AudioFilePicker.pickAudio(activity) { result ->
            result.onFailure { err ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "选择音频失败: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }.onSuccess { picked ->
                if (picked == null) return@onSuccess
                val info = runCatching { SilkAudioConverter.inspectAudio(picked.file) }.getOrNull()
                if (info == null) {
                    deleteOrphanPicked(picked.file)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "无法解析音频文件信息", Toast.LENGTH_SHORT).show()
                    }
                    return@onSuccess
                }
                activity.runOnUiThread {
                    showSendConfirmModal(activity, talker, title, picked, info)
                }
            }
        }
    }

    private fun showSendConfirmModal(
        activity: Activity,
        talker: String,
        title: String,
        picked: AudioFilePicker.PickedAudio,
        info: SilkAudioConverter.AudioInfo,
    ) {
        val detectedSec = ((info.durationMs + 500) / 1000).coerceAtLeast(1)
        val defaultUiSec = detectedSec.coerceIn(1, 60)
        val sizeStr = AcidIo.formatBytes(picked.file.length())
        val longTip = if (detectedSec > 60) "（超长音频将完整发送，气泡显示上限 60 秒）" else ""
        val submitted = AtomicBoolean(false)

        val dialog = AcidModal.show(
            activity,
            AcidModalSpec(
                title = "发送语音消息",
                subtitle = "目标聊天：$title",
                primaryButtonText = "转码并发送",
                heightRatio = 0.44f,
                blocks = listOf(
                    ModalBlock.Preview(
                        title = "已选音频：${picked.displayName}",
                        body = "大小：$sizeStr · 检测时长：${detectedSec} 秒$longTip",
                    ),
                    ModalBlock.NumberInput(
                        key = KEY_DURATION_SEC,
                        label = "气泡显示时长（秒，1～60）",
                        value = defaultUiSec,
                        min = 1,
                        max = 60,
                    ),
                ),
                onSubmit = { res ->
                    if (!submitted.compareAndSet(false, true)) return@AcidModalSpec true
                    val chosenSec = res.intValue(KEY_DURATION_SEC, defaultUiSec).coerceIn(1, 60)
                    val durationMsOverride = if (detectedSec > 60 && chosenSec == 60) {
                        info.durationMs
                    } else {
                        chosenSec * 1000
                    }
                    dispatchVoiceAsync(activity, talker, picked.file, durationMsOverride)
                    true
                },
            ),
        )

        if (dialog == null) {
            deleteOrphanPicked(picked.file)
            return
        }
        dialog.setOnDismissListener {
            if (!submitted.get()) {
                deleteOrphanPicked(picked.file)
            }
        }
    }

    private fun dispatchVoiceAsync(
        activity: Activity,
        talker: String,
        pickedFile: File,
        durationMsOverride: Int,
    ) {
        activity.runOnUiThread {
            Toast.makeText(activity, "正在转码并发送语音...", Toast.LENGTH_SHORT).show()
        }
        Thread({
            val res = try {
                WeChatVoiceSender.sendVoice(talker, pickedFile, durationMsOverride)
            } finally {
                deleteOrphanPicked(pickedFile)
            }
            if (res.ok) {
                sentVoiceCount.incrementAndGet()
                FeatureRegistry.recordHit(id)
            } else {
                FeatureRegistry.recordError(id, IllegalStateException(res.detail))
            }
            activity.runOnUiThread {
                val msg = if (res.ok) res.detail else "语音发送失败: ${res.detail}"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }, "AcidVoiceSend").apply { isDaemon = true; start() }
    }

    private fun deleteOrphanPicked(file: File?) {
        if (file != null && file.exists()) {
            runCatching { file.delete() }
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("sentVoiceCount", sentVoiceCount.get())
        put("voiceSenderReady", WeChatVoiceSender.isReady())
    }
}
