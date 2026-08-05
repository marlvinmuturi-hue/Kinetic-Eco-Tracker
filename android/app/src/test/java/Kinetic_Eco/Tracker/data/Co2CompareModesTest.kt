package Kinetic_Eco.Tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [Co2Calculator.compareModes], which backs the calculator's all-modes list.
 *
 * The ordering contract matters more than the arithmetic here: the UI renders the
 * list top-down as "what you should have done", and labels row deltas relative to the
 * user's pick. If the sort ever flipped, every delta sign in that card would read
 * backwards while still looking plausible.
 */
class Co2CompareModesTest {

    private val modes = listOf(
        ActivityType.DRIVING,
        ActivityType.ELECTRIC_VEHICLE,
        ActivityType.MOTORCYCLE,
        ActivityType.TRAIN,
        ActivityType.FLYING,
        ActivityType.CYCLING,
        ActivityType.WALKING,
        ActivityType.RUNNING
    )

    private fun compare(km: Double) =
        Co2Calculator.compareModes(km, VehicleProfile.DEFAULT, modes)

    @Test
    fun `returns one estimate per requested mode`() {
        val result = compare(10.0)
        assertEquals(modes.size, result.size)
        assertEquals(modes.toSet(), result.map { it.activity }.toSet())
    }

    @Test
    fun `sorted ascending by net impact - biggest saver first`() {
        val nets = compare(10.0).map { it.netKg }
        assertEquals(nets.sorted(), nets)
    }

    @Test
    fun `savers rank ahead of emitters`() {
        val result = compare(10.0)
        val lastSaverIndex = result.indexOfLast { it.isSaving }
        val firstEmitterIndex = result.indexOfFirst { it.netKg > 0.0 }
        assertTrue("expected at least one saver", lastSaverIndex >= 0)
        assertTrue("expected at least one emitter", firstEmitterIndex >= 0)
        assertTrue("savers must precede emitters", lastSaverIndex < firstEmitterIndex)
    }

    @Test
    fun `flying is the worst option and walking beats driving`() {
        val result = compare(10.0)
        assertEquals(ActivityType.FLYING, result.last().activity)
        val walking = result.indexOfFirst { it.activity == ActivityType.WALKING }
        val driving = result.indexOfFirst { it.activity == ActivityType.DRIVING }
        assertTrue("walking should rank above driving", walking < driving)
    }

    @Test
    fun `each entry matches a direct single-mode estimate`() {
        val km = 12.5
        compare(km).forEach { row ->
            val direct = Co2Calculator.estimate(row.activity, km, VehicleProfile.DEFAULT)
            assertEquals(
                "mismatch for ${row.activity}",
                direct.netKg,
                row.netKg,
                1e-9
            )
        }
    }

    @Test
    fun `distance scales impact linearly`() {
        val ten = compare(10.0).first { it.activity == ActivityType.DRIVING }
        val twenty = compare(20.0).first { it.activity == ActivityType.DRIVING }
        assertEquals(ten.netKg * 2, twenty.netKg, 1e-9)
    }

    @Test
    fun `zero distance yields no impact for any mode`() {
        assertTrue(compare(0.0).all { it.netKg == 0.0 })
    }

    @Test
    fun `an empty mode list yields an empty comparison`() {
        assertTrue(Co2Calculator.compareModes(10.0, VehicleProfile.DEFAULT, emptyList()).isEmpty())
    }
}
