package com.acid.wechat.features

import android.content.ContentValues
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.MsgNoticeHelper
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.wechat.ChatRepository
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dual-layer incoming message anti-recall:
 * 1) Primary: blocks BigBallSysCmdMsgConsumer.doRevokeMsg so WeChat keeps message & media untouched.
 * 2) Fallback: intercepts WCDB message-table recall rewrites and restores original row values.
 * Self-sent recalls are always allowed to pass normally.
 */
object AntiRecallFeature : AcidFeature {
    private const val TAG = "AntiRecall"
    private const val MAX_ROW_CACHE = 400

    override val id = "anti_recall"
    override val name = "消息防撤回"
    override val category = FeatureCategory.CHAT
    override val group = "防止撤回"
    override val description = "拦截对方撤回消息并保留原内容"
    override val configKey = ConfigStore.KEY_ANTI_RECALL

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption(
            title = "撤回本地提示",
            configKey = ConfigStore.KEY_ANTI_RECALL_NOTICE,
            group = group,
            defaultValue = true,
            dependsOn = ConfigStore.KEY_ANTI_RECALL,
        ),
        FeatureOption(
            title = "撤回提示文案",
            configKey = ConfigStore.KEY_ANTI_RECALL_NOTICE_TEXT,
            group = group,
            type = FeatureOption.TYPE_TEXT,
            defaultText = ConfigStore.DEFAULT_RECALL_NOTICE_TEXT,
            placeholder = ConfigStore.DEFAULT_RECALL_NOTICE_TEXT,
            dependsOn = ConfigStore.KEY_ANTI_RECALL_NOTICE,
        ),
    )

    private lateinit var ruleDoRevokeMsg: DexKitLocator.DexRule

    private val newMsgIdRegex = Regex("<newmsgid>\\s*(\\d+)\\s*</newmsgid>", RegexOption.IGNORE_CASE)
    private val replaceMsgRegex = Regex("<replacemsg>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</replacemsg>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val nameQuoteRegex = Regex("[\"「]([^\"」]{1,40})[」\"]")
    private val whereMsgIdRegex = Regex("msgId\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

    private val recentMessages = object : LinkedHashMap<Long, ContentValues>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ContentValues>?): Boolean =
            size > MAX_ROW_CACHE
    }
    private val notifiedSvrIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val notifiedMsgIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    private val primaryBlocked = AtomicInteger(0)
    private val fallbackBlocked = AtomicInteger(0)

    override fun registerDexRules() {
        ruleDoRevokeMsg = DexKitLocator.registerMethod(
            id = "anti_recall_do_revoke_msg",
            description = "BigBallSysCmdMsgConsumer.doRevokeMsg",
        ) {
            matcher {
                usingEqStrings("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
            }
        }
    }

    override fun onInit() {
        hookPrimaryDoRevokeMsg()
        hookDatabaseFallback()
    }

    private fun isNoticeEffective(): Boolean =
        ConfigStore.isEffective(ConfigStore.KEY_ANTI_RECALL_NOTICE, true, ConfigStore.KEY_ANTI_RECALL)

    private fun hookPrimaryDoRevokeMsg() {
        val method = ruleDoRevokeMsg.resolveMethod()
        if (method == null) {
            AcidLogger.w(TAG, "doRevokeMsg not resolved; relying on WCDB fallback")
            return
        }
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@AntiRecallFeature)) return
                    runCatching {
                        val args = param.args ?: emptyArray()
                        val xmlArg = args.firstOrNull { it is String && it.contains("revokemsg") } as? String
                        val msgSvrId = xmlArg?.let { parseNewMsgId(it) }
                            ?: (args.firstOrNull { it is Long } as? Long ?: 0L)
                        val replaceArg = xmlArg ?: (args.getOrNull(3) as? String)
                        val selfHint = args.filterIsInstance<String>().any {
                            it.contains("你撤回") || it.contains("You recalled", ignoreCase = true)
                        }

                        if (!shouldBlockRecall(msgSvrId, selfHint)) {
                            AcidLogger.i(TAG, "allow self revoke pass (msgSvrId=$msgSvrId)")
                            return
                        }

                        param.result = null
                        primaryBlocked.incrementAndGet()
                        FeatureRegistry.recordHit(id)
                        AcidLogger.i(TAG, "blocked doRevokeMsg (msgSvrId=$msgSvrId)")

                        if (isNoticeEffective()) {
                            emitNoticeForSvrId(msgSvrId, replaceArg)
                        }
                    }.onFailure {
                        FeatureRegistry.recordError(id, it)
                        AcidLogger.w(TAG, "doRevokeMsg hook body failed", it)
                    }
                }
            })
            AcidLogger.i(TAG, "hooked primary doRevokeMsg: ${method.declaringClass.name}.${method.name}")
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.e(TAG, "failed hooking doRevokeMsg", it)
        }
    }

    private fun hookDatabaseFallback() {
        WcdbBridge.addInsertListener { table, values, rowId ->
            if (table != "message") return@addInsertListener
            val msgId = values.getAsLong("msgId") ?: rowId.takeIf { it > 0 } ?: return@addInsertListener
            val type = values.getAsInteger("type") ?: return@addInsertListener
            if (type == MsgNoticeHelper.TYPE_SYSTEM) return@addInsertListener
            synchronized(recentMessages) {
                recentMessages[msgId] = ContentValues(values)
            }
        }

        WcdbBridge.addUpdateListener { table, values, whereClause, whereArgs, _ ->
            if (table != "message") return@addUpdateListener
            if (!FeatureRegistry.isEnabled(this@AntiRecallFeature)) return@addUpdateListener

            val content = runCatching { values.getAsString("content") }.getOrNull() ?: return@addUpdateListener
            if (!content.contains("撤回") &&
                !content.contains("revokemsg", ignoreCase = true) &&
                !content.contains("recalled", ignoreCase = true)
            ) {
                return@addUpdateListener
            }

            val msgId = extractMsgId(whereClause, whereArgs) ?: return@addUpdateListener
            val original = synchronized(recentMessages) { recentMessages[msgId] }
                ?: ChatRepository.queryMessageById(msgId)
                ?: return@addUpdateListener

            val origType = original.getAsInteger("type") ?: return@addUpdateListener
            if (origType == MsgNoticeHelper.TYPE_SYSTEM) return@addUpdateListener
            if ((original.getAsInteger("isSend") ?: 0) != 0) return@addUpdateListener

            restoreValues(values, original)
            fallbackBlocked.incrementAndGet()
            FeatureRegistry.recordHit(id)
            AcidLogger.i(TAG, "WCDB fallback restored recalled row msgId=$msgId")

            if (isNoticeEffective() && notifiedMsgIds.add(msgId)) {
                val talker = original.getAsString("talker")
                val createTime = original.getAsLong("createTime") ?: System.currentTimeMillis()
                val svrId = original.getAsLong("msgSvrId") ?: 0L
                if (!talker.isNullOrEmpty() && (svrId == 0L || notifiedSvrIds.add(svrId))) {
                    dispatchNotice(talker, original.getAsString("content"), content, createTime + 1)
                }
            }
        }
    }

    private fun shouldBlockRecall(msgSvrId: Long, selfHint: Boolean): Boolean {
        if (selfHint) return false
        if (msgSvrId == 0L) return true
        return ChatRepository.queryIsSendBySvrId(msgSvrId) != true
    }

    private fun emitNoticeForSvrId(msgSvrId: Long, replaceArg: String?) {
        if (msgSvrId == 0L || !notifiedSvrIds.add(msgSvrId)) return
        val row = ChatRepository.queryMessageBySvrId(msgSvrId)
        if (row == null) {
            AcidLogger.w(TAG, "emitNotice: original message not found for msgSvrId=$msgSvrId")
            return
        }
        val type = row.getAsInteger("type") ?: 0
        if (type == MsgNoticeHelper.TYPE_SYSTEM) return
        val talker = row.getAsString("talker") ?: return
        val msgId = row.getAsLong("msgId") ?: 0L
        if (msgId > 0) notifiedMsgIds.add(msgId)
        if ((row.getAsInteger("isSend") ?: 0) != 0) return

        val createTime = row.getAsLong("createTime") ?: System.currentTimeMillis()
        val replaceText = replaceArg?.let {
            replaceMsgRegex.find(it)?.groupValues?.getOrNull(1) ?: it
        } ?: ""
        dispatchNotice(talker, row.getAsString("content"), replaceText, createTime + 1)
    }

    private fun dispatchNotice(
        talker: String,
        originalContent: String?,
        replaceText: String,
        noticeTimeMs: Long,
    ) {
        val quotedHint = nameQuoteRegex.find(replaceText)?.groupValues?.getOrNull(1)
        val sender = ChatRepository.resolveSenderDisplayName(talker, originalContent, quotedHint)
        val rawTemplate = ConfigStore.getString(
            ConfigStore.KEY_ANTI_RECALL_NOTICE_TEXT,
            ConfigStore.DEFAULT_RECALL_NOTICE_TEXT,
        ).ifEmpty { ConfigStore.DEFAULT_RECALL_NOTICE_TEXT }
        val text = ConfigStore.decodeNewlines(rawTemplate).replace("{name}", sender)
        MsgNoticeHelper.insertSystemNotice(talker = talker, noticeText = text, createTimeMs = noticeTimeMs)
    }

    private fun parseNewMsgId(xml: String): Long? =
        newMsgIdRegex.find(xml)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractMsgId(whereClause: String?, whereArgs: Array<String>?): Long? {
        if (whereClause.isNullOrBlank()) return null
        if (whereClause.contains("msgId", ignoreCase = true) && !whereArgs.isNullOrEmpty()) {
            whereArgs[0].toLongOrNull()?.let { return it }
        }
        return whereMsgIdRegex.find(whereClause)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun restoreValues(target: ContentValues, orig: ContentValues) {
        orig.getAsInteger("type")?.let { target.put("type", it) }
        orig.getAsInteger("status")?.let { target.put("status", it) }
        orig.getAsString("content")?.let { target.put("content", it) }
        orig.getAsString("imgPath")?.let { target.put("imgPath", it) }
        target.remove("flag")
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("primaryBlocked", primaryBlocked.get())
        put("fallbackBlocked", fallbackBlocked.get())
        put("cachedRows", synchronized(recentMessages) { recentMessages.size })
    }
}
