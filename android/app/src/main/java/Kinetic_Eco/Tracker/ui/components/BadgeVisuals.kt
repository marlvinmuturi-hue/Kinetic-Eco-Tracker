package Kinetic_Eco.Tracker.ui.components

import androidx.compose.ui.graphics.Color
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.BadgeTier

/**
 * Shared visual mapping for achievement badges — tier colours, per-tier artwork, and value
 * formatting. Kept in one place so the dashboard chip, the magnified [BadgeDetailDialog], and the
 * off-screen [Kinetic_Eco.Tracker.ui.utils.BadgeShareCardRenderer] all stay in sync.
 *
 * `internal` so it is reachable across packages within the app module.
 */

/** ARGB int form of the tier colour (for the Canvas-based share renderer). */
internal fun badgeTierColorArgb(tier: BadgeTier): Int = when (tier) {
    BadgeTier.BRONZE  -> 0xFFCD7F32.toInt()
    BadgeTier.SILVER  -> 0xFFB0B8C1.toInt()
    BadgeTier.GOLD    -> 0xFFFFD700.toInt()
    BadgeTier.DIAMOND -> 0xFF62EFFF.toInt()
}

/** Compose [Color] form of the tier colour (for Compose UI). */
internal fun badgeTierColor(tier: BadgeTier): Color = Color(badgeTierColorArgb(tier))

/**
 * Custom milestone artwork for badge categories that have dedicated art per tier (CO₂ Hero:
 * 1/5/15/30 kg, Green Streak: 3/7/14/30 days, Distance Champion: 10/25/50/100 km). Returns null for
 * categories that still use the generic emoji chip.
 */
internal fun badgeArtworkDrawableRes(id: String, tier: BadgeTier): Int? = when (id) {
    "co2" -> when (tier) {
        BadgeTier.BRONZE  -> R.drawable.badge_co2_bronze
        BadgeTier.SILVER  -> R.drawable.badge_co2_silver
        BadgeTier.GOLD    -> R.drawable.badge_co2_gold
        BadgeTier.DIAMOND -> R.drawable.badge_co2_diamond
    }
    "streak" -> when (tier) {
        BadgeTier.BRONZE  -> R.drawable.badge_streak_bronze
        BadgeTier.SILVER  -> R.drawable.badge_streak_silver
        BadgeTier.GOLD    -> R.drawable.badge_streak_gold
        BadgeTier.DIAMOND -> R.drawable.badge_streak_diamond
    }
    "distance" -> when (tier) {
        BadgeTier.BRONZE  -> R.drawable.badge_distance_bronze
        BadgeTier.SILVER  -> R.drawable.badge_distance_silver
        BadgeTier.GOLD    -> R.drawable.badge_distance_gold
        BadgeTier.DIAMOND -> R.drawable.badge_distance_diamond
    }
    else -> null
}

/** Formats a badge value: whole numbers for discrete units (days/kcal/m), one decimal otherwise. */
internal fun badgeFmtVal(value: Double, unit: String): String = when (unit) {
    "days", "kcal", "m" -> value.toInt().toString()
    else                 -> "%.1f".format(value)
}