package Kinetic_Eco.Tracker.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.MeasuredEconomy

/**
 * Tells the user, once, that their fuel log has produced a real economy figure.
 *
 * This is the payoff for the only genuinely tedious thing the app asks of anyone:
 * typing in fill-ups. Until the second brim-full entry lands, every cost figure comes
 * from an engine-displacement class average that can be 30% out. After it, they come
 * from this person's car. That transition is worth one interruption and no more —
 * there is deliberately no reminder to log fill-ups, and no repeat when the figure is
 * later refined.
 */
object MeasuredEconomyNotifier {

    private const val TAG = "MeasuredEconomy"
    private const val CHANNEL_ID = "fuel_economy_channel"
    private const val NOTIFICATION_ID = 5005

    fun celebrateFirstMeasurement(context: Context, economy: MeasuredEconomy) {
        val ctx = context.applicationContext
        val prefs = UserPreferencesManager(ctx)
        if (prefs.hasCelebratedMeasuredEconomy()) return

        // Claim the flag before posting. If the notification is blocked — permission
        // denied, channel muted — the milestone has still passed, and re-attempting on
        // every future recompute would be the nagging this is designed to avoid.
        prefs.setCelebratedMeasuredEconomy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "No notification permission; skipping first-measurement notice")
            return
        }

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.fuel_economy_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = ctx.getString(R.string.fuel_economy_channel_desc)
                    enableVibration(false)
                }
            )
        }

        val openIntent = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val body = ctx.getString(
            R.string.fuel_economy_measured_body,
            String.format("%.1f", economy.lPer100Km)
        )

        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ctx.getString(R.string.fuel_economy_measured_title))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openIntent)
                    .setAutoCancel(true)
                    .build()
            )
        }.onFailure { Log.w(TAG, "Could not post first-measurement notice", it) }
    }
}
