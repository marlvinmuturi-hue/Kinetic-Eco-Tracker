package Kinetic_Eco.Tracker.services

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import Kinetic_Eco.Tracker.data.UnitSystem
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
        private const val KEY_AGE = "physical_profile_age"
        private const val KEY_GENDER = "physical_profile_gender"
        
        // Unit preference (Metric = km, kcal | Imperial = miles, Wh)
        private const val KEY_UNIT_PREFERENCE = "unit_preference"
        
        // Distance milestone notifications (km or miles based on unit)
        private const val KEY_DISTANCE_ALERTS_ENABLED = "distance_alerts_enabled"
        private const val KEY_KM_NOTIFICATIONS_ENABLED = "km_notifications_enabled"  // Legacy, migrated to KEY_DISTANCE_ALERTS_ENABLED
        
        // Auto-start tracking when walking detected
        private const val KEY_AUTO_START_ON_WALK_ENABLED = "auto_start_on_walk_enabled"

        // Auto-stop on idle: minutes of inactivity before stopping (3, 5, or 10)
        private const val KEY_IDLE_STOP_MINUTES = "idle_stop_minutes"
        private const val DEFAULT_IDLE_STOP_MINUTES = 10

        // Notification sounds (milestones, tracking start/stop, session summary)
        private const val KEY_NOTIFICATION_SOUNDS_ENABLED = "notification_sounds_enabled"

        // App language: "auto" = follow device locale, or "en", "fr", "de", "es", "zh"
        private const val KEY_APP_LOCALE = "app_locale"

        // Theme: "system" = follow device, "light", "dark"
        private const val KEY_THEME_MODE = "theme_mode"

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
        private const val DEFAULT_AUTO_START_ON_WALK = false
        private const val DEFAULT_NOTIFICATION_SOUNDS = true
        
        private const val METERS_PER_MILE = 1609.344
    }
    
    /**
     * Save user's physical profile
     */
    fun savePhysicalProfile(profile: UserPhysicalProfile) {
        prefs.edit().apply {
            putFloat(KEY_WEIGHT, profile.weight.toFloat())
            putFloat(KEY_HEIGHT, profile.height.toFloat())
            putInt(KEY_AGE, profile.age)
            putString(KEY_GENDER, profile.gender.name)
            apply()
        }
        android.util.Log.d("UserPrefsManager", "Physical profile saved: $profile")
    }
    
    /**
     * Load user's physical profile
     * Returns saved profile or default values if not set
     */
    fun loadPhysicalProfile(): UserPhysicalProfile {
        val weight = prefs.getFloat(KEY_WEIGHT, DEFAULT_WEIGHT.toFloat()).toDouble()
        val height = prefs.getFloat(KEY_HEIGHT, DEFAULT_HEIGHT.toFloat()).toDouble()
        val age = prefs.getInt(KEY_AGE, DEFAULT_AGE)
        val genderString = prefs.getString(KEY_GENDER, DEFAULT_GENDER) ?: DEFAULT_GENDER
        val gender = try {
            Gender.valueOf(genderString)
        } catch (e: Exception) {
            Gender.MALE
        }
        
        val profile = UserPhysicalProfile(
            weight = weight,
            height = height,
            age = age,
            gender = gender
        )
        android.util.Log.d("UserPrefsManager", "Physical profile loaded: $profile")
        return profile
    }
    
    /**
     * Check if user has set their physical profile
     */
    fun hasPhysicalProfile(): Boolean {
        return prefs.contains(KEY_WEIGHT)
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
     * Save theme preference. "system" = follow device, "light", "dark"
     */
    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    /**
     * Load theme preference. Returns "system" if not set.
     */
    fun getThemeMode(): String {
        return prefs.getString(KEY_THEME_MODE, "system") ?: "system"
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
    
    /**
     * Clear all preferences (for logout)
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        android.util.Log.d("UserPrefsManager", "All preferences cleared")
    }
}
