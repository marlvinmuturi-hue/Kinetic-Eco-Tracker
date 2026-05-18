package Kinetic_Eco.Tracker.services

import java.util.Calendar
import Kinetic_Eco.Tracker.data.ActivityType

/**
 * Advanced Calorie Calculation Engine
 * 
 * Uses MET (Metabolic Equivalent of Task) values with user-specific adjustments
 * for accurate calorie burn estimation.
 * 
 * Based on:
 * - Compendium of Physical Activities (Ainsworth et al., 2011)
 * - ACSM Metabolic Calculations
 * - Pandolf Equation for walking/running
 */

/**
 * User's physical attributes used for calorie / BMR calculations.
 *
 * The user-facing input is a **date of birth** (collected via a date picker
 * in onboarding & settings) rather than a raw age in years, because:
 *  - Birth dates don't go stale — your age advances naturally on each
 *    birthday without the user re-entering it.
 *  - It's a single piece of canonical data; "age" can always be derived.
 *
 * [age] is exposed as a derived property so callers like [CalorieEngine]
 * keep working unchanged. When [birthDateMs] is null (user hasn't set a
 * birth date yet, e.g. skipped onboarding) we fall back to
 * [DEFAULT_AGE_FALLBACK] for calculation purposes.
 */
data class UserPhysicalProfile(
    val weight: Double = 70.0,        // kg
    val height: Double = 170.0,       // cm
    /**
     * UTC milliseconds at midnight of the user's date of birth, or null when
     * not provided. Material 3's `DatePickerState.selectedDateMillis` is
     * already in this format, which keeps wiring simple.
     */
    val birthDateMs: Long? = null,
    val gender: Gender = Gender.MALE
) {
    /**
     * Derived whole-year age. Returns [DEFAULT_AGE_FALLBACK] when no birth
     * date has been recorded yet so existing math (BMR, correction factor)
     * still produces a sane number for skip-onboarding users.
     */
    val age: Int
        get() = birthDateMs?.let { ageInYearsFromBirthMs(it, System.currentTimeMillis()) }
            ?: DEFAULT_AGE_FALLBACK

    companion object {
        const val DEFAULT_AGE_FALLBACK = 30
    }
}

/**
 * Whole-year age between [birthMs] (UTC midnight of birth date) and [nowMs]
 * (any timestamp). Uses [Calendar] rather than `java.time` so the helper is
 * safe on every API level the app supports (minSdk 24, no desugaring needed).
 *
 * Caps at zero — a birth date in the future just yields age 0 rather than
 * a negative number, which would otherwise flow into the BMR formula and
 * produce unhelpful values.
 */
fun ageInYearsFromBirthMs(birthMs: Long, nowMs: Long): Int {
    val birth = Calendar.getInstance().apply { timeInMillis = birthMs }
    val today = Calendar.getInstance().apply { timeInMillis = nowMs }
    var years = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
    val notReached =
        today.get(Calendar.MONTH) < birth.get(Calendar.MONTH) ||
        (today.get(Calendar.MONTH) == birth.get(Calendar.MONTH) &&
         today.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH))
    if (notReached) years--
    return years.coerceAtLeast(0)
}

enum class Gender {
    MALE, FEMALE, OTHER
}

object CalorieEngine {
    
    private val DEFAULT_PROFILE = UserPhysicalProfile()
    
    /**
     * Calculate calories burned for a given activity
     * 
     * @param activityType Type of activity
     * @param durationSeconds Duration in seconds
     * @param speedMps Speed in meters per second (null for stationary activities)
     * @param elevationGainMeters Elevation gained in meters
     * @param elevationLossMeters Elevation lost in meters
     * @param userProfile User's physical profile (optional, uses defaults if not provided)
     * @return Calories burned in kcal
     */
    fun calculateCalories(
        activityType: ActivityType,
        durationSeconds: Long,
        speedMps: Double? = null,
        elevationGainMeters: Double = 0.0,
        elevationLossMeters: Double = 0.0,
        userProfile: UserPhysicalProfile? = null
    ): Double {
        val profile = userProfile ?: DEFAULT_PROFILE
        val durationHours = durationSeconds / 3600.0
        
        // Get base MET value
        val met = getMET(activityType, speedMps)
        
        // Calculate correction factor (K) based on gender and age
        val k = getCorrectionFactor(profile.gender, profile.age)
        
        // Base calorie calculation: MET × weight × duration × correction factor
        val baseCalories = met * profile.weight * durationHours * k
        
        // Passengers: altitude changes are not "hiking" — GPS altitude on aircraft is noisy and must not add huge kcal.
        val elevationCalories = if (activityType == ActivityType.FLYING) {
            0.0
        } else {
            calculateElevationBonus(
                elevationGainMeters,
                elevationLossMeters,
                profile.weight
            )
        }
        
        return baseCalories + elevationCalories
    }
    
    /**
     * Get MET value for activity based on type and speed
     */
    private fun getMET(activityType: ActivityType, speedMps: Double?): Double {
        return when (activityType) {
            ActivityType.IDLE -> 1.0
            ActivityType.WALKING -> getWalkingMET(speedMps)
            ActivityType.RUNNING -> getRunningMET(speedMps)
            ActivityType.CYCLING -> getCyclingMET(speedMps)
            ActivityType.MOTORCYCLE -> {
                val c = getCyclingMET(speedMps)
                (c * 0.88).coerceIn(3.8, 11.0)
            }
            ActivityType.TRAIN -> 1.3  // Seated travel
            ActivityType.DRIVING -> 1.3  // Sitting, light stress
            ActivityType.ELECTRIC_VEHICLE -> 1.3  // Same as driving
            ActivityType.FLYING -> 1.3  // Sitting
        }
    }
    
    /**
     * Get MET value for walking based on speed
     * Reference: Compendium of Physical Activities
     */
    private fun getWalkingMET(speedMps: Double?): Double {
        if (speedMps == null) return 3.5  // Default moderate walking
        
        val kmh = speedMps * 3.6
        
        return when {
            kmh < 3.2 -> 2.0   // Slow (2 mph)
            kmh < 4.0 -> 2.8   // 2.5 mph
            kmh < 4.8 -> 3.5   // 3 mph - moderate
            kmh < 5.6 -> 4.3   // 3.5 mph - brisk
            kmh < 6.4 -> 5.0   // 4 mph - very brisk
            else -> 6.3        // 4.5+ mph - very fast walking
        }
    }
    
    /**
     * Get MET value for running based on speed
     * Reference: Compendium of Physical Activities
     */
    private fun getRunningMET(speedMps: Double?): Double {
        if (speedMps == null) return 9.8  // Default moderate running
        
        val kmh = speedMps * 3.6
        
        return when {
            kmh < 6.4 -> 6.0    // Jogging (4 mph)
            kmh < 8.0 -> 8.3    // 5 mph
            kmh < 9.7 -> 9.8    // 6 mph - moderate
            kmh < 11.3 -> 11.0  // 7 mph - fast
            kmh < 12.9 -> 11.5  // 8 mph - very fast
            kmh < 14.5 -> 12.3  // 9 mph
            else -> 12.8        // 10+ mph - sprinting
        }
    }
    
    /**
     * Get MET value for cycling based on speed
     * Reference: Compendium of Physical Activities
     */
    private fun getCyclingMET(speedMps: Double?): Double {
        if (speedMps == null) return 6.8  // Default moderate cycling
        
        val kmh = speedMps * 3.6
        
        return when {
            kmh < 16 -> 4.0   // <10 mph - leisure
            kmh < 19 -> 6.8   // 10-12 mph - moderate
            kmh < 22 -> 8.0   // 12-14 mph
            kmh < 26 -> 10.0  // 14-16 mph - vigorous
            kmh < 31 -> 12.0  // 16-19 mph - fast
            else -> 15.6      // 19+ mph - racing
        }
    }
    
    /**
     * Calculate correction factor based on gender and age
     * 
     * - Females have ~10% lower BMR than males
     * - Metabolism decreases ~1% per year after age 30
     */
    private fun getCorrectionFactor(gender: Gender, age: Int): Double {
        var k = 1.0
        
        // Gender adjustment
        if (gender == Gender.FEMALE) {
            k *= 0.9
        }
        
        // Age adjustment (decline after 30)
        if (age > 30) {
            val ageYearsOver30 = age - 30
            k *= (1.0 - (ageYearsOver30 * 0.01))
        }
        
        return k
    }
    
    /**
     * Calculate additional calories from elevation changes
     * 
     * Physics: Work = mass × gravity × height
     * - Climbing: full energy cost
     * - Descending: ~30% energy cost (eccentric muscle contraction)
     */
    private fun calculateElevationBonus(
        gainMeters: Double,
        lossMeters: Double,
        weightKg: Double
    ): Double {
        val GRAVITY = 9.8  // m/s²
        val DESCENT_FACTOR = 0.3  // Descending costs ~30% of ascending
        val JOULES_TO_KCAL = 0.000239006  // Conversion factor
        
        // Work done climbing (Joules)
        val climbWork = gainMeters * weightKg * GRAVITY
        
        // Work done descending (Joules) - less than climbing
        val descendWork = lossMeters * weightKg * GRAVITY * DESCENT_FACTOR
        
        // Convert to kcal
        val totalKcal = (climbWork + descendWork) * JOULES_TO_KCAL
        
        return totalKcal
    }
    
    /**
     * Estimate BMR (Basal Metabolic Rate) using Harris-Benedict Equation
     * This is the number of calories burned at rest per day
     */
    fun calculateBMR(profile: UserPhysicalProfile): Double {
        // Explicit field access (not destructuring) because `age` is now a
        // derived property on [UserPhysicalProfile] rather than a constructor
        // parameter — destructuring would yield `birthDateMs: Long?` in the
        // third slot instead of `age: Int`.
        val weight = profile.weight
        val height = profile.height
        val age = profile.age
        val gender = profile.gender

        return if (gender == Gender.FEMALE) {
            // BMR (women) = 655 + (9.6 × weight in kg) + (1.8 × height in cm) - (4.7 × age in years)
            655 + (9.6 * weight) + (1.8 * height) - (4.7 * age)
        } else {
            // BMR (men) = 66 + (13.7 × weight in kg) + (5 × height in cm) - (6.8 × age in years)
            66 + (13.7 * weight) + (5 * height) - (6.8 * age)
        }
    }
    
    /**
     * Get calories burned per hour for display purposes
     * (Used for real-time display in UI)
     */
    fun getCaloriesPerHour(
        activityType: ActivityType,
        speedMps: Double? = null,
        userProfile: UserPhysicalProfile? = null
    ): Double {
        return calculateCalories(
            activityType,
            3600, // 1 hour in seconds
            speedMps,
            0.0, // No elevation
            0.0,
            userProfile
        )
    }
}
