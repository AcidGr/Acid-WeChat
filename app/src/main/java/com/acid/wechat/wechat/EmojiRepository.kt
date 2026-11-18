package com.acid.wechat.wechat

import com.acid.wechat.core.WcdbBridge

/**
 * Queries WeChat built-in animated game emojis (`EmojiInfo` in `EnMicroMsg.db`):
 * - Rock-Paper-Scissors (`catalog = 49`, template md5 `9bd1281af3a31710a45b84d736363691`)
 * - Dice (`catalog = 50`, template md5 `08f223fa83f1ca34e143d1e580252c7c`)
 */
object EmojiRepository {
    const val MD5_JSB = "9bd1281af3a31710a45b84d736363691"
    const val MD5_DICE = "08f223fa83f1ca34e143d1e580252c7c"
    const val CATALOG_JSB = 49
    const val CATALOG_DICE = 50

    private val diceNumRegex = Regex("dice_([1-6])", RegexOption.IGNORE_CASE)
    private val diceFaces = listOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")

    enum class GameKind(val catalog: Int) {
        JSB(CATALOG_JSB),
        DICE(CATALOG_DICE),
    }

    data class GameEmojiChoice(
        val md5: String,
        val label: String,
        val order: Int,
    )

    fun classifyTemplate(md5: String?, catalog: Int, content: String?): GameKind? {
        if (md5 == MD5_JSB || catalog == CATALOG_JSB || (catalog == 17 && content == "49")) {
            return GameKind.JSB
        }
        if (md5 == MD5_DICE || catalog == CATALOG_DICE || (catalog == 17 && content == "50")) {
            return GameKind.DICE
        }
        return null
    }

    fun queryChoices(kind: GameKind): List<GameEmojiChoice> = runCatching {
        val cursor = WcdbBridge.rawQuery(
            "SELECT md5, name FROM EmojiInfo WHERE catalog = ? AND temp = 0 ORDER BY rowid ASC",
            arrayOf(kind.catalog.toString()),
        ) ?: return@runCatching emptyList()

        val list = mutableListOf<GameEmojiChoice>()
        var idx = 0
        cursor.use { c ->
            while (c.moveToNext()) {
                val md5 = c.getString(0)?.trim().orEmpty()
                if (md5.isEmpty() || md5 == MD5_JSB || md5 == MD5_DICE) continue
                val rawName = c.getString(1)?.trim().orEmpty()
                list += formatChoice(kind, md5, rawName, idx++)
            }
        }
        list.sortedBy { it.order }
    }.getOrDefault(emptyList())

    private fun formatChoice(kind: GameKind, md5: String, rawName: String, fallbackIdx: Int): GameEmojiChoice {
        return when (kind) {
            GameKind.JSB -> when {
                rawName.contains("jsb_j", ignoreCase = true) -> GameEmojiChoice(md5, "✌️ 剪刀", 1)
                rawName.contains("jsb_s", ignoreCase = true) -> GameEmojiChoice(md5, "✊ 石头", 2)
                rawName.contains("jsb_b", ignoreCase = true) -> GameEmojiChoice(md5, "🖐️ 布", 3)
                else -> GameEmojiChoice(md5, "出拳 ${fallbackIdx + 1}", 10 + fallbackIdx)
            }
            GameKind.DICE -> {
                val digit = diceNumRegex.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (digit != null && digit in 1..6) {
                    GameEmojiChoice(md5, "${diceFaces[digit - 1]} $digit 点", digit)
                } else {
                    GameEmojiChoice(md5, "🎲 ${fallbackIdx + 1} 点", 10 + fallbackIdx)
                }
            }
        }
    }
}
