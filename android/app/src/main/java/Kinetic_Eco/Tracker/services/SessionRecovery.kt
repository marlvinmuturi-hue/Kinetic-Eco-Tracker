package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import Kinetic_Eco.Tracker.data.SessionCheckpoint
import com.google.firebase.auth.FirebaseAuth

/**
 * Rescues a trip whose service was killed and never came back.
 *
 * The checkpoint written by `TrackingService` covers two failure shapes. If the
 * service is restarted promptly, START_STICKY hands it back and the session
 * simply resumes. If it is not — the process stayed dead, the user rebooted, or
 * an OEM battery manager kept it down — nothing would ever pick the snapshot up
 * and the trip would still be lost, just more slowly. This is the second half:
 * on app launch, an orphaned snapshot is written out as a finished session.
 *
 * A salvaged trip is genuinely incomplete. It ends at the last checkpoint rather
 * than where the user actually stopped, so it under-reports by up to
 * [SessionCheckpointStore.CHECKPOINT_INTERVAL_SECONDS] of travel. That is
 * categorically better than losing the whole thing.
 */
object SessionRecovery {

    private const val TAG = "SessionRecovery"

    /**
     * Persist an orphaned checkpoint as a completed session, then clear it.
     *
     * Safe to call unconditionally at launch: it no-ops when there is no
     * checkpoint. Must **not** be called while tracking is live — the snapshot
     * belongs to the running session and saving it would duplicate the trip.
     *
     * @return the new session id, or null when nothing was salvaged.
     */
    suspend fun salvage(context: Context): String? {
        val appContext = context.applicationContext
        val store = SessionCheckpointStore(appContext)
        val checkpoint = store.read() ?: return null

        // Same floor the three normal save paths apply. Without it, a session
        // killed seconds after starting would be resurrected as a stub trip.
        if (checkpoint.distanceMeters < MIN_SALVAGE_DISTANCE_M) {
            Log.d(TAG, "Orphaned session too short (${checkpoint.distanceMeters}m); dropping")
            store.clear()
            return null
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId.isNullOrEmpty()) {
            // Keep the checkpoint. Auth may simply not have resolved yet, and
            // deleting it here would throw away a recoverable trip for a reason
            // that will likely have fixed itself by the next launch.
            Log.w(TAG, "No signed-in user; leaving checkpoint for a later attempt")
            return null
        }

        return try {
            val stats = checkpoint.stats.copy(
                routePath = checkpoint.routePath,
                segments = checkpoint.segments,
                sessionEndTimeMs = checkpoint.savedAtMs
            )
            val sessionId = SessionManager(appContext).saveSession(
                userId = userId,
                stats = stats,
                sessionStartTimeMs = checkpoint.sessionStartTimeMs,
                // Accelerometer samples are not checkpointed — they exist only for
                // downstream analytics and would multiply the snapshot's size for
                // no benefit to the user's trip record.
                accelerometerSamples = emptyList()
            )
            store.clear()
            Log.w(
                TAG,
                "Salvaged orphaned session $sessionId: ${checkpoint.distanceMeters}m, " +
                    "${checkpoint.stats.totalDuration}s"
            )
            sessionId
        } catch (e: Exception) {
            // Leave the checkpoint in place so the next launch can retry.
            Log.e(TAG, "Failed to salvage orphaned session; keeping checkpoint", e)
            null
        }
    }

    /** Matches the 200 m floor used by the three interactive save paths. */
    private const val MIN_SALVAGE_DISTANCE_M = 200.0
}