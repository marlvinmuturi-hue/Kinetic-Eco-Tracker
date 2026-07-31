package Kinetic_Eco.Tracker.data

/**
 * The user's premium entitlement, as written by the server.
 *
 * Lives at `users/{uid}/entitlements/premium` in Firestore and is **only ever
 * written by Cloud Functions via the Admin SDK** — the client can read its own
 * and nothing more (see `firestore.rules`). A client that can write its own
 * entitlement is a client that will, so the purchase token is verified against
 * the Play Developer API server-side and the result is pushed down here.
 *
 * Entitlement is keyed to the Firebase UID rather than the device, so multiple
 * devices and reinstalls resolve for free.
 */
data class Entitlement(
    /**
     * Play's view of the subscription state. Useful for UI copy ("cancels on
     * the 14th"), but **not** the access check — see [isEntitledAt]. A cancelled
     * subscriber is still entitled until their paid period runs out.
     */
    val active: Boolean = false,
    /** Play product id that granted this, e.g. "premium". Null when never subscribed. */
    val productId: String? = null,
    /** End of the paid period in epoch millis. This is what actually grants access. */
    val expiryMs: Long = 0L,
    /** False once cancelled or in account hold; drives "your plan ends on…" copy. */
    val willRenew: Boolean = false,
    /** Where it came from — "play" today, room for promo/comp grants later. */
    val source: String? = null,
    /** Server write time, for staleness diagnostics. */
    val updatedAtMs: Long = 0L
) {
    /**
     * Whether premium is unlocked at [nowMs].
     *
     * Deliberately keyed on [expiryMs] rather than [active]. Play keeps a
     * cancelled subscriber entitled until the period they already paid for ends,
     * and revoking early would be taking away something they bought. Refunds and
     * revocations arrive as a server-side expiry rewrite, not a client decision.
     */
    fun isEntitledAt(nowMs: Long = System.currentTimeMillis()): Boolean = expiryMs > nowMs

    companion object {
        /** Firestore document path segment under `users/{uid}/entitlements/`. */
        const val DOCUMENT_ID = "premium"
        /** Firestore subcollection name under `users/{uid}/`. */
        const val COLLECTION = "entitlements"

        val NONE = Entitlement()
    }
}