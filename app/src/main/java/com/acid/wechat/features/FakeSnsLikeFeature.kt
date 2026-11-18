package com.acid.wechat.features

import android.app.Activity
import android.app.Dialog
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.ProtoLite
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.ui.overlay.FloatingActionRegistry
import com.acid.wechat.ui.overlay.FloatingActionSpec
import com.acid.wechat.ui.overlay.FloatingSheetDialog
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.SnsFakeLikeStore
import com.acid.wechat.wechat.SnsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moments Fake Likes feature:
 * - Registers a contextual floating overlay entry on Moments pages (timeline, album, detail).
 * - Lets users hand-pick friends or randomly select N friends to generate fake likes in SnsInfo.attrBuf.
 * - Preserves fake likes across server syncs via WcdbBridge SnsInfo update interception.
 */
object FakeSnsLikeFeature : AcidFeature {
    private const val TAG = "FakeSnsLike"
    private const val TBL_SNS_INFO = "SnsInfo"

    private val momentsActivities = setOf(
        "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
        "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI",
        "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI",
        "com.tencent.mm.plugin.sns.ui.SnsUserUI",
    )

    private val appliedCount = AtomicInteger(0)

    override val id = "fake_like"
    override val name = "朋友圈伪集赞"
    override val category = FeatureCategory.MOMENTS
    override val group = "伪集赞"
    override val description = "为指定朋友圈自选好友或随机指定人数集赞"
    override val configKey = ConfigStore.KEY_FAKE_LIKE_ENABLED

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption(
            title = "朋友圈悬浮入口",
            configKey = ConfigStore.KEY_FAKE_LIKE_FLOAT_BUTTON,
            group = group,
            defaultValue = true,
            dependsOn = ConfigStore.KEY_FAKE_LIKE_ENABLED,
        ),
    )

    override fun registerDexRules() {
        SnsRepository.registerDexRules()
    }

    override fun onInit() {
        SnsFakeLikeStore.load()
        registerFloatingEntry()
        hookSnsSyncPreserve()
    }

    private fun registerFloatingEntry() {
        FloatingActionRegistry.register(
            FloatingActionSpec(
                id = "fake_sns_like_sheet",
                label = "伪集赞",
                isEnabled = {
                    FeatureRegistry.isEnabled(this@FakeSnsLikeFeature) &&
                        ConfigStore.isEffective(
                            ConfigStore.KEY_FAKE_LIKE_FLOAT_BUTTON,
                            true,
                            ConfigStore.KEY_FAKE_LIKE_ENABLED,
                        )
                },
                matchesActivity = { activity -> activity.javaClass.name in momentsActivities },
                onTrigger = { activity -> openSheet(activity) },
            ),
        )
    }

    private fun hookSnsSyncPreserve() {
        WcdbBridge.addUpdateListener { table, values, _, _, _ ->
            if (table != TBL_SNS_INFO) return@addUpdateListener
            if (!FeatureRegistry.isEnabled(this@FakeSnsLikeFeature)) return@addUpdateListener
            if (!values.containsKey("attrBuf")) return@addUpdateListener

            runCatching {
                val snsId = values.getAsLong("snsId") ?: return@runCatching
                if (snsId == 0L) return@runCatching
                val fakeLikes = SnsFakeLikeStore.get(snsId)
                if (fakeLikes.isEmpty()) return@runCatching

                val attrBuf = values.getAsByteArray("attrBuf") ?: return@runCatching
                if (attrBuf.isEmpty()) return@runCatching
                val patched = ProtoLite.applyLikesToSnsObject(attrBuf, emptySet(), fakeLikes) ?: return@runCatching
                values.put("attrBuf", patched)
            }.onFailure {
                AcidLogger.w(TAG, "sync preserve fake likes failed", it)
            }
        }
    }

    private fun openSheet(activity: Activity) {
        FloatingSheetDialog.show(
            activity = activity,
            assetPath = "assets/fake_like_sheet.html",
            bridgeName = "FakeLikeBridge",
            heightRatio = 0.84f,
            bridgeFactory = { dialog, _ -> FakeLikeBridge(activity, dialog) },
        )
    }

    private class FakeLikeBridge(
        private val activity: Activity,
        private val dialog: Dialog,
    ) {
        @JavascriptInterface
        fun getData(): String {
            val targetSnsId = SnsRepository.extractSnsIdFromActivity(activity)
            val posts = SnsRepository.queryRecentPosts(40)
            val friends = ChatRepository.queryFriends(1000)

            val postArr = JSONArray()
            for (p in posts) {
                val fakeUsers = SnsFakeLikeStore.getUsernames(p.snsId)
                val realUsers = JSONArray()
                val fakeArr = JSONArray()
                for (like in p.likes) {
                    if (like.username in fakeUsers) {
                        fakeArr.put(like.username)
                    } else {
                        realUsers.put(like.username)
                    }
                }
                postArr.put(JSONObject().apply {
                    put("snsId", p.snsId.toString())
                    put("author", p.authorName)
                    put("caption", p.caption)
                    put("timeText", p.timeText)
                    put("realUsers", realUsers)
                    put("fakeUsers", fakeArr)
                    put("totalLikes", p.likes.size)
                })
            }

            val friendArr = JSONArray()
            for (f in friends) {
                friendArr.put(JSONObject().apply {
                    put("username", f.username)
                    put("displayName", f.displayName)
                    put("subName", if (f.remark.isNotEmpty() && f.nickname.isNotEmpty() && f.remark != f.nickname) f.nickname else f.alias)
                })
            }

            return JSONObject().apply {
                put("targetSnsId", targetSnsId?.toString() ?: JSONObject.NULL)
                put("posts", postArr)
                put("friends", friendArr)
            }.toString()
        }

        @JavascriptInterface
        fun applyFakeLikes(snsIdStr: String, selectedUsernamesJson: String): String {
            val snsId = snsIdStr.toLongOrNull()
                ?: return """{"ok":false,"error":"无效的 snsId"}"""
            val arr = runCatching { JSONArray(selectedUsernamesJson) }.getOrNull()
                ?: return """{"ok":false,"error":"参数解析失败"}"""

            val nowSec = (System.currentTimeMillis() / 1000L).toInt()
            val desiredEntries = ArrayList<ProtoLite.LikeEntry>(arr.length())
            val newSet = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val username = arr.optString(i).trim()
                if (username.isEmpty() || !newSet.add(username)) continue
                val displayName = ChatRepository.resolveSenderDisplayName(username, null, null)
                desiredEntries += ProtoLite.LikeEntry(
                    username = username,
                    nickname = displayName,
                    createTime = nowSec - i,
                )
            }

            val oldSet = SnsFakeLikeStore.getUsernames(snsId)
            val removed = oldSet.filterTo(HashSet()) { it !in newSet }

            SnsFakeLikeStore.set(snsId, desiredEntries)
            val (ok, totalLikes) = SnsRepository.applyLikesToPost(
                snsId = snsId,
                desiredFakeLikes = desiredEntries,
                removedFakeUsernames = removed,
                currentActivity = activity,
            )

            if (ok) {
                appliedCount.incrementAndGet()
                FeatureRegistry.recordHit(id)
                activity.runOnUiThread {
                    Toast.makeText(activity, "已更新集赞（共 $totalLikes 赞）", Toast.LENGTH_SHORT).show()
                    if (dialog.isShowing) dialog.dismiss()
                }
            } else {
                activity.runOnUiThread {
                    Toast.makeText(activity, "写入点赞失败，请先刷新朋友圈后再试", Toast.LENGTH_SHORT).show()
                }
            }
            return JSONObject().apply {
                put("ok", ok)
                put("totalLikes", totalLikes)
            }.toString()
        }

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("appliedCount", appliedCount.get())
    }
}
