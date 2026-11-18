package com.acid.wechat.ui.overlay

/**
 * Declarative schema for the standard reusable floating modal ([AcidModal]).
 * Features build UI modals strictly from these blocks without writing custom HTML/JS.
 */
data class AcidModalSpec(
    val title: String,
    val subtitle: String = "",
    val blocks: List<ModalBlock>,
    val primaryButtonText: String = "确定",
    val widthRatio: Float = 0.90f,
    val heightRatio: Float = 0.68f,
    val onSubmit: (AcidModalResult) -> Boolean,
)

data class ChecklistItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val avatarUsername: String? = null,
    val checked: Boolean = false,
    val lockedBadge: String? = null,
)

sealed class ModalBlock {
    data class Preview(
        val title: String,
        val body: String = "",
    ) : ModalBlock()

    data class RandomCountBar(
        val checklistKey: String,
        val defaultCount: Int = 10,
        val placeholder: String = "数量",
        val pickLabel: String = "随机勾选",
        val clearLabel: String = "清空",
    ) : ModalBlock()

    data class Checklist(
        val key: String,
        val items: List<ChecklistItem>,
        val multiSelect: Boolean = true,
        val showSearch: Boolean = true,
        val searchPlaceholder: String = "搜索",
        val filterSelectedLabel: String = "已选",
        val emptyText: String = "暂无可选项",
        val lockedCountOffset: Int = 0,
        val headerTemplate: String? = null,
        val footerTemplate: String = "已选 {selected}",
    ) : ModalBlock()

    data class TextInput(
        val key: String,
        val label: String,
        val value: String = "",
        val placeholder: String = "",
    ) : ModalBlock()

    data class NumberInput(
        val key: String,
        val label: String,
        val value: Int = 0,
        val min: Int = 0,
        val max: Int = 99999,
    ) : ModalBlock()

    data class Toggle(
        val key: String,
        val label: String,
        val checked: Boolean = false,
    ) : ModalBlock()
}

class AcidModalResult(
    private val values: Map<String, String>,
    private val lists: Map<String, List<String>>,
) {
    fun selectedIds(key: String): List<String> = lists[key].orEmpty()
    fun stringValue(key: String, defaultValue: String = ""): String = values[key] ?: defaultValue
    fun intValue(key: String, defaultValue: Int = 0): Int = values[key]?.toIntOrNull() ?: defaultValue
    fun boolValue(key: String, defaultValue: Boolean = false): Boolean =
        values[key]?.equals("true", ignoreCase = true) ?: defaultValue
}
