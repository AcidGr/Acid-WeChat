package com.acid.wechat.core

import android.content.ContentValues
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Helper to insert local grey system messages (type = 10000) into WeChat's MsgInfoStorage
 * and notify registered observers when new WeChat messages are inserted.
 */
object MsgNoticeHelper {
    private const val TAG = "MsgNoticeHelper"
    const val TYPE_SYSTEM = 10000

    private lateinit var ruleMsgInfo: DexKitLocator.DexRule
    private lateinit var ruleMsgInfoStorageInsert: DexKitLocator.DexRule

    @Volatile
    private var capturedMsgInfoStorage: Any? = null

    @Volatile
    private var convertToMethod: Method? = null

    private val insertObservers = CopyOnWriteArrayList<(Any, Long, ContentValues) -> Unit>()

    fun getCapturedStorage(): Any? = capturedMsgInfoStorage

    fun resolveMsgInfoClass(): Class<*>? =
        if (::ruleMsgInfo.isInitialized) ruleMsgInfo.resolveClass() else null

    fun addInsertObserver(observer: (msgObj: Any, insertedMsgId: Long, values: ContentValues) -> Unit) {
        insertObservers.add(observer)
    }

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
        val msgInfoCls = ruleMsgInfo.resolveClass()
        if (msgInfoCls != null) {
            convertToMethod = ReflectHelper.findMethod(msgInfoCls, "convertTo", emptyList())
        }
        runCatching {
            XposedBridge.hookMethod(insertMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (capturedMsgInfoStorage == null && param.thisObject != null) {
                        capturedMsgInfoStorage = param.thisObject
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (insertObservers.isEmpty()) return
                    val msgObj = param.args?.firstOrNull() ?: return
                    val insertedId = (param.result as? Long) ?: 0L
                    if (insertedId <= 0L) return
                    val conv = convertToMethod ?: return
                    runCatching {
                        val values = conv.invoke(msgObj) as? ContentValues ?: return
                        for (obs in insertObservers) {
                            runCatching { obs(msgObj, insertedId, values) }
                        }
                    }
                }
            })
            AcidLogger.i(TAG, "hooked MsgInfoStorage.insert for capture & message observation")
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
