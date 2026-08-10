package Kinetic_Eco.Tracker.services

import android.content.Context
import android.location.Geocoder
import android.telephony.TelephonyManager
import android.util.Log
import Kinetic_Eco.Tracker.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Works out which country's energy prices apply to this user.
 *
 * Ordered by how much each signal actually knows, not by how easy it is to read:
 *
 *  1. **A country the user picked.** Explicit beats inferred, always.
 *  2. **The SIM / carrier country.** Needs no permission, survives someone setting
 *     their phone language to English, and is right for the overwhelming majority.
 *  3. **Where they actually drove**, reverse-geocoded from a stored trip endpoint. The
 *     truest signal, but it needs a tracked session to exist first.
 *  4. **Device locale.** Last resort — plenty of people run en-US on a phone that has
 *     never left Nairobi, which is exactly how this app came to quote Kenyan shillings
 *     to everyone.
 *
 * Returns an empty string when nothing is knowable. Callers must treat that as "no
 * prices" rather than substituting a default country.
 */
object CountryResolver {

    private const val TAG = "CountryResolver"

    suspend fun resolve(context: Context, userId: String? = null): String {
        val ctx = context.applicationContext

        UserPreferencesManager(ctx).getCountryOverride()?.let { return it }

        simCountry(ctx)?.let { return it }
        gpsCountry(ctx, userId)?.let { return it }

        return runCatching { Locale.getDefault().country.uppercase(Locale.US) }
            .getOrDefault("")
            .takeIf { it.length == 2 } ?: ""
    }

    /**
     * Carrier country. `simCountryIso` is the home network of the SIM, which is what we
     * want — a Kenyan roaming in France still buys fuel on a Kenyan salary and, more to
     * the point, `networkCountryIso` would flip mid-holiday and churn their prices.
     */
    private fun simCountry(context: Context): String? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.simCountryIso?.uppercase(Locale.US)?.takeIf { it.length == 2 }
    } catch (e: Exception) {
        Log.w(TAG, "SIM country unavailable", e)
        null
    }

    /**
     * Country of the most recent trip, from the denormalised endpoints.
     *
     * Uses stored coordinates rather than a live location request: no permission
     * prompt, no GPS wake-up, and it works the instant the app opens. Geocoding is a
     * network call, so this only runs when the SIM told us nothing — a tablet, a
     * dual-SIM oddity, or an eSIM-less device.
     */
    private suspend fun gpsCountry(context: Context, userId: String?): String? {
        if (userId.isNullOrBlank() || !Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getDatabase(context).sessionDao()
                val row = dao.getTripEndpoints(userId, 0L).firstOrNull() ?: return@withContext null
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault())
                    .getFromLocation(row.startLat, row.startLng, 1)
                    ?.firstOrNull()
                    ?.countryCode
                    ?.uppercase(Locale.US)
                    ?.takeIf { it.length == 2 }
            } catch (e: Exception) {
                Log.w(TAG, "Reverse geocode for country failed", e)
                null
            }
        }
    }
}