package com.acid.wechat.wechat

import android.app.Activity
import android.widget.BaseAdapter
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ProtoLite
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.core.WcdbBridge
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
        val likes: List<SnsProto.LikeEntry>,
        val comments: List<SnsProto.CommentEntry>,
    ) {
        fun headerLine(): String =
            authorName + if (timeText.isNotEmpty()) " · $timeText" else ""
    }

    private lateinit var ruleGetStorage: DexKitLocator.DexRule
    private lateinit var ruleGetBySnsId: DexKitLocator.DexRule
    private lateinit var ruleReplaceBySnsId: DexKitLocator.DexRule
    private lateinit var ruleSetLikedHeader: DexKitLocator.DexRule
    private val dexRegistered = AtomicBoolean(false)

    fun registerDexRules() {
        if (!dexRegistered.compareAndSet(false, true)) return

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

    fun resolveSnsId(timeLineObject: Any?, snsTableId: String?, activity: Activity?): Long? {
        runCatching {
            val idStr = timeLineObject?.let {
                ReflectHelper.findField(it.javaClass, "Id")?.get(it) as? String
            }?.trim()
            if (!idStr.isNullOrEmpty() && idStr != "0") {
                return BigInteger(idStr).toLong().takeIf { it != 0L }
            }
        }
        val localId = snsTableId?.removePrefix("sns_table_")?.trim()?.toLongOrNull()
        if (localId != null && localId > 0L) {
            WcdbBridge.rawQuerySns(
                "SELECT snsId FROM SnsInfo WHERE rowid = ? LIMIT 1",
                arrayOf(localId.toString()),
            )?.use { c ->
                if (c.moveToFirst()) return c.getLong(0).takeIf { it != 0L }
            }
        }
        return activity?.let(::extractSnsIdFromIntent)
    }

    fun extractSnsIdFromIntent(activity: Activity): Long? = runCatching {
        val intent = activity.intent ?: return@runCatching null
        val strId = intent.getStringExtra("INTENT_SNSID")?.removePrefix("sns_table_")?.trim()
        if (!strId.isNullOrEmpty()) {
            return@runCatching runCatching { BigInteger(strId).toLong() }.getOrNull()?.takeIf { it != 0L }
        }
        val longId = intent.getLongExtra("INTENT_SNSID", 0L)
        if (longId != 0L) return@runCatching longId
        null
    }.getOrNull()

    fun queryPostSummary(snsId: Long): SnsPostSummary? = runCatching {
        val cursor = WcdbBridge.rawQuerySns(
            "SELECT userName, createTime, content, attrBuf FROM SnsInfo WHERE snsId = ? LIMIT 1",
            arrayOf(snsId.toString()),
        ) ?: return@runCatching null

        cursor.use { c ->
            if (!c.moveToFirst()) return@runCatching null
            val userName = c.getString(0).orEmpty()
            val createSec = c.getInt(1)
            val content = c.getBlob(2)
            val attrBuf = c.getBlob(3)
            val author = if (userName.isNotEmpty()) {
                ChatRepository.resolveSenderDisplayName(userName, null, null)
            } else {
                "朋友圈"
            }
            val caption = ProtoLite.readUtf8Field(content, 5)
                ?.replace('\n', ' ')
                ?.trim()
                ?.take(48)
                ?.ifEmpty { null }
                ?: "[图文动态]"
            val timeText = if (createSec > 0) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createSec * 1000L))
            } else {
                ""
            }
            SnsPostSummary(
                snsId = snsId,
                authorName = author,
                caption = caption,
                timeText = timeText,
                likes = SnsProto.readLikes(attrBuf),
                comments = SnsProto.readComments(attrBuf),
            )
        }
    }.getOrNull()

    fun applyLikesToPost(
        snsId: Long,
        desiredFakeLikes: List<SnsProto.LikeEntry>,
        removedFakeUsernames: Set<String>,
        currentActivity: Activity?,
    ): Pair<Boolean, Int> = mutatePostAttrBuf(
        snsId = snsId,
        currentActivity = currentActivity,
        mutator = { old -> SnsProto.applyLikes(old, removedFakeUsernames, desiredFakeLikes) },
        countReader = { buf -> SnsProto.readLikes(buf).size },
    )

    fun applyCommentsToPost(
        snsId: Long,
        addComments: List<SnsProto.CommentEntry>,
        removeCommentIds: Set<Int>,
        currentActivity: Activity?,
    ): Pair<Boolean, Int> = mutatePostAttrBuf(
        snsId = snsId,
        currentActivity = currentActivity,
        mutator = { old -> SnsProto.applyComments(old, removeCommentIds, addComments) },
        countReader = { buf -> SnsProto.readComments(buf).size },
    )

    private fun mutatePostAttrBuf(
        snsId: Long,
        currentActivity: Activity?,
        mutator: (ByteArray) -> ByteArray?,
        countReader: (ByteArray) -> Int,
    ): Pair<Boolean, Int> {
        val storage = ruleGetStorage.resolveMethod()?.invoke(null) ?: return false to 0
        val getMethod = ruleGetBySnsId.resolveMethod() ?: return false to 0
        val replaceMethod = ruleReplaceBySnsId.resolveMethod() ?: return false to 0

        return runCatching {
            val snsInfo = getMethod.invoke(storage, snsId) ?: return false to 0
            val attrField = ReflectHelper.findField(snsInfo.javaClass, "field_attrBuf") ?: return false to 0
            val oldAttrBuf = (attrField.get(snsInfo) as? ByteArray) ?: ByteArray(0)
            val newAttrBuf = mutator(oldAttrBuf) ?: oldAttrBuf
            val count = countReader(newAttrBuf)

            if (newAttrBuf !== oldAttrBuf) {
                attrField.set(snsInfo, newAttrBuf)
                ReflectHelper.findField(snsInfo.javaClass, "contentByteMd5")?.set(snsInfo, null)
                replaceMethod.invoke(storage, snsId, snsInfo)
                ReflectHelper.findMethod(storage.javaClass, "doNotify", emptyList())?.invoke(storage)
            }
            refreshDetailActivityIfVisible(currentActivity, newAttrBuf)
            true to count
        }.onFailure {
            AcidLogger.w(TAG, "mutatePostAttrBuf failed for snsId=$snsId", it)
        }.getOrDefault(false to 0)
    }

    private fun refreshDetailActivityIfVisible(activity: Activity?, attrBuf: ByteArray) {
        if (activity == null || !activity.javaClass.name.endsWith("SnsCommentDetailUI")) return
        runCatching {
            val snsObjCls = Class.forName(SNS_OBJECT_CLASS, false, HostContext.classLoader)
            val snsObj = snsObjCls.getDeclaredConstructor().newInstance()
            val parseMethod = ReflectHelper.findMethod(snsObjCls, "parseFrom", listOf(ByteArray::class.java))
                ?: return
            parseMethod.invoke(snsObj, attrBuf)
            val likeList = ReflectHelper.findField(snsObjCls, "LikeUserList")?.get(snsObj) as? LinkedList<*> ?: return
            val commentList = ReflectHelper.findField(snsObjCls, "CommentUserList")?.get(snsObj) as? LinkedList<*>
            val commentsEmpty = commentList?.isEmpty() ?: true
            val setLikedHeader = ruleSetLikedHeader.resolveMethod()

            activity.runOnUiThread {
                runCatching { setLikedHeader?.invoke(activity, likeList, commentsEmpty) }
                refreshCommentAdapter(activity, commentList, likeList)
            }
        }.onFailure {
            AcidLogger.w(TAG, "refreshDetailActivityIfVisible failed", it)
        }
    }

    private fun refreshCommentAdapter(
        activity: Activity,
        commentList: LinkedList<*>?,
        likeList: LinkedList<*>,
    ) {
        runCatching {
            val adapterField = activity.javaClass.declaredFields.firstOrNull {
                it.type.name.endsWith("CommentListAdapter")
            } ?: return
            adapterField.isAccessible = true
            val adapter = adapterField.get(activity) as? BaseAdapter ?: return
            val listFields = adapter.javaClass.declaredFields.filter {
                LinkedList::class.java.isAssignableFrom(it.type)
            }
            if (listFields.size >= 2) {
                listFields[0].isAccessible = true
                listFields[1].isAccessible = true
                if (commentList != null) listFields[0].set(adapter, commentList)
                listFields[1].set(adapter, likeList)
            }
            adapter.notifyDataSetChanged()
        }
    }
}
