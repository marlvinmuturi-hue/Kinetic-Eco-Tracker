package Kinetic_Eco.Tracker.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Helpers for keeping the background auto-start monitor alive on OEM ROMs.
 *
 * Two independent obstacles kill background tracking on Samsung/Xiaomi/etc., and neither is a runtime
 * permission:
 *  1. **Battery optimization (Doze app-standby)** — standard Android. Detected via [PowerManager].
 *  2. **OEM "autostart" restrictions** — Xiaomi/Oppo/Vivo/Huawei add a proprietary allow-list on top of
 *     Android's. There's no API to query or request it, only best-effort deep-links into their settings.
 *
 * We deliberately do NOT declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (which would allow the one-tap
 * system dialog) — that permission triggers extra Google Play review. Instead we deep-link the user into
 * the relevant settings screen, which needs no special permission.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * True when the app can access location in the background ("Allow all the time"), or the OS
     * predates the separate background-location grant (Android 9 and below, where the foreground
     * grant already covers background use). This — not battery optimization — is what lets the
     * auto-start monitor take GPS fixes while the app is closed.
     */
    fun hasBackgroundLocationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True when the app is already exempt from battery optimization. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system battery-optimization list so the user can set Kinetic to "Not optimized" /
     * "Unrestricted". Falls back to the app-details page (from which Battery is one tap away) on any
     * device that doesn't expose the list activity.
     *
     * @return true if some settings screen was launched.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, list)) return true
        return openAppDetailsSettings(context)
    }

    /** Opens this app's system "App info" page (Battery / permissions reachable from there). */
    fun openAppDetailsSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, intent)
    }

    /**
     * True if this device is from an OEM known to add a proprietary autostart/background allow-list on
     * top of stock Android. Used to decide whether to surface the "Autostart" action at all.
     */
    fun hasOemAutoStartRestriction(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return OEM_AUTOSTART_INTENTS.keys.any { m.contains(it) }
    }

    /**
     * Best-effort deep-link into the OEM's autostart / background-activity manager. There is no standard
     * API, so we probe a per-manufacturer list of known component names and launch the first that
     * resolves. Returns false if none are present (e.g. stock Android / Pixel), so the caller can hide
     * the action rather than dead-ending the user.
     */
    fun openOemAutoStartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val components = OEM_AUTOSTART_INTENTS.entries
            .firstOrNull { manufacturer.contains(it.key) }
            ?.value
            ?: return false

        for (component in components) {
            val intent = Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Only launch if the activity actually exists on this build — component names drift
            // between ROM versions, so resolve first to avoid an ActivityNotFoundException crash.
            if (context.packageManager.resolveActivity(intent, 0) != null && tryStart(context, intent)) {
                return true
            }
        }
        return false
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch ${intent.action ?: intent.component}", e)
            false
        }
    }

    /**
     * Known OEM autostart-manager activities, keyed by a substring of [Build.MANUFACTURER]. Multiple
     * candidates per OEM because component names change across ROM versions — we try them in order.
     */
    private val OEM_AUTOSTART_INTENTS: Map<String, List<ComponentName>> = mapOf(
        "xiaomi" to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        "redmi" to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        "poco" to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        "oppo" to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        ),
        "vivo" to listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        ),
        "huawei" to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        ),
        "honor" to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        ),
        "oneplus" to listOf(
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        ),
        "samsung" to listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
    )
}