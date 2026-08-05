# Play Subscriptions — setup and testing

The code for premium is complete: `BillingManager` launches the purchase,
`verifyPlayPurchase` checks it against the Play Developer API and writes
`users/{uid}/entitlements/premium`, `playBillingRtdn` keeps that document current,
and `EntitlementRepository` gates the three ad surfaces off it.

None of that does anything until the console work below is done. Every step here is
manual — no build upload creates a subscription product, which is why the Play
Console Subscriptions page reads "Your app doesn't have any subscriptions yet".

## 1. Create the product

Play Console → **Monetize → Products → Subscriptions → Create subscription**

- **Product ID: `premiumv2`** — must match `BillingManager.PREMIUM_PRODUCT_ID`
  exactly. It is permanent; a typo means creating a second product and abandoning
  the first, so the code follows the console, never the other way round. Note this
  is *not* the same string as the Firestore entitlement document name (`premium`),
  which is ours and unrelated.
- Add a **base plan**, set the price, and **activate it**. A base plan left in Draft
  is not returned by `queryProductDetailsAsync`, and the paywall will honestly report
  "Premium isn't available on this account yet".
- Optional: add an offer with a free trial. `BillingManager.bestOffer` prefers a
  trial the user is eligible for, so it needs no code change.

Requires a payments profile on the developer account. Without one the Monetize
section will not let you finish.

## 2. Enable the API

Google Cloud Console for the functions project (`gen-lang-client-0114974661`) →
**APIs & Services → Enable APIs → "Google Play Android Developer API" → Enable**.

## 3. Grant the functions service account access to Play

This is the step that is easy to miss and produces the most confusing failure — a
401/403 from `subscriptionsv2.get` that surfaces to the user as "Verification
failed. Please try again."

1. Note the runtime service account: `<project>@appspot.gserviceaccount.com`.
2. Play Console → **Users and permissions → Invite new users**, paste that address.
3. Grant, for this app: **View financial data** and **Manage orders and
   subscriptions**.
4. Permissions take a few minutes to propagate.

## 4. Wire real-time developer notifications

Renewals, cancellations, expiries, holds and refunds arrive here — without it a
cancelled subscriber keeps premium until their last known expiry, and a refunded one
keeps it entirely.

```bash
gcloud pubsub topics create play-billing-rtdn
# Let Play publish to it:
gcloud pubsub topics add-iam-policy-binding play-billing-rtdn \
  --member="serviceAccount:google-play-developer-notifications@system.gserviceaccount.com" \
  --role="roles/pubsub.publisher"
```

Play Console → **Monetize → Monetization setup → Real-time developer notifications**
→ topic name `projects/<project-id>/topics/play-billing-rtdn` → **Send test
notification**. A successful test logs
`✅ Received Play test notification — RTDN wiring is live`.

Also enable **voided purchase notifications** on the same page so refunds revoke
access.

## 5. Deploy

```bash
cd functions && npm install
firebase deploy --only functions:verifyPlayPurchase,functions:playBillingRtdn
firebase deploy --only firestore:rules   # adds the playPurchases deny rule
```

## 6. Test without paying

Play Console → **Setup → License testing** → add the tester Google accounts. License
testers get real purchase flows with test payment methods, renewals compressed to
minutes, and no charges.

Then:

- The tester must be opted into a track that contains this build (the closed track is
  fine) and must **install from Play**, not sideload. A sideloaded APK gets
  `BILLING_UNAVAILABLE` or an empty product list no matter how correct the console is.
- The app must be signed with the same key Play distributes — an internal-app-sharing
  or Play-signed build, not a local debug build.

Renewal cadence for testers: a monthly plan renews every ~5 minutes, up to 6 times.

## Debugging

**Always read the `Unfetched product '<id>': statusCode=N` line first** — it names the
cause directly, and the three values point at unrelated fixes:

| statusCode | Meaning | Fix |
|---|---|---|
| `2` `INVALID_PRODUCT_ID_FORMAT` | The id is malformed; rejected client-side, Play never contacted. Ids allow only `a-z`, `0-9`, `_`, `.` — **a hyphen is illegal** | Fix `PREMIUM_PRODUCT_ID` in the app |
| `3` `PRODUCT_NOT_FOUND` | Play has no such id for this app | Check the Product ID field in console |
| `4` `NO_ELIGIBLE_OFFER` | Id exists, but no offer this account can buy | Activate the base plan; check country pricing |

| Symptom | Cause |
|---|---|
| Paywall says "not available on this account yet" | Read the statusCode above first. Otherwise: base plan is Draft, or tester not opted into a track with this build |
| `BILLING_UNAVAILABLE` | Sideloaded build, or no Play Store on device |
| Purchase succeeds, ads stay | Verification failed — check `verifyPlayPurchase` logs for 401/403 (step 3) |
| Subscription auto-refunds after 3 days | Acknowledgement never happened; check `acknowledgeIfNeeded` logs |
| Cancellation doesn't revoke | RTDN not wired (step 4) |

Client logcat tags: `BillingManager`, `PlayPurchaseVerifier`, `EntitlementRepository`.

## What is deliberately not client-side

The client cannot grant itself premium. `firestore.rules` denies all client writes to
`entitlements/**`, the entitlement document is written only by the Admin SDK after a
Play Developer API check, and the debug override in `EntitlementRepository` is ignored
in release builds. Purchases are bound to a Firebase uid via
`setObfuscatedAccountId(sha256(uid))`, which the server re-checks, so a purchase token
cannot be replayed across accounts.
