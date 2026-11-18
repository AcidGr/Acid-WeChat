package com.acid.wechat.agent

import com.acid.wechat.agent.AgentSessionModels.TimelineItem
import com.acid.wechat.agent.AgentSessionStore.ChatSessionState
import com.acid.wechat.core.AcidIo
import com.acid.wechat.core.AcidLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Atomic JSON disk persistence for per-chat Agent sessions (`AcidWeChat/agent/sessions/`).
 */
internal object AgentSessionDisk {
    private const val TAG = "AgentSessionDisk"

    fun sessionFile(talker: String): File {
        val hash = sha1Short(talker)
        val safe = AgentConfigStore.sanitizeTalkerKey(talker).take(32)
        return File(AgentConfigStore.sessionsDir(), "${safe}_$hash.json")
    }

    fun hasFile(talker: String): Boolean = sessionFile(talker).isFile

    fun delete(talker: String) {
        runCatching { sessionFile(talker).delete() }
    }

    fun persist(session: ChatSessionState) {
        val json = synchronized(session) {
            val arr = JSONArray()
            for (it in session.timeline) {
                if (!it.running) arr.put(it.toJson())
            }
            JSONObject().apply {
                put("talker", session.talker)
                put("title", session.title)
                put("memorySummary", session.memorySummary)
                put("compactCount", session.compactCount)
                put("lastTotalTokens", session.lastTotalTokens)
                put("lastPromptTokens", session.lastPromptTokens)
                put("items", arr)
            }.toString()
        }
        runCatching {
            AcidIo.writeAtomicUtf8(sessionFile(session.talker), json)
        }.onFailure {
            AcidLogger.w(TAG, "persist session failed for ${session.talker}", it)
        }
    }

    fun load(talker: String, fallbackTitle: String): ChatSessionState? {
        val f = sessionFile(talker)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val title = obj.optString("title", fallbackTitle).ifBlank { fallbackTitle }
            ChatSessionState(talker, title).apply {
                memorySummary = obj.optString("memorySummary", "")
                compactCount = obj.optInt("compactCount", 0)
                lastTotalTokens = obj.optInt("lastTotalTokens", 0)
                lastPromptTokens = obj.optInt("lastPromptTokens", 0)
                dbSeeded = true
                val arr = obj.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { timeline += TimelineItem.fromJson(it) }
                }
            }
        }.onFailure {
            AcidLogger.w(TAG, "failed loading session file ${f.name}", it)
        }.getOrNull()
    }

    private fun sha1Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
