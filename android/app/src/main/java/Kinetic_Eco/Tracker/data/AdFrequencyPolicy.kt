package Kinetic_Eco.Tracker.data

/**
 * How often an interstitial may be shown.
 *
 * Pure and Android-free so the rules can be tested without a device. The frequency of
 * a full-screen ad is the single most user-hostile knob in the app, and "we think it
 * only fires twice a day" is not something worth believing without a test — especially
 * since the previous implementation was wrong in a way nobody could see (see
 * [Kinetic_Eco.Tracker.ui.components.InterstitialAdManager] on why the counters had to
 * move to disk).
 */
object AdFrequencyPolicy {

    /**
     * Minimum gap between two interstitials.
     *
     * Was 3 minutes, which in practice meant one ad per trip. Thirty minutes puts an
     * ad at the pace of a genuinely separate outing rather than a separate errand.
     */
    const val COOLDOWN_MS = 30 * 60 * 1_000L

    /** Hard ceiling per local day, however well-spaced the trips are. */
    const val MAX_PER_DAY = 3

    /** Why an ad was or was not allowed. Carried so the reason can be logged. */
    enum class Decision {
        ALLOW,
        PREMIUM,
        NO_CONSENT,
        SESSION_CAP,
        DAILY_CAP,
        COOLDOWN
    }

    /**
     * @param shownToday   count since local midnight
     * @param lastShownMs  wall-clock of the last show, 0 when never
     */
    fun decide(
        isPremium: Boolean,
        canRequestAds: Boolean,
        shownThisSession: Boolean,
        shownToday: Int,
        lastShownMs: Long,
        nowMs: Long
    ): Decision {
        if (isPremium) return Decision.PREMIUM
        if (!canRequestAds) return Decision.NO_CONSENT
        if (shownThisSession) return Decision.SESSION_CAP
        if (shownToday >= MAX_PER_DAY) return Decision.DAILY_CAP

        // Never shown: no cooldown to serve.
        if (lastShownMs <= 0L) return Decision.ALLOW

        val since = nowMs - lastShownMs
        // Negative means the clock moved backwards — a timezone change, a manual set,
        // or an NTP correction. Treat it as "too soon" rather than letting it unlock an
        // immediate ad, which is the direction that costs the user rather than us.
        if (since < 0L) return Decision.COOLDOWN
        if (since < COOLDOWN_MS) return Decision.COOLDOWN

        return Decision.ALLOW
    }
}
