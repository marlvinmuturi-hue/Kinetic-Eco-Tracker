package Kinetic_Eco.Tracker.services

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts GPS **ellipsoidal** altitude (height above the WGS84 ellipsoid, which is what
 * `Location.getAltitude()` returns per the Android contract) to **orthometric / mean-sea-level (MSL)**
 * altitude — the number that matches maps, signage, and everyday "height above sea level".
 *
 * The two differ by the *geoid separation* N (a.k.a. geoid undulation):
 *
 *     MSL = ellipsoidal − N
 *
 * N is a fixed, slowly-varying value for a given location (ranges roughly −107 m … +85 m worldwide),
 * so this correction fixes the systematic altitude *offset* — it does not affect elevation gain/loss
 * (the constant N cancels in a difference) or spike rejection (handled by AltitudeFilter).
 *
 * ## Data file (required to activate)
 * N comes from the EGM96 geoid model, distributed as a 15-arc-minute grid. This class reads the
 * canonical NGA binary **`WW15MGH.DAC`**, placed at:
 *
 *     app/src/main/assets/egm96-15.dac
 *
 * It is public-domain and freely downloadable (NGA / NOAA / GeographicLib mirrors). Until the file is
 * present, [geoidSeparation]/[toMeanSeaLevel] no-op (return null / pass the altitude through), so the
 * app behaves exactly as before — this feature is inert without the grid.
 *
 * ### `WW15MGH.DAC` format (fixed, header-less)
 *  - 721 rows × 1440 columns of signed 16-bit **big-endian** integers, units of **centimetres**.
 *  - Row 0 = 90°N, stepping −0.25° per row to 90°S (row 720).
 *  - Column 0 = 0°E, stepping +0.25° per column to 359.75°E (column 1439).
 *  - Total size: 721 × 1440 × 2 = 2,076,480 bytes.
 *
 * A [geoidSeparation] lookup does bilinear interpolation between the four surrounding grid nodes,
 * wrapping longitude and clamping latitude at the poles.
 */
class GeoidService(private val context: Context) {

    @Volatile private var grid: ShortArray? = null
    /** True once a load has been attempted (success or failure), so we don't retry the missing asset every fix. */
    @Volatile private var loadAttempted = false

    /** True once the geoid grid is loaded and lookups will return real values. */
    val isAvailable: Boolean get() = grid != null

    /**
     * Load the grid from assets if not already attempted. Safe to call repeatedly and from any thread;
     * the ~2 MB read happens at most once. Call off the main thread (it does file I/O).
     */
    @Synchronized
    fun ensureLoaded() {
        if (loadAttempted) return
        loadAttempted = true
        try {
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val expected = ROWS * COLS * 2
            if (bytes.size != expected) {
                Log.w(TAG, "Geoid grid size ${bytes.size} != expected $expected — ignoring (wrong file?)")
                return
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
            val arr = ShortArray(ROWS * COLS)
            buf.get(arr)
            grid = arr
            Log.d(TAG, "Geoid grid loaded ($ROWS×$COLS, ${bytes.size} bytes) — MSL altitude correction active")
        } catch (e: java.io.FileNotFoundException) {
            Log.d(TAG, "No geoid grid at assets/$ASSET_NAME — altitude stays ellipsoidal (correction inactive)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load geoid grid — altitude stays ellipsoidal", e)
        }
    }

    /**
     * Geoid separation N (metres) at [lat]/[lon], or null when the grid is unavailable. Triggers a
     * one-time lazy load on first call.
     */
    fun geoidSeparation(lat: Double, lon: Double): Double? {
        if (!loadAttempted) ensureLoaded()
        val g = grid ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null

        val clampedLat = lat.coerceIn(-90.0, 90.0)
        var normLon = lon % 360.0
        if (normLon < 0) normLon += 360.0

        // Fractional grid coordinates. Rows run north→south from 90°N.
        val rowF = (90.0 - clampedLat) / STEP_DEG
        val colF = normLon / STEP_DEG

        val row0 = rowF.toInt().coerceIn(0, ROWS - 1)
        val row1 = (row0 + 1).coerceAtMost(ROWS - 1)
        val col0 = colF.toInt().coerceIn(0, COLS - 1)
        val col1 = (col0 + 1) % COLS   // wrap 359.75° → 0°

        val fRow = (rowF - row0).coerceIn(0.0, 1.0)
        val fCol = (colF - col0).coerceIn(0.0, 1.0)

        // Grid stores centimetres → metres.
        val v00 = g[row0 * COLS + col0] / 100.0
        val v01 = g[row0 * COLS + col1] / 100.0
        val v10 = g[row1 * COLS + col0] / 100.0
        val v11 = g[row1 * COLS + col1] / 100.0

        val top = v00 + (v01 - v00) * fCol
        val bottom = v10 + (v11 - v10) * fCol
        return top + (bottom - top) * fRow
    }

    /**
     * Convert an ellipsoidal altitude to MSL using the geoid separation at [lat]/[lon]. Returns the
     * input unchanged when the grid is unavailable, so callers need no branching.
     */
    fun toMeanSeaLevel(ellipsoidalAltitude: Double, lat: Double, lon: Double): Double {
        val n = geoidSeparation(lat, lon) ?: return ellipsoidalAltitude
        return ellipsoidalAltitude - n
    }

    companion object {
        private const val TAG = "GeoidService"
        private const val ASSET_NAME = "egm96-15.dac"
        private const val ROWS = 721
        private const val COLS = 1440
        private const val STEP_DEG = 0.25  // 15 arc-minutes
    }
}