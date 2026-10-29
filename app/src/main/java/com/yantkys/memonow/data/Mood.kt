package com.yantkys.memonow.data

/** IDs are persisted; labels and emoji are presentation only. Never guess an unknown ID. */
enum class Mood(val id: String, val emoji: String, val label: String) {
    HAPPY("happy", "😊", "うれしい"),
    FUN("fun", "😆", "楽しい"),
    ANGRY("angry", "😠", "怒り"),
    SAD("sad", "😢", "悲しい"),
    NORMAL("normal", "😐", "普通"),
    TIRED("tired", "😮‍💨", "疲れた");

    val display get() = "$emoji $label"
    val accessibilityName get() = "${id.replaceFirstChar { it.uppercase() }}／$label"

    companion object {
        // Selection is narrower than the persisted ID set. Keep legacy IDs readable.
        val selectable = listOf(HAPPY, ANGRY, SAD, TIRED)
        fun find(id: String?): Mood? = entries.firstOrNull { it.id == id }
        fun isKnownOrNull(id: String?) = id == null || find(id) != null
    }
}
