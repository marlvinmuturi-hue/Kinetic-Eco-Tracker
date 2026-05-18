package Kinetic_Eco.Tracker.services

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.hypot

/**
 * Looks up mapped passenger rail (OSM) near the GPS fix via the public Overpass API
 * and returns the shortest horizontal distance from the fix to any matching way geometry.
 *
 * Used to promote [Kinetic_Eco.Tracker.data.ActivityType.DRIVING] / EV → TRAIN when
 * speed and corridor criteria are met (see [TrackingService]).
 *
 * Network calls MUST run off the main thread. Returns **null** on failure or empty geometry
 * (caller treats unknown conservatively — no automatic train classification).
 */
object RailwayCorridorDetector {

    /** Primary public Overpass endpoint (HTTPS). */
    private val OVERPASS_URL = URL("https://overpass-api.de/api/interpreter")

    /** Pre-filter radius for Overpass (meters); real distance computed from geometry. */
    private const val OVERPASS_AROUND_METERS = 220

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 14_000

    /** Caps work when many parallel ways exist near city hubs. */
    private const val MAX_WAYS_TO_MEASURE = 96

    /**
     * Blocking network + JSON parse. Call from Dispatchers.IO only.
     *
     * @return minimum distance to any OSM railway way satisfying the query, or **null**
     *   if the request failed, timed out, or no geometry was returned.
     */
    fun fetchMinDistanceToRailMeters(lat: Double, lon: Double): Double? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val q = buildQuery(lat, lon)
        val body = "data=" + URLEncoder.encode(q, Charsets.UTF_8.name())
        var conn: HttpURLConnection? = null
        return try {
            conn = (OVERPASS_URL.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "KineticEcoTracker/1.0 (train-corridor-detection)")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return null
            val jsonText = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            if (jsonText.isBlank()) return null
            val root = JSONObject(jsonText)
            val elements = root.optJSONArray("elements") ?: return null
            parseMinDistanceMeters(lat, lon, elements)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * OpenStreetMap [railway](https://wiki.openstreetmap.org/wiki/Key:railway) values that
     * represent fixed-guideway passenger-heavy infrastructure (excludes miniature / abandoned
     * to reduce noise near mapping quirks).
     */
    private fun buildQuery(lat: Double, lon: Double): String = """
        [out:json][timeout:10];
        (
          way["railway"~"^(rail|subway|light_rail|tram|narrow_gauge|funicular)$"](around:$OVERPASS_AROUND_METERS,$lat,$lon);
        );
        out geom;
    """.trimIndent().replace("\n", "")

    internal fun parseMinDistanceMeters(lat: Double, lon: Double, elements: org.json.JSONArray): Double? {
        var best = Double.POSITIVE_INFINITY
        var count = 0
        val refLat = lat
        val refLon = lon
        val pxLon = projectedX(lat, lon, refLat, refLon)
        val pyLat = projectedY(lat, lon, refLat, refLon)
        val n = elements.length()
        for (i in 0 until n) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "way") continue
            val geom = el.optJSONArray("geometry") ?: continue
            val len = geom.length()
            if (len < 2) continue
            for (g in 0 until len - 1) {
                val a = geom.optJSONObject(g) ?: continue
                val b = geom.optJSONObject(g + 1) ?: continue
                val latA = a.optDouble("lat", Double.NaN)
                val lonA = a.optDouble("lon", Double.NaN)
                val latB = b.optDouble("lat", Double.NaN)
                val lonB = b.optDouble("lon", Double.NaN)
                if (!latA.isFinite() || !lonA.isFinite() || !latB.isFinite() || !lonB.isFinite()) continue
                val ax = projectedX(latA, lonA, refLat, refLon)
                val ay = projectedY(latA, lonA, refLat, refLon)
                val bx = projectedX(latB, lonB, refLat, refLon)
                val by = projectedY(latB, lonB, refLat, refLon)
                val d = distancePointToSegmentMeters(pxLon, pyLat, ax, ay, bx, by)
                if (d < best) best = d
            }
            count++
            if (count >= MAX_WAYS_TO_MEASURE) break
        }
        return if (best.isFinite() && best < Double.POSITIVE_INFINITY) best else null
    }

    /** Local equirectangular meters (good enough for sub-km segments). */
    private fun projectedX(lat: Double, lon: Double, refLat: Double, refLon: Double): Double {
        val R = 6371000.0
        return (lon - refLon) * Math.PI / 180.0 * R * kotlin.math.cos(Math.toRadians(refLat))
    }

    private fun projectedY(lat: Double, lon: Double, refLat: Double, refLon: Double): Double {
        val R = 6371000.0
        return (lat - refLat) * Math.PI / 180.0 * R
    }

    private fun distancePointToSegmentMeters(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val ab2 = abx * abx + aby * aby
        if (ab2 < 1e-6) return hypot(apx, apy)
        var t = (apx * abx + apy * aby) / ab2
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * abx
        val cy = ay + t * aby
        return hypot(px - cx, py - cy)
    }
}
