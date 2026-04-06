package Kinetic_Eco.Tracker.ui.utils

import Kinetic_Eco.Tracker.data.UnitSystem
import java.util.concurrent.TimeUnit

/** True if distance/speed use metric (km, km/h). */
fun UnitSystem.usesMetricDistance(): Boolean = this == UnitSystem.METRIC || this == UnitSystem.METRIC_WH

/** Formats speed in m/s as "32 km/h max" or "20 mph max" per unit system. */
fun formatSpeedMax(mps: Double, unitSystem: UnitSystem): String {
    val (value, unit) = if (unitSystem.usesMetricDistance()) {
        (mps * 3.6).format(1) to "km/h"
    } else {
        (mps * 2.23694).format(1) to "mph"
    }
    return "$value $unit max"
}

fun Float.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}

fun Double.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}

fun formatTime(seconds: Long): String {
    val hours = TimeUnit.SECONDS.toHours(seconds)
    val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val secs = seconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, secs)
}

fun formatSteps(steps: Int): String {
    return String.format("%,d", steps)
}

// ── Energy unit ─────────────────────────────────────────────

enum class EnergyUnit { KCAL, WH }

private const val KCAL_TO_WH = 1.163

/** Energy unit for a unit system: km,kcal and Miles,kcal -> kcal; km,Wh and Miles,Wh -> Wh */
fun UnitSystem.toEnergyUnit(): EnergyUnit = when (this) {
    UnitSystem.METRIC -> EnergyUnit.KCAL
    UnitSystem.METRIC_WH -> EnergyUnit.WH
    UnitSystem.IMPERIAL -> EnergyUnit.WH
    UnitSystem.IMPERIAL_KCAL -> EnergyUnit.KCAL
}

/**
 * Formats a calorie value (kcal) into the chosen energy unit.
 * Returns a string like "348 kcal" or "404.7 Wh" (or "1.2 kWh" for large values).
 */
fun formatEnergy(kcal: Double, unit: EnergyUnit): String {
    return when (unit) {
        EnergyUnit.KCAL -> "${kcal.toInt()} kcal"
        EnergyUnit.WH -> {
            val wh = kcal * KCAL_TO_WH
            if (wh >= 1000.0) {
                "${(wh / 1000.0).format(2)} kWh"
            } else {
                "${wh.format(1)} Wh"
            }
        }
    }
}

/**
 * Returns just the unit label for the chosen energy unit.
 */
fun energyUnitLabel(unit: EnergyUnit): String {
    return when (unit) {
        EnergyUnit.KCAL -> "kcal"
        EnergyUnit.WH -> "Wh"
    }
}

/**
 * Returns just the numeric part formatted for the chosen energy unit.
 */
fun formatEnergyValue(kcal: Double, unit: EnergyUnit): String {
    return when (unit) {
        EnergyUnit.KCAL -> "${kcal.toInt()}"
        EnergyUnit.WH -> {
            val wh = kcal * KCAL_TO_WH
            if (wh >= 1000.0) {
                (wh / 1000.0).format(2)
            } else {
                wh.format(1)
            }
        }
    }
}

