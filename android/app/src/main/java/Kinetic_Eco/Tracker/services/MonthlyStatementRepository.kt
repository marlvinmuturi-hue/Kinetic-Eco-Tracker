package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.MonthlyStatement
import Kinetic_Eco.Tracker.data.MonthlyStatementCalculator
import Kinetic_Eco.Tracker.data.StatementTrip
import Kinetic_Eco.Tracker.data.database.AppDatabase
import Kinetic_Eco.Tracker.util.MonthWindow

/**
 * Assembles a month's statement.
 *
 * Everything is computed **on the device**, deliberately. The two inputs that make the
 * money figure credible — the user's measured fuel economy and the price they actually
 * pay — live here, not on the server. A server-side statement would have to fall back
 * to a class average and a national price cap, and quote the result as though it were
 * the user's spending.
 */
class MonthlyStatementRepository(context: Context) {

    private val appContext = context.applicationContext
    private val sessionDao = AppDatabase.getDatabase(appContext).sessionDao()
    private val prefs = UserPreferencesManager(appContext)

    companion object {
        private const val TAG = "MonthlyStatement"

        /** Modes paid for out of a fuel tank or a battery. */
        private val MOTORISED = setOf(
            ActivityType.DRIVING,
            ActivityType.MOTORCYCLE,
            ActivityType.ELECTRIC_VEHICLE
        )
    }

    suspend fun statementFor(userId: String, monthStartMs: Long): MonthlyStatement? {
        if (userId.isBlank()) return null
        return try {
            val endMs = MonthWindow.endOfMonthMs(monthStartMs)
            val trips = sessionDao.getStatementRows(userId, monthStartMs, endMs).map { row ->
                StatementTrip(
                    timestampMs = row.createdAt,
                    distanceKm = row.totalDistance / 1000.0,
                    // Motorised distance comes from the per-activity breakdown, not the
                    // session total: one session can mix the walk to the car with the
                    // drive, and charging the whole thing to fuel would overstate spend.
                    motorisedKm = row.breakdown.entries
                        .filter { it.key in MOTORISED }
                        .sumOf { it.value.distance } / 1000.0,
                    co2SavedKg = row.co2Conserved,
                    co2EmittedKg = row.co2Emissions
                )
            }

            MonthlyStatementCalculator.build(
                monthStartMs = monthStartMs,
                trips = trips,
                profile = prefs.loadVehicleProfile(),
                prices = EnergyPriceRepository.prices.value,
                measured = FuelLogRepository.economy.value,
                isPartial = MonthWindow.isCurrentMonth(monthStartMs)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Statement build failed for ${MonthWindow.key(monthStartMs)}", e)
            null
        }
    }
}