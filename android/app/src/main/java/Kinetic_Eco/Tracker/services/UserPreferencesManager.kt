package Kinetic_Eco.Tracker.services

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import Kinetic_Eco.Tracker.data.AircraftCategory
import Kinetic_Eco.Tracker.data.DrivingEngineCcBand
import Kinetic_Eco.Tracker.data.ElectricMotorPowerBand
import Kinetic_Eco.Tracker.data.ElectricVehicleClass
import Kinetic_Eco.Tracker.data.IceFuel
import Kinetic_Eco.Tracker.data.PrimaryFuelType
import Kinetic_Eco.Tracker.data.TrainPropulsion
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.data.VehicleBodyType
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance

/**
 * Manages user preferences and physical profile data
 */
class UserPreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "kinetic_user_prefs"
        
        // Physical Profile Keys
        private const val KEY_WEIGHT = "physical_profile_weight"
        private const val KEY_HEIGHT = "physical_profile_height"
        /**
         * UTC milliseconds at midnight of the user's date of birth. Canonical
         * since the v2 profile rework — replaces [KEY_AGE] which went stale
         * every birthday. Loaded back into [UserPhysicalProfile.birthDateMs].
         */
        private const val KEY_BIRTH_DATE_MS = "physical_profile_birth_date_ms"
        /**
         * Legacy raw age in years. Still read on first load by
         * [loadPhysicalProfile] for the one-time migration to [KEY_BIRTH_DATE_MS],
         * then deleted on the next save. Do not write to it from new code.
         */
        private const val KEY_AGE = "physical_profile_age"
        private const val KEY_GENDER = "physical_profile_gender"

        private const val KEY_VEHICLE_DRIVING_CC = "vehicle_profile_driving_cc"
        private const val KEY_VEHICLE_ICE_FUEL = "vehicle_profile_ice_fuel"
        /** Legacy (pre-fuel-type-redesign): EV class was Car or Motorcycle.
         *  Read on first load and migrated to [KEY_VEHICLE_EV_CLASS]. */
        private const val KEY_VEHICLE_EV_ROAD = "vehicle_profile_ev_road"
        private const val KEY_VEHICLE_TRAIN = "vehicle_profile_train"
        private const val KEY_VEHICLE_AIRCRAFT = "vehicle_profile_aircraft"
        /** Top-level "your vehicle is petrol/diesel/electric" choice. The
         *  source of truth for which sub-fields the UX surfaces. */
        private const val KEY_VEHICLE_PRIMARY_FUEL = "vehicle_profile_primary_fuel"
        /** Body type for combustion vehicles (sedan reference, SUV heavier, …). */
        private const val KEY_VEHICLE_BODY_TYPE = "vehicle_profile_body_type"
        /** Electric vehicle class (2-wheeler / 3-wheeler / car). Replaces
         *  [KEY_VEHICLE_EV_ROAD]; legacy values are migrated on load. */
        private const val KEY_VEHICLE_EV_CLASS = "vehicle_profile_ev_class"
        /** Electric motor power band — multiplies the EV class baseline. */
        private const val KEY_VEHICLE_EV_MOTOR_POWER = "vehicle_profile_ev_motor_power"
        
        // Unit preference (Metric = km, kcal | Imperial = miles, Wh)
        private const val KEY_UNIT_PREFERENCE = "unit_preference"
        
        // Distance milestone notifications (km or miles based on unit)
        private const val KEY_DISTANCE_ALERTS_ENABLED = "distance_alerts_enabled"
        private const val KEY_KM_NOTIFICATIONS_ENABLED = "km_notifications_enabled"  // Legacy, migrated to KEY_DISTANCE_ALERTS_ENABLED
        
        // Auto-start tracking when walking detected
        private const val KEY_AUTO_START_ON_WALK_ENABLED = "auto_start_on_walk_enabled"

        /** True after the user has completed the one-time Auto Detect first-selection setup. */
        private const val KEY_AUTODETECT_SETUP_DONE = "autodetect_setup_done"

        /** One-shot: idle timeout auto-saved; allow movement to restart without toggling "auto-start on walk". */
        private const val KEY_PENDING_RESUME_AFTER_IDLE_AUTO_STOP = "pending_resume_after_idle_auto_stop"

        /** Epoch-ms of the last MANUAL stop/discard. Used to suppress auto-restart for 30 s. */
        private const val KEY_MANUAL_STOP_MS = "manual_stop_ms"

        // Auto-stop on idle: minutes of inactivity before stopping (3, 5, or 10)
        private const val KEY_IDLE_STOP_MINUTES = "idle_stop_minutes"
        private const val DEFAULT_IDLE_STOP_MINUTES = 3

        // Notification sounds (milestones, tracking start/stop, session summary)
        private const val KEY_NOTIFICATION_SOUNDS_ENABLED = "notification_sounds_enabled"

        // App language: "auto" = follow device locale, or "en", "fr", "de", "es", "zh"
        private const val KEY_APP_LOCALE = "app_locale"

        // Theme: "light" or "dark". The historical "system" value is migrated
        // away by [migrateThemeIfNeeded] on first launch after the theme rework.
        private const val KEY_THEME_MODE = "theme_mode"

        // Legacy accent customization key. The accent picker has been removed
        // and the value is no longer read; we still know about the key so the
        // migration can clear it and free the slot. Do not re-introduce.
        private const val KEY_THEME_ACCENT_ARGB = "theme_accent_argb"

        /**
         * Tracks the on-device migration state for the theme system. v1 is the
         * original 3-mode + accent-picker world; v2 is the simplified Light/Dark
         * world introduced alongside this constant. See [migrateThemeIfNeeded].
         */
        private const val KEY_THEME_FLOW_VERSION = "theme_flow_version"
        private const val CURRENT_THEME_FLOW_VERSION = 2

        /** Accepted bundled terms version; bump when replacing assets/legal PDF to require re-acceptance. */
        private const val KEY_TERMS_ACCEPTED_VERSION = "terms_accepted_version"

        // Floating play button position (0-1 fraction of screen width/height)
        private const val KEY_FLOATING_BUTTON_X = "floating_button_x"
        private const val KEY_FLOATING_BUTTON_Y = "floating_button_y"
        private const val DEFAULT_FLOATING_BUTTON_X = 0.85f
        private const val DEFAULT_FLOATING_BUTTON_Y = 0.75f
        
        // Default values
        private const val DEFAULT_WEIGHT = 70.0
        private const val DEFAULT_HEIGHT = 170.0
        private const val DEFAULT_AGE = 30
        private const val DEFAULT_GENDER = "MALE"
        private const val DEFAULT_DISTANCE_ALERTS = false
        private const val DEFAULT_AUTO_START_ON_WALK = true
        private const val DEFAULT_NOTIFICATION_SOUNDS = true
        
        private const val METERS_PER_MILE = 1609.344

        /** Must match the document in assets/legal/user_terms_and_conditions.pdf when you update it. */
        const val CURRENT_TERMS_DOCUMENT_VERSION = "2"

        // Weekly digest notification opt-in (default: true)
        private const val KEY_WEEKLY_DIGEST_ENABLED = "weekly_digest_enabled"
        private const val KEY_DAILY_DIGEST_ENABLED  = "daily_digest_enabled"

        /**
         * Personal weekly CO₂-saved goal in kilograms. Drives the dashboard's
         * sprouting-plant scene and the progress ring around the hero number.
         * Stored as a float because users typically pick whole/half kg values
         * in onboarding (the slider snaps to 0.5 kg increments).
         */
        private const val KEY_VEHICLE_KM_PER_L = "vehicle_km_per_litre"
        private const val KEY_COUNTRY_OVERRIDE = "price_country_override"
        private const val KEY_PRICE_CURRENCY = "price_currency_code"
        private const val KEY_MONTHLY_STATEMENT_ENABLED = "monthly_statement_enabled"
        private const val KEY_MEASURED_ECONOMY_CELEBRATED = "measured_economy_celebrated"
        private const val KEY_AD_LAST_SHOWN_MS = "ad_interstitial_last_shown_ms"
        private const val KEY_AD_DAY = "ad_interstitial_day"
        private const val KEY_AD_DAY_COUNT = "ad_interstitial_day_count"
        private const val KEY_WEEKLY_CO2_GOAL_KG = "weekly_co2_goal_kg"
        private const val DEFAULT_WEEKLY_CO2_GOAL_KG = 5.0f
        /** Hard caps so a stray value can't break the ring/plant maths. */
        private const val MIN_WEEKLY_CO2_GOAL_KG = 0.5f
        private const val MAX_WEEKLY_CO2_GOAL_KG = 50.0f

        /** Kinetic Eco Tracker first-run onboarding completed flag. */
        private const val KEY_ONBOARDING_DONE = "kinetic_onboarding_done"

        /** True after the user has tapped "Get started" on the welcome splash. */
        private const val KEY_WELCOME_SEEN = "kinetic_welcome_seen"

        /**
         * True once the user has discovered the dashboard's "tap-to-cycle"
         * gesture on the hero card's CO₂ equivalency line — either by tapping
         * it or by silently outliving the onboarding hint visibility window.
         * Used to render a one-time pulse + caption ("Tap to switch comparison")
         * that disappears for good after the first tap.
         */
        private const val KEY_HERO_EQUIVALENCY_HINT_SEEN = "kinetic_hero_equivalency_hint_seen"

        /**
         * Version of the onboarding flow this device has completed. Bumping
         * [CURRENT_ONBOARDING_FLOW_VERSION] forces a one-time re-run of the
         * onboarding flow for existing users on next launch (see
         * [migrateOnboardingFlowIfNeeded]).
         *
         * v1 = original 3-step flow (Welcome+features / Permissions / Profile)
         * v2 = split flow with pre-login Welcome, post-login Description, and
         *      profile-photo capture in Profile setup
         */
        private const val KEY_ONBOARDING_FLOW_VERSION = "kinetic_onboarding_flow_version"
        private const val CURRENT_ONBOARDING_FLOW_VERSION = 2

        /** Display name cache — avoids email-prefix flicker on cold launch while Firestore loads. */
        private const val KEY_CACHED_DISPLAY_NAME = "cached_display_name"

        /** Set after the user manually dismisses the "start your first trip" dashboard hint. */
        private const val KEY_FIRST_TRIP_PROMPT_DISMISSED = "kinetic_first_trip_prompt_dismissed"

        /**
         * Per-badge high-water-mark of the tier the user has already been congratulated for
         * (stored as [Kinetic_Eco.Tracker.data.BadgeTier.ordinal]; -1 = never celebrated). Suffixed
         * with the badge id (e.g. "badge_celebrated_tier_co2"). The achievement pop-up fires only
         * when a freshly-earned tier exceeds this mark, so a user is celebrated once per new personal
         * best rather than every week they re-earn the same tier.
         */
        private const val KEY_BADGE_CELEBRATED_TIER_PREFIX = "badge_celebrated_tier_"

        /**
         * True once we've recorded the user's already-earned badges as the celebration baseline. Set
         * on first run of the badge-celebration feature so existing achievements are not all popped
         * up at once — only tiers earned *after* baselining trigger the pop-up.
         */
        private const val KEY_BADGES_CELEBRATION_BASELINE_DONE = "kinetic_badges_celebration_baseline_done"
    }
    
    /**
     * Save user's physical profile.
     *
     * Persists [UserPhysicalProfile.birthDateMs] under [KEY_BIRTH_DATE_MS] when
     * present, and explicitly clears any legacy [KEY_AGE] entry so the
     * one-time migration in [loadPhysicalProfile] doesn't keep firing on
     * subsequent loads.
     */
    fun savePhysicalProfile(profile: UserPhysicalProfile) {
        val editor = prefs.edit()
            .putFloat(KEY_WEIGHT, profile.weight.toFloat())
            .putFloat(KEY_HEIGHT, profile.height.toFloat())
            .putString(KEY_GENDER, profile.gender.name)
            // Drop the legacy age entry on every save — once we own a birth
            // date the raw int is dead weight.
            .remove(KEY_AGE)

        if (profile.birthDateMs != null) {
            editor.putLong(KEY_BIRTH_DATE_MS, profile.birthDateMs)
        } else {
            editor.remove(KEY_BIRTH_DATE_MS)
        }

        val ok = editor.commit()
        if (!ok) {
            android.util.Log.e("UserPrefsManager", "Physical profile commit() failed")
        }
        android.util.Log.d("UserPrefsManager", "Physical profile saved (committed=$ok): $profile")
    }

    /**
     * Load user's physical profile.
     *
     * **Migration:** the v1 schema stored a raw integer age under [KEY_AGE].
     * For pre-v2 users we synthesise an approximate birth date — July 1 of
     * `currentYear − savedAge` — and surface it via [UserPhysicalProfile.birthDateMs].
     * Mid-year is the lowest-error fallback when we don't know the actual
     * month/day; the user can always refine it from Settings. The legacy
     * key is wiped on the next [savePhysicalProfile] call.
     *
     * Returns the saved profile, or sensible defaults when nothing is stored.
     */
    fun loadPhysicalProfile(): UserPhysicalProfile {
        val weight = prefs.getFloat(KEY_WEIGHT, DEFAULT_WEIGHT.toFloat()).toDouble()
        val height = prefs.getFloat(KEY_HEIGHT, DEFAULT_HEIGHT.toFloat()).toDouble()
        val genderString = prefs.getString(KEY_GENDER, DEFAULT_GENDER) ?: DEFAULT_GENDER
        val gender = try {
            Gender.valueOf(genderString)
        } catch (e: Exception) {
            Gender.MALE
        }

        val birthDateMs: Long? = when {
            prefs.contains(KEY_BIRTH_DATE_MS) -> prefs.getLong(KEY_BIRTH_DATE_MS, 0L).takeIf { it > 0L }
            prefs.contains(KEY_AGE) -> {
                val legacyAge = prefs.getInt(KEY_AGE, DEFAULT_AGE)
                approximateBirthDateMsFromAge(legacyAge)
            }
            else -> null
        }

        val profile = UserPhysicalProfile(
            weight = weight,
            height = height,
            birthDateMs = birthDateMs,
            gender = gender
        )
        android.util.Log.d("UserPrefsManager", "Physical profile loaded: $profile")
        return profile
    }

    /**
     * Estimate a birth date from a saved whole-year age. Uses July 1 (UTC) of
     * `currentYear − age` so the resulting derived age round-trips back to
     * the same value regardless of when in the year the migration runs —
     * a January load of an age-30 record yields a birth date that still
     * derives to 30 in July.
     */
    private fun approximateBirthDateMsFromAge(age: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            val currentYear = get(java.util.Calendar.YEAR)
            clear()
            set(currentYear - age.coerceAtLeast(0), java.util.Calendar.JULY, 1, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
    
    /**
     * Check if user has set their physical profile
     */
    fun hasPhysicalProfile(): Boolean {
        return prefs.contains(KEY_WEIGHT)
    }

    fun saveVehicleProfile(profile: VehicleProfile) {
        prefs.edit()
            .putString(KEY_VEHICLE_PRIMARY_FUEL, profile.primaryFuelType.name)
            .putString(KEY_VEHICLE_ICE_FUEL, profile.iceFuel.name)
            .putString(KEY_VEHICLE_DRIVING_CC, profile.drivingCcBand.name)
            .putString(KEY_VEHICLE_BODY_TYPE, profile.bodyType.name)
            .putString(KEY_VEHICLE_EV_CLASS, profile.electricVehicleClass.name)
            .putString(KEY_VEHICLE_EV_MOTOR_POWER, profile.electricMotorPower.name)
            .putString(KEY_VEHICLE_TRAIN, profile.trainPropulsion.name)
            .putString(KEY_VEHICLE_AIRCRAFT, profile.aircraftCategory.name)
            .apply {
                // Absent, not zero: zero km/L would mean infinite consumption.
                val economy = profile.fuelEconomyKmPerL
                if (economy != null && economy.isFinite() && economy > 0.0) {
                    putFloat(KEY_VEHICLE_KM_PER_L, economy.toFloat())
                } else {
                    remove(KEY_VEHICLE_KM_PER_L)
                }
            }
            // Drop the legacy EV-class key on save so old installs don't keep
            // a stale shadow value alongside the new one.
            .remove(KEY_VEHICLE_EV_ROAD)
            .apply()
    }

    fun loadVehicleProfile(): VehicleProfile {
        val iceFuel = IceFuel.fromStoredName(prefs.getString(KEY_VEHICLE_ICE_FUEL, null))

        // Primary fuel type was added in v2 of the vehicle profile. If it's
        // missing, infer it from the legacy iceFuel — every legacy profile
        // was either Petrol or Diesel (Electric was a separate per-activity
        // setting), so this preserves intent for users upgrading the app.
        val primaryFuelType = if (prefs.contains(KEY_VEHICLE_PRIMARY_FUEL)) {
            PrimaryFuelType.fromStoredName(prefs.getString(KEY_VEHICLE_PRIMARY_FUEL, null))
        } else {
            PrimaryFuelType.fromLegacyIceFuel(iceFuel)
        }

        // Body type was added in v2; sedan (1.0× multiplier) is the safe
        // default that leaves combustion factors visually unchanged for
        // existing users until they pick something different.
        val bodyType = VehicleBodyType.fromStoredName(prefs.getString(KEY_VEHICLE_BODY_TYPE, null))

        // EV class is also v2. If the user had the old Car/Motorcycle key set,
        // map Motorcycle → 2-wheeler and Car → Car. Brand-new installs land
        // on the default (Car).
        val electricVehicleClass = if (prefs.contains(KEY_VEHICLE_EV_CLASS)) {
            ElectricVehicleClass.fromStoredName(prefs.getString(KEY_VEHICLE_EV_CLASS, null))
        } else {
            ElectricVehicleClass.fromLegacyElectricRoadVehicle(prefs.getString(KEY_VEHICLE_EV_ROAD, null))
        }

        val electricMotorPower = ElectricMotorPowerBand.fromStoredName(
            prefs.getString(KEY_VEHICLE_EV_MOTOR_POWER, null)
        )

        return VehicleProfile(
            primaryFuelType = primaryFuelType,
            iceFuel = iceFuel,
            drivingCcBand = DrivingEngineCcBand.fromStoredName(prefs.getString(KEY_VEHICLE_DRIVING_CC, null)),
            bodyType = bodyType,
            electricVehicleClass = electricVehicleClass,
            electricMotorPower = electricMotorPower,
            trainPropulsion = TrainPropulsion.fromStoredName(prefs.getString(KEY_VEHICLE_TRAIN, null)),
            aircraftCategory = AircraftCategory.fromStoredName(prefs.getString(KEY_VEHICLE_AIRCRAFT, null)),
            fuelEconomyKmPerL = prefs.getFloat(KEY_VEHICLE_KM_PER_L, 0f)
                .toDouble()
                .takeIf { it > 0.0 }
        )
    }
    
    /**
     * Get default unit system from device locale (US -> Imperial, others -> Metric)
     */
    fun getDefaultUnitFromLocale(): UnitSystem {
        val country = Locale.getDefault().country.uppercase(Locale.US)
        return if (country == "US" || country == "LR" || country == "MM") {
            UnitSystem.IMPERIAL
        } else {
            UnitSystem.METRIC
        }
    }
    
    /**
     * Save unit preference (Metric = km, kcal | Imperial = miles, Wh)
     */
    fun setUnitPreference(unit: UnitSystem) {
        prefs.edit().putString(KEY_UNIT_PREFERENCE, unit.name).apply()
        android.util.Log.d("UserPrefsManager", "Unit preference saved: $unit")
    }
    
    /**
     * Load unit preference. Uses locale-based default on first run.
     */
    fun getUnitPreference(): UnitSystem {
        val stored = prefs.getString(KEY_UNIT_PREFERENCE, null)
        return if (stored != null) {
            try {
                UnitSystem.valueOf(stored)
            } catch (e: Exception) {
                getDefaultUnitFromLocale()
            }
        } else {
            getDefaultUnitFromLocale()
        }
    }
    
    /**
     * Enable or disable distance milestone notifications (km in metric, miles in imperial)
     */
    fun setDistanceAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISTANCE_ALERTS_ENABLED, enabled).apply()
        android.util.Log.d("UserPrefsManager", "Distance alerts enabled: $enabled")
    }
    
    /**
     * Check if distance milestone notifications are enabled
     */
    fun getDistanceAlertsEnabled(): Boolean {
        if (prefs.contains(KEY_DISTANCE_ALERTS_ENABLED)) {
            return prefs.getBoolean(KEY_DISTANCE_ALERTS_ENABLED, DEFAULT_DISTANCE_ALERTS)
        }
        // Migrate from legacy key
        if (prefs.contains(KEY_KM_NOTIFICATIONS_ENABLED)) {
            val value = prefs.getBoolean(KEY_KM_NOTIFICATIONS_ENABLED, DEFAULT_DISTANCE_ALERTS)
            prefs.edit().putBoolean(KEY_DISTANCE_ALERTS_ENABLED, value).apply()
            return value
        }
        return DEFAULT_DISTANCE_ALERTS
    }
    
    /**
     * Meters per distance unit (1000 for km, 1609.344 for mile)
     */
    fun getMetersPerUnit(): Double {
        return if (getUnitPreference().usesMetricDistance()) 1000.0 else METERS_PER_MILE
    }
    
    /**
     * Distance unit label ("km" or "mi")
     */
    fun getDistanceUnitLabel(): String {
        return if (getUnitPreference().usesMetricDistance()) "km" else "mi"
    }
    
    /**
     * Enable or disable auto-start tracking when walking is detected (Activity Recognition)
     */
    fun setAutoStartOnWalkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_ON_WALK_ENABLED, enabled).apply()
        android.util.Log.d("UserPrefsManager", "Auto-start on walk enabled: $enabled")
    }
    
    /**
     * Check if auto-start on walk is enabled
     */
    fun getAutoStartOnWalkEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_START_ON_WALK_ENABLED, DEFAULT_AUTO_START_ON_WALK)
    }

    fun isAutodetectSetupDone(): Boolean = prefs.getBoolean(KEY_AUTODETECT_SETUP_DONE, false)
    fun setAutodetectSetupDone() = prefs.edit().putBoolean(KEY_AUTODETECT_SETUP_DONE, true).apply()

    /**
     * Set by tracking idle auto-stop; cleared when a new session starts. While true, [AutoStartMonitorService]
     * and in-app step auto-start may resume tracking when movement is detected.
     */
    fun setPendingResumeAfterIdleAutoStop(pending: Boolean) {
        prefs.edit().putBoolean(KEY_PENDING_RESUME_AFTER_IDLE_AUTO_STOP, pending).apply()
    }

    fun getPendingResumeAfterIdleAutoStop(): Boolean {
        return prefs.getBoolean(KEY_PENDING_RESUME_AFTER_IDLE_AUTO_STOP, false)
    }

    fun setManualStopMs(ms: Long) {
        prefs.edit().putLong(KEY_MANUAL_STOP_MS, ms).apply()
    }

    fun getManualStopMs(): Long = prefs.getLong(KEY_MANUAL_STOP_MS, 0L)

    /**
     * Set idle stop timeout (minutes of inactivity before auto-stop). Valid: 3, 5, 10.
     */
    fun setIdleStopMinutes(minutes: Int) {
        val valid = when (minutes) {
            3, 5, 10 -> minutes
            else -> DEFAULT_IDLE_STOP_MINUTES
        }
        prefs.edit().putInt(KEY_IDLE_STOP_MINUTES, valid).apply()
    }

    /**
     * Get idle stop timeout in minutes. Default 10.
     */
    fun getIdleStopMinutes(): Int {
        val stored = prefs.getInt(KEY_IDLE_STOP_MINUTES, DEFAULT_IDLE_STOP_MINUTES)
        return if (stored in listOf(3, 5, 10)) stored else DEFAULT_IDLE_STOP_MINUTES
    }

    /**
     * Enable or disable notification sounds (milestones, tracking start/stop, session summary).
     * When disabled, respects Do Not Disturb. When enabled, uses system default sound.
     */
    fun setNotificationSoundsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUNDS_ENABLED, enabled).apply()
        android.util.Log.d("UserPrefsManager", "Notification sounds enabled: $enabled")
    }

    /**
     * Check if notification sounds are enabled
     */
    fun getNotificationSoundsEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_SOUNDS_ENABLED, DEFAULT_NOTIFICATION_SOUNDS)
    }

    /**
     * Save app language preference. Use "auto" to follow device locale.
     * Supported: "auto", "en", "fr", "de", "es", "zh"
     */
    fun setLocalePreference(localeTag: String) {
        prefs.edit().putString(KEY_APP_LOCALE, localeTag).apply()
    }

    /**
     * Load app language preference. Returns "auto" if not set (follow device).
     */
    fun getLocalePreference(): String {
        return prefs.getString(KEY_APP_LOCALE, "auto") ?: "auto"
    }

    /**
     * Save theme preference. Only `"light"` or `"dark"` are accepted now —
     * any other value is normalised to `"dark"` to match the migration that
     * runs on launch.
     */
    fun setThemeMode(mode: String) {
        val normalised = if (mode == "light") "light" else "dark"
        prefs.edit().putString(KEY_THEME_MODE, normalised).apply()
    }

    /**
     * Load theme preference. Returns `"dark"` by default. Existing devices
     * with a stored `"system"` value will have already been migrated to a
     * concrete `"light"` / `"dark"` by [migrateThemeIfNeeded].
     */
    fun getThemeMode(): String {
        return when (prefs.getString(KEY_THEME_MODE, "dark")) {
            "light" -> "light"
            else -> "dark"
        }
    }

    /**
     * One-time migration from the old 3-mode + custom-accent world to the new
     * Light/Dark-only world. Pass the device's *current* dark-mode state
     * (resolved via `Configuration.UI_MODE_NIGHT_MASK` in `MainActivity`) so
     * users previously on `"system"` get pinned to whatever System resolves to
     * right now — exactly matching the visual experience they had a moment
     * before opening the app.
     *
     * Safe to call on every launch; short-circuits when already migrated.
     */
    fun migrateThemeIfNeeded(systemIsDark: Boolean) {
        val storedVersion = prefs.getInt(KEY_THEME_FLOW_VERSION, 0)
        if (storedVersion >= CURRENT_THEME_FLOW_VERSION) return

        val priorMode = prefs.getString(KEY_THEME_MODE, null)
        val resolvedMode = when (priorMode) {
            "light" -> "light"
            "dark" -> "dark"
            // Either explicitly "system" or unset (fresh install): snapshot to
            // whatever the device is on right now. Fresh installs land here too,
            // so the first launch already feels native to the user's device.
            else -> if (systemIsDark) "dark" else "light"
        }

        prefs.edit()
            .putString(KEY_THEME_MODE, resolvedMode)
            .remove(KEY_THEME_ACCENT_ARGB)
            .putInt(KEY_THEME_FLOW_VERSION, CURRENT_THEME_FLOW_VERSION)
            .apply()
    }

    /** Save floating play button position (0-1 fraction of container). */
    fun setFloatingButtonPosition(x: Float, y: Float) {
        prefs.edit()
            .putFloat(KEY_FLOATING_BUTTON_X, x.coerceIn(0f, 1f))
            .putFloat(KEY_FLOATING_BUTTON_Y, y.coerceIn(0f, 1f))
            .apply()
    }

    /** Load floating play button position. Returns (x, y) as 0-1 fractions. */
    fun getFloatingButtonPosition(): Pair<Float, Float> {
        return Pair(
            prefs.getFloat(KEY_FLOATING_BUTTON_X, DEFAULT_FLOATING_BUTTON_X),
            prefs.getFloat(KEY_FLOATING_BUTTON_Y, DEFAULT_FLOATING_BUTTON_Y)
        )
    }

    /** True if the user accepted the current in-app terms & privacy document version. */
    fun hasAcceptedCurrentTerms(): Boolean {
        return prefs.getString(KEY_TERMS_ACCEPTED_VERSION, null) == CURRENT_TERMS_DOCUMENT_VERSION
    }

    /** Persist acceptance of the bundled terms (call after checkbox + Continue). */
    fun setTermsAccepted() {
        prefs.edit()
            .putString(KEY_TERMS_ACCEPTED_VERSION, CURRENT_TERMS_DOCUMENT_VERSION)
            .commit()
    }
    
    // ── Kinetic onboarding ────────────────────────────────────────────────────

    fun isOnboardingDone(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** Resets the onboarding-done flag so the user is sent through onboarding again. */
    fun clearOnboardingDone() {
        prefs.edit().remove(KEY_ONBOARDING_DONE).apply()
    }

    /** True after the user taps "Get started" on the pre-login welcome splash. */
    fun hasSeenWelcome(): Boolean =
        prefs.getBoolean(KEY_WELCOME_SEEN, false)

    fun setWelcomeSeen() {
        prefs.edit().putBoolean(KEY_WELCOME_SEEN, true).apply()
    }

    /**
     * True once the dashboard hero's "Tap to switch comparison" hint has been
     * dismissed. The hint is rendered as a subtle pulse on the refresh icon
     * plus a caption under the equivalency line; tapping the line marks it
     * seen so it never reappears.
     */
    fun hasSeenHeroEquivalencyHint(): Boolean =
        prefs.getBoolean(KEY_HERO_EQUIVALENCY_HINT_SEEN, false)

    fun setHeroEquivalencyHintSeen() {
        prefs.edit().putBoolean(KEY_HERO_EQUIVALENCY_HINT_SEEN, true).apply()
    }

    /** Tier ordinal already celebrated for [badgeId] (-1 = never). See [KEY_BADGE_CELEBRATED_TIER_PREFIX]. */
    fun getCelebratedBadgeTier(badgeId: String): Int =
        prefs.getInt(KEY_BADGE_CELEBRATED_TIER_PREFIX + badgeId, -1)

    fun setCelebratedBadgeTier(badgeId: String, tierOrdinal: Int) {
        prefs.edit().putInt(KEY_BADGE_CELEBRATED_TIER_PREFIX + badgeId, tierOrdinal).apply()
    }

    fun isBadgeCelebrationBaselineDone(): Boolean =
        prefs.getBoolean(KEY_BADGES_CELEBRATION_BASELINE_DONE, false)

    fun setBadgeCelebrationBaselineDone() {
        prefs.edit().putBoolean(KEY_BADGES_CELEBRATION_BASELINE_DONE, true).apply()
    }

    fun hasFirstTripPromptBeenDismissed(): Boolean =
        prefs.getBoolean(KEY_FIRST_TRIP_PROMPT_DISMISSED, false)

    fun setFirstTripPromptDismissed() {
        prefs.edit().putBoolean(KEY_FIRST_TRIP_PROMPT_DISMISSED, true).apply()
    }

    /**
     * One-time migration: when the onboarding flow shape changes (a new
     * version constant), reset the per-device flags so existing users walk
     * through the new flow once. Call this once during MainActivity#onCreate.
     */
    fun migrateOnboardingFlowIfNeeded() {
        val storedVersion = prefs.getInt(KEY_ONBOARDING_FLOW_VERSION, 0)
        if (storedVersion >= CURRENT_ONBOARDING_FLOW_VERSION) return
        prefs.edit()
            .remove(KEY_ONBOARDING_DONE)
            .remove(KEY_WELCOME_SEEN)
            .putInt(KEY_ONBOARDING_FLOW_VERSION, CURRENT_ONBOARDING_FLOW_VERSION)
            .apply()
    }

    // ── Weekly digest FCM preferences ─────────────────────────────────────────

    fun isWeeklyDigestEnabled(): Boolean =
        prefs.getBoolean(KEY_WEEKLY_DIGEST_ENABLED, true)

    fun setWeeklyDigestEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEEKLY_DIGEST_ENABLED, enabled).apply()
    }

    fun isDailyDigestEnabled(): Boolean =
        prefs.getBoolean(KEY_DAILY_DIGEST_ENABLED, true)

    fun setDailyDigestEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DAILY_DIGEST_ENABLED, enabled).apply()
    }

    // ── Personal weekly CO₂-saved goal ────────────────────────────────────────

    /**
     * Returns the user's weekly CO₂-saved goal in kilograms, clamped to the
     * supported range. Defaults to 5 kg/week — the value used as the visual
     * "fully grown" reference for the sprouting plant when no goal has been
     * set yet, so behaviour is identical for first-launch users.
     */
    /**
     * Monthly statement push. Defaults on: it is the main thing a subscriber is paying
     * for, and one notification a month is not an imposition.
     */
    fun isMonthlyStatementEnabled(): Boolean =
        prefs.getBoolean(KEY_MONTHLY_STATEMENT_ENABLED, true)

    fun setMonthlyStatementEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONTHLY_STATEMENT_ENABLED, enabled).apply()
    }

    // ── Energy price overrides ────────────────────────────────────────────────

    /** Which unit price the user is overriding. */
    enum class FuelKind(internal val key: String) {
        PETROL("price_petrol_per_litre"),
        DIESEL("price_diesel_per_litre"),
        ELECTRICITY("price_electricity_per_kwh")
    }

    /**
     * The user's own price for [kind], or null to follow the published table.
     *
     * Null and "0" are different states: null means "use EPRA / the seed", whereas a
     * typed 0 would mean "fuel is free" and silently zero out every cost in the app.
     * Non-positive values are therefore rejected on write rather than stored.
     */
    fun getFuelPriceOverride(kind: FuelKind): Double? {
        if (!prefs.contains(kind.key)) return null
        val v = prefs.getFloat(kind.key, 0f).toDouble()
        return v.takeIf { it > 0.0 }
    }

    /**
     * Country the user picked for pricing, or null to follow detection.
     *
     * An explicit choice always wins: someone who has emigrated, uses a foreign SIM, or
     * simply knows better should not have to argue with the phone about where they buy
     * fuel.
     */
    fun getCountryOverride(): String? =
        prefs.getString(KEY_COUNTRY_OVERRIDE, null)?.takeIf { it.length == 2 }

    fun setCountryOverride(code: String?) {
        val editor = prefs.edit()
        if (code.isNullOrBlank()) editor.remove(KEY_COUNTRY_OVERRIDE) else editor.putString(KEY_COUNTRY_OVERRIDE, code.uppercase(java.util.Locale.US))
        editor.apply()
    }

    /**
     * Currency the user's typed prices are in.
     *
     * Stored separately from the amounts because it applies to all of them, and
     * because a price without its unit is worse than no price — overriding the number
     * while inheriting a foreign currency code is how "KES 1.75" happens.
     */
    fun getPriceCurrencyOverride(): String? =
        prefs.getString(KEY_PRICE_CURRENCY, null)?.takeIf { it.isNotBlank() }

    fun setPriceCurrencyOverride(code: String?) {
        val editor = prefs.edit()
        if (code.isNullOrBlank()) editor.remove(KEY_PRICE_CURRENCY) else editor.putString(KEY_PRICE_CURRENCY, code)
        editor.apply()
    }

    // ── Interstitial ad frequency ────────────────────────────────────────────
    //
    // On disk rather than in memory. The manager's own counters live in a process
    // singleton, so they reset every time Android kills the app — which for a
    // location-tracking app that spends its life in the background is often. That
    // turned a "3 minutes between ads" rule into "3 minutes, unless we got killed",
    // and a user doing short errands could see one after every single trip.

    /** Wall-clock ms of the last interstitial actually shown. 0 when never. */
    fun interstitialLastShownMs(): Long = prefs.getLong(KEY_AD_LAST_SHOWN_MS, 0L)

    /** How many interstitials have been shown since local midnight. */
    fun interstitialsShownToday(): Int =
        if (prefs.getString(KEY_AD_DAY, null) == todayKey()) {
            prefs.getInt(KEY_AD_DAY_COUNT, 0)
        } else {
            0
        }

    /** Record a show, rolling the daily counter over at local midnight. */
    fun recordInterstitialShown() {
        val today = todayKey()
        val count = if (prefs.getString(KEY_AD_DAY, null) == today) {
            prefs.getInt(KEY_AD_DAY_COUNT, 0)
        } else {
            0
        }
        prefs.edit()
            .putLong(KEY_AD_LAST_SHOWN_MS, System.currentTimeMillis())
            .putString(KEY_AD_DAY, today)
            .putInt(KEY_AD_DAY_COUNT, count + 1)
            .apply()
    }

    /** Local calendar day, so the cap resets at the user's midnight rather than UTC. */
    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Whether the "your car's real economy is now measured" notification has fired.
     *
     * One-shot for the life of the install. It marks a threshold being crossed, not a
     * state that persists — re-firing it every time the figure is recalculated would
     * turn a genuine milestone into a nag about fill-ups.
     */
    fun hasCelebratedMeasuredEconomy(): Boolean =
        prefs.getBoolean(KEY_MEASURED_ECONOMY_CELEBRATED, false)

    fun setCelebratedMeasuredEconomy() {
        prefs.edit().putBoolean(KEY_MEASURED_ECONOMY_CELEBRATED, true).apply()
    }

    fun setFuelPriceOverride(kind: FuelKind, value: Double?) {
        val editor = prefs.edit()
        if (value == null || !value.isFinite() || value <= 0.0) {
            editor.remove(kind.key)
        } else {
            editor.putFloat(kind.key, value.toFloat())
        }
        editor.apply()
    }

    fun getWeeklyCo2GoalKg(): Float {
        val raw = prefs.getFloat(KEY_WEEKLY_CO2_GOAL_KG, DEFAULT_WEEKLY_CO2_GOAL_KG)
        return raw.coerceIn(MIN_WEEKLY_CO2_GOAL_KG, MAX_WEEKLY_CO2_GOAL_KG)
    }

    /**
     * Persists the user's weekly CO₂-saved goal in kilograms, clamping to
     * the supported range so accidental UI inputs (e.g. a slider scrolled
     * past its label) can't poison the value.
     */
    fun setWeeklyCo2GoalKg(goalKg: Float) {
        val clamped = goalKg.coerceIn(MIN_WEEKLY_CO2_GOAL_KG, MAX_WEEKLY_CO2_GOAL_KG)
        prefs.edit().putFloat(KEY_WEEKLY_CO2_GOAL_KG, clamped).apply()
    }

    fun getCachedDisplayName(): String? = prefs.getString(KEY_CACHED_DISPLAY_NAME, null)

    fun setCachedDisplayName(name: String?) {
        if (name.isNullOrBlank()) {
            prefs.edit().remove(KEY_CACHED_DISPLAY_NAME).apply()
        } else {
            prefs.edit().putString(KEY_CACHED_DISPLAY_NAME, name).apply()
        }
    }

    /**
     * Clear all preferences (for logout). Preserves terms acceptance and auto-start preference so
     * users are not prompted again and background features stay enabled after re-login.
     */
    fun clearAll() {
        val terms = prefs.getString(KEY_TERMS_ACCEPTED_VERSION, null)
        val autoStart = prefs.getBoolean(KEY_AUTO_START_ON_WALK_ENABLED, DEFAULT_AUTO_START_ON_WALK)
        prefs.edit().clear().apply()
        prefs.edit().apply {
            if (terms != null) putString(KEY_TERMS_ACCEPTED_VERSION, terms)
            // Preserve auto-start: clearAll() must not silently disable a feature the user turned on.
            putBoolean(KEY_AUTO_START_ON_WALK_ENABLED, autoStart)
        }.apply()
        android.util.Log.d("UserPrefsManager", "All preferences cleared (terms + auto-start=$autoStart preserved)")
    }
}
