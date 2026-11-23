package com.acid.wechat.wechat

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Sends real WeChat voice messages (`type=34`, `voiceinfo` + `uploadvoice` NetScene)
 * from local SILK or common audio files (MP3/WAV/FLAC/OGG/M4A/AAC) via [SilkAudioConverter].
 */
object WeChatVoiceSender {
    private const val TAG = "WeChatVoiceSender"
    private const val LONG_VOICE_MS = 60_000
    private const val NORMAL_VOICE_MAX_BYTES = 460_000L

    private lateinit var ruleStartRecord: DexKitLocator.DexRule
    private lateinit var ruleFullPath: DexKitLocator.DexRule
    private lateinit var ruleUploadVoiceClass: DexKitLocator.DexRule

    @Volatile private var startRecordMethod: Method? = null
    @Volatile private var finishRecordMethod: Method? = null
    @Volatile private var fullPathMethod: Method? = null
    @Volatile private var uploadClass: Class<*>? = null
    @Volatile private var uploadNormalCtor: Constructor<*>? = null
    @Volatile private var uploadCdnCtor: Constructor<*>? = null

    fun registerDexRules() {
        ruleStartRecord = DexKitLocator.registerMethod(
            id = "voice_start_record_v1",
            description = "VoiceLogic.startRecord(talker, prefix)",
        ) {
            matcher {
                usingEqStrings("startRecord insert voicestg success")
                returnType = "java.lang.String"
                params {
                    count(2)
                    add("java.lang.String")
                    add("java.lang.String")
                }
            }
        }
        ruleFullPath = DexKitLocator.registerMethod(
            id = "voice_full_path_v2",
            description = "VoiceLogic.getAmrFullPath(fileName, create)",
        ) {
            matcher {
                usingStrings("getAmrFullPath cost:")
                returnType = "java.lang.String"
            }
        }
        ruleUploadVoiceClass = DexKitLocator.registerClass(
            id = "voice_upload_class_v1",
            description = "NetSceneUploadVoice via /cgi-bin/micromsg-bin/uploadvoice",
        ) {
            matcher {
                usingEqStrings("/cgi-bin/micromsg-bin/uploadvoice")
            }
        }
    }

    fun init() {
        resolveMethods()
        AcidLogger.i(
            TAG,
            "initialized (start=${startRecordMethod?.name}, finish=${finishRecordMethod?.name}, " +
                "path=${fullPathMethod?.name}, upload=${uploadClass?.name}, cdn=${uploadCdnCtor != null})",
        )
    }

    fun isReady(): Boolean {
        resolveMethods()
        return startRecordMethod != null &&
            finishRecordMethod != null &&
            fullPathMethod != null &&
            uploadClass != null
    }

    fun statusJson(): JSONObject {
        resolveMethods()
        return JSONObject().apply {
            put("startRecordReady", startRecordMethod != null)
            put("finishRecordReady", finishRecordMethod != null)
            put("fullPathReady", fullPathMethod != null)
            put("uploadVoiceReady", uploadClass != null)
            put("uploadCdnReady", uploadCdnCtor != null)
        }
    }

    fun sendVoice(
        talker: String,
        audioFile: File,
        durationMsOverride: Int? = null,
    ): WeChatSender.SendResult {
        if (talker.isBlank()) return WeChatSender.SendResult(false, "talker 不能为空")
        if (!audioFile.exists() || !audioFile.isFile || audioFile.length() <= 0L) {
            return WeChatSender.SendResult(false, "音频文件不存在或为空: ${audioFile.absolutePath}")
        }
        resolveMethods()
        val startM = startRecordMethod
            ?: return WeChatSender.SendResult(false, "VoiceLogic.startRecord 未解析（请检查 /dexkit）")
        val finishM = finishRecordMethod
            ?: return WeChatSender.SendResult(false, "VoiceLogic.finishRecord 未解析（请检查 /dexkit）")
        val pathM = fullPathMethod
            ?: return WeChatSender.SendResult(false, "VoiceLogic.getAmrFullPath 未解析（请检查 /dexkit）")
        val upCls = uploadClass
            ?: return WeChatSender.SendResult(false, "NetSceneUploadVoice 未解析（请检查 /dexkit）")

        val prepared = SilkAudioConverter.prepareSilk(audioFile).getOrElse { err ->
            return WeChatSender.SendResult(false, err.message ?: "音频转码 SILK 失败")
        }

        return try {
            val actualDurationMs = (durationMsOverride?.takeIf { it > 0 } ?: prepared.durationMs).coerceAtLeast(1)
            val displayDurationMs = actualDurationMs.coerceIn(1, LONG_VOICE_MS)
            val prefix = if (prepared.isAmr) "amr_" else "silk_"

            WeChatSender.runOnMainLooper {
                runCatching {
                    val fileName = (startM.invoke(null, talker, prefix) as? String)?.trim().orEmpty()
                    if (fileName.isEmpty()) return@runCatching WeChatSender.SendResult(false, "创建 voiceinfo 失败")

                    val targetPath = resolveFullPath(pathM, fileName, create = true)
                    if (targetPath.isEmpty()) return@runCatching WeChatSender.SendResult(false, "获取语音目标路径失败")

                    val dstFile = File(targetPath)
                    dstFile.parentFile?.mkdirs()
                    prepared.silkFile.copyTo(dstFile, overwrite = true)

                    if (!invokeFinishRecord(finishM, fileName, displayDurationMs)) {
                        return@runCatching WeChatSender.SendResult(false, "finishRecord 写入语音记录失败")
                    }

                    val useCdn = uploadCdnCtor != null && (
                        actualDurationMs > LONG_VOICE_MS ||
                            prepared.silkFile.length() >= NORMAL_VOICE_MAX_BYTES ||
                            uploadNormalCtor == null
                        )
                    val request = buildUploadRequest(upCls, fileName, useCdn)
                        ?: return@runCatching WeChatSender.SendResult(false, "创建语音上传请求失败 (${upCls.name})")

                    val dispatch = WeChatSender.dispatchNetScene(request)
                    if (dispatch.ok) {
                        AcidLogger.i(TAG, "dispatched voice to $talker (file=$fileName, dur=${displayDurationMs}ms, cdn=$useCdn)")
                        WeChatSender.SendResult(true, "语音已发送 (${displayDurationMs / 1000}s)")
                    } else {
                        dispatch
                    }
                }.onFailure { err ->
                    AcidLogger.w(TAG, "sendVoice failed for $talker", err)
                }.getOrElse { err ->
                    WeChatSender.SendResult(false, "${err.javaClass.simpleName}: ${err.message}")
                }
            }
        } finally {
            prepared.cleanup()
        }
    }

    private fun resolveMethods() {
        if (startRecordMethod == null && ::ruleStartRecord.isInitialized) {
            val m = ruleStartRecord.resolveMethod()
            if (m != null && Modifier.isStatic(m.modifiers)) {
                m.isAccessible = true
                startRecordMethod = m
            }
        }
        if (finishRecordMethod == null && startRecordMethod != null) {
            val owner = startRecordMethod!!.declaringClass
            finishRecordMethod = owner.declaredMethods.firstOrNull { isFinishRecordMethod(it) }?.apply {
                isAccessible = true
            }
        }
        if (fullPathMethod == null && ::ruleFullPath.isInitialized) {
            val m = ruleFullPath.resolveMethod()
            val picked = when {
                m != null && isFullPathMethod(m) -> m
                m != null -> m.declaringClass.declaredMethods.firstOrNull { isFullPathMethod(it) }
                else -> null
            }
            if (picked != null) {
                picked.isAccessible = true
                fullPathMethod = picked
            }
        }
        if (uploadClass == null && ::ruleUploadVoiceClass.isInitialized) {
            val cls = ruleUploadVoiceClass.resolveClass()
            if (cls != null) {
                uploadClass = cls
                uploadNormalCtor = findCtor(cls, String::class.java, Int::class.javaPrimitiveType!!)
                    ?: findCtor(cls, String::class.java, Int::class.javaObjectType)
                uploadCdnCtor = findCtor(cls, String::class.java, Boolean::class.javaPrimitiveType!!)
                    ?: findCtor(cls, String::class.java, Boolean::class.javaObjectType)
            }
        }
    }

    private fun isFinishRecordMethod(m: Method): Boolean {
        if (!Modifier.isStatic(m.modifiers) || m.returnType != Boolean::class.javaPrimitiveType) return false
        val p = m.parameterTypes
        if (p.size !in 3..5) return false
        if (p[0] != String::class.java) return false
        if (!isIntType(p[1]) || !isIntType(p[2])) return false
        if (p.size >= 4 && !p[3].name.startsWith("com.tencent.mm.storage.")) return false
        if (p.size == 5 && p[4] != String::class.java) return false
        return true
    }

    private fun isFullPathMethod(m: Method): Boolean {
        if (m.returnType != String::class.java) return false
        val p = m.parameterTypes
        if (p.size == 2) {
            return Modifier.isStatic(m.modifiers) && p[0] == String::class.java && isBoolType(p[1])
        }
        return p.size == 3 && !p[0].isPrimitive && p[1] == String::class.java && isBoolType(p[2])
    }

    private fun resolveFullPath(method: Method, fileName: String, create: Boolean): String {
        val target = if (Modifier.isStatic(method.modifiers)) null else WeChatSender.resolveServiceInstance(method.declaringClass)
        if (!Modifier.isStatic(method.modifiers) && target == null) return ""
        val p = method.parameterTypes
        return if (p.size == 2) {
            (method.invoke(target, fileName, create) as? String).orEmpty()
        } else {
            val resKey = resolveDefaultResourceKey(p[0]) ?: return ""
            (method.invoke(target, resKey, fileName, create) as? String).orEmpty()
        }
    }

    private fun resolveDefaultResourceKey(cls: Class<*>): Any? {
        runCatching {
            val f = cls.getDeclaredField("j").apply { isAccessible = true }
            f.get(null)?.let { return it }
        }
        for (f: Field in cls.declaredFields) {
            if (Modifier.isStatic(f.modifiers) && cls.isAssignableFrom(f.type)) {
                runCatching {
                    f.isAccessible = true
                    f.get(null)
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun invokeFinishRecord(method: Method, fileName: String, durationMs: Int): Boolean {
        val res = when (method.parameterTypes.size) {
            3 -> method.invoke(null, fileName, durationMs, 0)
            4 -> method.invoke(null, fileName, durationMs, 0, null)
            5 -> method.invoke(null, fileName, durationMs, 0, null, null)
            else -> return false
        }
        return res == true
    }

    private fun buildUploadRequest(cls: Class<*>, fileName: String, useCdn: Boolean): Any? {
        val preferred = if (useCdn) (uploadCdnCtor ?: uploadNormalCtor) else (uploadNormalCtor ?: uploadCdnCtor)
        if (preferred != null) {
            val arg2: Any = if (preferred == uploadCdnCtor) useCdn else 0
            runCatching {
                preferred.isAccessible = true
                return preferred.newInstance(fileName, arg2)
            }
        }
        for (ctor in cls.declaredConstructors) {
            val p = ctor.parameterTypes
            if (p.isEmpty() || p[0] != String::class.java) continue
            val args = arrayOfNulls<Any?>(p.size)
            args[0] = fileName
            var valid = true
            for (i in 1 until p.size) {
                args[i] = defaultArg(p[i]) ?: run {
                    if (!p[i].isPrimitive) null else {
                        valid = false
                        null
                    }
                }
            }
            if (!valid) continue
            runCatching {
                ctor.isAccessible = true
                return ctor.newInstance(*args)
            }
        }
        return null
    }

    private fun findCtor(cls: Class<*>, vararg types: Class<*>): Constructor<*>? =
        runCatching { cls.getDeclaredConstructor(*types).apply { isAccessible = true } }.getOrNull()

    private fun isIntType(cls: Class<*>): Boolean =
        cls == Int::class.javaPrimitiveType || cls == Int::class.javaObjectType

    private fun isBoolType(cls: Class<*>): Boolean =
        cls == Boolean::class.javaPrimitiveType || cls == Boolean::class.javaObjectType

    private fun defaultArg(type: Class<*>): Any? = when {
        !type.isPrimitive -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
