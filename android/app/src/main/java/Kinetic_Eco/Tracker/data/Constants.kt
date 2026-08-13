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
            ActivityType.DRIVING -> drivingCo2(profile)
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
                // A motorcycle on electricity is an electric motorcycle, not an
                // e-scooter — it reads its own class rather than TWO_WHEELER.
                PrimaryFuelType.ELECTRIC ->
                    ElectricVehicleClass.MOTORCYCLE.baseCo2KgPerKm * profile.electricMotorPower.multiplier
                PrimaryFuelType.PETROL, PrimaryFuelType.DIESEL,
                PrimaryFuelType.HYBRID, PrimaryFuelType.PLUG_IN_HYBRID ->
                    // Hybrid motorcycles are vanishingly rare; a hybrid profile on two
                    // wheels is treated as its combustion equivalent rather than
                    // inventing a category nobody rides.
                    IceFuel.co2FromCcBandAndBody(
                        profile.drivingCcBand,
                        VehicleBodyType.HATCHBACK,
                        profile.iceFuel
                    ) * 0.42
            }
        }
    }

    /**
     * CO₂ for the DRIVING activity, which is the only mode where the hybrid fuel types
     * change the answer.
     *
     * Petrol and diesel run the existing displacement × body model untouched. A hybrid
     * is that same model scaled down; a plug-in splits its distance between battery and
     * a hybrid-efficiency engine. Electric selected against DRIVING keeps its historical
     * behaviour — the dedicated ELECTRIC_VEHICLE activity is the supported route for EVs.
     */
    private fun drivingCo2(profile: VehicleProfile): Double {
        val petrolEquivalent = IceFuel.co2FromCcBandAndBody(
            ccBand = profile.drivingCcBand,
            bodyType = profile.bodyType,
            fuel = profile.iceFuel
        )
        return when (profile.primaryFuelType) {
            PrimaryFuelType.HYBRID -> petrolEquivalent * PrimaryFuelType.HYBRID_VS_PETROL
            PrimaryFuelType.PLUG_IN_HYBRID -> {
                val share = PrimaryFuelType.PHEV_ELECTRIC_SHARE
                val onBattery = ElectricVehicleClass.CAR.baseCo2KgPerKm *
                    profile.electricMotorPower.multiplier
                val onEngine = petrolEquivalent * PrimaryFuelType.HYBRID_VS_PETROL
                share * onBattery + (1.0 - share) * onEngine
            }
            PrimaryFuelType.PETROL,
            PrimaryFuelType.DIESEL,
            PrimaryFuelType.ELECTRIC -> petrolEquivalent
        }
    }
}

/**
 * Energy the **vehicle** consumes per km, in watt-hours.
 *
 * Deliberately separate from [CalorieFactorsPerHour], which is energy the
 * *person* spends. A 40 km drive burns a few hundred calories of human effort
 * and tens of kWh of fuel; conflating the two would be meaningless.
 *
 * Two properties that differ from [CO2Factors] and matter to callers:
 *  - **Always unsigned.** CO₂ factors go negative to encode "saved versus the
 *    car trip you didn't take"; energy has no such convention. A train ride
 *    consumes energy even though it saves CO₂.
 *  - **Zero for human-powered modes**, rather than a saving. Walking has no
 *    vehicle to consume anything. The calories the walker burns live in
 *    [CalorieFactorsPerHour].
 *
 * Figures are illustrative reference values in the same spirit as [CO2Factors]
 * — good enough to rank modes against each other, not a certified LCA.
 */
object EnergyFactors {

    /** Wh per km consumed by [activity], given the user's [profile]. */
    fun getWhPerKm(activity: ActivityType, profile: VehicleProfile = VehicleProfile.DEFAULT): Double {
        return when (activity) {
            ActivityType.DRIVING -> drivingWhPerKm(profile)
            ActivityType.ELECTRIC_VEHICLE ->
                profile.electricVehicleClass.baseWhPerKm * profile.electricMotorPower.multiplier
            ActivityType.TRAIN -> profile.trainPropulsion.whPerKm
            ActivityType.FLYING -> profile.aircraftCategory.whPerKm
            // Mirrors the MOTORCYCLE branch in CO2Factors.getFactor: electric
            // reads the two-wheeler base, combustion is a hatchback scaled by
            // MOTORCYCLE_VS_CAR. Kept in step with that function by hand.
            ActivityType.MOTORCYCLE -> when (profile.primaryFuelType) {
                PrimaryFuelType.ELECTRIC ->
                    ElectricVehicleClass.MOTORCYCLE.baseWhPerKm * profile.electricMotorPower.multiplier
                PrimaryFuelType.PETROL, PrimaryFuelType.DIESEL,
                PrimaryFuelType.HYBRID, PrimaryFuelType.PLUG_IN_HYBRID ->
                    IceFuel.energyWhFromCcBandAndBody(
                        profile.drivingCcBand,
                        VehicleBodyType.HATCHBACK,
                        profile.iceFuel
                    ) * MOTORCYCLE_VS_CAR
            }
            // Human-powered or stationary — no vehicle energy.
            ActivityType.IDLE,
            ActivityType.WALKING,
            ActivityType.RUNNING,
            ActivityType.CYCLING -> 0.0
        }
    }

    /** Share of an equivalent car's consumption a motorcycle uses. Matches the
     *  0.42 factor applied to the MOTORCYCLE CO₂ branch. */
    private const val MOTORCYCLE_VS_CAR = 0.42

    /**
     * Mirrors `CO2Factors.drivingCo2`. Note the plug-in branch adds battery watt-hours
     * to chemical watt-hours: the same convention [getWhPerKm] already uses across
     * modes, where DRIVING reports fuel energy and ELECTRIC_VEHICLE reports battery
     * draw. Cost never uses this blended figure — [MobilityCostCalculator] prices the
     * two portions separately, because one is bought in litres and the other in kWh.
     */
    private fun drivingWhPerKm(profile: VehicleProfile): Double {
        val petrolEquivalent = IceFuel.energyWhFromCcBandAndBody(
            ccBand = profile.drivingCcBand,
            bodyType = profile.bodyType,
            fuel = profile.iceFuel
        )
        return when (profile.primaryFuelType) {
            PrimaryFuelType.HYBRID -> petrolEquivalent * PrimaryFuelType.HYBRID_VS_PETROL
            PrimaryFuelType.PLUG_IN_HYBRID -> {
                val share = PrimaryFuelType.PHEV_ELECTRIC_SHARE
                val onBattery = ElectricVehicleClass.CAR.baseWhPerKm *
                    profile.electricMotorPower.multiplier
                val onEngine = petrolEquivalent * PrimaryFuelType.HYBRID_VS_PETROL
                share * onBattery + (1.0 - share) * onEngine
            }
            PrimaryFuelType.PETROL,
            PrimaryFuelType.DIESEL,
            PrimaryFuelType.ELECTRIC -> petrolEquivalent
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



