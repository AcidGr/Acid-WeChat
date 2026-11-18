package com.acid.wechat.wechat

import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ProtoLite
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent store for fake-like entries (`snsId -> List<ProtoLite.LikeEntry>`)
 * so server syncs never wipe locally applied fake likes and unchecking stays deterministic.
 */
object SnsFakeLikeStore {
    private const val TAG = "SnsFakeLikeStore"
    private const val FILE_NAME = "fake_likes.json"

    private val lock = Any()
    private val map = ConcurrentHashMap<String, List<ProtoLite.LikeEntry>>()

    fun load() {
        synchronized(lock) {
            runCatching {
                val f = File(HostContext.getDataDir(), FILE_NAME)
                if (!f.exists() || f.length() == 0L) return
                val root = JSONObject(f.readText(Charsets.UTF_8))
                val keys = root.keys()
                while (keys.hasNext()) {
                    val snsIdKey = keys.next()
                    val arr = root.optJSONArray(snsIdKey) ?: continue
                    val list = ArrayList<ProtoLite.LikeEntry>(arr.length())
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val u = obj.optString("u").trim()
                        if (u.isEmpty()) continue
                        list += ProtoLite.LikeEntry(
                            username = u,
                            nickname = obj.optString("n", u),
                            createTime = obj.optInt("t", 0),
                        )
                    }
                    if (list.isNotEmpty()) {
                        map[snsIdKey] = list
                    }
                }
            }.onFailure {
                AcidLogger.w(TAG, "load fake_likes.json failed", it)
            }
        }
    }

    fun get(snsId: Long): List<ProtoLite.LikeEntry> = map[snsId.toString()].orEmpty()

    fun getUsernames(snsId: Long): Set<String> =
        get(snsId).mapTo(LinkedHashSet()) { it.username }

    fun set(snsId: Long, entries: List<ProtoLite.LikeEntry>) {
        val key = snsId.toString()
        synchronized(lock) {
            if (entries.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = entries.toList()
            }
            saveLocked()
        }
    }

    private fun saveLocked() {
        runCatching {
            val root = JSONObject()
            for ((snsIdKey, list) in map) {
                val arr = JSONArray()
                for (item in list) {
                    arr.put(JSONObject().apply {
                        put("u", item.username)
                        put("n", item.nickname)
                        put("t", item.createTime)
                    })
                }
                root.put(snsIdKey, arr)
            }
            File(HostContext.getDataDir(), FILE_NAME).writeText(root.toString(2), Charsets.UTF_8)
        }.onFailure {
            AcidLogger.w(TAG, "save fake_likes.json failed", it)
        }
    }
}
