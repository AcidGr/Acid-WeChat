package com.acid.wechat.wechat

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.MsgNoticeHelper
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays transcribed voice text directly in WeChat's home conversation list (`MainUI`)
 * by hooking conversation adapter `getView` and refreshing when async transcription completes.
 */
object VoiceHomeDigestBridge {
    private const val TAG = "VoiceHomeDigest"
    private const val NO_MEASURED_TEXT_VIEW = "com.tencent.mm.ui.base.NoMeasuredTextView"
    private const val MAX_TRANSCRIPT_LEN = 80
    private const val MAX_CACHE_ENTRIES = 400
    private const val VOICE_MSG_TYPE = "34"

    private val bracketVoiceSuffixRegex = Regex(
        "^(.*?)\\[(?:语音|Voice|語音)\\](?:\\s*\\d+[\"'])?$",
        RegexOption.IGNORE_CASE,
    )
    private val plainVoiceDurationRegex = Regex(
        "^((?:\\[\\d+条\\]\\s*)?(?:[^:：\\n]{1,32}[:：]\\s*)?)(?:语音|Voice|語音)\\s*\\d+[\"']$",
        RegexOption.IGNORE_CASE,
    )

    private data class CacheEntry(
        val msgId: Long,
        val isVoice: Boolean,
        val transcript: String?,
    )

    private data class ConvFieldAccess(
        val usernameField: Field?,
        val msgTypeField: Field?,
    )

    private data class CustomViewTextAccess(
        val getText: Method?,
        val setText: Method?,
        val textFields: List<Field>,
    )

    private lateinit var ruleMvvmGetView: DexKitLocator.DexRule
    private lateinit var ruleLegacyGetView: DexKitLocator.DexRule

    private val mainHandler = Handler(Looper.getMainLooper())
    private val latestCache = ConcurrentHashMap<String, CacheEntry>()
    private val convFieldCache = ConcurrentHashMap<Class<*>, ConvFieldAccess>()
    private val customViewCache = ConcurrentHashMap<Class<*>, CustomViewTextAccess>()
    @Volatile private var latestAdapter: WeakReference<BaseAdapter>? = null
    @Volatile private var loggedFirstApply = false

    fun registerDexRules() {
        ruleMvvmGetView = DexKitLocator.registerMethod(
            id = "conv_mvvm_getview_v1",
            description = "WeChat MvvmConversationAdapter.getView(int, View, ViewGroup)",
        ) {
            matcher {
                declaredClass {
                    usingEqStrings(
                        "MicroMsg.ConversationAdapter.MvvmConversationAdapter",
                        "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
                    )
                }
                name = "getView"
                paramTypes("int", "android.view.View", "android.view.ViewGroup")
                returnType = "android.view.View"
            }
        }
        ruleLegacyGetView = DexKitLocator.registerMethod(
            id = "conv_legacy_getview_v1",
            description = "WeChat ConversationWithCacheAdapter.getView(int, View, ViewGroup)",
        ) {
            matcher {
                name = "getView"
                paramTypes("int", "android.view.View", "android.view.ViewGroup")
                returnType = "android.view.View"
                usingEqStrings("Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
        }
    }

    fun init(isFeatureEnabled: () -> Boolean) {
        MsgNoticeHelper.addInsertObserver { _, _, values ->
            val talker = values.getAsString("talker")?.trim().orEmpty()
            val type = values.getAsInteger("type") ?: return@addInsertObserver
            if (talker.isEmpty() || type == MsgNoticeHelper.TYPE_SYSTEM) return@addInsertObserver
            if (type == VoiceTransBridge.MSG_TYPE_VOICE) {
                latestCache.remove(talker)
            } else {
                putCache(talker, CacheEntry(msgId = 0L, isVoice = false, transcript = null))
            }
        }

        val methods = listOfNotNull(
            if (::ruleMvvmGetView.isInitialized) ruleMvvmGetView.resolveMethod() else null,
            if (::ruleLegacyGetView.isInitialized) ruleLegacyGetView.resolveMethod() else null,
        ).distinct()
        if (methods.isEmpty()) {
            AcidLogger.w(TAG, "conversation adapter getView not resolved")
            return
        }

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isFeatureEnabled()) return
                val adapter = param.thisObject as? BaseAdapter ?: return
                if (latestAdapter?.get() !== adapter) latestAdapter = WeakReference(adapter)
                val row = param.result as? View ?: return
                val pos = param.args?.getOrNull(0) as? Int ?: return
                val conv = runCatching { adapter.getItem(pos) }.getOrNull() ?: return
                applyTranscriptToRow(row, conv)
            }
        }
        for (m in methods) {
            runCatching {
                XposedBridge.hookMethod(m, hook)
                AcidLogger.i(TAG, "hooked conversation getView: ${m.declaringClass.name}.${m.name}")
            }.onFailure {
                AcidLogger.w(TAG, "failed hooking ${m.declaringClass.name}.${m.name}", it)
            }
        }
    }

    fun onTranscriptReady(talker: String, msgId: Long, rawText: String) {
        if (talker.isBlank() || msgId <= 0L) return
        val clean = cleanTranscript(rawText) ?: return
        val state = ChatRepository.queryLatestVoiceState(talker)
        if (state != null && (!state.isVoice || (state.msgId > 0L && state.msgId != msgId))) {
            putCache(talker, CacheEntry(state.msgId, state.isVoice, state.transcript))
            return
        }
        putCache(talker, CacheEntry(msgId, isVoice = true, transcript = clean))
        mainHandler.post {
            runCatching { latestAdapter?.get()?.notifyDataSetChanged() }
        }
    }

    private fun applyTranscriptToRow(row: View, conv: Any) {
        val access = resolveConvFields(conv.javaClass)
        val talker = (access.usernameField?.let { runCatching { it.get(conv) as? String }.getOrNull() })
            ?.trim().orEmpty()
        if (talker.isEmpty()) return

        val msgType = access.msgTypeField?.let { runCatching { it.get(conv)?.toString()?.trim() }.getOrNull() }.orEmpty()
        if (msgType.isNotEmpty() && msgType != VOICE_MSG_TYPE) return

        val cached = latestCache[talker] ?: run {
            val state = ChatRepository.queryLatestVoiceState(talker) ?: return
            CacheEntry(state.msgId, state.isVoice, state.transcript).also { putCache(talker, it) }
        }
        if (!cached.isVoice || cached.transcript.isNullOrBlank()) return

        replaceVoiceDigestInViewTree(row, cached.transcript)
    }

    private fun replaceVoiceDigestInViewTree(root: View, transcript: String) {
        val queue = ArrayDeque<View>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.visibility != View.VISIBLE) continue
            if (v is ViewGroup && !isNeatTextView(v.javaClass)) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
                continue
            }
            val current = readViewText(v)?.trim().orEmpty()
            if (current.isEmpty()) continue
            val replaced = formatVoiceDigest(current, transcript) ?: continue
            if (replaced != current && writeViewText(v, replaced)) {
                if (!loggedFirstApply) {
                    loggedFirstApply = true
                    AcidLogger.i(TAG, "applied home voice digest via ${v.javaClass.name}")
                }
                return
            }
        }
    }

    private fun formatVoiceDigest(current: String, transcript: String): String? {
        if (current.startsWith("[草稿]") || current.startsWith("[Draft]", ignoreCase = true)) return null
        val m1 = bracketVoiceSuffixRegex.matchEntire(current)
        if (m1 != null) {
            return "${m1.groupValues[1]}[语音] $transcript"
        }
        val m2 = plainVoiceDurationRegex.matchEntire(current) ?: return null
        return "${m2.groupValues[1]}[语音] $transcript"
    }

    private fun readViewText(v: View): String? {
        val cls = v.javaClass
        if (isNoMeasuredTextView(cls) || isNeatTextView(cls)) {
            val acc = resolveCustomViewAccess(cls)
            val viaMethod = acc.getText?.let { runCatching { it.invoke(v) as? CharSequence }.getOrNull() }
                ?.toString()?.takeIf { it.isNotEmpty() }
            if (viaMethod != null) return viaMethod
            for (f in acc.textFields) {
                val viaField = runCatching { f.get(v) as? CharSequence }.getOrNull()
                    ?.toString()?.takeIf { it.isNotEmpty() }
                if (viaField != null) return viaField
            }
        }
        return (v as? TextView)?.text?.toString()
    }

    private fun writeViewText(v: View, text: String): Boolean {
        val cls = v.javaClass
        if (isNoMeasuredTextView(cls) || isNeatTextView(cls)) {
            val setter = resolveCustomViewAccess(cls).setText
            if (setter != null) {
                val ok = runCatching {
                    setter.invoke(v, text)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        val tv = v as? TextView ?: return false
        tv.text = text
        return true
    }

    private fun resolveConvFields(cls: Class<*>): ConvFieldAccess =
        convFieldCache.getOrPut(cls) {
            ConvFieldAccess(
                usernameField = ReflectHelper.findField(cls, "field_username"),
                msgTypeField = ReflectHelper.findField(cls, "field_msgType"),
            )
        }

    private fun resolveCustomViewAccess(cls: Class<*>): CustomViewTextAccess =
        customViewCache.getOrPut(cls) {
            val getText = ReflectHelper.findMethod(cls, "getText", emptyList())
                ?: if (isNeatTextView(cls)) ReflectHelper.findMethod(cls, "a", emptyList()) else null
            val setText = ReflectHelper.findMethod(cls, "setText", listOf(CharSequence::class.java))
                ?: ReflectHelper.findMethod(cls, "setText", listOf(String::class.java))
                ?: if (isNeatTextView(cls)) ReflectHelper.findMethod(cls, "b", listOf(CharSequence::class.java)) else null
            val textFields = findCharSeqFieldsRecursive(cls)
            CustomViewTextAccess(getText, setText, textFields)
        }

    private fun findCharSeqFieldsRecursive(cls: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var cur: Class<*>? = cls
        while (cur != null && cur != View::class.java && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type == CharSequence::class.java || f.type == String::class.java) {
                    f.isAccessible = true
                    out += f
                }
            }
            cur = cur.superclass
        }
        return out
    }

    private fun isNoMeasuredTextView(cls: Class<*>): Boolean {
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            if (cur.name == NO_MEASURED_TEXT_VIEW) return true
            cur = cur.superclass
        }
        return false
    }

    private fun isNeatTextView(cls: Class<*>): Boolean {
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            if (cur.name.contains("NeatTextView") || cur.name == "com.tencent.mm.ui.widget.MMNeat7extView") return true
            cur = cur.superclass
        }
        return false
    }

    private fun putCache(talker: String, entry: CacheEntry) {
        if (latestCache.size >= MAX_CACHE_ENTRIES) {
            latestCache.clear()
        }
        latestCache[talker] = entry
    }

    private fun cleanTranscript(raw: String): String? =
        raw.trim().replace('\n', ' ').take(MAX_TRANSCRIPT_LEN).takeIf { it.isNotEmpty() }
}
