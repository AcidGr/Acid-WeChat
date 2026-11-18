package com.acid.wechat.wechat

import android.app.Activity
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ProtoLite
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.core.WcdbBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moments (`SnsMicroMsg.db` / `SnsInfoStorage`) repository and live UI sync helper.
 */
object SnsRepository {
    private const val TAG = "SnsRepository"
    private const val SNS_OBJECT_CLASS = "com.tencent.mm.protocal.protobuf.SnsObject"

    data class SnsPostSummary(
        val snsId: Long,
        val authorName: String,
        val caption: String,
        val timeText: String,
        val likes: List<ProtoLite.LikeEntry>,
    )

    private lateinit var ruleGetStorage: DexKitLocator.DexRule
    private lateinit var ruleGetBySnsId: DexKitLocator.DexRule
    private lateinit var ruleReplaceBySnsId: DexKitLocator.DexRule
    private lateinit var ruleSetLikedHeader: DexKitLocator.DexRule

    fun registerDexRules() {
        ruleGetStorage = DexKitLocator.registerMethod(
            id = "sns_core_get_info_storage",
            description = "SnsCore.getSnsInfoStorage()",
        ) {
            matcher {
                usingEqStrings("getSnsInfoStorage", "com.tencent.mm.plugin.sns.model.SnsCore")
            }
        }

        ruleGetBySnsId = DexKitLocator.registerMethod(
            id = "sns_info_get_by_sns_id",
            description = "SnsInfoStorage.get(snsId)",
        ) {
            matcher {
                usingEqStrings(
                    "get",
                    "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
                    "select *,rowid from SnsInfo  where SnsInfo.snsId=",
                )
            }
        }

        ruleReplaceBySnsId = DexKitLocator.registerMethod(
            id = "sns_info_replace_by_sns_id",
            description = "SnsInfoStorage.replaceUserBySnsId(long, SnsInfo)",
        ) {
            matcher {
                usingEqStrings("replaceUserBySnsId", "com.tencent.mm.plugin.sns.storage.SnsInfoStorage")
            }
        }

        ruleSetLikedHeader = DexKitLocator.registerMethod(
            id = "sns_detail_set_liked_header",
            description = "SnsCommentDetailUI.setLikedHeader(List, boolean)",
        ) {
            matcher {
                usingEqStrings("setLikedHeader", "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI")
            }
        }
    }

    fun queryRecentPosts(limit: Int = 35): List<SnsPostSummary> = runCatching {
        val cursor = WcdbBridge.rawQuerySns(
            "SELECT snsId, userName, createTime, content, attrBuf FROM SnsInfo " +
                "WHERE snsId != 0 ORDER BY createTime DESC LIMIT ?",
            arrayOf(limit.toString()),
        ) ?: return@runCatching emptyList()

        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val posts = mutableListOf<SnsPostSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val snsId = c.getLong(0)
                if (snsId == 0L) continue
                val userName = c.getString(1).orEmpty()
                val createSec = c.getInt(2)
                val content = c.getBlob(3)
                val attrBuf = c.getBlob(4)
                val author = if (userName.isNotEmpty()) {
                    ChatRepository.resolveSenderDisplayName(userName, null, null)
                } else {
                    "动态"
                }
                val caption = ProtoLite.readUtf8Field(content, 5)
                    ?.replace('\n', ' ')
                    ?.trim()
                    ?.take(42)
                    ?.ifEmpty { null }
                    ?: "[图文动态]"
                val timeText = if (createSec > 0) fmt.format(Date(createSec * 1000L)) else ""
                val likes = ProtoLite.readSnsObjectLikes(attrBuf)
                posts += SnsPostSummary(snsId, author, caption, timeText, likes)
            }
        }
        posts
    }.getOrDefault(emptyList())

    fun extractSnsIdFromActivity(activity: Activity): Long? = runCatching {
        val intent = activity.intent ?: return@runCatching null
        val strId = intent.getStringExtra("INTENT_SNSID")?.removePrefix("sns_table_")?.trim()
        strId?.toLongOrNull()?.takeIf { it != 0L }?.let { return@runCatching it }

        val longId = intent.getLongExtra("INTENT_SNSID", 0L)
        if (longId != 0L) return@runCatching longId

        val localStr = intent.getStringExtra("INTENT_SNS_LOCAL_ID")?.removePrefix("sns_table_")?.trim()
        val localId = localStr?.toIntOrNull() ?: intent.getIntExtra("INTENT_SNS_LOCAL_ID", -1)
        if (localId >= 0) {
            WcdbBridge.rawQuerySns(
                "SELECT snsId FROM SnsInfo WHERE rowid = ? LIMIT 1",
                arrayOf(localId.toString()),
            )?.use { c ->
                if (c.moveToFirst()) return@runCatching c.getLong(0).takeIf { it != 0L }
            }
        }
        null
    }.getOrNull()

    /**
     * Patches `SnsInfo.field_attrBuf` in-place via `SnsInfoStorage` so both `SnsMicroMsg.db`
     * and WeChat's UI notify pipeline (`ImproveDataUIC` / `SnsCommentDetailUI`) reflect changes immediately.
     */
    fun applyLikesToPost(
        snsId: Long,
        desiredFakeLikes: List<ProtoLite.LikeEntry>,
        removedFakeUsernames: Set<String>,
        currentActivity: Activity?,
    ): Pair<Boolean, Int> {
        val storage = ruleGetStorage.resolveMethod()?.invoke(null) ?: return false to 0
        val getMethod = ruleGetBySnsId.resolveMethod() ?: return false to 0
        val replaceMethod = ruleReplaceBySnsId.resolveMethod() ?: return false to 0

        return runCatching {
            val snsInfo = getMethod.invoke(storage, snsId) ?: return false to 0
            val attrField = ReflectHelper.findField(snsInfo.javaClass, "field_attrBuf") ?: return false to 0
            val oldAttrBuf = (attrField.get(snsInfo) as? ByteArray) ?: ByteArray(0)
            val newAttrBuf = ProtoLite.applyLikesToSnsObject(oldAttrBuf, removedFakeUsernames, desiredFakeLikes)
                ?: oldAttrBuf
            val totalLikes = ProtoLite.readSnsObjectLikes(newAttrBuf).size

            if (newAttrBuf !== oldAttrBuf) {
                attrField.set(snsInfo, newAttrBuf)
                replaceMethod.invoke(storage, snsId, snsInfo)
                ReflectHelper.findMethod(storage.javaClass, "doNotify", emptyList())?.invoke(storage)
            }
            refreshDetailActivityIfVisible(currentActivity, newAttrBuf)
            AcidLogger.i(TAG, "applied fake likes on snsId=$snsId -> totalLikes=$totalLikes")
            true to totalLikes
        }.onFailure {
            AcidLogger.w(TAG, "applyLikesToPost failed for snsId=$snsId", it)
        }.getOrDefault(false to 0)
    }

    private fun refreshDetailActivityIfVisible(activity: Activity?, attrBuf: ByteArray) {
        if (activity == null || !activity.javaClass.name.endsWith("SnsCommentDetailUI")) return
        val setLikedHeader = ruleSetLikedHeader.resolveMethod() ?: return
        runCatching {
            val snsObjCls = Class.forName(SNS_OBJECT_CLASS, false, HostContext.classLoader)
            val snsObj = snsObjCls.getDeclaredConstructor().newInstance()
            val parseMethod = ReflectHelper.findMethod(snsObjCls, "parseFrom", listOf(ByteArray::class.java))
                ?: return
            parseMethod.invoke(snsObj, attrBuf)
            val likeList = ReflectHelper.findField(snsObjCls, "LikeUserList")?.get(snsObj) as? List<*> ?: return
            val commentList = ReflectHelper.findField(snsObjCls, "CommentUserList")?.get(snsObj) as? List<*>
            val commentsEmpty = commentList?.isEmpty() ?: true
            activity.runOnUiThread {
                runCatching { setLikedHeader.invoke(activity, likeList, commentsEmpty) }
            }
        }.onFailure {
            AcidLogger.w(TAG, "refreshDetailActivityIfVisible failed", it)
        }
    }
}
