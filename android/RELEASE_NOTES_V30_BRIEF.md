# V1.9.18 — versionCode 31 (brief)

Full detail: `RELEASE_NOTES.md`

## New in 31
- Settings leaderboard switch was completely unresponsive — fixed (three causes: a loading
  flag shared with fetches, no optimistic move, and the flag cleared in the wrong order).
- Settings now reads the stored opt-in preference; `loadLeaderboardOptIn` had no callers.
- A failed opt-in read no longer counts as "no preference" — that had been auto-opting
  opted-out users back onto the public leaderboard on offline cold starts.
- Tapping the switch signed-out now explains itself instead of silently doing nothing.
- Toggle verified on device; the read-failure branch is compile-verified only.

## Ship rules
- 31 is the only release to promote. Production is on 20; users jump 20 → 31.
- 21–30 were build/test iterations. Do not promote any of them.
- 23 crashes on launch (`PendingPurchasesParams` missing `enableOneTimeProducts()`).
- Room v8 → v9 has no downgrade fallback — irreversible once a device updates.
- Internal track first, then production as a staged rollout.
- Deploy `firestore:rules` + the two functions before users get the app.

## New for users
- Premium subscriptions: real paywall, Play's localized price, free trial when eligible.
- Purchases restore themselves on launch and sign-in; server-verified, never client-decided.
- Repeat-journey CO₂ savings corrected — were roughly double (25-journey cluster: ~146 → ~73 kg/yr).
- Permissions asked once, at the point of need — no prompt on cold start.
- Four languages complete: French, German, Spanish, Simplified Chinese (694 keys each).
- Interstitials: 30 min apart (was 3), hard cap 3/day, counters persisted to disk.
- Mobility Cost Calculator: litres and money alongside CO₂, cost leads.
- Monthly statement screen + push notification for subscribers.
- Billing-failure notifications during grace period, and on recovery.
- Editable owner-stated km/L in Settings.
- Region-aware pricing; picker no longer needs Firestore.

## Fixed
- `RouteClusterer.suggestAlternative` double-counted savings (`netKg` already nets `savedKg`).
- Cluster prices never applied — loading keyed on user/premium/profile, so prices arrived too late.
- Settings upsold premium to existing subscribers (fixed in 27).
- App Open ad fired when returning from the Play payment sheet.
- Settings version string was hardcoded `V1.5.0`; now `BuildConfig.VERSION_NAME`.

## Internal
- `billing-ktx:9.1.0`; entitlement written by `verifyPlayPurchase` Cloud Function.
- Acknowledgement server-side (client-side would refund users who closed the app).
- Purchases bound to Firebase uid via `setObfuscatedAccountId(sha256(uid))`.
- `playBillingRtdn` Pub/Sub handler for renewals, cancellations, expiries, holds, refunds.
- Grace period counts as entitled.
- Room v9 migration tests (4), `RouteClusterer` extracted and unit-tested (18).
- `kotlinx-serialization` forced to 1.8.1 app-wide.
- 15 Firestore rules tests passing.

## Untested
- Saving a newly tracked session — emulator cannot supply GPS velocity.
- In-place Play update on physical hardware.
- Sideloaded builds always fail billing with `BILLING_UNAVAILABLE`; test from a track install.

## Play Store "What's new" (en-US, under 500 chars)

```
✨ Premium is here — remove every ad in the app, unlock deeper AI insights into your commute, and support development. Subscribe from Settings → Go Premium.

🔁 Your subscription follows your account. Reinstall or switch phones and premium comes back on its own — no restore button to hunt for.

🔒 Purchases are verified on our servers, not on the device.
```

---

_versionName `V1.9.18`, versionCode `31`, targetSdk 36, minSdk 24. Artifact:
`app/build/outputs/bundle/release/app-release.aab`._