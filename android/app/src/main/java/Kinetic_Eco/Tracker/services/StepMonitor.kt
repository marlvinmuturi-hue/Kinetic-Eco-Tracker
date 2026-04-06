package Kinetic_Eco.Tracker.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Lightweight step counter for in-app auto-start.
 * Emits step count delta (0-based from when flow starts).
 * Use when TrackerScreen is visible and not tracking - auto-start when steps exceed threshold.
 */
class StepMonitor(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    fun getStepCountFlow(): Flow<Int> = callbackFlow {
        if (stepCounter == null) {
            trySend(0)
            close()
            return@callbackFlow
        }

        var initialSteps = -1

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    val raw = event.values[0].toInt()
                    if (initialSteps == -1) {
                        initialSteps = raw
                    }
                    val relative = raw - initialSteps
                    trySend(relative)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, stepCounter, SensorManager.SENSOR_DELAY_UI)

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    fun hasStepCounter(): Boolean = stepCounter != null
}
