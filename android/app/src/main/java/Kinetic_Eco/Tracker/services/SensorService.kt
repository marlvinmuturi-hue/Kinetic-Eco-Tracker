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
    val stepCount: Int = 0,
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
 * Describes the motion pattern detected from acceleration data
 */
enum class MotionPattern {
    UNKNOWN,    // Not enough data yet
    BOUNCY,     // High variance - likely running/walking
    SMOOTH      // Low variance - likely driving
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

    // Pattern analyzer for detecting running vs driving
    val patternAnalyzer = AccelerationPatternAnalyzer()
    
    // Barometric altitude tracking
    private var currentPressure: Float = 1013.25f  // Standard sea level pressure
    private var referenceAltitude: Double? = null
    private var referencePressure: Float? = null

    fun getSensorUpdates(): Flow<SensorData> = callbackFlow {
        var currentSteps = 0
        var initialSteps = -1
        
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
                        
                        // Add sample to pattern analyzer
                        patternAnalyzer.addSample(magnitude)
                        
                        trySend(SensorData(
                            acceleration = magnitude,
                            accelX = x,
                            accelY = y,
                            accelZ = z,
                            isAccelerometerReading = true,
                            stepCount = currentSteps,
                            pressure = currentPressure
                        ))
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        val steps = event.values[0].toInt()
                        if (initialSteps == -1) {
                            initialSteps = steps
                            android.util.Log.d("SensorService", "Initial step count: $initialSteps")
                        }
                        currentSteps = steps - initialSteps
                        android.util.Log.d("SensorService", "Step counter event - Raw: $steps, Current: $currentSteps")
                        trySend(SensorData(
                            stepCount = currentSteps,
                            pressure = currentPressure
                        ))
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val rotationRate = sqrt(x * x + y * y + z * z)
                        trySend(SensorData(
                            rotationRate = rotationRate, 
                            stepCount = currentSteps,
                            pressure = currentPressure
                        ))
                    }
                    Sensor.TYPE_PRESSURE -> {
                        currentPressure = event.values[0]
                        trySend(SensorData(
                            stepCount = currentSteps,
                            pressure = currentPressure
                        ))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let { 
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            android.util.Log.d("SensorService", "Accelerometer registered")
        }
        stepCounter?.let { 
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            android.util.Log.d("SensorService", "Step counter registered")
        } ?: android.util.Log.w("SensorService", "Step counter not available!")
        gyroscope?.let { 
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
    
    fun hasBarometer(): Boolean {
        return barometer != null
    }

    /**
     * Get the current motion pattern (BOUNCY = running, SMOOTH = driving)
     */
    fun getMotionPattern(): MotionPattern {
        return patternAnalyzer.analyzePattern()
    }

    /**
     * Reset the pattern analyzer (call when tracking stops)
     */
    fun resetPatternAnalyzer() {
        patternAnalyzer.reset()
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
