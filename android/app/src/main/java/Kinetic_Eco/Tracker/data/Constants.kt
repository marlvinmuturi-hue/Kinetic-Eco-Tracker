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

// CO2 in kg per km (negative = saved vs. standard car baseline; positive = direct emissions)
// Baseline: 0.21 kg/km for an average petrol passenger car (IPCC/EEA reference)
object CO2Factors {
    const val IDLE = 0.0
    const val WALKING = -0.21   // Saves full baseline — human locomotion has negligible direct emissions
    const val RUNNING = -0.21   // Same as walking
    const val CYCLING = -0.17   // Saves slightly less — food-production lifecycle cost ~0.04 kg/km
    const val TRAIN = -0.17     // Saves 0.17 kg/km vs. driving baseline (0.21 − 0.04 electric rail)
    const val DRIVING = 0.21    // Standard car baseline (avg petrol passenger vehicle)
    const val ELECTRIC_VEHICLE = 0.053  // EV well-to-wheel grid emissions (~75% less than gas)
    const val FLYING = 0.255    // Avg narrow-body jet (emits MORE than driving — net emitter)
    
    fun getFactor(activity: ActivityType): Double {
        return getFactor(activity, VehicleProfile.DEFAULT)
    }

    fun getFactor(activity: ActivityType, profile: VehicleProfile): Double {
        return when (activity) {
            // Combustion driving: blend engine displacement, body type
            // (sedan reference, SUV/pickup heavier, hatch lighter), and the
            // diesel-vs-petrol bump.
            ActivityType.DRIVING -> IceFuel.co2FromCcBandAndBody(
                ccBand = profile.drivingCcBand,
                bodyType = profile.bodyType,
                fuel = profile.iceFuel
            )
            // Electric road vehicle: pick a base by class (2-/3-wheeler/car)
            // and scale by motor power band — a 200 kW dual-motor sedan draws
            // markedly more per km than a 5 kW e-scooter.
            ActivityType.ELECTRIC_VEHICLE ->
                profile.electricVehicleClass.baseCo2KgPerKm * profile.electricMotorPower.multiplier
            ActivityType.TRAIN -> profile.trainPropulsion.co2KgPerKm
            ActivityType.FLYING -> profile.aircraftCategory.co2KgPerKm
            ActivityType.IDLE -> IDLE
            ActivityType.WALKING -> WALKING
            ActivityType.RUNNING -> RUNNING
            ActivityType.CYCLING -> CYCLING
            ActivityType.MOTORCYCLE -> when (profile.primaryFuelType) {
                PrimaryFuelType.ELECTRIC ->
                    ElectricVehicleClass.TWO_WHEELER.baseCo2KgPerKm * profile.electricMotorPower.multiplier
                PrimaryFuelType.PETROL, PrimaryFuelType.DIESEL ->
                    IceFuel.co2FromCcBandAndBody(
                        profile.drivingCcBand,
                        VehicleBodyType.HATCHBACK,
                        profile.iceFuel
                    ) * 0.42
            }
        }
    }
}

// Calories burned per hour (approximate average person)
object CalorieFactorsPerHour {
    const val IDLE = 60
    const val WALKING = 250
    const val RUNNING = 600
    const val CYCLING = 400
    const val MOTORCYCLE = 280 // Active riding posture; between cycling and seated car
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
            ActivityType.MOTORCYCLE -> MOTORCYCLE
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
            ActivityType.MOTORCYCLE -> Orange500
            ActivityType.TRAIN -> Teal500
            ActivityType.DRIVING -> Amber500
            ActivityType.ELECTRIC_VEHICLE -> Violet500
            ActivityType.FLYING -> Blue500
        }
    }
}

// Standard car baseline used for "CO2 saved vs. driving" calculations
const val BASELINE_DRIVING_CO2_PER_KM = 0.21



