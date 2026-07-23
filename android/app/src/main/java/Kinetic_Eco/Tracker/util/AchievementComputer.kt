package Kinetic_Eco.Tracker.util

import Kinetic_Eco.Tracker.data.AchievementBadge
import Kinetic_Eco.Tracker.data.BadgeTier
import Kinetic_Eco.Tracker.data.SessionStats

object AchievementComputer {

    private data class TierDef(val tier: BadgeTier, val threshold: Double)

    private val DISTANCE_KM = listOf(
        TierDef(BadgeTier.BRONZE,  10.0),
        TierDef(BadgeTier.SILVER,  25.0),
        TierDef(BadgeTier.GOLD,    50.0),
        TierDef(BadgeTier.DIAMOND, 100.0)
    )
    private val CO2_KG = listOf(
        TierDef(BadgeTier.BRONZE,  1.0),
        TierDef(BadgeTier.SILVER,  5.0),
        TierDef(BadgeTier.GOLD,    15.0),
        TierDef(BadgeTier.DIAMOND, 30.0)
    )
    private val STREAK_DAYS = listOf(
        TierDef(BadgeTier.BRONZE,  3.0),
        TierDef(BadgeTier.SILVER,  7.0),
        TierDef(BadgeTier.GOLD,    14.0),
        TierDef(BadgeTier.DIAMOND, 30.0)
    )

    fun compute(
        weekSessions: List<SessionStats>,
        @Suppress("UNUSED_PARAMETER") allSessions: List<SessionStats>,
        streakDays: Int
    ): List<AchievementBadge> {
        val weekDistKm = weekSessions.sumOf { it.totalDistance } / 1000.0
        val weekCo2Kg  = weekSessions.sumOf { it.co2Conserved }

        return listOf(
            badge("co2", "🌿", "CO₂ Hero",
                "CO₂ conserved this week",
                weekCo2Kg, CO2_KG, "kg"),
            badge("streak", "🔥", "Green Streak",
                "Consecutive eco-friendly days",
                streakDays.toDouble(), STREAK_DAYS, "days"),
            badge("distance", "🏃", "Distance Champion",
                "Eco km covered this week",
                weekDistKm, DISTANCE_KM, "km"),
        )
    }

    private fun badge(
        id: String,
        emoji: String,
        title: String,
        description: String,
        value: Double,
        tiers: List<TierDef>,
        unit: String
    ): AchievementBadge {
        val earnedIdx  = tiers.indexOfLast { value >= it.threshold }
        val earnedTier = if (earnedIdx >= 0) tiers[earnedIdx] else null
        val nextTier   = tiers.getOrNull(earnedIdx + 1)

        val progress: Float
        val target: Double
        if (nextTier != null) {
            val prevThreshold = if (earnedIdx >= 0) tiers[earnedIdx].threshold else 0.0
            progress = ((value - prevThreshold) / (nextTier.threshold - prevThreshold))
                .coerceIn(0.0, 1.0).toFloat()
            target = nextTier.threshold
        } else {
            // Diamond reached — full bar
            progress = 1.0f
            target   = tiers.last().threshold
        }

        return AchievementBadge(
            id           = id,
            emoji        = emoji,
            title        = title,
            description  = description,
            tier         = earnedTier?.tier ?: BadgeTier.BRONZE,
            isEarned     = earnedTier != null,
            progress     = progress,
            currentValue = value,
            targetValue  = target,
            unit         = unit
        )
    }
}
