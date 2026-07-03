package Kinetic_Eco.Tracker.services

import android.content.Context
import android.location.Geocoder
import android.util.Log
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.TravelRecap
import Kinetic_Eco.Tracker.data.TripRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class TravelRecapService(private val context: Context) {

    suspend fun compute(sessions: List<SessionStats>): TravelRecap = withContext(Dispatchers.IO) {
        if (sessions.isEmpty()) return@withContext empty()

        // --- Reverse geocoding ---
        // Round to 2 dp (~1.1 km grid) so repeated commutes become a single lookup.
        val locationCache   = mutableMapOf<String, Pair<String, String>>()  // key -> (city, country)
        val countryCodeCache = mutableMapOf<String, String>()               // countryName -> ISO-2

        if (Geocoder.isPresent()) {
            val geocoder = Geocoder(context, Locale.getDefault())

            // Collect at most 30 unique rounded start coordinates
            val uniqueCoords = sessions
                .mapNotNull { s ->
                    s.routePath.firstOrNull()?.let { pt ->
                        val key = "%.2f,%.2f".format(pt.latitude, pt.longitude)
                        key to (pt.latitude to pt.longitude)
                    }
                }
                .distinctBy { it.first }
                .take(30)

            uniqueCoords.forEach { (key, coords) ->
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(coords.first, coords.second, 1)
                    addresses?.firstOrNull()?.let { addr ->
                        val city    = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                        val country = addr.countryName
                        if (city != null && country != null) {
                            locationCache[key] = city to country
                            addr.countryCode?.uppercase()?.let { countryCodeCache[country] = it }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Geocoding failed for $key: ${e.message}")
                }
            }
        }

        fun locationFor(session: SessionStats): Pair<String?, String?> {
            val pt = session.routePath.firstOrNull() ?: return null to null
            val key = "%.2f,%.2f".format(pt.latitude, pt.longitude)
            return locationCache[key]?.let { (city, country) -> city to country } ?: (null to null)
        }

        // --- Aggregate unique cities / countries ---
        val allCities   = linkedSetOf<String>()
        val allCountries = linkedSetOf<String>()
        val allCodes    = linkedSetOf<String>()

        sessions.forEach { s ->
            val (city, country) = locationFor(s)
            city?.let { allCities.add(it) }
            country?.let { c ->
                allCountries.add(c)
                countryCodeCache[c]?.let { allCodes.add(it) }
            }
        }

        // --- Personal records ---
        fun fmtDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        fun record(session: SessionStats, value: String): TripRecord {
            val (city, country) = locationFor(session)
            return TripRecord(
                displayValue = value,
                sessionDate  = session.date,
                city         = city,
                country      = country
            )
        }

        val longestTrip = sessions.maxByOrNull { it.totalDistance }?.let { s ->
            record(s, "%.1f km".format(s.totalDistance / 1000.0))
        }

        val longestSession = sessions.maxByOrNull { it.totalDuration }?.let { s ->
            record(s, fmtDuration(s.totalDuration))
        }

        val topSpeedRecord = sessions
            .filter { it.topSpeedMps > 0.5 }
            .maxByOrNull { it.topSpeedMps }
            ?.let { s -> record(s, "%.1f km/h".format(s.topSpeedMps * 3.6)) }

        val mostElevation = sessions
            .filter { it.elevationGain > 0 }
            .maxByOrNull { it.elevationGain }
            ?.let { s -> record(s, "%.0f m".format(s.elevationGain)) }

        val bestCo2 = sessions
            .filter { it.co2Conserved > 0 }
            .maxByOrNull { it.co2Conserved }
            ?.let { s -> record(s, "%.2f kg".format(s.co2Conserved)) }

        val mostCalories = sessions
            .filter { it.caloriesBurned > 0 }
            .maxByOrNull { it.caloriesBurned }
            ?.let { s -> record(s, "%.0f kcal".format(s.caloriesBurned)) }

        val firstTripDate = sessions.minByOrNull { it.date }?.date

        TravelRecap(
            citiesVisited       = allCities.sorted(),
            countriesVisited    = allCountries.sorted(),
            countryCodes        = allCodes.sorted(),
            longestTrip         = longestTrip,
            longestSession      = longestSession,
            topSpeedRecord      = topSpeedRecord,
            mostElevationRecord = mostElevation,
            bestCo2Record       = bestCo2,
            mostCaloriesRecord  = mostCalories,
            firstTripDate       = firstTripDate,
            totalSessions       = sessions.size
        )
    }

    private fun empty() = TravelRecap(
        citiesVisited = emptyList(), countriesVisited = emptyList(), countryCodes = emptyList(),
        longestTrip = null, longestSession = null, topSpeedRecord = null,
        mostElevationRecord = null, bestCo2Record = null, mostCaloriesRecord = null,
        firstTripDate = null, totalSessions = 0
    )

    companion object { private const val TAG = "TravelRecapService" }
}
