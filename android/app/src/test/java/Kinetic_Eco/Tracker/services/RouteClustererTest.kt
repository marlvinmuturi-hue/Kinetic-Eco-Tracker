package Kinetic_Eco.Tracker.services

import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.data.database.ActivityBreakdownEntity
import Kinetic_Eco.Tracker.data.database.TripEndpointRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the recurring-trip clustering that backs the premium "repeat journeys" card.
 *
 * This logic decides what the app tells a user about their own habits, so the risks are
 * reputational rather than crashy: merging two unrelated destinations, calling a
 * one-off a routine, or recommending a change that saves nothing all look plausible on
 * screen and quietly cost trust. The assertions below pin those cases specifically.
 */
class RouteClustererTest {

    // Nairobi-ish anchors, far enough apart to be unambiguous.
    private val homeLat = -1.2921
    private val homeLng = 36.8219
    private val workLat = -1.3200
    private val workLng = 36.8500

    private var seq = 0

    /** ~111 m per 0.001° of latitude, near enough for fixture offsets. */
    private fun metresNorth(m: Double) = m / 111_000.0

    private fun trip(
        startLat: Double = homeLat,
        startLng: Double = homeLng,
        endLat: Double = workLat,
        endLng: Double = workLng,
        distanceM: Double = 6_000.0,
        activity: ActivityType = ActivityType.DRIVING,
        date: String = "2026-08-0${(seq % 9) + 1}"
    ) = TripEndpointRow(
        id = "s${seq++}",
        date = date,
        startLat = startLat,
        startLng = startLng,
        endLat = endLat,
        endLng = endLng,
        totalDistance = distanceM,
        totalDuration = 900L,
        co2Conserved = 0.0,
        co2Emissions = 1.2,
        createdAt = 1_000L + seq,
        breakdown = mapOf(activity to ActivityBreakdownEntity(time = 900L, distance = distanceM))
    )

    private fun cluster(rows: List<TripEndpointRow>, lookbackDays: Int = 90) =
        RouteClusterer.clusterByOD(rows, lookbackDays, VehicleProfile.DEFAULT)

    // ── Grouping ─────────────────────────────────────────────────────────────

    @Test
    fun `identical journeys form one cluster`() {
        val result = cluster(List(4) { trip() })
        assertEquals(1, result.size)
        assertEquals(4, result.first().tripCount)
    }

    @Test
    fun `journeys within the match radius still group`() {
        // ~200 m apart at each end: same commute, different parking spot.
        val jittered = List(3) { i ->
            trip(
                startLat = homeLat + metresNorth(70.0 * i),
                endLat = workLat + metresNorth(70.0 * i)
            )
        }
        assertEquals(1, cluster(jittered).size)
    }

    @Test
    fun `a different destination does not merge into the same cluster`() {
        val commute = List(3) { trip() }
        // 5 km away — a different place entirely.
        val elsewhere = List(3) { trip(endLat = workLat + metresNorth(5_000.0)) }
        val result = cluster(commute + elsewhere)
        assertEquals(2, result.size)
        assertTrue(result.all { it.tripCount == 3 })
    }

    @Test
    fun `same destination from a different origin is a separate journey`() {
        val fromHome = List(3) { trip() }
        val fromGym = List(3) { trip(startLat = homeLat + metresNorth(4_000.0)) }
        assertEquals(2, cluster(fromHome + fromGym).size)
    }

    // ── Thresholds ───────────────────────────────────────────────────────────

    @Test
    fun `two trips is a coincidence, not a habit`() {
        assertTrue(cluster(List(2) { trip() }).isEmpty())
        assertEquals(1, cluster(List(3) { trip() }).size)
    }

    @Test
    fun `car-park jitter is excluded before it can cluster`() {
        val tiny = List(5) { trip(distanceM = 120.0) }
        assertTrue(cluster(tiny).isEmpty())
    }

    @Test
    fun `sessions with no GPS fix are dropped rather than clustered at null island`() {
        val noFix = List(5) {
            trip(startLat = 0.0, startLng = 0.0, endLat = 0.0, endLng = 0.0)
        }
        assertTrue(cluster(noFix).isEmpty())
    }

    // ── Cluster contents ─────────────────────────────────────────────────────

    @Test
    fun `dominant activity comes from cumulative distance, not trip count`() {
        // Two short drives, one long cycle — cycling covers the most ground.
        val rows = listOf(
            trip(activity = ActivityType.DRIVING, distanceM = 1_000.0),
            trip(activity = ActivityType.DRIVING, distanceM = 1_000.0),
            trip(activity = ActivityType.CYCLING, distanceM = 9_000.0)
        )
        assertEquals(ActivityType.CYCLING, cluster(rows).single().dominantActivity)
    }

    @Test
    fun `clusters are ordered by trip count descending`() {
        val frequent = List(6) { trip() }
        val occasional = List(3) { trip(endLat = workLat + metresNorth(5_000.0)) }
        val result = cluster(occasional + frequent)
        assertEquals(listOf(6, 3), result.map { it.tripCount })
    }

    // ── Suggestions ──────────────────────────────────────────────────────────

    @Test
    fun `a short drive is told to walk`() {
        val result = cluster(List(3) { trip(distanceM = 2_000.0) })
        assertEquals(ActivityType.WALKING, result.single().greenerAlternative?.suggestedMode)
    }

    @Test
    fun `a medium drive is told to cycle`() {
        val result = cluster(List(3) { trip(distanceM = 6_000.0) })
        assertEquals(ActivityType.CYCLING, result.single().greenerAlternative?.suggestedMode)
    }

    /** The regression that matters: never recommend transit we cannot verify exists. */
    @Test
    fun `a long drive gets no suggestion rather than a speculative train`() {
        val result = cluster(List(3) { trip(distanceM = 40_000.0) })
        assertNull(result.single().greenerAlternative)
    }

    @Test
    fun `already-green modes are left alone`() {
        listOf(ActivityType.WALKING, ActivityType.CYCLING, ActivityType.RUNNING, ActivityType.TRAIN)
            .forEach { mode ->
                val result = cluster(List(3) { trip(activity = mode, distanceM = 4_000.0) })
                assertNull("$mode should get no suggestion", result.single().greenerAlternative)
            }
    }

    @Test
    fun `savings scale with how often the journey is made`() {
        val thrice = cluster(List(3) { trip(distanceM = 6_000.0) }).single()
        val sixTimes = cluster(List(6) { trip(distanceM = 6_000.0) }).single()
        val a = thrice.greenerAlternative!!
        val b = sixTimes.greenerAlternative!!
        assertEquals(a.estimatedSavingsKgPerTrip, b.estimatedSavingsKgPerTrip, 1e-9)
        assertEquals(a.projectedAnnualSavingsKg * 2, b.projectedAnnualSavingsKg, 1e-6)
    }

    @Test
    fun `a suggestion is never offered unless it actually saves something`() {
        val results = cluster(List(3) { trip(distanceM = 6_000.0) })
        results.forEach { c ->
            c.greenerAlternative?.let {
                assertTrue("suggested ${it.suggestedMode} with no saving", it.estimatedSavingsKgPerTrip > 0.0)
            }
        }
    }

    @Test
    fun `the vehicle profile changes the quoted saving`() {
        val rows = List(3) { trip(distanceM = 6_000.0) }
        val defaultSaving = RouteClusterer
            .clusterByOD(rows, 90, VehicleProfile.DEFAULT)
            .single().greenerAlternative!!.estimatedSavingsKgPerTrip

        // An electric vehicle emits far less than the default car, so swapping to a
        // bicycle saves correspondingly less. If these ever match, the suggestion has
        // stopped reading the profile — the exact bug this replaced.
        val evSaving = RouteClusterer.suggestAlternative(
            dominant = ActivityType.ELECTRIC_VEHICLE,
            avgDistanceM = 6_000.0,
            tripCount = 3,
            lookbackDays = 90,
            profile = VehicleProfile.DEFAULT
        )?.estimatedSavingsKgPerTrip ?: 0.0

        assertTrue("expected a saving for the default car", defaultSaving > 0.0)
        assertTrue("EV should save less than a petrol car", evSaving < defaultSaving)
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    fun `haversine is symmetric and zero for a point against itself`() {
        assertEquals(0.0, RouteClusterer.haversineM(homeLat, homeLng, homeLat, homeLng), 1e-6)
        val ab = RouteClusterer.haversineM(homeLat, homeLng, workLat, workLng)
        val ba = RouteClusterer.haversineM(workLat, workLng, homeLat, homeLng)
        assertEquals(ab, ba, 1e-6)
        assertTrue("home→work should be a few km, got $ab m", ab in 1_000.0..10_000.0)
    }

    @Test
    fun `an empty history yields no clusters`() {
        assertTrue(cluster(emptyList()).isEmpty())
        assertNotNull(cluster(emptyList()))
    }

    // ── Money savings ────────────────────────────────────────────────────────

    private val prices = Kinetic_Eco.Tracker.data.EnergyPrices(
        currencyCode = "KES",
        petrolPerLitre = 200.0,
        dieselPerLitre = 180.0,
        electricityPerKwh = 25.0,
        source = Kinetic_Eco.Tracker.data.PriceSource.BUNDLED,
        effectiveMonth = "2026-08"
    )

    @Test
    fun `a money saving is quoted when prices are available`() {
        val alt = RouteClusterer.suggestAlternative(
            dominant = ActivityType.DRIVING,
            avgDistanceM = 6_000.0,
            tripCount = 12,
            lookbackDays = 90,
            profile = VehicleProfile.DEFAULT,
            prices = prices
        )!!
        assertEquals("KES", alt.currencyCode)
        assertTrue("expected a positive annual cost saving", alt.projectedAnnualSavingsCost!! > 0.0)
    }

    /** Without a price table there is no honest money figure — carbon still stands. */
    @Test
    fun `no prices means no money figure, but the carbon saving survives`() {
        val alt = RouteClusterer.suggestAlternative(
            dominant = ActivityType.DRIVING,
            avgDistanceM = 6_000.0,
            tripCount = 12,
            lookbackDays = 90,
            profile = VehicleProfile.DEFAULT
        )!!
        assertNull(alt.projectedAnnualSavingsCost)
        assertNull(alt.currencyCode)
        assertTrue(alt.projectedAnnualSavingsKg > 0.0)
    }

    @Test
    fun `money saving scales with how often the journey is made`() {
        fun costFor(trips: Int) = RouteClusterer.suggestAlternative(
            ActivityType.DRIVING, 6_000.0, trips, 90, VehicleProfile.DEFAULT, prices
        )!!.projectedAnnualSavingsCost!!
        assertEquals(costFor(6) * 2, costFor(12), 1e-6)
    }

    @Test
    fun `a measured economy changes the money quoted`() {
        val thirsty = RouteClusterer.suggestAlternative(
            ActivityType.DRIVING, 6_000.0, 12, 90, VehicleProfile.DEFAULT, prices,
            Kinetic_Eco.Tracker.data.MeasuredEconomy(15.0, 3, 150.0, 1000.0)
        )!!.projectedAnnualSavingsCost!!
        val frugal = RouteClusterer.suggestAlternative(
            ActivityType.DRIVING, 6_000.0, 12, 90, VehicleProfile.DEFAULT, prices,
            Kinetic_Eco.Tracker.data.MeasuredEconomy(5.0, 3, 50.0, 1000.0)
        )!!.projectedAnnualSavingsCost!!
        assertTrue("a thirstier car should save more by not driving", thirsty > frugal)
    }
}
