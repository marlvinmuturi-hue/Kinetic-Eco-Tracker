package Kinetic_Eco.Tracker.services

import android.content.Context
import android.hardware.Sensor
import android.content.pm.PackageManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

data class SensorData(
    val acceleration: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val isAccelerometerReading: Boolean = false,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val stepCount: Int = 0,
    /** Angular rate magnitude (rad/s) from gyro X/Y/Z — used together with accel for fusion heuristics. */
    val rotationRate: Float = 0f,
    val pressure: Float = 0f,  // Barometric pressure in hPa
    val timestamp: Long = System.currentTimeMillis()
)

/** Single accelerometer sample for Firestore analytics (1 sample/sec during tracking). */
data class AccelerometerSample(
    val timestampMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float
)

/**
 * Describes the motion pattern detected from acceleration data (legacy — used for backward compat).
 */
enum class MotionPattern {
    UNKNOWN,
    BOUNCY,
    SMOOTH
}

/**
 * Rich activity hint from fusing accelerometer + gyroscope + step counter.
 * Gives [LocationService.classifyActivitySmart] enough signal to auto-detect cycling
 * and reliably separate pedestrian from motor motion.
 */
enum class SensorHint {
    UNKNOWN,        // Not enough data yet
    STILL,          // Near-zero motion — stationary
    ON_FOOT,        // Steps active OR high body accel/rotation → walking or running
    CYCLING_LIKELY, // Medium rhythmic accel, low gyro, no steps → cyclist pedalling
    MOTOR_LIKELY    // Low smooth accel, low gyro, no steps → car / motorcycle / train
}

/**
 * Spectral power profile computed from the accelerometer magnitude signal.
 * All power values are in (m/s²)² — useful as ratios, not absolute thresholds.
 */
data class FrequencyProfile(
    /** Summed Goertzel power in the pedestrian footfall band (0.8–2.5 Hz). */
    val walkBandPower: Float,
    /** Summed Goertzel power in the cycling road-vibration band (5–15 Hz). */
    val cycleBandPower: Float,
    /** Ratio of the peak bin to mean power — high values mean one frequency dominates (rhythmic motion). */
    val peakToMeanRatio: Float,
    /** The probe frequency (Hz) carrying the most power in this window. */
    val dominantFreqHz: Float,
    /**
     * True when the dominant frequency is in the walk band and the peak clearly
     * stands above the noise floor — strong indicator of on-foot locomotion.
     */
    val hasWalkingRhythm: Boolean,
    /**
     * True when the cycling band carries significantly more energy than the walk band,
     * distinguishing tyre/road vibration (bicycle) from footfall noise or near-zero car floor noise.
     */
    val hasCycleVibration: Boolean
)

/**
 * Goertzel-based frequency analyser for activity classification.
 *
 * Maintains a ~3.2 s ring buffer of linear-acceleration magnitude samples
 * (fed at the device's SENSOR_DELAY_GAME rate, typically ~50 Hz) and computes
 * spectral power at physiologically-relevant probe frequencies without a full FFT:
 *
 *   Walking cadence  : 0.8–2.5 Hz   one footfall every 0.4–1.25 s
 *   Cycling vibration: 5–15 Hz      tyre + road vibration conducted through the frame
 *
 * Cars produce broadband, very-low-amplitude noise — no dominant spectral peak.
 * Walking produces a sharp periodic peak at 1–2 Hz.
 * Cycling shows elevated 5–15 Hz energy alongside a weaker 1–2 Hz pedalling signal.
 */
class FrequencyAnalyzer {
    companion object {
        private const val BUFFER_SIZE = 160         // ≈ 3.2 s at 50 Hz
        private const val MIN_SAMPLES = 80          // require ≥ 1.6 s before analysing

        private val PROBE_HZ = floatArrayOf(
            0.8f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f,  // walk / run cadence
            5.0f, 8.0f, 10.0f, 12.0f, 15.0f         // cycling tyre/road vibration
        )

        private const val WALK_LOW  = 0.8f
        private const val WALK_HIGH = 2.5f
        private const val CYCLE_LOW  = 5.0f
        private const val CYCLE_HIGH = 15.0f

        // Cycling band must carry this many times more energy than the walk band
        private const val CYCLE_DOMINANCE = 1.8f
        // Peak/mean ratio above which there is a clear dominant frequency (rhythmic motion)
        private const val RHYTHMIC_RATIO = 3.0f
    }

    private val ring      = FloatArray(BUFFER_SIZE)
    private var writeHead = 0
    private var count     = 0

    private var estimatedHz  = 50f
    private var firstMs      = 0L
    private var totalSamples = 0

    fun addSample(magnitude: Float, nowMs: Long = System.currentTimeMillis()) {
        ring[writeHead] = magnitude
        writeHead = (writeHead + 1) % BUFFER_SIZE
        if (count < BUFFER_SIZE) count++
        totalSamples++

        if (firstMs == 0L) firstMs = nowMs
        // Re-estimate every 100 samples so we adapt if the sensor rate drifts
        if (totalSamples % 100 == 0 && count >= 50) {
            val elapsedS = (nowMs - firstMs) / 1000f
            if (elapsedS > 1f) {
                estimatedHz = (totalSamples.coerceAtMost(BUFFER_SIZE) / elapsedS)
                    .coerceIn(20f, 100f)
            }
        }
    }

    /** Returns null until MIN_SAMPLES have been collected. */
    fun analyze(): FrequencyProfile? {
        if (count < MIN_SAMPLES) return null

        val n = count
        val x = FloatArray(n) { i -> ring[(writeHead - n + i + BUFFER_SIZE) % BUFFER_SIZE] }

        // Remove DC offset so slow gravity drift doesn't bleed into the walk band
        val dc = x.average().toFloat()
        for (i in x.indices) x[i] -= dc

        val powers = FloatArray(PROBE_HZ.size) { i -> goertzel(x, PROBE_HZ[i], estimatedHz) }

        val maxPow    = powers.maxOrNull() ?: return null
        val meanPow   = (powers.sum() / powers.size).coerceAtLeast(1e-8f)
        val maxIdx    = powers.indexOfFirst { it == maxPow }
        val walkPow   = bandSum(powers, WALK_LOW,  WALK_HIGH)
        val cyclePow  = bandSum(powers, CYCLE_LOW, CYCLE_HIGH)
        val dominantHz = PROBE_HZ[maxIdx]
        val peakRatio  = maxPow / meanPow

        return FrequencyProfile(
            walkBandPower     = walkPow,
            cycleBandPower    = cyclePow,
            peakToMeanRatio   = peakRatio,
            dominantFreqHz    = dominantHz,
            hasWalkingRhythm  = dominantHz in WALK_LOW..WALK_HIGH && peakRatio > RHYTHMIC_RATIO,
            hasCycleVibration = cyclePow > walkPow * CYCLE_DOMINANCE
        )
    }

    fun reset() {
        ring.fill(0f)
        writeHead    = 0
        count        = 0
        firstMs      = 0L
        totalSamples = 0
        estimatedHz  = 50f
    }

    private fun bandSum(powers: FloatArray, low: Float, high: Float): Float {
        var sum = 0f
        for (i in PROBE_HZ.indices) {
            if (PROBE_HZ[i] in low..high) sum += powers[i]
        }
        return sum
    }

    /**
     * Goertzel algorithm: O(N) DFT power at a single target frequency.
     * Returns squared DFT magnitude (proportional to spectral power at freqHz).
     */
    private fun goertzel(x: FloatArray, freqHz: Float, fs: Float): Float {
        val n     = x.size
        val k     = (n * freqHz / fs + 0.5).toInt().coerceIn(0, n / 2)
        val coeff = (2.0 * cos(2.0 * PI * k / n)).toFloat()

        var s1 = 0f
        var s2 = 0f
        for (v in x) {
            val s0 = v + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}

/**
 * Multi-feature activity classifier using accelerometer magnitude variance,
 * gyroscope mean, and step-counter events together.
 *
 * Key insight: the step counter is the most reliable on-foot signal.
 * Without steps, accel variance and gyro magnitude distinguish
 * cycling (medium rhythmic accel, low gyro) from motor travel (very low accel + gyro).
 */
class SensorActivityClassifier(private val deviceHasGyro: Boolean = true) {
    companion object {
        private const val WINDOW_MS   = 5_000L
        private const val MIN_SAMPLES = 20

        // Accel variance thresholds (m/s² squared)
        private const val ACCEL_STILL       = 0.10f
        private const val ACCEL_MOTOR_MAX   = 0.70f  // below = motor-smooth or still
        private const val ACCEL_CYCLING_MAX = 5.0f   // above = foot activity (running)

        /**
         * Mean linear-acceleration magnitude ceiling for STILL (m/s²).
         *
         * Variance alone measures *jerk*, not motion: a vehicle under steady power pins the
         * magnitude at a near-constant 1–3 m/s², which has almost no variance and was therefore
         * being classified STILL — the same verdict as a phone on a table. Constant-velocity
         * cruising on smooth tarmac reads the same way. Requiring a low *mean* as well
         * distinguishes the two: a genuinely stationary device sits near zero, while a car that
         * is accelerating (or riding road forces) does not.
         */
        private const val ACCEL_STILL_MEAN  = 0.35f

        // Gyro mean thresholds (rad/s)
        private const val GYRO_STILL     = 0.06f
        private const val GYRO_MOTOR_MAX = 0.18f     // below + no steps = cycling or motor

        private const val STEP_ACTIVE_WINDOW_MS = 5_000L
    }

    private data class Sample(val ms: Long, val accel: Float, val gyro: Float)
    private val samples      = mutableListOf<Sample>()
    private var lastStepMs   = 0L
    private val freqAnalyzer = FrequencyAnalyzer()

    fun addSample(accel: Float, gyro: Float, nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - WINDOW_MS
        samples.removeAll { it.ms < cutoff }
        samples.add(Sample(nowMs, accel, gyro))
        freqAnalyzer.addSample(accel, nowMs)
    }

    fun recordStep(nowMs: Long = System.currentTimeMillis()) { lastStepMs = nowMs }

    fun classify(): SensorHint {
        if (samples.size < MIN_SAMPLES) return SensorHint.UNKNOWN

        val accelVar   = variance(samples.map { it.accel })
        val accelMean  = samples.map { it.accel }.average().toFloat()
        val gyroMean   = if (deviceHasGyro) samples.map { it.gyro }.average().toFloat() else 0f
        val stepActive = lastStepMs > 0 &&
            (System.currentTimeMillis() - lastStepMs) < STEP_ACTIVE_WINDOW_MS

        if (stepActive) return SensorHint.ON_FOOT

        val gyroOk    = !deviceHasGyro || gyroMean < GYRO_MOTOR_MAX
        val gyroStill = !deviceHasGyro || gyroMean < GYRO_STILL

        // Both a low variance AND a low mean are required — see ACCEL_STILL_MEAN. Variance
        // on its own cannot separate "not moving" from "moving at a constant rate".
        if (accelVar < ACCEL_STILL && accelMean < ACCEL_STILL_MEAN && gyroStill) return SensorHint.STILL

        // Frequency-domain refinement: analyse spectral content of the acceleration signal
        val freq = freqAnalyzer.analyze()
        if (freq != null) {
            // Elevated 5–15 Hz energy relative to the walk band is the clearest cycling indicator:
            // tyre/road vibration conducts through the frame at this range, absent in walking or cars.
            if (freq.hasCycleVibration && gyroOk && accelVar < ACCEL_CYCLING_MAX)
                return SensorHint.CYCLING_LIKELY

            // A sharp dominant peak at 1–2 Hz confirms rhythmic footfall even when the step
            // counter has not fired (phone orientation or model may suppress step events).
            if (freq.hasWalkingRhythm)
                return SensorHint.ON_FOOT

            // No rhythmic dominant peak + low overall variance = smooth motor travel (car, train).
            if (!freq.hasWalkingRhythm && !freq.hasCycleVibration &&
                freq.peakToMeanRatio < 2.5f && accelVar < ACCEL_MOTOR_MAX && gyroOk)
                return SensorHint.MOTOR_LIKELY
        }

        // Variance-only fallback for when the frequency analyser has insufficient data
        if (accelVar < ACCEL_MOTOR_MAX && gyroOk)   return SensorHint.MOTOR_LIKELY
        if (accelVar < ACCEL_CYCLING_MAX && gyroOk)  return SensorHint.CYCLING_LIKELY
        return SensorHint.ON_FOOT
    }

    /** Exposes the current frequency profile for logging or downstream use. */
    fun getFrequencyProfile(): FrequencyProfile? = freqAnalyzer.analyze()

    fun reset() { samples.clear(); lastStepMs = 0L; freqAnalyzer.reset() }
    fun hasEnoughData() = samples.size >= MIN_SAMPLES

    private fun variance(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val mean = v.average().toFloat()
        return v.map { (it - mean) * (it - mean) }.average().toFloat()
    }
}

/**
 * Analyzes acceleration patterns to distinguish running from driving
 */
class AccelerationPatternAnalyzer {
    companion object {
        private const val WINDOW_SIZE_MS = 3000L  // 3 second observation window
        private const val MIN_SAMPLES = 15        // Minimum samples needed for analysis
        private const val BOUNCY_VARIANCE_THRESHOLD = 2.5f  // Variance above this = bouncy (running)
    }

    private val accelerationSamples = mutableListOf<Pair<Long, Float>>()

    /**
     * Add a new acceleration sample
     */
    fun addSample(acceleration: Float, timestamp: Long = System.currentTimeMillis()) {
        accelerationSamples.add(Pair(timestamp, acceleration))
        
        // Remove samples older than the window
        val cutoff = timestamp - WINDOW_SIZE_MS
        accelerationSamples.removeAll { it.first < cutoff }
    }

    /**
     * Analyze the current acceleration pattern
     * Returns BOUNCY for running-like patterns, SMOOTH for driving-like patterns
     */
    fun analyzePattern(): MotionPattern {
        if (accelerationSamples.size < MIN_SAMPLES) {
            return MotionPattern.UNKNOWN
        }

        val values = accelerationSamples.map { it.second }
        val variance = calculateVariance(values)

        return if (variance > BOUNCY_VARIANCE_THRESHOLD) {
            MotionPattern.BOUNCY
        } else {
            MotionPattern.SMOOTH
        }
    }

    /**
     * Get the current variance value for debugging/logging
     */
    fun getCurrentVariance(): Float {
        if (accelerationSamples.size < MIN_SAMPLES) return 0f
        return calculateVariance(accelerationSamples.map { it.second })
    }

    /**
     * Check if we have enough data for analysis
     */
    fun hasEnoughData(): Boolean = accelerationSamples.size >= MIN_SAMPLES

    /**
     * Clear all samples (call when tracking stops)
     */
    fun reset() {
        accelerationSamples.clear()
    }

    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        val squaredDiffs = values.map { (it - mean) * (it - mean) }
        return squaredDiffs.average().toFloat()
    }
}

class SensorService(private val context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val patternAnalyzer = AccelerationPatternAnalyzer()
    val sensorClassifier = SensorActivityClassifier(deviceHasGyro = gyroscope != null)

    // Barometric altitude tracking
    private var currentPressure: Float = 1013.25f  // Standard sea level pressure
    private var referenceAltitude: Double? = null
    private var referencePressure: Float? = null

    fun getSensorUpdates(): Flow<SensorData> = callbackFlow {
        var currentSteps = 0
        var initialSteps = -1

        var lastLax = 0f
        var lastLay = 0f
        var lastLaz = 0f
        var lastAccelMag = 0f

        var lastGyroX = 0f
        var lastGyroY = 0f
        var lastGyroZ = 0f
        var lastGyroMag = 0f

        fun emitSnapshot(isAccelerometerReading: Boolean, eventTimestampNs: Long) {
            trySend(
                SensorData(
                    acceleration = lastAccelMag,
                    accelX = lastLax,
                    accelY = lastLay,
                    accelZ = lastLaz,
                    isAccelerometerReading = isAccelerometerReading,
                    gyroX = lastGyroX,
                    gyroY = lastGyroY,
                    gyroZ = lastGyroZ,
                    rotationRate = lastGyroMag,
                    stepCount = currentSteps,
                    pressure = currentPressure,
                    timestamp = eventTimestampNs / 1_000_000L
                )
            )
        }
        
        // Log step counter availability
        android.util.Log.d("SensorService", "Step counter available: ${stepCounter != null}")
        android.util.Log.d("SensorService", "Has step counter feature: ${hasStepCounter()}")
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val magnitude = sqrt(x * x + y * y + z * z)

                        lastLax = x
                        lastLay = y
                        lastLaz = z
                        lastAccelMag = magnitude

                        patternAnalyzer.addSample(magnitude)
                        sensorClassifier.addSample(magnitude, lastGyroMag)

                        emitSnapshot(isAccelerometerReading = true, event.timestamp)
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        val steps = event.values[0].toInt()
                        if (initialSteps == -1) {
                            initialSteps = steps
                            android.util.Log.d("SensorService", "Initial step count: $initialSteps")
                        }
                        currentSteps = steps - initialSteps
                        android.util.Log.d("SensorService", "Step counter event - Raw: $steps, Current: $currentSteps")
                        sensorClassifier.recordStep()
                        emitSnapshot(isAccelerometerReading = false, event.timestamp)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val gx = event.values[0]
                        val gy = event.values[1]
                        val gz = event.values[2]
                        val rotationRate = sqrt(gx * gx + gy * gy + gz * gz)
                        lastGyroX = gx
                        lastGyroY = gy
                        lastGyroZ = gz
                        lastGyroMag = rotationRate
                        emitSnapshot(isAccelerometerReading = false, event.timestamp)
                    }
                    Sensor.TYPE_PRESSURE -> {
                        currentPressure = event.values[0]
                        emitSnapshot(isAccelerometerReading = false, event.timestamp)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            android.util.Log.d("SensorService", "Accelerometer registered")
        }
        stepCounter?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            android.util.Log.d("SensorService", "Step counter registered")
        } ?: android.util.Log.w("SensorService", "Step counter not available!")
        gyroscope?.let {
            // SENSOR_DELAY_UI (~16 Hz), not GAME (~50 Hz): the gyro is only consumed as a
            // windowed *mean* angular rate in SensorActivityClassifier, so a lower rate is
            // sufficient and saves power. (The accelerometer stays at GAME because
            // FrequencyAnalyzer needs ≥30 Hz to resolve the 5–15 Hz cycling band.)
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            android.util.Log.d("SensorService", "Gyroscope registered")
        }
        barometer?.let { 
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            android.util.Log.d("SensorService", "Barometer registered")
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    fun hasStepCounter(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    }

    fun hasAccelerometer(): Boolean {
        return accelerometer != null
    }

    fun hasGyroscope(): Boolean = gyroscope != null
    
    fun hasBarometer(): Boolean {
        return barometer != null
    }

    fun getMotionPattern(): MotionPattern = patternAnalyzer.analyzePattern()

    fun getSensorHint(): SensorHint = sensorClassifier.classify()

    fun getFrequencyProfile(): FrequencyProfile? = sensorClassifier.getFrequencyProfile()

    fun resetPatternAnalyzer() {
        patternAnalyzer.reset()
        sensorClassifier.reset()
    }
    
    /**
     * Calculate altitude from barometric pressure using the standard atmosphere formula.
     * More accurate than GPS altitude (±5m vs ±50m) and updates faster (10Hz vs 1Hz).
     * 
     * @param gpsAltitude Optional GPS altitude for calibration
     * @return Estimated altitude in meters
     */
    fun getBarometricAltitude(gpsAltitude: Double? = null): Double {
        // Calibrate reference pressure with GPS altitude when available
        if (gpsAltitude != null && (referenceAltitude == null || 
            kotlin.math.abs(gpsAltitude - referenceAltitude!!) > 100)) {
            referenceAltitude = gpsAltitude
            referencePressure = currentPressure
            android.util.Log.d("SensorService", 
                "Calibrated barometer: GPS=${gpsAltitude}m, P=${currentPressure}hPa")
        }
        
        // Standard barometric formula: h = 44330 * (1 - (P/P0)^0.1903)
        // where P0 = reference pressure (standard: 1013.25 hPa)
        val baseAltitude = 44330.0 * (1.0 - (currentPressure / 1013.25).toDouble().pow(0.1903))
        
        // Apply calibration offset if available
        return if (referenceAltitude != null && referencePressure != null) {
            val referenceCalcAltitude = 44330.0 * (1.0 - (referencePressure!! / 1013.25).toDouble().pow(0.1903))
            val offset = referenceAltitude!! - referenceCalcAltitude
            baseAltitude + offset
        } else {
            baseAltitude
        }
    }
    
    /**
     * Get current barometric pressure in hPa
     */
    fun getCurrentPressure(): Float = currentPressure
    
    /**
     * Reset barometric calibration
     */
    fun resetBarometerCalibration() {
        referenceAltitude = null
        referencePressure = null
        android.util.Log.d("SensorService", "Barometer calibration reset")
    }
}
