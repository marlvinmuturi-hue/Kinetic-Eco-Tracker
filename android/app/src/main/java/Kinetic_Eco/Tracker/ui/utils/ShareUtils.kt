package Kinetic_Eco.Tracker.ui.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import Kinetic_Eco.Tracker.BuildConfig
import Kinetic_Eco.Tracker.data.AchievementBadge
import Kinetic_Eco.Tracker.ui.components.AppOpenAdManager
import Kinetic_Eco.Tracker.ui.components.badgeFmtVal
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ShareUtils {

    private val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"

    fun buildWeeklyShareText(
        weekCo2KgSaved: Double,
        weekDistanceM: Double,
        sessionCount: Int,
        equivalency: String?,
        unitSystem: UnitSystem
    ): String {
        val distanceStr = if (unitSystem.usesMetricDistance()) {
            "${(weekDistanceM / 1000).format(1)} km"
        } else {
            "${(weekDistanceM / 1609.34).format(1)} mi"
        }
        return buildString {
            appendLine("🌿 My Kinetic Eco week:")
            appendLine("• $distanceStr covered")
            if (weekCo2KgSaved > 0.01) {
                val co2Line = "• ${weekCo2KgSaved.format(2)} kg CO₂ saved"
                appendLine(if (equivalency != null) "$co2Line — like $equivalency" else co2Line)
            }
            appendLine("• $sessionCount session${if (sessionCount != 1) "s" else ""} logged")
            appendLine()
            appendLine("Going green one trip at a time! 💚")
            appendLine("Try Kinetic Eco: $PLAY_STORE_URL")
            append("#KineticEco #GreenCommute")
        }
    }

    fun buildSessionShareText(
        session: SessionStats,
        unitSystem: UnitSystem
    ): String {
        val distanceStr = if (unitSystem.usesMetricDistance()) {
            "${(session.totalDistance / 1000).format(2)} km"
        } else {
            "${(session.totalDistance / 1609.34).format(2)} mi"
        }
        val durationMin = TimeUnit.SECONDS.toMinutes(session.totalDuration)
        val durationStr = if (durationMin >= 60) {
            "${durationMin / 60}h ${durationMin % 60}m"
        } else "${durationMin}m"

        val mainActivity = session.breakdown.entries
            .filter { (k, _) -> k != ActivityType.IDLE }
            .maxByOrNull { (_, v) -> v.distance }
            ?.key

        val (activityEmoji, activityLabel) = when (mainActivity) {
            ActivityType.WALKING          -> "🚶" to "Walking"
            ActivityType.RUNNING          -> "🏃" to "Running"
            ActivityType.CYCLING          -> "🚴" to "Cycling"
            ActivityType.MOTORCYCLE       -> "🛵" to "Motorcycling"
            ActivityType.TRAIN            -> "🚆" to "Train"
            ActivityType.DRIVING          -> "🚗" to "Driving"
            ActivityType.ELECTRIC_VEHICLE -> "⚡" to "EV ride"
            ActivityType.FLYING           -> "✈️" to "Flying"
            else                          -> "🌿" to "Activity"
        }

        return buildString {
            appendLine("$activityEmoji Just logged a session on Kinetic Eco!")
            appendLine("• $distanceStr in $durationStr")
            appendLine("• $activityLabel")
            if (session.co2Conserved > 0.01) {
                appendLine("• ${session.co2Conserved.format(2)} kg CO₂ saved")
            }
            if (session.caloriesBurned > 1) {
                appendLine("• ${session.caloriesBurned.toInt()} kcal burned")
            }
            appendLine()
            appendLine("Track your eco impact with Kinetic Eco 🌱")
            appendLine(PLAY_STORE_URL)
            append("#KineticEco")
        }
    }

    fun buildBadgeShareText(badge: AchievementBadge): String = buildString {
        appendLine("🏅 New badge on Kinetic Eco: ${badge.tier.label} ${badge.title}!")
        appendLine("• ${badge.description}: ${badgeFmtVal(badge.currentValue, badge.unit)} ${badge.unit}")
        appendLine()
        appendLine("Track your real eco impact with Kinetic Eco 🌱")
        appendLine(PLAY_STORE_URL)
        append("#KineticEco #GreenCommute")
    }

    fun buildInviteText(): String = buildString {
        appendLine("🌱 I've been using Kinetic Eco to track my commutes and see my real CO₂ impact.")
        appendLine("It auto-detects walking, cycling, driving, and more — and shows you how much CO₂ you're saving.")
        appendLine()
        appendLine("Give it a try: $PLAY_STORE_URL")
        append("#KineticEco #GreenCommute")
    }

    fun launchShareSheet(context: Context, text: String) {
        // Returning from the share sheet is an app-initiated external return — don't fire an App Open ad.
        AppOpenAdManager.suppressNextForegroundAd()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    /**
     * Shares [bitmap] as a PNG (plus an optional [caption]) via the system share sheet. Writes to the
     * app cache and exposes it through the existing FileProvider (authority `${applicationId}.fileprovider`,
     * whose file_paths maps the whole cache dir).
     */
    fun shareImage(context: Context, bitmap: Bitmap, caption: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "kinetic_recap_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        // Returning from the share sheet is an app-initiated external return — don't fire an App Open ad.
        AppOpenAdManager.suppressNextForegroundAd()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }
}