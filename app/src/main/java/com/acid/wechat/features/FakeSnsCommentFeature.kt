package com.acid.wechat.features

import android.app.Activity
import android.widget.Toast
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.ui.overlay.AcidModal
import com.acid.wechat.ui.overlay.AcidModalSpec
import com.acid.wechat.ui.overlay.ChecklistItem
import com.acid.wechat.ui.overlay.ModalBlock
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.SnsContextMenuHook
import com.acid.wechat.wechat.SnsFakeCommentStore
import com.acid.wechat.wechat.SnsProto
import com.acid.wechat.wechat.SnsRepository
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moments Fake Comments feature:
 * - Injects "伪评论" (and "清除伪评论" when present) via [SnsContextMenuHook].
 * - Uses [AcidModal] to pick a friend and input comment text, then patches `SnsInfo.attrBuf`.
 * - Preserves fake comments across server syncs via WcdbBridge SnsInfo update interception.
 */
object FakeSnsCommentFeature : AcidFeature, SnsContextMenuHook.Provider {
    private const val TAG = "FakeSnsComment"
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val KEY_FRIENDS = "friends"
    private const val KEY_COMMENT_TEXT = "comment_text"
    private const val MENU_ITEM_FAKE_COMMENT_ID = 198903
    private const val MENU_ITEM_CLEAR_FAKE_COMMENT_ID = 198904
    private const val MENU_TITLE_FAKE_COMMENT = "伪评论"
    private const val MENU_TITLE_CLEAR_FAKE_COMMENT = "清除伪评论"

    private val appliedCount = AtomicInteger(0)

    override val id = "fake_comment"
    override val spec = ConfigSchema.FAKE_COMMENT_ENABLED

    override fun registerDexRules() {
        SnsContextMenuHook.registerDexRules()
    }

    override fun onInit() {
        SnsFakeCommentStore.load()
        SnsContextMenuHook.registerProvider(this)
        hookSnsSyncPreserve()
    }

    override fun menuItemsFor(snsId: Long): List<SnsContextMenuHook.MenuSpec> {
        if (!FeatureRegistry.isEnabled(this)) return emptyList()
        val list = ArrayList<SnsContextMenuHook.MenuSpec>(2)
        list += SnsContextMenuHook.MenuSpec(MENU_ITEM_FAKE_COMMENT_ID, MENU_TITLE_FAKE_COMMENT)
        if (snsId != 0L && SnsFakeCommentStore.get(snsId).isNotEmpty()) {
            list += SnsContextMenuHook.MenuSpec(MENU_ITEM_CLEAR_FAKE_COMMENT_ID, MENU_TITLE_CLEAR_FAKE_COMMENT)
        }
        return list
    }

    override fun onMenuSelected(itemId: Int, activity: Activity, snsId: Long): Boolean {
        if (!FeatureRegistry.isEnabled(this)) return false
        return when (itemId) {
            MENU_ITEM_FAKE_COMMENT_ID -> {
                openModal(activity, snsId)
                true
            }
            MENU_ITEM_CLEAR_FAKE_COMMENT_ID -> {
                clearFakeCommentsForPost(activity, snsId)
                true
            }
            else -> false
        }
    }

    private fun openModal(activity: Activity, snsId: Long) {
        val post = SnsRepository.queryPostSummary(snsId)
        val friends = ChatRepository.queryFriends(1000)
        val existingFakeCount = SnsFakeCommentStore.get(snsId).size
        val realCount = post?.comments?.count { it.commentId !in SnsFakeCommentStore.getIds(snsId) } ?: 0

        val items = friends.map { f ->
            ChecklistItem(
                id = f.username,
                title = f.displayName,
                subtitle = f.subtitleLabel(),
                avatarUsername = f.username,
            )
        }
        val authorLine = post?.headerLine() ?: "当前朋友圈"

        AcidModal.show(
            activity = activity,
            spec = AcidModalSpec(
                title = MENU_TITLE_FAKE_COMMENT,
                primaryButtonText = "添加评论",
                blocks = listOf(
                    ModalBlock.Preview(title = authorLine, body = post?.caption ?: "[当前动态]"),
                    ModalBlock.TextInput(
                        key = KEY_COMMENT_TEXT,
                        label = "评论内容",
                        placeholder = "输入要伪造的评论内容",
                    ),
                    ModalBlock.Checklist(
                        key = KEY_FRIENDS,
                        items = items,
                        multiSelect = false,
                        searchPlaceholder = "搜索评论好友备注 / 昵称",
                        emptyText = "暂未获取到好友列表，请先打开任意聊天或通讯录",
                        lockedCountOffset = realCount,
                        headerTemplate = "真评 {locked} · 伪评 $existingFakeCount",
                        footerTemplate = "",
                    ),
                ),
                onSubmit = { result ->
                    val username = result.selectedIds(KEY_FRIENDS).firstOrNull()?.trim().orEmpty()
                    val text = result.stringValue(KEY_COMMENT_TEXT).trim()
                    if (username.isEmpty() || text.isEmpty()) {
                        val warn = if (text.isEmpty()) "请先输入评论内容" else "请选择评论好友"
                        activity.runOnUiThread {
                            Toast.makeText(activity, warn, Toast.LENGTH_SHORT).show()
                        }
                        false
                    } else {
                        appendFakeComment(activity, snsId, username, text)
                    }
                },
            ),
        )
    }

    private fun appendFakeComment(
        activity: Activity,
        snsId: Long,
        username: String,
        content: String,
    ): Boolean {
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val nickname = ChatRepository.resolveSenderDisplayName(username, null, null)
        val entry = SnsProto.CommentEntry(
            commentId = SnsFakeCommentStore.nextCommentId(),
            username = username,
            nickname = nickname,
            content = content,
            createTime = nowSec,
        )
        SnsFakeCommentStore.add(snsId, entry)
        val (ok, totalComments) = SnsRepository.applyCommentsToPost(
            snsId = snsId,
            addComments = listOf(entry),
            removeCommentIds = emptySet(),
            currentActivity = activity,
        )

        if (ok) {
            appliedCount.incrementAndGet()
            FeatureRegistry.recordHit(id)
        }
        activity.runOnUiThread {
            val msg = if (ok) "已添加「$nickname」的伪评论（共 $totalComments 条）" else "写入评论失败，请先刷新朋友圈后再试"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    private fun clearFakeCommentsForPost(activity: Activity, snsId: Long) {
        val removed = SnsFakeCommentStore.clear(snsId)
        if (removed.isEmpty()) {
            Toast.makeText(activity, "当前动态没有伪评论记录", Toast.LENGTH_SHORT).show()
            return
        }
        val removeIds = removed.mapTo(HashSet()) { it.commentId }
        Thread {
            val (ok, totalComments) = SnsRepository.applyCommentsToPost(
                snsId = snsId,
                addComments = emptyList(),
                removeCommentIds = removeIds,
                currentActivity = activity,
            )
            if (ok) FeatureRegistry.recordHit(id)
            activity.runOnUiThread {
                val msg = if (ok) "已清除伪评论（剩余真实评论 $totalComments）" else "清除失败，请刷新朋友圈后再试"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun hookSnsSyncPreserve() {
        WcdbBridge.addUpdateListener { table, values, _, _, _ ->
            if (table != TBL_SNS_INFO || !FeatureRegistry.isEnabled(this)) return@addUpdateListener
            if (!values.containsKey("attrBuf")) return@addUpdateListener

            runCatching {
                val snsId = values.getAsLong("snsId") ?: return@runCatching
                if (snsId == 0L) return@runCatching
                val fakeComments = SnsFakeCommentStore.get(snsId)
                if (fakeComments.isEmpty()) return@runCatching

                val attrBuf = values.getAsByteArray("attrBuf") ?: return@runCatching
                if (attrBuf.isEmpty()) return@runCatching
                val patched = SnsProto.applyComments(attrBuf, emptySet(), fakeComments) ?: return@runCatching
                values.put("attrBuf", patched)
            }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "sync preserve fake comments failed", it)
            }
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("appliedCount", appliedCount.get())
        put("storedPostCount", SnsFakeCommentStore.postCount())
        put("storedCommentCount", SnsFakeCommentStore.totalCommentCount())
    }
}
