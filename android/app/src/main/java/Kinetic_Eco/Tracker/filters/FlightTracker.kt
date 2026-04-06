package Kinetic_Eco.Tracker.filters

import kotlin.math.abs

/**
 * Flight phase detection for improved plane tracking accuracy.
 * 
 * Detects different phases of flight (ground, taxi, takeoff, climb, cruise, descent, landing)
 * to apply phase-specific tracking parameters and improve accuracy.
 */
class FlightTracker {
    enum class FlightPhase {
        GROUND,      // On ground, not moving
        TAXI,        // Moving on ground
        TAKEOFF,     // Accelerating, gaining altitude rapidly
        CLIMB,       // Ascending to cruise altitude
        CRUISE,      // Stable altitude, high speed
        DESCENT,     // Descending from cruise
        LANDING      // Final approach and touchdown
    }
    
    data class FlightState(
        val phase: FlightPhase,
        val cruiseAltitude: Double = 0.0,
        val isFlying: Boolean = false
    )
    
    private var currentPhase = FlightPhase.GROUND
    private var cruiseAltitude = 0.0
    private var phaseStartTime = System.currentTimeMillis()
    private var stablePhaseCount = 0
    
    /**
     * Update flight phase based on current telemetry.
     * 
     * @param speed Current speed in m/s
     * @param altitude Current altitude in meters
     * @param previousAltitude Previous altitude in meters
     * @param timeDelta Time since last update in seconds
     * @return Current flight state
     */
    fun updatePhase(
        speed: Float,
        altitude: Double,
        previousAltitude: Double,
        timeDelta: Float
    ): FlightState {
        val speedKmh = speed * 3.6
        
        // Calculate vertical speed (m/s)
        val verticalSpeed = if (timeDelta > 0) {
            ((altitude - previousAltitude) / timeDelta).toFloat()
        } else 0f
        
        // Determine new phase
        val newPhase = when {
            // Ground - low speed, low altitude
            speedKmh < 5 && altitude < 100 -> FlightPhase.GROUND
            
            // Taxi - moderate ground speed
            speedKmh in 5.0..100.0 && altitude < 100 -> FlightPhase.TAXI
            
            // Takeoff - high acceleration with altitude gain
            speedKmh > 150 && verticalSpeed > 10 && altitude < 3000 -> FlightPhase.TAKEOFF
            
            // Climb - ascending to cruise altitude
            verticalSpeed > 5 && altitude in 1000.0..10000.0 -> FlightPhase.CLIMB
            
            // Cruise - stable altitude, high speed
            speedKmh > 500 && abs(verticalSpeed) < 3 && altitude > 8000 -> {
                // Update cruise altitude
                if (abs(altitude - cruiseAltitude) > 500) {
                    cruiseAltitude = altitude
                }
                FlightPhase.CRUISE
            }
            
            // Descent - descending from altitude
            verticalSpeed < -5 && altitude > 1000 -> FlightPhase.DESCENT
            
            // Landing - final approach
            speedKmh in 150.0..300.0 && altitude < 1000 && verticalSpeed < -3 -> FlightPhase.LANDING
            
            // Default - maintain current phase if unclear
            else -> currentPhase
        }
        
        // Update phase with hysteresis (require 3 consistent readings to change)
        if (newPhase != currentPhase) {
            stablePhaseCount++
            if (stablePhaseCount >= 3) {
                currentPhase = newPhase
                phaseStartTime = System.currentTimeMillis()
                stablePhaseCount = 0
            }
        } else {
            stablePhaseCount = 0
        }
        
        // Determine if currently flying (in air)
        val isFlying = currentPhase in listOf(
            FlightPhase.TAKEOFF,
            FlightPhase.CLIMB,
            FlightPhase.CRUISE,
            FlightPhase.DESCENT,
            FlightPhase.LANDING
        )
        
        return FlightState(
            phase = currentPhase,
            cruiseAltitude = cruiseAltitude,
            isFlying = isFlying
        )
    }
    
    /**
     * Get recommended GPS update interval for current flight phase (milliseconds)
     */
    fun getRecommendedUpdateInterval(): Long {
        return when (currentPhase) {
            FlightPhase.GROUND -> 5000L      // 5 seconds when stationary
            FlightPhase.TAXI -> 2000L         // 2 seconds when taxiing
            FlightPhase.TAKEOFF -> 1000L      // 1 second during takeoff
            FlightPhase.CLIMB -> 2000L        // 2 seconds while climbing
            FlightPhase.CRUISE -> 3000L       // 3 seconds at cruise (stable)
            FlightPhase.DESCENT -> 2000L      // 2 seconds while descending
            FlightPhase.LANDING -> 1000L      // 1 second during landing
        }
    }
    
    /**
     * Get recommended maximum GPS accuracy threshold for current phase (meters)
     */
    fun getRecommendedAccuracyThreshold(): Float {
        return when (currentPhase) {
            FlightPhase.GROUND, FlightPhase.TAXI -> 50f      // Strict on ground
            FlightPhase.TAKEOFF, FlightPhase.LANDING -> 100f  // Moderate during transitions
            FlightPhase.CLIMB, FlightPhase.DESCENT -> 150f    // Lenient while changing altitude
            FlightPhase.CRUISE -> 200f                        // Most lenient at cruise
        }
    }
    
    /**
     * Get recommended Kalman filter process noise for current phase
     */
    fun getRecommendedProcessNoise(): Float {
        return when (currentPhase) {
            FlightPhase.GROUND -> 0.1f        // Very stable
            FlightPhase.TAXI -> 0.3f          // Some movement
            FlightPhase.TAKEOFF -> 1.0f       // High dynamics
            FlightPhase.CLIMB -> 0.8f         // Moderate dynamics
            FlightPhase.CRUISE -> 0.3f        // Stable flight
            FlightPhase.DESCENT -> 0.8f       // Moderate dynamics
            FlightPhase.LANDING -> 1.2f       // Highest dynamics
        }
    }
    
    /**
     * Reset the flight tracker
     */
    fun reset() {
        currentPhase = FlightPhase.GROUND
        cruiseAltitude = 0.0
        phaseStartTime = System.currentTimeMillis()
        stablePhaseCount = 0
    }
    
    /**
     * Get current flight phase
     */
    fun getCurrentPhase(): FlightPhase = currentPhase
    
    /**
     * Get time spent in current phase (milliseconds)
     */
    fun getPhaseTime(): Long = System.currentTimeMillis() - phaseStartTime
}
