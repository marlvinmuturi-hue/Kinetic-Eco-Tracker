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

        // Gyro mean thresholds (rad/s)
        private const val GYRO_STILL     = 0.06f
        private const val GYRO_MOTOR_MAX = 0.18f     // below + no steps = cycling or motor

        private const val STEP_ACTIVE_WINDOW_MS = 5_000L
    }

    private data class Sample(val ms: Long, val accel: Float, val gyro: Float)
    private val samples   = mutableListOf<Sample>()
    private var lastStepMs = 0L

    fun addSample(accel: Float, gyro: Float, nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - WINDOW_MS
        samples.removeAll { it.ms < cutoff }
        samples.add(Sample(nowMs, accel, gyro))
    }

    fun recordStep(nowMs: Long = System.currentTimeMillis()) { lastStepMs = nowMs }

    fun classify(): SensorHint {
        if (samples.size < MIN_SAMPLES) return SensorHint.UNKNOWN

        val accelVar  = variance(samples.map { it.accel })
        val gyroMean  = if (deviceHasGyro) samples.map { it.gyro }.average().toFloat() else 0f
        val stepActive = lastStepMs > 0 &&
            (System.currentTimeMillis() - lastStepMs) < STEP_ACTIVE_WINDOW_MS

        if (stepActive) return SensorHint.ON_FOOT

        val gyroOk = !deviceHasGyro || gyroMean < GYRO_MOTOR_MAX
        val gyroStill = !deviceHasGyro || gyroMean < GYRO_STILL

        if (accelVar < ACCEL_STILL && gyroStill) return SensorHint.STILL
        if (accelVar < ACCEL_MOTOR_MAX && gyroOk)  return SensorHint.MOTOR_LIKELY
        if (accelVar < ACCEL_CYCLING_MAX && gyroOk) return SensorHint.CYCLING_LIKELY
        return SensorHint.ON_FOOT
    }

    fun reset() { samples.clear(); lastStepMs = 0L }
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
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
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
