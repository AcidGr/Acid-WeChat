package com.acid.wechat.features

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.widget.Toast
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureCategory
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.ProtoLite
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.ui.overlay.FloatingSheetDialog
import com.acid.wechat.wechat.ChatRepository
import com.acid.wechat.wechat.SnsFakeLikeStore
import com.acid.wechat.wechat.SnsRepository
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moments Fake Likes feature:
 * - Injects "伪集赞" (or "清除伪集赞" when already fake-liked) into WeChat's native Moment long-press menu.
 * - Opens a centered floating panel pre-targeted to the long-pressed Moment `snsId`, displaying
 *   cached WeChat avatars, friend search, and custom-count random selection.
 * - Preserves fake likes across server syncs via WcdbBridge SnsInfo update interception.
 */
object FakeSnsLikeFeature : AcidFeature {
    private const val TAG = "FakeSnsLike"
    private const val TBL_SNS_INFO = "SnsInfo"
    private const val MENU_ITEM_FAKE_LIKE_ID = 198901
    private const val MENU_ITEM_CLEAR_FAKE_LIKE_ID = 198902
    private const val MENU_TITLE_FAKE_LIKE = "伪集赞"
    private const val MENU_TITLE_CLEAR_FAKE_LIKE = "清除伪集赞"

    private data class SelectedPostContext(
        val snsId: Long,
        val activity: Activity?,
    )

    private lateinit var ruleContextMenuCreate: DexKitLocator.DexRule
    private lateinit var ruleMenuBindSelected: DexKitLocator.DexRule
    private lateinit var ruleMenuItemSelected: DexKitLocator.DexRule

    private val selectedPosts = Collections.synchronizedMap(WeakHashMap<Any, SelectedPostContext>())
    private val viewSnsIds = Collections.synchronizedMap(WeakHashMap<View, Long>())

    @Volatile
    private var latestMenuSnsId: Long = 0L

    private val appliedCount = AtomicInteger(0)

    override val id = "fake_like"
    override val name = "朋友圈伪集赞"
    override val category = FeatureCategory.MOMENTS
    override val group = "伪集赞"
    override val description = "长按朋友圈菜单增加「伪集赞」，支持自选好友或随机指定人数"
    override val configKey = ConfigStore.KEY_FAKE_LIKE_ENABLED

    override fun registerDexRules() {
        SnsRepository.registerDexRules()

        ruleContextMenuCreate = DexKitLocator.registerMethod(
            id = "sns_context_menu_create",
            description = "TimelineOnCreateContextMenuListener.onCreateContextMenu",
        ) {
            matcher {
                usingEqStrings(
                    "onCreateContextMenu",
                    "com.tencent.mm.plugin.sns.ui.listener.TimelineOnCreateContextMenuListener",
                )
            }
        }

        ruleMenuBindSelected = DexKitLocator.registerMethod(
            id = "sns_menu_bind_selected",
            description = "TimeLineMMMenuItemSelectedListener.bindOnSelectedView",
        ) {
            matcher {
                usingEqStrings(
                    "bindOnSelectedView",
                    "com.tencent.mm.plugin.sns.ui.listener.TimeLineMMMenuItemSelectedListener",
                )
            }
        }

        ruleMenuItemSelected = DexKitLocator.registerMethod(
            id = "sns_menu_item_selected",
            description = "TimeLineMMMenuItemSelectedListener.onMMMenuItemSelected",
        ) {
            matcher {
                usingEqStrings(
                    "onMMMenuItemSelected",
                    "com.tencent.mm.plugin.sns.ui.listener.TimeLineMMMenuItemSelectedListener",
                )
            }
        }
    }

    override fun onInit() {
        SnsFakeLikeStore.load()
        hookContextMenuCreate()
        hookMenuBindSelected()
        hookMenuItemSelected()
        hookSnsSyncPreserve()
    }

    private fun hookContextMenuCreate() {
        val m = ruleContextMenuCreate.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    latestMenuSnsId = 0L
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@FakeSnsLikeFeature)) return
                    val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
                    val view = param.args?.getOrNull(1) as? View
                    val snsId = (latestMenuSnsId.takeIf { it != 0L } ?: view?.let { viewSnsIds[it] })
                        ?: unwrapActivity(view?.context)?.let(SnsRepository::extractSnsIdFromIntent)
                        ?: 0L
                    val hasFakeLikes = snsId != 0L && SnsFakeLikeStore.getUsernames(snsId).isNotEmpty()
                    if (hasFakeLikes) {
                        if (menu.findItem(MENU_ITEM_CLEAR_FAKE_LIKE_ID) == null) {
                            menu.add(0, MENU_ITEM_CLEAR_FAKE_LIKE_ID, 0, MENU_TITLE_CLEAR_FAKE_LIKE)
                        }
                    } else if (menu.findItem(MENU_ITEM_FAKE_LIKE_ID) == null) {
                        menu.add(0, MENU_ITEM_FAKE_LIKE_ID, 0, MENU_TITLE_FAKE_LIKE)
                    }
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookContextMenuCreate failed", it)
        }
    }

    private fun hookMenuBindSelected() {
        val m = ruleMenuBindSelected.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@FakeSnsLikeFeature)) return
                    val listener = param.thisObject ?: return
                    val view = param.args?.getOrNull(0) as? View
                    val snsTableId = param.args?.getOrNull(1) as? String
                    val tlObj = param.args?.getOrNull(2)
                    val activity = unwrapActivity(view?.context) ?: findActivityField(listener)
                    val snsId = SnsRepository.resolveSnsId(tlObj, snsTableId, activity) ?: return
                    selectedPosts[listener] = SelectedPostContext(snsId, activity)
                    if (view != null) viewSnsIds[view] = snsId
                    latestMenuSnsId = snsId
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookMenuBindSelected failed", it)
        }
    }

    private fun hookMenuItemSelected() {
        val m = ruleMenuItemSelected.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@FakeSnsLikeFeature)) return
                    val menuItem = param.args?.getOrNull(0) as? MenuItem ?: return
                    val itemId = menuItem.itemId
                    if (itemId != MENU_ITEM_FAKE_LIKE_ID && itemId != MENU_ITEM_CLEAR_FAKE_LIKE_ID) return

                    param.result = null
                    val listener = param.thisObject ?: return
                    val ctx = selectedPosts[listener]
                    val activity = ctx?.activity ?: findActivityField(listener)
                    val snsId = ctx?.snsId ?: activity?.let(SnsRepository::extractSnsIdFromIntent)
                    if (activity == null || snsId == null || snsId == 0L) {
                        AcidLogger.w(TAG, "cannot handle menu action: activity=$activity snsId=$snsId")
                        return
                    }
                    if (itemId == MENU_ITEM_CLEAR_FAKE_LIKE_ID) {
                        clearFakeLikesForPost(activity, snsId)
                    } else {
                        openSheet(activity, snsId)
                    }
                }
            })
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookMenuItemSelected failed", it)
        }
    }

    private fun clearFakeLikesForPost(activity: Activity, snsId: Long) {
        val fakeUsernames = SnsFakeLikeStore.getUsernames(snsId)
        if (fakeUsernames.isEmpty()) {
            Toast.makeText(activity, "当前动态没有伪集赞记录", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            SnsFakeLikeStore.set(snsId, emptyList())
            val (ok, totalLikes) = SnsRepository.applyLikesToPost(
                snsId = snsId,
                desiredFakeLikes = emptyList(),
                removedFakeUsernames = fakeUsernames,
                currentActivity = activity,
            )
            if (ok) {
                FeatureRegistry.recordHit(id)
                activity.runOnUiThread {
                    Toast.makeText(activity, "已清除伪集赞（真实赞 $totalLikes）", Toast.LENGTH_SHORT).show()
                }
            } else {
                activity.runOnUiThread {
                    Toast.makeText(activity, "清除失败，请刷新朋友圈后再试", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

    private fun openSheet(activity: Activity, snsId: Long) {
        FloatingSheetDialog.show(
            activity = activity,
            assetPath = "assets/fake_like_sheet.html",
            bridgeName = "FakeLikeBridge",
            widthRatio = 0.90f,
            heightRatio = 0.68f,
            requestInterceptor = ::interceptAvatarRequest,
            bridgeFactory = { dialog, _ -> FakeLikeBridge(activity, dialog, snsId) },
        )
    }

    private fun interceptAvatarRequest(url: Uri): WebResourceResponse? {
        if (url.host != "acid.local") return null
        val path = url.path ?: return null
        if (!path.startsWith("/avatar/")) return null
        val username = Uri.decode(path.removePrefix("/avatar/")).trim()
        val (file, mime) = ChatRepository.findCachedAvatar(username) ?: return null
        return WebResourceResponse(
            mime,
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "max-age=300"),
            FileInputStream(file),
        )
    }

    private fun unwrapActivity(ctx: Context?): Activity? {
        var cur = ctx
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    private fun findActivityField(obj: Any): Activity? {
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (Activity::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    (f.get(obj) as? Activity)?.let { return it }
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private class FakeLikeBridge(
        private val activity: Activity,
        private val dialog: Dialog,
        private val snsId: Long,
    ) {
        @JavascriptInterface
        fun getData(): String {
            val post = SnsRepository.queryPostSummary(snsId)
            val friends = ChatRepository.queryFriends(1000)

            val fakeUsers = SnsFakeLikeStore.getUsernames(snsId)
            val realUsers = JSONArray()
            val fakeArr = JSONArray()
            if (post != null) {
                for (like in post.likes) {
                    if (like.username in fakeUsers) {
                        fakeArr.put(like.username)
                    } else {
                        realUsers.put(like.username)
                    }
                }
            }

            val friendArr = JSONArray()
            for (f in friends) {
                friendArr.put(JSONObject().apply {
                    put("username", f.username)
                    put("displayName", f.displayName)
                    put(
                        "subName",
                        if (f.remark.isNotEmpty() && f.nickname.isNotEmpty() && f.remark != f.nickname) {
                            f.nickname
                        } else {
                            f.alias
                        },
                    )
                })
            }

            return JSONObject().apply {
                put("snsId", snsId.toString())
                put("author", post?.authorName ?: "当前朋友圈")
                put("caption", post?.caption ?: "")
                put("timeText", post?.timeText ?: "")
                put("realUsers", realUsers)
                put("fakeUsers", fakeArr)
                put("friends", friendArr)
            }.toString()
        }

        @JavascriptInterface
        fun applyFakeLikes(selectedUsernamesJson: String): String {
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
