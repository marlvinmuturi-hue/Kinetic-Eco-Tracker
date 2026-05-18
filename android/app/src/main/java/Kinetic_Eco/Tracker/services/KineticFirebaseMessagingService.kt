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
 * the weekly-digest Cloud Function can fan-out to all registered devices per user.
 *
 * Incoming payloads that include a `title` / `body` key in the data map are displayed as a
 * high-priority notification in the "Weekly Digest" channel.
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
        if (!prefs.isWeeklyDigestEnabled()) {
            Log.d(TAG, "Weekly digest disabled by user — suppressing FCM message")
            return
        }

        val title = message.data["title"]
            ?: message.notification?.title
            ?: "Kinetic Eco"
        val body = message.data["body"]
            ?: message.notification?.body
            ?: "Your weekly activity summary is ready."

        showDigestNotification(title, body)
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

    private fun showDigestNotification(title: String, body: String) {
        val channelId = "weekly_digest_channel"
        val nm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Weekly Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Your weekly activity summary from Kinetic Eco"
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).also {
            it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
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

        nm.notify(WEEKLY_DIGEST_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "KineticFCMService"
        private const val WEEKLY_DIGEST_NOTIFICATION_ID = 5001
    }
}
