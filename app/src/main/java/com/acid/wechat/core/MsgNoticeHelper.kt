package com.acid.wechat.core

import android.content.ContentValues
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlin.random.Random

/**
 * Helper to insert local grey system messages (type = 10000) into WeChat's MsgInfoStorage
 * so intercepted recalls show a clean inline notice without touching the preserved original message.
 */
object MsgNoticeHelper {
    private const val TAG = "MsgNoticeHelper"
    const val TYPE_SYSTEM = 10000

    private lateinit var ruleMsgInfo: DexKitLocator.DexRule
    private lateinit var ruleMsgInfoStorageInsert: DexKitLocator.DexRule

    @Volatile
    private var capturedMsgInfoStorage: Any? = null

    fun registerDexRules() {
        ruleMsgInfo = DexKitLocator.registerClass(
            id = "msg_info_class",
            description = "WeChat MsgInfo entity class",
        ) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
            }
        }

        ruleMsgInfoStorageInsert = DexKitLocator.registerMethod(
            id = "msg_info_storage_insert",
            description = "MsgInfoStorage.insert(MsgInfo) for local system notices",
        ) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MsgInfo processAddMsg insert db error")
            }
        }
    }

    fun init() {
        val insertMethod = ruleMsgInfoStorageInsert.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(insertMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (capturedMsgInfoStorage == null && param.thisObject != null) {
                        capturedMsgInfoStorage = param.thisObject
                    }
                }
            })
            AcidLogger.i(TAG, "hooked MsgInfoStorage.insert for instance capture")
        }.onFailure {
            AcidLogger.w(TAG, "failed hooking MsgInfoStorage.insert", it)
        }
    }

    fun insertSystemNotice(talker: String, noticeText: String, createTimeMs: Long): Boolean {
        val storage = capturedMsgInfoStorage ?: run {
            AcidLogger.w(TAG, "MsgInfoStorage instance not yet captured; notice skipped")
            return false
        }
        val msgInfoCls = ruleMsgInfo.resolveClass() ?: return false
        val insertMethod = ruleMsgInfoStorageInsert.resolveMethod() ?: return false

        return runCatching {
            val values = ContentValues().apply {
                put("msgid", 0)
                put("msgSvrId", createTimeMs + Random.nextInt(1000, 99999))
                put("type", TYPE_SYSTEM)
                put("status", 3)
                put("createTime", createTimeMs)
                put("talker", talker)
                put("content", noticeText)
            }
            val msgInfo = msgInfoCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val convertMethod = ReflectHelper.findMethod(
                msgInfoCls,
                "convertFrom",
                listOf(ContentValues::class.java, Boolean::class.javaPrimitiveType!!),
            ) ?: ReflectHelper.findMethod(
                msgInfoCls,
                "convertFrom",
                listOf(ContentValues::class.java, Boolean::class.java),
            ) ?: error("convertFrom(ContentValues, boolean) not found on ${msgInfoCls.name}")
            convertMethod.invoke(msgInfo, values, true)
            insertMethod.invoke(storage, msgInfo)
            AcidLogger.i(TAG, "inserted local recall notice in $talker")
            true
        }.onFailure {
            AcidLogger.w(TAG, "insertSystemNotice failed", it)
        }.getOrDefault(false)
    }
}
