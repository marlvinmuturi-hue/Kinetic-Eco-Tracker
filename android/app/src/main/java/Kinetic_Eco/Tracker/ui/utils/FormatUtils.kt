package Kinetic_Eco.Tracker.ui.utils

import Kinetic_Eco.Tracker.data.Co2Calculator
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

/**
 * Durations that are **sums** (e.g. many days in the same time-of-day bucket).
 * Uses "9h 48m", "25h 52m", "2d 3h" — never looks like a clock time (unlike [formatTime]).
 */
fun formatDurationSumHoursMinutes(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0m"
    val days = totalSeconds / 86400L
    var rem = totalSeconds % 86400L
    val hours = rem / 3600L
    rem %= 3600L
    val minutes = rem / 60L
    return when {
        days > 0L -> "${days}d ${hours}h ${minutes}m"
        hours > 0L -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
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

// ── Fuel volume, economy, and price ─────────────────────────
//
// Everything is *stored* metric — litres, km/L, price per litre — and converted only for
// display, the same way distance is stored in metres and rendered as km or miles. Nothing
// below is ever written back to a profile, a fuel entry, or a price document.

/** Fuel presentation: litres + km/L, or US gallons + MPG. */
enum class FuelUnit { LITRES_KM_PER_L, US_GALLONS_MPG }

/** Litres in one **US** liquid gallon. The imperial gallon is 4.546 L — a 21% different MPG. */
const val LITRES_PER_US_GALLON = 3.785411784

/**
 * Fuel units follow the distance unit already chosen in Appearance: km → litres and km/L,
 * miles → gallons and MPG. There is deliberately no separate setting.
 *
 * Consequence: **L/100km is not reachable.** It is a metric unit, so it cannot be
 * distinguished from km/L by a metric/imperial flag; exposing it needs its own preference.
 */
fun UnitSystem.toFuelUnit(): FuelUnit =
    if (usesMetricDistance()) FuelUnit.LITRES_KM_PER_L else FuelUnit.US_GALLONS_MPG

/** Converts a canonical litre volume into the display unit. */
fun convertFuelVolume(litres: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> litres
    FuelUnit.US_GALLONS_MPG -> litres / LITRES_PER_US_GALLON
}

/** Inverse of [convertFuelVolume] — for turning a typed volume back into stored litres. */
fun fuelVolumeToLitres(volume: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> volume
    FuelUnit.US_GALLONS_MPG -> volume * LITRES_PER_US_GALLON
}

/**
 * Converts canonical km/L into the display unit.
 *
 * MPG(US) = km/L × (miles per km) × (litres per gallon). Expressed via [Co2Calculator.KM_PER_MILE]
 * rather than a baked-in 2.352 so the factor cannot drift from the one distance already uses.
 */
fun convertFuelEconomy(kmPerL: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> kmPerL
    FuelUnit.US_GALLONS_MPG -> kmPerL * LITRES_PER_US_GALLON / Co2Calculator.KM_PER_MILE
}

/**
 * Converts canonical **L/100 km** (as measured from fill-ups) into the display unit.
 *
 * Note this stays L/100 km on metric rather than becoming km/L: the fuel log has always
 * reported measured consumption that way, and it is the one place L/100 km is reachable.
 * Consumption and economy are reciprocals, so MPG = 235.2 / (L/100 km).
 */
fun convertConsumption(lPer100Km: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> lPer100Km
    // A zero would mean a tank that covers infinite distance; guard rather than divide.
    FuelUnit.US_GALLONS_MPG ->
        if (lPer100Km <= 0.0) 0.0
        else 100.0 * LITRES_PER_US_GALLON / (Co2Calculator.KM_PER_MILE * lPer100Km)
}

/** Inverse of [convertFuelEconomy] — for turning typed input back into stored km/L. */
fun fuelEconomyToKmPerL(displayed: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> displayed
    FuelUnit.US_GALLONS_MPG -> displayed * Co2Calculator.KM_PER_MILE / LITRES_PER_US_GALLON
}

/** Converts a canonical price-per-litre into price per displayed volume unit. */
fun convertPricePerVolume(perLitre: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> perLitre
    FuelUnit.US_GALLONS_MPG -> perLitre * LITRES_PER_US_GALLON
}

/** Inverse of [convertPricePerVolume] — for turning a typed price back into a stored per-litre one. */
fun pricePerVolumeToPerLitre(perVolume: Double, unit: FuelUnit): Double = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> perVolume
    FuelUnit.US_GALLONS_MPG -> perVolume / LITRES_PER_US_GALLON
}

/** "L" or "gal". */
fun fuelVolumeUnitLabel(unit: FuelUnit): String = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> "L"
    FuelUnit.US_GALLONS_MPG -> "gal"
}

/** "km/L" or "MPG". */
fun fuelEconomyUnitLabel(unit: FuelUnit): String = when (unit) {
    FuelUnit.LITRES_KM_PER_L -> "km/L"
    FuelUnit.US_GALLONS_MPG -> "MPG"
}

