package com.acid.wechat.wechat

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent store for fake-comment entries (`snsId -> List<SnsProto.CommentEntry>`)
 * so server syncs preserve local fake comments and clearing removes only synthetic entries.
 */
object SnsFakeCommentStore {
    private const val TAG = "SnsFakeCommentStore"
    private const val FILE_NAME = "fake_comments.json"
    private const val SYNTHETIC_ID_BASE = 1_900_000_000

    private val lock = Any()
    private val map = ConcurrentHashMap<String, List<SnsProto.CommentEntry>>()
    private val nextIdSeq = AtomicInteger(0)

    fun load() {
        synchronized(lock) {
            runCatching {
                val f = File(HostContext.getDataDir(), FILE_NAME)
                if (!f.exists() || f.length() == 0L) return
                val root = JSONObject(f.readText(Charsets.UTF_8))
                var maxOffset = 0
                val keys = root.keys()
                while (keys.hasNext()) {
                    val snsIdKey = keys.next()
                    val arr = root.optJSONArray(snsIdKey) ?: continue
                    val list = ArrayList<SnsProto.CommentEntry>(arr.length())
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val u = obj.optString("u").trim()
                        val c = obj.optString("c")
                        val cid = obj.optInt("id", 0)
                        if (u.isEmpty() || c.isEmpty() || cid == 0) continue
                        if (cid >= SYNTHETIC_ID_BASE) {
                            maxOffset = maxOf(maxOffset, cid - SYNTHETIC_ID_BASE)
                        }
                        list += SnsProto.CommentEntry(
                            commentId = cid,
                            username = u,
                            nickname = obj.optString("n", u),
                            content = c,
                            createTime = obj.optInt("t", 0),
                            replyUsername = obj.optString("ru", ""),
                            replyCommentId = obj.optInt("rid", 0),
                        )
                    }
                    if (list.isNotEmpty()) {
                        map[snsIdKey] = list
                    }
                }
                nextIdSeq.set(maxOffset)
            }.onFailure {
                AcidLogger.w(TAG, "load fake_comments.json failed", it)
            }
        }
    }

    fun nextCommentId(): Int = SYNTHETIC_ID_BASE + nextIdSeq.incrementAndGet()

    fun get(snsId: Long): List<SnsProto.CommentEntry> = map[snsId.toString()].orEmpty()

    fun getIds(snsId: Long): Set<Int> = get(snsId).mapTo(LinkedHashSet()) { it.commentId }

    fun postCount(): Int = map.size

    fun totalCommentCount(): Int = map.values.sumOf { it.size }

    fun add(snsId: Long, entry: SnsProto.CommentEntry): List<SnsProto.CommentEntry> {
        val key = snsId.toString()
        synchronized(lock) {
            val updated = map[key].orEmpty() + entry
            map[key] = updated
            saveLocked()
            return updated
        }
    }

    fun clear(snsId: Long): List<SnsProto.CommentEntry> {
        val key = snsId.toString()
        synchronized(lock) {
            val prev = map.remove(key).orEmpty()
            saveLocked()
            return prev
        }
    }

    private fun saveLocked() {
        runCatching {
            val root = JSONObject()
            for ((snsIdKey, list) in map) {
                val arr = JSONArray()
                for (item in list) {
                    arr.put(JSONObject().apply {
                        put("id", item.commentId)
                        put("u", item.username)
                        put("n", item.nickname)
                        put("c", item.content)
                        put("t", item.createTime)
                        if (item.replyUsername.isNotEmpty()) put("ru", item.replyUsername)
                        if (item.replyCommentId != 0) put("rid", item.replyCommentId)
                    })
                }
                root.put(snsIdKey, arr)
            }
            File(HostContext.getDataDir(), FILE_NAME).writeText(root.toString(2), Charsets.UTF_8)
        }.onFailure {
            AcidLogger.w(TAG, "save fake_comments.json failed", it)
        }
    }
}
