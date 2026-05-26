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

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        saveTokenToFirestore(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val prefs = UserPreferencesManager(applicationContext)
        val type = message.data["type"] ?: "weekly"

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
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("fcmTokens").document(token)
            .set(data)
            .addOnSuccessListener { Log.d(TAG, "FCM token saved to Firestore") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to save FCM token", e) }
    }

    private fun showNotification(
        title: String,
        body: String,
        channelId: String,
        notificationId: Int
    ) {
        val nm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (channelName, channelDesc) = when (channelId) {
                DAILY_DIGEST_CHANNEL_ID -> "Daily Digest" to "Your daily eco impact from Kinetic Eco"
                else                    -> "Weekly Digest" to "Your weekly activity summary from Kinetic Eco"
            }
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
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
    }
}
