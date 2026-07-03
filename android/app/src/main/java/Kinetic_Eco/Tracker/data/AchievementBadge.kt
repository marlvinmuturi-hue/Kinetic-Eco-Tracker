package Kinetic_Eco.Tracker.data

enum class BadgeTier(val label: String) {
    BRONZE("Bronze"),
    SILVER("Silver"),
    GOLD("Gold"),
    DIAMOND("Diamond")
}

data class AchievementBadge(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    val tier: BadgeTier,
    val isEarned: Boolean,
    /** 0..1 progress toward the next tier (or 1.0 when at Diamond). */
    val progress: Float,
    val currentValue: Double,
    val targetValue: Double,
    val unit: String
)
