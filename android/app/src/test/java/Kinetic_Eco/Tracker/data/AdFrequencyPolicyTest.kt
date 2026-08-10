package Kinetic_Eco.Tracker.data

import Kinetic_Eco.Tracker.data.AdFrequencyPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frequency rules for the interstitial.
 *
 * Worth testing properly rather than eyeballing: the previous implementation kept its
 * counters in a process singleton, so the cooldown quietly evaporated every time
 * Android killed the app — a bug that is invisible in a debug session and shows up as
 * "why does this app keep throwing ads at me" in a review.
 */
class AdFrequencyPolicyTest {

    private val now = 1_760_000_000_000L

    private fun decide(
        isPremium: Boolean = false,
        canRequestAds: Boolean = true,
        shownThisSession: Boolean = false,
        shownToday: Int = 0,
        lastShownMs: Long = 0L,
        nowMs: Long = now
    ) = AdFrequencyPolicy.decide(
        isPremium, canRequestAds, shownThisSession, shownToday, lastShownMs, nowMs
    )

    @Test
    fun `allows when nothing has been shown`() {
        assertEquals(Decision.ALLOW, decide())
    }

    @Test
    fun `premium never sees an interstitial`() {
        assertEquals(Decision.PREMIUM, decide(isPremium = true))
    }

    @Test
    fun `premium outranks every other gate`() {
        // A subscriber must not see one even in a state that would otherwise allow it.
        assertEquals(
            Decision.PREMIUM,
            decide(isPremium = true, shownToday = 0, lastShownMs = 0L)
        )
    }

    @Test
    fun `no ads without consent`() {
        assertEquals(Decision.NO_CONSENT, decide(canRequestAds = false))
    }

    @Test
    fun `one per tracking session`() {
        assertEquals(Decision.SESSION_CAP, decide(shownThisSession = true))
    }

    // ── Daily cap ────────────────────────────────────────────────────────────

    @Test
    fun `allows up to the daily cap`() {
        for (n in 0 until AdFrequencyPolicy.MAX_PER_DAY) {
            assertEquals("shownToday=$n", Decision.ALLOW, decide(shownToday = n))
        }
    }

    @Test
    fun `blocks at the daily cap`() {
        assertEquals(Decision.DAILY_CAP, decide(shownToday = AdFrequencyPolicy.MAX_PER_DAY))
    }

    @Test
    fun `blocks beyond the daily cap`() {
        assertEquals(Decision.DAILY_CAP, decide(shownToday = AdFrequencyPolicy.MAX_PER_DAY + 5))
    }

    @Test
    fun `daily cap outranks an expired cooldown`() {
        // Long past the cooldown, but the day's budget is spent.
        assertEquals(
            Decision.DAILY_CAP,
            decide(
                shownToday = AdFrequencyPolicy.MAX_PER_DAY,
                lastShownMs = now - 10 * AdFrequencyPolicy.COOLDOWN_MS
            )
        )
    }

    // ── Cooldown ─────────────────────────────────────────────────────────────

    @Test
    fun `blocks immediately after a show`() {
        assertEquals(Decision.COOLDOWN, decide(lastShownMs = now))
    }

    @Test
    fun `blocks one millisecond before the cooldown expires`() {
        assertEquals(
            Decision.COOLDOWN,
            decide(lastShownMs = now - AdFrequencyPolicy.COOLDOWN_MS + 1)
        )
    }

    @Test
    fun `allows exactly at the cooldown boundary`() {
        assertEquals(
            Decision.ALLOW,
            decide(lastShownMs = now - AdFrequencyPolicy.COOLDOWN_MS)
        )
    }

    @Test
    fun `blocks a trip finished ten minutes after the last ad`() {
        // The case that motivated the change: back-to-back errands.
        assertEquals(Decision.COOLDOWN, decide(lastShownMs = now - 10 * 60 * 1_000L))
    }

    @Test
    fun `allows a trip finished an hour later`() {
        assertEquals(Decision.ALLOW, decide(lastShownMs = now - 60 * 60 * 1_000L))
    }

    // ── Clock hazards ────────────────────────────────────────────────────────

    @Test
    fun `a clock moved backwards does not unlock an ad`() {
        // lastShown in the "future": timezone change, manual set, NTP correction.
        assertEquals(Decision.COOLDOWN, decide(lastShownMs = now + 60 * 60 * 1_000L))
    }

    @Test
    fun `never shown is not treated as shown at epoch`() {
        // lastShownMs = 0 must mean "never", not "shown in 1970 so cooldown passed"
        // — same outcome here, but for the right reason, and it keeps working if the
        // cooldown ever becomes relative to something else.
        assertEquals(Decision.ALLOW, decide(lastShownMs = 0L))
    }

    @Test
    fun `negative stored timestamp is treated as never shown`() {
        assertEquals(Decision.ALLOW, decide(lastShownMs = -1L))
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    fun `consent is checked before the session cap`() {
        assertEquals(
            Decision.NO_CONSENT,
            decide(canRequestAds = false, shownThisSession = true)
        )
    }

    @Test
    fun `a full day of short trips yields at most the daily cap`() {
        // Simulates the real complaint: many trips, well spaced, over one day.
        var shownToday = 0
        var lastShownMs = 0L
        var shows = 0
        // A trip every 20 minutes for 12 hours.
        for (i in 0 until 36) {
            val t = now + i * 20 * 60 * 1_000L
            val d = AdFrequencyPolicy.decide(
                isPremium = false,
                canRequestAds = true,
                shownThisSession = false,
                shownToday = shownToday,
                lastShownMs = lastShownMs,
                nowMs = t
            )
            if (d == Decision.ALLOW) {
                shows++
                shownToday++
                lastShownMs = t
            }
        }
        assertEquals(AdFrequencyPolicy.MAX_PER_DAY, shows)
    }
}
