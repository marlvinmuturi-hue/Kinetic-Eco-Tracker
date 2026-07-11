package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Durable backfill of locally-saved sessions that never reached Firestore.
 *
 * Sessions are always written to Room first, then synced to Firestore fire-and-forget in
 * [SessionManager.saveSession]. That inline sync can be killed with the process when a session ends and
 * the app is backgrounded — so this worker sweeps up anything still marked un-synced. Because it runs
 * under WorkManager with a network constraint and exponential backoff, it survives process death and
 * automatically waits for connectivity, which the original coroutine could not.
 */
class SessionSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            // Not signed in yet — nothing to attribute. Re-enqueued on next launch / after login.
            Log.d(TAG, "No authenticated user; skipping session backfill")
            return Result.success()
        }

        return SessionManager(applicationContext).backfillUnsyncedSessions(userId).fold(
            onSuccess = {
                Log.d(TAG, "Session backfill complete ($it uploaded)")
                Result.success()
            },
            onFailure = { e ->
                // Transient (offline / auth token refresh) — let WorkManager retry with backoff.
                Log.w(TAG, "Session backfill failed, will retry: ${e.message}")
                Result.retry()
            }
        )
    }

    companion object {
        private const val TAG = "SessionSyncWorker"
        private const val UNIQUE_WORK_NAME = "session-backfill"

        /**
         * Enqueue a one-off backfill that runs once the network is available. Safe to call on every
         * launch and after login — [ExistingWorkPolicy.KEEP] coalesces duplicate requests so we never
         * stack redundant work.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SessionSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}