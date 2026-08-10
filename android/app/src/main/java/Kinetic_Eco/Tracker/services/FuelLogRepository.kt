package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.FillUp
import Kinetic_Eco.Tracker.data.FuelEconomyCalculator
import Kinetic_Eco.Tracker.data.MeasuredEconomy
import Kinetic_Eco.Tracker.data.database.AppDatabase
import Kinetic_Eco.Tracker.data.database.FuelEntryEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Owns the fuel log and the measured economy derived from it.
 *
 * Assembles what [FuelEconomyCalculator] needs — fill-ups plus the motorised distance
 * tracked between them — and caches the result so the calculator can ask for it on
 * every keystroke without touching the database.
 */
class FuelLogRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    private val fuelDao = db.fuelEntryDao()

    companion object {
        private const val TAG = "FuelLogRepository"

        /** Modes whose distance is paid for out of the fuel tank. */
        private val MOTORISED = setOf(ActivityType.DRIVING, ActivityType.MOTORCYCLE)

        private val _economy = MutableStateFlow<MeasuredEconomy?>(null)

        /** Null until enough full-to-full intervals exist. */
        val economy: StateFlow<MeasuredEconomy?> = _economy.asStateFlow()

        private val _observedPrice = MutableStateFlow<Double?>(null)

        /**
         * Price per litre from the user's recent receipts, or null when none are recent.
         *
         * The most authoritative price the app can hold: it is what this person paid,
         * at their pump, rather than a national cap or a compiled-in seed. Offered as a
         * suggestion rather than applied silently — changing someone's numbers without
         * telling them is how a helpful figure becomes a suspicious one.
         */
        val observedPrice: StateFlow<Double?> = _observedPrice.asStateFlow()
    }

    fun observeEntries(userId: String) = fuelDao.observeRecent(userId)

    // ── Firestore sync ───────────────────────────────────────────────────────
    //
    // Fuel entries are hand-typed and cannot be recovered any other way. A session can
    // be re-walked; a fill-up receipt is in a bin. So they sync to
    // `users/{uid}/fuelEntries/{id}` and survive reinstalls the way sessions already do.

    private fun remote(userId: String) =
        FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("fuelEntries")

    /** Push anything not yet acknowledged by the server. Safe to call repeatedly. */
    suspend fun syncPending(userId: String) {
        if (userId.isBlank()) return
        for (entry in runCatching { fuelDao.getUnsynced(userId) }.getOrDefault(emptyList())) {
            try {
                remote(userId).document(entry.id).set(
                    mapOf(
                        "filledAtMs" to entry.filledAtMs,
                        "litres" to entry.litres,
                        "amountPaid" to entry.amountPaid,
                        "currencyCode" to entry.currencyCode,
                        "isFullTank" to entry.isFullTank,
                        "createdAt" to entry.createdAt
                    )
                ).await()
                fuelDao.markSynced(entry.id)
            } catch (e: Exception) {
                // Leave it unsynced and stop — the next attempt retries from here rather
                // than hammering a connection that is evidently down.
                Log.w(TAG, "Fuel entry sync failed for ${entry.id}; will retry", e)
                return
            }
        }
    }

    /**
     * Pull down entries this device does not have — the reinstall path.
     *
     * Only inserts ids that are missing locally. A local row is never overwritten,
     * because the only rows that can differ are ones this device has not yet uploaded,
     * and clobbering those would discard the newer of the two.
     */
    suspend fun restoreFromFirestore(userId: String): Int {
        if (userId.isBlank()) return 0
        return try {
            val localIds = fuelDao.getAllIds(userId).toSet()
            val docs = remote(userId).get().await().documents
            var restored = 0
            for (doc in docs) {
                if (doc.id in localIds) continue
                val litres = doc.getDouble("litres") ?: continue
                fuelDao.insert(
                    FuelEntryEntity(
                        id = doc.id,
                        userId = userId,
                        filledAtMs = doc.getLong("filledAtMs") ?: continue,
                        litres = litres,
                        amountPaid = doc.getDouble("amountPaid") ?: 0.0,
                        currencyCode = doc.getString("currencyCode") ?: "",
                        isFullTank = doc.getBoolean("isFullTank") ?: true,
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        // Came from the server, so it is by definition already there.
                        synced = true
                    )
                )
                restored++
            }
            if (restored > 0) recompute(userId)
            Log.d(TAG, "Restored $restored fuel entries")
            restored
        } catch (e: Exception) {
            Log.w(TAG, "Fuel log restore failed", e)
            0
        }
    }

    /** Upload, download, then recompute — the whole reconciliation, for screen entry. */
    suspend fun sync(userId: String) {
        syncPending(userId)
        restoreFromFirestore(userId)
        recompute(userId)
    }

    suspend fun addFillUp(
        userId: String,
        litres: Double,
        amountPaid: Double,
        currencyCode: String,
        filledAtMs: Long = System.currentTimeMillis(),
        isFullTank: Boolean = true
    ) {
        if (litres <= 0.0 || amountPaid < 0.0) return
        fuelDao.insert(
            FuelEntryEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                filledAtMs = filledAtMs,
                litres = litres,
                amountPaid = amountPaid,
                currencyCode = currencyCode,
                isFullTank = isFullTank,
                createdAt = System.currentTimeMillis()
            )
        )
        recompute(userId)
        syncPending(userId)
    }

    /**
     * Delete locally *and* remotely.
     *
     * The remote delete is not optional: [restoreFromFirestore] inserts any id it does
     * not find locally, so an entry deleted on the device would be resurrected on the
     * next sync — and a user who deleted a mistyped fill-up would watch it come back
     * and keep skewing their economy.
     */
    suspend fun deleteEntry(userId: String, id: String) {
        fuelDao.delete(id)
        runCatching { remote(userId).document(id).delete().await() }
            .onFailure { Log.w(TAG, "Remote delete failed for $id; it may reappear on restore", it) }
        recompute(userId)
    }

    /**
     * Recalculate economy from the whole log.
     *
     * Cheap enough to run on every change — a fill-up log is tens of rows, and the
     * distance query is a projection that never touches route geometry.
     */
    suspend fun recompute(userId: String) {
        try {
            val fillUps = fuelDao.getAll(userId).map {
                FillUp(
                    filledAtMs = it.filledAtMs,
                    litres = it.litres,
                    amountPaid = it.amountPaid,
                    isFullTank = it.isFullTank
                )
            }
            val intervals = FuelEconomyCalculator.fullTankPairs(fillUps).map { (previous, current) ->
                Kinetic_Eco.Tracker.data.FuelInterval(
                    litres = current.litres,
                    distanceKm = drivingKmBetween(userId, previous.filledAtMs, current.filledAtMs)
                )
            }
            val previous = _economy.value
            val next = FuelEconomyCalculator.economyFrom(intervals)
            _economy.value = next
            _observedPrice.value = FuelEconomyCalculator.recentPricePerLitre(fillUps)

            // The moment the log stops being data entry and starts paying off. Fired on
            // the transition only, and only once ever — see the prefs flag.
            if (previous == null && next != null) {
                MeasuredEconomyNotifier.celebrateFirstMeasurement(appContext, next)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Economy recompute failed; keeping previous value", e)
        }
    }

    /**
     * Motorised kilometres tracked in a window.
     *
     * Summed from each session's per-activity breakdown, not `totalDistance`: a single
     * session can mix a walk to the car with the drive itself, and counting the whole
     * session as driving would understate consumption.
     */
    private suspend fun drivingKmBetween(userId: String, fromMs: Long, toMs: Long): Double {
        val rows = fuelDao.getBreakdownsInRange(userId, fromMs, toMs)
        val metres = rows.sumOf { row ->
            row.breakdown.entries
                .filter { it.key in MOTORISED }
                .sumOf { it.value.distance }
        }
        return metres / 1000.0
    }


}
