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
import com.acid.wechat.wechat.SnsFakeLikeStore
import com.acid.wechat.wechat.SnsProto
import com.acid.wechat.wechat.SnsRepository
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moments Fake Likes feature:
 * - Injects "伪集赞" (or "清除伪集赞" when already fake-liked) via [SnsContextMenuHook].
 * - Uses the standard [AcidModal] floating dialog pre-targeted to the long-pressed Moment `snsId`.
 * - Preserves fake likes across server syncs via WcdbBridge SnsInfo update interception.
 */
object FakeSnsLikeFeature : AcidFeature, SnsContextMenuHook.Provider {
    private const val TAG = "FakeSnsLike"
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val KEY_FRIENDS = "friends"
    private const val MENU_ITEM_FAKE_LIKE_ID = 198901
    private const val MENU_ITEM_CLEAR_FAKE_LIKE_ID = 198902
    private const val MENU_TITLE_FAKE_LIKE = "伪集赞"
    private const val MENU_TITLE_CLEAR_FAKE_LIKE = "清除伪集赞"

    private val appliedCount = AtomicInteger(0)

    override val id = "fake_like"
    override val spec = ConfigSchema.FAKE_LIKE_ENABLED

    override fun registerDexRules() {
        SnsContextMenuHook.registerDexRules()
    }

    override fun onInit() {
        SnsFakeLikeStore.load()
        SnsContextMenuHook.registerProvider(this)
        hookSnsSyncPreserve()
    }

    override fun menuItemsFor(snsId: Long): List<SnsContextMenuHook.MenuSpec> {
        if (!FeatureRegistry.isEnabled(this)) return emptyList()
        val hasFakeLikes = snsId != 0L && SnsFakeLikeStore.getUsernames(snsId).isNotEmpty()
        return if (hasFakeLikes) {
            listOf(SnsContextMenuHook.MenuSpec(MENU_ITEM_CLEAR_FAKE_LIKE_ID, MENU_TITLE_CLEAR_FAKE_LIKE))
        } else {
            listOf(SnsContextMenuHook.MenuSpec(MENU_ITEM_FAKE_LIKE_ID, MENU_TITLE_FAKE_LIKE))
        }
    }

    override fun onMenuSelected(itemId: Int, activity: Activity, snsId: Long): Boolean {
        if (!FeatureRegistry.isEnabled(this)) return false
        return when (itemId) {
            MENU_ITEM_CLEAR_FAKE_LIKE_ID -> {
                clearFakeLikesForPost(activity, snsId)
                true
            }
            MENU_ITEM_FAKE_LIKE_ID -> {
                openModal(activity, snsId)
                true
            }
            else -> false
        }
    }

    private fun openModal(activity: Activity, snsId: Long) {
        val post = SnsRepository.queryPostSummary(snsId)
        val friends = ChatRepository.queryFriends(1000)
        val fakeUsers = SnsFakeLikeStore.getUsernames(snsId)
        val realSet = HashSet<String>()
        post?.likes?.forEach { like ->
            if (like.username !in fakeUsers) realSet += like.username
        }

        val items = friends.map { f ->
            ChecklistItem(
                id = f.username,
                title = f.displayName,
                subtitle = f.subtitleLabel(),
                avatarUsername = f.username,
                checked = f.username in fakeUsers,
                lockedBadge = if (f.username in realSet) "已真赞" else null,
            )
        }
        val authorLine = post?.headerLine() ?: "当前朋友圈"

        AcidModal.show(
            activity = activity,
            spec = AcidModalSpec(
                title = MENU_TITLE_FAKE_LIKE,
                primaryButtonText = "应用集赞",
                blocks = listOf(
                    ModalBlock.Preview(title = authorLine, body = post?.caption ?: "[当前动态]"),
                    ModalBlock.RandomCountBar(
                        checklistKey = KEY_FRIENDS,
                        defaultCount = 10,
                        placeholder = "赞数",
                        pickLabel = "随机勾选",
                        clearLabel = "清空",
                    ),
                    ModalBlock.Checklist(
                        key = KEY_FRIENDS,
                        items = items,
                        multiSelect = true,
                        searchPlaceholder = "搜索好友备注 / 昵称",
                        filterSelectedLabel = "已选",
                        emptyText = "暂未获取到好友列表，请先打开任意聊天或通讯录",
                        lockedCountOffset = realSet.size,
                        headerTemplate = "真赞 {locked} · 合计 {total}",
                        footerTemplate = "伪赞 {selected} · 共 {total}",
                    ),
                ),
                onSubmit = { result ->
                    applySelection(activity, snsId, result.selectedIds(KEY_FRIENDS))
                },
            ),
        )
    }

    private fun applySelection(activity: Activity, snsId: Long, selectedUsernames: List<String>): Boolean {
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val desiredEntries = ArrayList<SnsProto.LikeEntry>(selectedUsernames.size)
        val newSet = LinkedHashSet<String>()
        for ((idx, raw) in selectedUsernames.withIndex()) {
            val username = raw.trim()
            if (username.isEmpty() || !newSet.add(username)) continue
            desiredEntries += SnsProto.LikeEntry(
                username = username,
                nickname = ChatRepository.resolveSenderDisplayName(username, null, null),
                createTime = nowSec - idx,
            )
        }

        val removed = SnsFakeLikeStore.getUsernames(snsId).filterTo(HashSet()) { it !in newSet }
        SnsFakeLikeStore.set(snsId, desiredEntries)
        val (ok, totalLikes) = SnsRepository.applyLikesToPost(snsId, desiredEntries, removed, activity)

        if (ok) {
            appliedCount.incrementAndGet()
            FeatureRegistry.recordHit(id)
        }
        activity.runOnUiThread {
            val msg = if (ok) "已更新集赞（共 $totalLikes 赞）" else "写入点赞失败，请先刷新朋友圈后再试"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    private fun clearFakeLikesForPost(activity: Activity, snsId: Long) {
        val fakeUsernames = SnsFakeLikeStore.getUsernames(snsId)
        if (fakeUsernames.isEmpty()) {
            Toast.makeText(activity, "当前动态没有伪集赞记录", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            SnsFakeLikeStore.set(snsId, emptyList())
            val (ok, totalLikes) = SnsRepository.applyLikesToPost(snsId, emptyList(), fakeUsernames, activity)
            if (ok) FeatureRegistry.recordHit(id)
            activity.runOnUiThread {
                val msg = if (ok) "已清除伪集赞（真实赞 $totalLikes）" else "清除失败，请刷新朋友圈后再试"
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
                val fakeLikes = SnsFakeLikeStore.get(snsId)
                if (fakeLikes.isEmpty()) return@runCatching

                val attrBuf = values.getAsByteArray("attrBuf") ?: return@runCatching
                if (attrBuf.isEmpty()) return@runCatching
                val patched = SnsProto.applyLikes(attrBuf, emptySet(), fakeLikes) ?: return@runCatching
                values.put("attrBuf", patched)
            }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "sync preserve fake likes failed", it)
            }
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("appliedCount", appliedCount.get())
        put("storedPostCount", SnsFakeLikeStore.postCount())
    }
}
