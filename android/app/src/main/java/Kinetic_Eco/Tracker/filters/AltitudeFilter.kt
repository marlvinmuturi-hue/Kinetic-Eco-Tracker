package Kinetic_Eco.Tracker.filters

import kotlin.math.abs

/**
 * Rejects GPS altitude spikes and smooths the result so session altitude (start/stop/min/max),
 * route elevation, and elevation gain/loss stop being poisoned by single bad fixes.
 *
 * Why this exists: raw `Location.altitude` is noisy — vertical GPS error is typically 1.5–3× the
 * horizontal, so a fix with 20 m horizontal accuracy can be 40–60 m off vertically, and one such
 * spike previously became the recorded min/max for the whole session (e.g. "65 m" when the user was
 * at 1 m). Speed already had an EMA + outlier gate; altitude had none. This adds the equivalent.
 *
 * Strategy:
 *  1. **Accuracy gate** — drop a fix whose vertical accuracy (or, when the platform doesn't report
 *     it, horizontal accuracy as a proxy) is worse than the thresholds below.
 *  2. **Median window** — keep the last few accepted raw values; a lone spike enters the window but
 *     does not move the median, so it can never shift the estimate on its own.
 *  3. **Jump gate** — a reading far from the current estimate is only accepted once the *median*
 *     also confirms a sustained shift (real climb/descent), never on a single outlier.
 *  4. **EMA** — light exponential smoothing on the median for a stable output.
 *
 * Note: this corrects *noise and spikes*. It does NOT correct the systematic ellipsoid-vs-mean-sea-
 * level (geoid) offset — that is a fixed per-location bias needing a geoid model and is a separate
 * concern. See [reportedAltitudeIsFromGeoid] docs where consumed.
 */
class AltitudeFilter {

    private val window = ArrayDeque<Double>()
    private var ema: Double? = null

    /** Best current smoothed altitude, or null until a trustworthy value is established. */
    val current: Double? get() = ema

    fun reset() {
        window.clear()
        ema = null
    }

    /**
     * Feed a candidate altitude. Returns the current smoothed estimate (unchanged when the fix is
     * rejected), or null if no trustworthy value exists yet.
     *
     * @param rawAltitude altitude from GPS (or barometer) in meters
     * @param verticalAccuracy vertical accuracy in meters, or null when unavailable
     * @param horizontalAccuracy horizontal accuracy in meters (proxy gate when vertical is null)
     */
    fun update(rawAltitude: Double, verticalAccuracy: Float?, horizontalAccuracy: Float): Double? {
        if (!rawAltitude.isFinite()) return ema

        val acceptable = when {
            verticalAccuracy != null && verticalAccuracy.isFinite() ->
                verticalAccuracy <= MAX_VERTICAL_ACCURACY_M
            else ->
                horizontalAccuracy.isFinite() && horizontalAccuracy <= MAX_HORIZONTAL_FOR_ALTITUDE_M
        }
        if (!acceptable) return ema

        window.addLast(rawAltitude)
        if (window.size > MEDIAN_WINDOW) window.removeFirst()
        val median = window.sorted()[window.size / 2]

        val ref = ema
        if (ref == null) {
            ema = median
            return ema
        }

        // Large divergence from the running estimate: only accept once the median (not a lone fix)
        // confirms a sustained shift. Otherwise treat as a spike and hold the previous estimate.
        if (abs(rawAltitude - ref) > MAX_ALTITUDE_JUMP_M) {
            if (window.size >= MEDIAN_WINDOW && abs(median - ref) > MAX_ALTITUDE_JUMP_M) {
                ema = ref + EMA_ALPHA * (median - ref)
            }
            return ema
        }

        ema = ref + EMA_ALPHA * (median - ref)
        return ema
    }

    companion object {
        /** Reject a fix reporting vertical accuracy worse than this (meters). */
        private const val MAX_VERTICAL_ACCURACY_M = 20f
        /** When vertical accuracy is unavailable, gate on horizontal accuracy instead (meters). */
        private const val MAX_HORIZONTAL_FOR_ALTITUDE_M = 20f
        /** Number of recent accepted samples in the median window (odd → unambiguous median). */
        private const val MEDIAN_WINDOW = 5
        /** A single reading this far from the estimate is a suspected spike until the median agrees. */
        private const val MAX_ALTITUDE_JUMP_M = 15.0
        /** EMA weight on the (spike-filtered) median. Lower = smoother/slower. */
        private const val EMA_ALPHA = 0.35
    }
}