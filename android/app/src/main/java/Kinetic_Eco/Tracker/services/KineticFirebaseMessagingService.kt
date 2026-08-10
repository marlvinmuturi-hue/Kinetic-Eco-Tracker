package Kinetic_Eco.Tracker.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import Kinetic_Eco.Tracker.MainActivity
import Kinetic_Eco.Tracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Handles FCM token refreshes and incoming push notifications.
 *
 * Token storage: saved to Firestore at `users/{uid}/fcmTokens/{token}` with a timestamp so
 * scheduled Cloud Functions can fan-out to all registered devices per user.
 *
 * Message routing uses the `type` data key:
 *   "daily"  → daily_digest_channel  (daily eco impact at ~20:00 EAT)
 *   anything else → weekly_digest_channel  (Monday weekly summary)
 */
class KineticFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        saveTokenToFirestore(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val prefs = UserPreferencesManager(applicationContext)
        val type = message.data["type"] ?: "weekly"

        // A dedicated branch, not a fall-through. The `else` below is the *weekly*
        // digest — a new type without its own case would inherit the weekly channel and
        // be silenced by the weekly toggle, which is a bug that looks like a
        // mis-configured preference rather than a routing mistake.
        if (type == "monthly") {
            if (!prefs.isMonthlyStatementEnabled()) {
                Log.d(TAG, "Monthly statement disabled by user — suppressing")
                return
            }
            val title = message.data["title"] ?: "Your monthly statement"
            val body = message.data["body"] ?: "Last month's summary is ready."
            showNotification(title, body, MONTHLY_STATEMENT_CHANNEL_ID, MONTHLY_STATEMENT_NOTIFICATION_ID)
            return
        }

        // Deliberately not behind a user toggle. Every other push here is something the
        // app wants to tell you; this one is that your payment failed and Premium is
        // about to stop. Letting a digest preference suppress it would mean the user
        // silently loses what they paid for.
        if (type == "billing") {
            val title = message.data["title"] ?: "Payment problem"
            val body = message.data["body"]
                ?: "Google Play could not take your payment. Update it to keep Premium."
            showNotification(title, body, BILLING_CHANNEL_ID, BILLING_NOTIFICATION_ID)
            return
        }

        if (type == "daily") {
            if (!prefs.isDailyDigestEnabled()) {
                Log.d(TAG, "Daily digest disabled by user — suppressing")
                return
            }
            val title = message.data["title"] ?: "🌿 Today's Eco Impact"
            val body  = message.data["body"]  ?: "Check your activity for today."
            showNotification(title, body, DAILY_DIGEST_CHANNEL_ID, DAILY_DIGEST_NOTIFICATION_ID)
        } else {
            if (!prefs.isWeeklyDigestEnabled()) {
                Log.d(TAG, "Weekly digest disabled by user — suppressing")
                return
            }
            val title = message.data["title"]
                ?: message.notification?.title
                ?: "Kinetic Eco"
            val body = message.data["body"]
                ?: message.notification?.body
                ?: "Your weekly activity summary is ready."
            showNotification(title, body, WEEKLY_DIGEST_CHANNEL_ID, WEEKLY_DIGEST_NOTIFICATION_ID)
        }
    }

    private fun saveTokenToFirestore(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.w(TAG, "No signed-in user — FCM token not saved")
            return
        }
        val data: Map<String, Any> = mapOf(
            "token" to token,
            "updatedAt" to System.currentTimeMillis(),
            "platform" to "android"
        )
        serviceScope.launch {
            repeat(3) { attempt ->
                try {
                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .collection("fcmTokens").document(token)
                        .set(data)
                        .await()
                    Log.d(TAG, "FCM token saved to Firestore")
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "FCM token save attempt ${attempt + 1} failed", e)
                    if (attempt < 2) delay(1000L * (attempt + 1))
                }
            }
            Log.e(TAG, "FCM token save failed after 3 attempts — will retry on next token refresh")
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        channelId: String,
        notificationId: Int
    ) {
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (channelName, channelDesc) = when (channelId) {
                DAILY_DIGEST_CHANNEL_ID -> "Daily Digest" to "Your daily eco impact from Kinetic Eco"
                MONTHLY_STATEMENT_CHANNEL_ID ->
                    "Monthly Statement" to "A summary of what your travel cost last month"
                BILLING_CHANNEL_ID ->
                    "Subscription & billing" to "Payment problems that affect your Premium access"
                else                    -> "Weekly Digest" to "Your weekly activity summary from Kinetic Eco"
            }
            // Billing gets HIGH: a failed payment is time-limited and actionable, and
            // sinking it to the same weight as a digest is how people discover it a
            // week after Premium switched off.
            val importance =
                if (channelId == BILLING_CHANNEL_ID) NotificationManager.IMPORTANCE_HIGH
                else NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance)
                .apply {
                    description = channelDesc
                    enableVibration(false)
                }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "KineticFCMService"
        private const val WEEKLY_DIGEST_CHANNEL_ID   = "weekly_digest_channel"
        private const val DAILY_DIGEST_CHANNEL_ID    = "daily_digest_channel"
        private const val WEEKLY_DIGEST_NOTIFICATION_ID = 5001
        private const val DAILY_DIGEST_NOTIFICATION_ID  = 5002
        private const val MONTHLY_STATEMENT_CHANNEL_ID  = "monthly_statement_channel"
        private const val MONTHLY_STATEMENT_NOTIFICATION_ID = 5003
        const val BILLING_CHANNEL_ID = "billing_channel"
        private const val BILLING_NOTIFICATION_ID = 5004
    }
}
