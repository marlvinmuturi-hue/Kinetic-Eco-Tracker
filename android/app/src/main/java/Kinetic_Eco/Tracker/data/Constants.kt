package Kinetic_Eco.Tracker.data

import androidx.compose.ui.graphics.Color
import Kinetic_Eco.Tracker.ui.theme.*

// Speed thresholds in m/s (1 m/s = 3.6 km/h)
object SpeedThresholds {
    /** Max speed to classify as IDLE: 0.5 km/h (avoids "0 km/h WALKING" display) */
    const val IDLE_SPEED_MAX = 0.14f  // 0.5 km/h in m/s
    const val WALKING_MIN = 0.5  // 1.8 km/h (to filter GPS drift)
    const val RUNNING_MIN = 2.0  // 7.2 km/h
    const val RUNNING_MAX = 5.0   // 18 km/h - max for running, min for driving
    const val CYCLING_MIN = 4.0  // 14.4 km/h
    const val DRIVING_MIN = 5.0  // 18 km/h (was 36 - running max/driving min)
    const val FLYING_MIN = 50.0  // 180 km/h - min for flying, max for driving
}

// CO2 Emissions in kg per km (positive = emissions, negative = conservation/savings)
object CO2Factors {
    const val IDLE = 0.0
    const val WALKING = -0.192  // Negative = CO2 saved vs driving
    const val RUNNING = -0.192  // Same as walking - conserving by not driving
    const val CYCLING = -0.192  // Conserving CO2 vs driving
    const val TRAIN = 0.04      // Electric/regional rail (~20% of driving)
    const val DRIVING = 0.192   // Avg passenger vehicle emissions (gas/diesel)
    const val ELECTRIC_VEHICLE = 0.053  // EV emissions (70% less than gas)
    const val FLYING = 0.255    // Avg domestic flight emissions
    
    fun getFactor(activity: ActivityType): Double {
        return when (activity) {
            ActivityType.IDLE -> IDLE
            ActivityType.WALKING -> WALKING
            ActivityType.RUNNING -> RUNNING
            ActivityType.CYCLING -> CYCLING
            ActivityType.TRAIN -> TRAIN
            ActivityType.DRIVING -> DRIVING
            ActivityType.ELECTRIC_VEHICLE -> ELECTRIC_VEHICLE
            ActivityType.FLYING -> FLYING
        }
    }
}

// Calories burned per hour (approximate average person)
object CalorieFactorsPerHour {
    const val IDLE = 60
    const val WALKING = 250
    const val RUNNING = 600
    const val CYCLING = 400
    const val TRAIN = 100       // Seated travel
    const val DRIVING = 100
    const val ELECTRIC_VEHICLE = 100
    const val FLYING = 90
    
    fun getFactor(activity: ActivityType): Int {
        return when (activity) {
            ActivityType.IDLE -> IDLE
            ActivityType.WALKING -> WALKING
            ActivityType.RUNNING -> RUNNING
            ActivityType.CYCLING -> CYCLING
            ActivityType.TRAIN -> TRAIN
            ActivityType.DRIVING -> DRIVING
            ActivityType.ELECTRIC_VEHICLE -> ELECTRIC_VEHICLE
            ActivityType.FLYING -> FLYING
        }
    }
}

// Activity Colors (matching web app)
object ActivityColors {
    fun getColor(activity: ActivityType): Color {
        return when (activity) {
            ActivityType.IDLE -> Slate400
            ActivityType.WALKING -> Green500
            ActivityType.RUNNING -> Emerald500
            ActivityType.CYCLING -> Cyan500
            ActivityType.TRAIN -> Teal500
            ActivityType.DRIVING -> Amber500
            ActivityType.ELECTRIC_VEHICLE -> Violet500
            ActivityType.FLYING -> Blue500
        }
    }
}

// Baseline CO2 per km that would be emitted if driving (for conservation calculations)
const val BASELINE_DRIVING_CO2_PER_KM = 0.192



