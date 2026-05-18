package Kinetic_Eco.Tracker.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import Kinetic_Eco.Tracker.data.*
import java.util.concurrent.TimeUnit

// Speed thresholds for ambiguous zone (15-22 km/h in m/s)
private const val AMBIGUOUS_SPEED_MIN = 4.17f   // 15 km/h
private const val AMBIGUOUS_SPEED_MAX = 6.11f   // 22 km/h

// Minimum speed to never classify as IDLE (user requirement)
private const val IDLE_OVERRIDE_THRESHOLD = 0.833f  // 3 km/h

// Cycling speed ceiling: sensor hint wins up to 50 km/h; above that → driving
private const val MAX_CYCLING_SPEED = 13.9f  // 50 km/h

class LocationService(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private var locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        250L // 250ms for near-instant speed updates (like pedometer)
    ).apply {
        setMaxUpdateDelayMillis(500L)
        setMinUpdateIntervalMillis(250L)
        setWaitForAccurateLocation(false)
    }.build()
    
    /** Lower-frequency request for GPS warm-up when Tracker screen is shown (saves battery) */
    private val warmUpLocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        1000L // 1 second for warm-up
    ).apply {
        setMaxUpdateDelayMillis(2000L)
        setMinUpdateIntervalMillis(1000L)
        setWaitForAccurateLocation(false)
    }.build()
    
    private var locationCallback: LocationCallback? = null
    /** When flying, use this looser threshold (aircraft GPS often 200–500m). Null = use normal threshold. */
    private var flyingAccuracyOverride: Float? = null
    
    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun getCurrentLocation(): Flow<GeoPosition?> = callbackFlow {
        if (!hasLocationPermission()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        
        try {
            val location = fusedLocationClient.lastLocation.await()
            if (location != null) {
                trySend(location.toGeoPosition())
            } else {
                trySend(null)
            }
        } catch (e: Exception) {
            trySend(null)
        }
        close()
    }
    
    /**
     * Get location updates with optional flying mode for adaptive accuracy filtering.
     * 
     * @param isFlying Whether the user is currently flying (enables lenient GPS filtering)
     * @param maxAccuracy Custom maximum accuracy threshold (null = use default)
     */
    fun getLocationUpdates(
        isFlying: Boolean = false,
        maxAccuracy: Float? = null
    ): Flow<GeoPosition> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // Use flying override when set (250m for aircraft), else maxAccuracy or default
                    val accuracyThreshold = flyingAccuracyOverride ?: maxAccuracy ?: if (isFlying) 150f else 30f
                    
                    if (location.accuracy <= accuracyThreshold) {
                        trySend(location.toGeoPosition())
                    } else {
                        android.util.Log.d("LocationService", 
                            "Rejecting location with poor accuracy: ${location.accuracy}m " +
                            "(threshold: ${accuracyThreshold}m)")
                    }
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e(
                "LocationService",
                "requestLocationUpdates denied (grant location, including All the time for background tracking)",
                e
            )
            close()
            return@callbackFlow
        }
        
        awaitClose {
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
            }
        }
    }
    
    /**
     * Get location updates for GPS warm-up (lower frequency to save battery).
     * Call when Tracker screen is shown so GPS is ready when user hits Start.
     */
    fun getLocationUpdatesForWarmUp(maxAccuracy: Float? = null): Flow<GeoPosition> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val accuracyThreshold = maxAccuracy ?: 30f
                    if (location.accuracy <= accuracyThreshold) {
                        trySend(location.toGeoPosition())
                    }
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                warmUpLocationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("LocationService", "Warm-up requestLocationUpdates denied", e)
            close()
            return@callbackFlow
        }
        
        awaitClose {
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
            }
        }
    }
    
    /**
     * Update GPS configuration for flying mode.
     * Flying mode uses longer intervals to save battery during stable cruise.
     * 
     * @param isFlying Whether flying mode should be enabled
     */
    fun setFlyingMode(isFlying: Boolean) {
        flyingAccuracyOverride = if (isFlying) 250f else null  // Looser threshold for aircraft GPS
        locationRequest = if (isFlying) {
            // Flying mode: longer intervals, more lenient settings
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000L  // 2 seconds (planes move ~500m per update at cruise)
            ).apply {
                setMaxUpdateDelayMillis(3000L)
                setMinUpdateIntervalMillis(1000L)
                setWaitForAccurateLocation(false)  // Accept what we get
            }.build()
        } else {
            // Ground mode: 250ms for near-instant speed (like pedometer steps)
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                250L
            ).apply {
                setMaxUpdateDelayMillis(500L)
                setMinUpdateIntervalMillis(250L)
                setWaitForAccurateLocation(false)
            }.build()
        }
        
        android.util.Log.d("LocationService", "GPS mode changed: flying=$isFlying")
    }
    
    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }
    
    /**
     * Basic activity classification based on speed only.
     * Note: Cycling is excluded from auto-detection but available for manual selection.
     */
    fun classifyActivity(speed: Float): ActivityType {
        val baseActivity = when {
            speed < SpeedThresholds.WALKING_MIN -> ActivityType.IDLE
            speed < SpeedThresholds.RUNNING_MIN -> ActivityType.WALKING
            speed < SpeedThresholds.DRIVING_MIN -> ActivityType.RUNNING  // Running absorbs cycling range
            speed < SpeedThresholds.FLYING_MIN -> ActivityType.DRIVING
            else -> ActivityType.FLYING
        }
        
        // OVERRIDE: Never classify as IDLE if speed > 3 km/h
        // This prevents showing IDLE when user is clearly moving
        if (baseActivity == ActivityType.IDLE && speed >= IDLE_OVERRIDE_THRESHOLD) {
            android.util.Log.d("LocationService", 
                "🚶 IDLE Override: Speed ${String.format("%.1f", speed * 3.6f)} km/h → WALKING (> 3 km/h threshold)")
            return ActivityType.WALKING
        }
        
        return baseActivity
    }

    /**
     * Activity classification based on speed only (altitude is not used for category).
     * 
     * Flying is determined purely by speed (>= FLYING_MIN 50 m/s = 180 km/h).
     * Altitude does NOT affect category - e.g. at 1761m you can be walking/driving.
     * 
     * @param speed Current speed in m/s
     * @param currentAltitude Unused (kept for API compatibility)
     * @param previousAltitude Unused (kept for API compatibility)
     * @param motionPattern The detected motion pattern from accelerometer data
     * @param hasAccelerometer Whether the device has an accelerometer
     * @return Classified activity type
     */
    fun classifyActivityWithAltitude(
        speed: Float,
        @Suppress("UNUSED_PARAMETER") currentAltitude: Double?,
        @Suppress("UNUSED_PARAMETER") previousAltitude: Double?,
        motionPattern: MotionPattern,
        hasAccelerometer: Boolean,
        sensorHint: SensorHint = SensorHint.UNKNOWN
    ): ActivityType {
        if (speed < SpeedThresholds.FLYING_MIN) {
            return classifyActivitySmart(speed, motionPattern, hasAccelerometer, sensorHint)
        }
        return ActivityType.FLYING
    }

    fun classifyActivitySmart(
        speed: Float,
        motionPattern: MotionPattern,
        hasAccelerometer: Boolean,
        sensorHint: SensorHint = SensorHint.UNKNOWN
    ): ActivityType {
        // Sensors confirm stillness: GPS speed is noise — user is not moving
        if (sensorHint == SensorHint.STILL && speed < SpeedThresholds.DRIVING_MIN.toFloat()) {
            return ActivityType.IDLE
        }

        // Cycling: sensor classifier wins when speed is in the plausible cycling range.
        // Gate lowered to RUNNING_MIN (7 km/h) so slow cyclists are detected even below 14 km/h.
        if (sensorHint == SensorHint.CYCLING_LIKELY &&
            speed >= SpeedThresholds.RUNNING_MIN.toFloat() && speed < MAX_CYCLING_SPEED) {
            return ActivityType.CYCLING
        }

        // Above 18 km/h without a cycling hint → driving
        if (speed >= SpeedThresholds.DRIVING_MIN) {
            return ActivityType.DRIVING
        }

        // Outside the ambiguous zone, fall back to speed-only
        if (speed < AMBIGUOUS_SPEED_MIN || speed >= AMBIGUOUS_SPEED_MAX) {
            return classifyActivity(speed)
        }

        // Ambiguous zone (15-22 km/h): use motion pattern
        val baseActivity = when {
            speed < SpeedThresholds.WALKING_MIN -> ActivityType.IDLE
            speed < SpeedThresholds.RUNNING_MIN -> ActivityType.WALKING
            else -> if (!hasAccelerometer) {
                ActivityType.DRIVING
            } else when (motionPattern) {
                MotionPattern.BOUNCY -> ActivityType.RUNNING
                MotionPattern.SMOOTH -> ActivityType.DRIVING
                MotionPattern.UNKNOWN -> ActivityType.DRIVING
            }
        }

        if (baseActivity == ActivityType.IDLE && speed >= IDLE_OVERRIDE_THRESHOLD) {
            return ActivityType.WALKING
        }

        return baseActivity
    }
    
    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
    
    private fun Location.toGeoPosition(): GeoPosition {
        return GeoPosition(
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            speed = speed,
            timestamp = time,
            accuracy = accuracy
        )
    }
}


