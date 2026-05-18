package Kinetic_Eco.Tracker.services

import kotlin.math.hypot

/**
 * Pocket-oriented heuristic for **corner-style** motion: bursts of gyro rotation
 * together with lateral linear acceleration suggest two-wheel leaning (motorcycle
 * or bicycle) versus a comparatively upright posture in typical cars/transit.
 *
 * This is inherently fuzzy — [corneringScore] is a smoothed pseudo-likelihood, not physics.
 */
class PocketTwoWheelLeanEstimator {

    private data class Sample(
        val timestampMs: Long,
        val gyroMag: Float,
        val lateralAccelApprox: Float
    )

    private val samples = ArrayDeque<Sample>()
    private val windowMs = 4_800L

    /** Minimum composite gyro angular rate (rad/s) counting as “active roll”. */
    private val gyroBurstFloorRadS = 1.05f

    /** Horizontal-plane linear acceleration magnitude floor (m/s²). */
    private val lateralAccelFloor = 1.65f

    companion object {
        /** Score threshold for auto-promotion (with GPS speed gates upstream). */
        const val PROMOTE_CORNER_SCORE_THRESHOLD = 0.19f

        /** Threshold for emitting a hint while manual activity is pinned. */
        const val HINT_CORNER_SCORE_THRESHOLD = 0.17f
    }

    fun reset() {
        samples.clear()
    }

    /** One sensor tick (merged accel + gyro snapshot from [SensorService]). */
    fun addSample(nowMs: Long, gyroXS: Float, gyroYS: Float, gyroZS: Float, linXMps2: Float, linYMps2: Float) {
        prune(nowMs)
        val gyroMag = hypot(hypot(gyroXS.toDouble(), gyroYS.toDouble()), gyroZS.toDouble()).toFloat().coerceAtLeast(0f)
        val lateralApprox = hypot(linXMps2.toDouble(), linYMps2.toDouble()).toFloat()
        samples.addLast(Sample(nowMs, gyroMag, lateralApprox))
    }

    /** 0f…~1f — fraction of samples in the window satisfying burst criteria. */
    fun corneringScore(nowMs: Long): Float {
        prune(nowMs)
        val n = samples.size
        if (n < 22) return 0f
        val hits = samples.count { it.gyroMag >= gyroBurstFloorRadS && it.lateralAccelApprox >= lateralAccelFloor }
        return (hits.toFloat() / n).coerceIn(0f, 1f)
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }
}
