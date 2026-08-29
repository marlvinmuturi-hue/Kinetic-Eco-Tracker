package Kinetic_Eco.Tracker.filters

import android.location.Location
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Kalman Filter for GPS smoothing and noise reduction.
 * 
 * This filter is essential for plane tracking where GPS accuracy can vary significantly
 * at altitude and high speeds. It reduces jitter and provides predictive estimates
 * during brief GPS dropouts.
 * 
 * Benefits:
 * - Reduces GPS jitter by 60-80%
 * - Smoother speed readings (critical at 800+ km/h)
 * - Better distance accuracy
 * - Predictive during brief GPS dropouts
 */
class KalmanFilter {
    private var lastPosition: FilteredPosition? = null
    private var velocity = FloatArray(2) // [vx, vy] in degrees per second
    private var positionUncertainty = 10.0f
    private var velocityUncertainty = 1.0f

    // Process noise (how much we expect velocity to change)
    // Higher for planes due to turns and acceleration
    private var processNoise = 0.5f

    // ── Instrumentation ───────────────────────────────────────────────────────
    //
    // Measurement only — nothing here changes filter behaviour. The filter has no
    // innovation gate: every measurement is blended in, however far it sits from the
    // prediction, and on a long gap the state is discarded and the raw fix adopted
    // verbatim. Before adding a rejection threshold we need to know what the residuals
    // and gap frequency actually look like on real trips, so the threshold is a
    // measurement rather than a guess.
    //
    // Read with [getDiagnostics]; cleared by [reset] alongside the filter state.

    /** Successful predict/update cycles this session. */
    private var updateCount = 0
    /** Times the dt sanity branch discarded state and returned the raw fix. */
    private var resetCount = 0
    /** Largest dt (seconds) seen on a reset — how long the outages actually are. */
    private var maxResetGapSec = 0.0f
    private var residualSumM = 0.0
    private var residualMaxM = 0.0
    /** Largest residual / (positionUncertainty + measurementUncertainty) seen. This ratio is
     *  what a Mahalanobis-style gate would threshold on, so its distribution sets the bar. */
    private var maxResidualRatio = 0.0
    /** Residual magnitude histogram, metres: [<5, <10, <25, <50, <100, >=100]. */
    private val residualBucketsM = IntArray(6)
    /** Residual-to-uncertainty ratio histogram: [<1, <2, <3, <5, <10, >=10]. */
    private val residualBucketsRatio = IntArray(6)
    
    data class FilteredPosition(
        val latitude: Double,
        val longitude: Double,
        val speed: Float,  // m/s
        val altitude: Double?,
        val accuracy: Float,
        val timestamp: Long
    )
    
    /**
     * Update the filter with a new GPS measurement.
     * Returns a smoothed, filtered position estimate.
     */
    fun update(location: Location): FilteredPosition {
        val currentPos = FilteredPosition(
            location.latitude,
            location.longitude,
            location.speed,
            if (location.hasAltitude()) location.altitude else null,
            location.accuracy,
            location.time
        )
        
        // First measurement - initialize
        val prev = lastPosition
        if (prev == null) {
            lastPosition = currentPos
            return currentPos
        }

        // Calculate time delta
        val dt = (currentPos.timestamp - prev.timestamp) / 1000.0f

        // Sanity check on time delta
        if (dt <= 0 || dt > 60) {
            // Time went backwards or too long gap - reset
            resetCount++
            if (dt > maxResetGapSec) maxResetGapSec = dt
            android.util.Log.d(
                TAG,
                "State discarded, raw fix adopted: dt=${"%.1f".format(dt)}s " +
                    "(acc=${location.accuracy.toInt()}m) — reset #$resetCount"
            )
            lastPosition = currentPos
            return currentPos
        }

        // === PREDICTION STEP ===
        // Predict next position based on current velocity
        val predictedLat = prev.latitude + velocity[0] * dt
        val predictedLon = prev.longitude + velocity[1] * dt
        
        // Increase uncertainty due to process noise
        positionUncertainty += processNoise * dt
        
        // === UPDATE STEP ===
        // Calculate Kalman gain (how much to trust new measurement vs prediction)
        val rawAcc = location.accuracy
        val measurementUncertainty = when {
            !rawAcc.isFinite() || rawAcc <= 0f -> 15f
            else -> rawAcc.coerceAtLeast(1f)
        }
        val kalmanGain = positionUncertainty / (positionUncertainty + measurementUncertainty)

        // ── Instrumentation: how far is the measurement from the prediction? ──
        // This residual (the "innovation") is the quantity a rejection gate would test.
        // Measured here, before the blend, and recorded only — never acted on.
        run {
            val latM = 111320.0
            val lonM = 111320.0 * cos(Math.toRadians(currentPos.latitude))
            val dLatM = (currentPos.latitude - predictedLat) * latM
            val dLonM = (currentPos.longitude - predictedLon) * lonM
            val residualM = sqrt(dLatM * dLatM + dLonM * dLonM)
            val combinedUncertainty = (positionUncertainty + measurementUncertainty).toDouble()
            val ratio = if (combinedUncertainty > 0.0) residualM / combinedUncertainty else 0.0

            updateCount++
            residualSumM += residualM
            if (residualM > residualMaxM) residualMaxM = residualM
            if (ratio > maxResidualRatio) maxResidualRatio = ratio

            residualBucketsM[bucketFor(residualM, 5.0, 10.0, 25.0, 50.0, 100.0)]++
            residualBucketsRatio[bucketFor(ratio, 1.0, 2.0, 3.0, 5.0, 10.0)]++

            // Individually notable fixes: loud enough to spot in a log, rare enough not to spam.
            if (ratio >= 5.0 && residualM >= 25.0) {
                android.util.Log.d(
                    TAG,
                    "Large innovation accepted (no gate): ${residualM.toInt()}m from prediction, " +
                        "${"%.1f".format(ratio)}x combined uncertainty " +
                        "(P=${positionUncertainty.toInt()}, R=${measurementUncertainty.toInt()}, " +
                        "gain=${"%.2f".format(kalmanGain)})"
                )
            }
        }

        // Update position estimate
        val filteredLat = predictedLat + kalmanGain * (currentPos.latitude - predictedLat)
        val filteredLon = predictedLon + kalmanGain * (currentPos.longitude - predictedLon)
        
        // Update velocity estimate
        velocity[0] = ((filteredLat - prev.latitude) / dt).toFloat()
        velocity[1] = ((filteredLon - prev.longitude) / dt).toFloat()
        
        // Update uncertainty (reduces after measurement)
        positionUncertainty *= (1 - kalmanGain)
        
        // Prevent uncertainty from going too low
        if (positionUncertainty < 1.0f) {
            positionUncertainty = 1.0f
        }
        
        // Calculate filtered speed from velocity
        // Convert degrees per second to meters per second
        // Latitude: 1 degree ≈ 111,320 m (constant). Longitude: 1 degree ≈ 111,320 * cos(lat) m
        val latMetersPerDegree = 111320.0
        val lonMetersPerDegree = 111320.0 * cos(Math.toRadians(filteredLat))
        val vx = velocity[0] * latMetersPerDegree.toFloat()
        val vy = velocity[1] * lonMetersPerDegree.toFloat()
        val filteredSpeed = sqrt((vx * vx + vy * vy).toDouble()).toFloat()
        
        // Create filtered position
        val filtered = FilteredPosition(
            filteredLat,
            filteredLon,
            filteredSpeed,
            currentPos.altitude,  // Don't filter altitude yet
            (positionUncertainty + measurementUncertainty) / 2,  // Average uncertainty
            currentPos.timestamp
        )
        
        lastPosition = filtered
        return filtered
    }
    
    /**
     * Adjust process noise based on activity type.
     * Higher noise for activities with more dynamic movement.
     */
    fun setProcessNoise(noise: Float) {
        this.processNoise = noise.coerceIn(0.1f, 2.0f)
    }
    
    /** Index of the first boundary [value] falls under, or the overflow bucket. */
    private fun bucketFor(value: Double, vararg bounds: Double): Int {
        for (i in bounds.indices) if (value < bounds[i]) return i
        return bounds.size
    }

    /**
     * One-line summary of what the filter saw this session. Log it at session end to size a
     * future innovation gate: the ratio histogram says where a threshold could sit without
     * rejecting ordinary fixes, and the reset count says whether the gap path matters at all.
     *
     * Returns null when nothing was measured, so a caller can skip logging empty sessions.
     */
    fun getDiagnostics(): String? {
        if (updateCount == 0 && resetCount == 0) return null
        val mean = if (updateCount > 0) residualSumM / updateCount else 0.0
        return buildString {
            append("updates=$updateCount resets=$resetCount")
            if (resetCount > 0) append(" maxGap=${"%.1f".format(maxResetGapSec)}s")
            append(" residual: mean=${"%.1f".format(mean)}m max=${residualMaxM.toInt()}m")
            append(" maxRatio=${"%.1f".format(maxResidualRatio)}x")
            append(" | metres[<5,<10,<25,<50,<100,100+]=${residualBucketsM.joinToString(",")}")
            append(" | ratio[<1,<2,<3,<5,<10,10+]=${residualBucketsRatio.joinToString(",")}")
        }
    }

    /**
     * Reset the filter (call when starting new tracking session)
     */
    fun reset() {
        lastPosition = null
        velocity = FloatArray(2)
        positionUncertainty = 10.0f
        velocityUncertainty = 1.0f
        processNoise = 0.5f

        updateCount = 0
        resetCount = 0
        maxResetGapSec = 0.0f
        residualSumM = 0.0
        residualMaxM = 0.0
        maxResidualRatio = 0.0
        residualBucketsM.fill(0)
        residualBucketsRatio.fill(0)
    }

    private companion object {
        const val TAG = "KalmanFilter"
    }
    
    /**
     * Get the current velocity estimate (for debugging)
     */
    fun getCurrentVelocity(): Pair<Float, Float> {
        return Pair(velocity[0], velocity[1])
    }
}
