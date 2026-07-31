package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import Kinetic_Eco.Tracker.data.SessionCheckpoint
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Durable store for the in-flight session snapshot — see [SessionCheckpoint].
 *
 * Backed by a plain file rather than SharedPreferences: the payload carries the
 * full GPS trace, which can run to thousands of points on a long drive, and
 * prefs are the wrong tool for values that size.
 *
 * Writes are **atomic**. The snapshot goes to a temp file first and is only then
 * renamed over the live one, so a kill part-way through a write can never leave
 * a truncated checkpoint behind — the worst case is that the previous good
 * snapshot survives, which is exactly what we want.
 */
class SessionCheckpointStore(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val file: File get() = File(appContext.filesDir, FILE_NAME)
    private val tempFile: File get() = File(appContext.filesDir, TEMP_FILE_NAME)

    /** True when a snapshot is on disk. Cheap — no parsing. */
    fun exists(): Boolean = file.exists()

    /**
     * Persist [checkpoint], replacing any previous snapshot.
     *
     * Failures are swallowed deliberately: a checkpoint is a safety net, and a
     * full disk or a transient IO error must never take down an active tracking
     * session. The cost of a failed write is that a kill in the next interval
     * loses what it would have lost anyway.
     */
    suspend fun write(checkpoint: SessionCheckpoint) = withContext(Dispatchers.IO) {
        try {
            tempFile.writeText(gson.toJson(checkpoint))
            if (!tempFile.renameTo(file)) {
                // renameTo can fail if the destination exists on some filesystems.
                file.delete()
                if (!tempFile.renameTo(file)) {
                    Log.w(TAG, "Checkpoint rename failed; snapshot not updated")
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Checkpoint write failed", e)
            runCatching { tempFile.delete() }
        }
    }

    /**
     * Read the stored snapshot, or null when there is none, it is unreadable, or
     * it was written by an incompatible version.
     *
     * A corrupt or stale-schema file is deleted rather than left to fail on
     * every subsequent read.
     */
    suspend fun read(): SessionCheckpoint? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val checkpoint = gson.fromJson(file.readText(), SessionCheckpoint::class.java)
            when {
                checkpoint == null -> {
                    Log.w(TAG, "Checkpoint parsed to null; discarding")
                    clearBlocking()
                    null
                }
                checkpoint.schemaVersion != SessionCheckpoint.CURRENT_SCHEMA_VERSION -> {
                    Log.w(
                        TAG,
                        "Checkpoint schema ${checkpoint.schemaVersion} != " +
                            "${SessionCheckpoint.CURRENT_SCHEMA_VERSION}; discarding"
                    )
                    clearBlocking()
                    null
                }
                else -> checkpoint
            }
        } catch (e: Exception) {
            Log.w(TAG, "Checkpoint unreadable; discarding", e)
            clearBlocking()
            null
        }
    }

    /** Remove the snapshot. Call once a session is safely persisted or discarded. */
    suspend fun clear() = withContext(Dispatchers.IO) { clearBlocking() }

    private fun clearBlocking() {
        runCatching { file.delete() }
        runCatching { tempFile.delete() }
    }

    companion object {
        private const val TAG = "SessionCheckpoint"
        private const val FILE_NAME = "session_checkpoint.json"
        private const val TEMP_FILE_NAME = "session_checkpoint.json.tmp"

        /**
         * How often the service snapshots an active session. A kill loses at most
         * this much progress. Short enough to be cheap in practice — the write is
         * off the main thread and a 30 s interval is negligible against the GPS
         * and sensor work already running.
         */
        const val CHECKPOINT_INTERVAL_SECONDS = 30

        /**
         * Beyond this age a snapshot is treated as an abandoned trip to be saved
         * outright, not resumed. Resuming a session the user started yesterday
         * would silently glue two unrelated journeys together.
         */
        const val RESUME_MAX_AGE_MS = 30 * 60 * 1000L
    }
}