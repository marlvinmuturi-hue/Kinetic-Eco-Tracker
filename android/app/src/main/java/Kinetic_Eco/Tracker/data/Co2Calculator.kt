package Kinetic_Eco.Tracker.data

import kotlin.math.abs

/**
 * Outcome of a single CO₂ estimate for one activity over one distance.
 *
 * [CO2Factors] stores **signed** per-km factors: a positive factor is direct
 * emission (driving, flying), a negative one is a saving measured against the
 * [BASELINE_DRIVING_CO2_PER_KM] car trip that the activity replaced (walking,
 * cycling, train). Callers almost always want those two cases separated rather
 * than a single signed number, so this type splits them: exactly one of
 * [emittedKg] / [savedKg] is non-zero, and both are zero for IDLE.
 */
data class Co2Estimate(
    val activity: ActivityType,
    val distanceKm: Double,
    /** Signed per-km factor used, after the vehicle profile was applied. */
    val factorKgPerKm: Double,
    val emittedKg: Double,
    val savedKg: Double,
    /**
     * Vehicle energy consumed over [distanceKm], in watt-hours. Always ≥ 0, and
     * exactly 0 for human-powered modes — see [EnergyFactors]. This is fuel or
     * battery energy, **not** the calories the traveller burns.
     */
    val energyWh: Double,
    /** Unsigned per-km energy draw used, after the vehicle profile was applied. */
    val energyWhPerKm: Double
) {
    /** Positive = net emitter over this distance, negative = net saver. */
    val netKg: Double get() = emittedKg - savedKg

    /** True when this activity saves CO₂ rather than emitting it. */
    val isSaving: Boolean get() = savedKg > 0.0

    /** The magnitude the UI actually shows — whichever side is non-zero. */
    val magnitudeKg: Double get() = if (isSaving) savedKg else emittedKg

    /** False for walking/running/cycling/idle, where there is no vehicle. */
    val hasVehicleEnergy: Boolean get() = energyWh > 0.0
}

/**
 * Single source of truth for turning *(activity, distance, vehicle profile)*
 * into a CO₂ figure.
 *
 * This exists so the manual calculator on the Analysis tab and the live tracker
 * cannot drift apart. Both go through [estimate]; a tracked 12 km drive and a
 * hand-entered 12 km drive must produce the same number, or the app is telling
 * the user two different things about the same trip.
 *
 * Deliberately pure and dependency-free — no Android, no Room, no prefs — so it
 * stays callable from a Composable, a service, and a unit test alike.
 */
object Co2Calculator {

    /** 1 mile in kilometres, for imperial input. */
    const val KM_PER_MILE = 1.609344

    /**
     * Upper bound on a single manual entry. Roughly half the earth's
     * circumference — beyond this the user has almost certainly typed a digit
     * too many, and an unbounded value makes the result card meaningless.
     */
    const val MAX_DISTANCE_KM = 20_000.0

    /**
     * Estimate CO₂ for [distanceKm] travelled by [activity].
     *
     * [profile] tunes the road, rail and air factors (engine size, body type,
     * fuel, EV class/power, train propulsion, aircraft category). Pass the
     * user's saved profile so manual entries match their tracked sessions;
     * the default only makes sense for a generic "average car" figure.
     *
     * Negative or non-finite distances are clamped to zero rather than throwing
     * — this is fed directly by a text field.
     */
    fun estimate(
        activity: ActivityType,
        distanceKm: Double,
        profile: VehicleProfile = VehicleProfile.DEFAULT
    ): Co2Estimate {
        val safeKm = if (distanceKm.isFinite() && distanceKm > 0.0) {
            distanceKm.coerceAtMost(MAX_DISTANCE_KM)
        } else {
            0.0
        }
        val factor = CO2Factors.getFactor(activity, profile)
        val impact = factor * safeKm
        val whPerKm = EnergyFactors.getWhPerKm(activity, profile)
        return Co2Estimate(
            activity = activity,
            distanceKm = safeKm,
            factorKgPerKm = factor,
            emittedKg = if (impact > 0) impact else 0.0,
            savedKg = if (impact < 0) abs(impact) else 0.0,
            energyWh = whPerKm * safeKm,
            energyWhPerKm = whPerKm
        )
    }

    /**
     * Metres-based variant matching the tracker's internal units.
     *
     * [SessionManager.calculateCO2] delegates here, which is what keeps the
     * live session path and the manual calculator on identical arithmetic.
     */
    fun estimateFromMeters(
        distanceMeters: Double,
        activity: ActivityType,
        profile: VehicleProfile = VehicleProfile.DEFAULT
    ): Co2Estimate = estimate(activity, distanceMeters / 1000.0, profile)

    /**
     * Parse a free-typed distance into kilometres.
     *
     * Accepts a plain decimal in whichever unit the user's [unitSystem] shows,
     * tolerating both `.` and `,` as the decimal separator since the field is a
     * plain number input and locale keyboards differ. Returns null for anything
     * unparseable, empty, negative, or absurd, so the caller can simply not
     * show a result rather than render NaN.
     */
    fun parseDistanceToKm(input: String, unitSystem: UnitSystem): Double? {
        val cleaned = input.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val km = if (unitSystem.isMetricDistance()) value else value * KM_PER_MILE
        if (km > MAX_DISTANCE_KM) return null
        return km
    }

    /**
     * Whether [UnitSystem] shows distances in kilometres.
     *
     * Mirrors `UnitSystem.usesMetricDistance()` in the ui.utils layer. Repeated
     * here so this file stays free of UI dependencies — the two must agree.
     */
    private fun UnitSystem.isMetricDistance(): Boolean =
        this == UnitSystem.METRIC || this == UnitSystem.METRIC_WH
}