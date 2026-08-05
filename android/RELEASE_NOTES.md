# Release Notes — V1.9.15 (versionCode 28)

> **28 is the single release that carries everything. Production is on versionCode
> 20**, so users jump 20 → 28 in one step: Play Billing, the paywall, the
> week-window fix, the calculator changes, recurring-trip mining, Room schema v9 and
> kotlinx-serialization 1.8.1. versionCodes 21–27 were build/test iterations and
> none of them should be promoted — the notes below record why each was superseded.
>
> ⚠️ **The Room v8 → v9 migration makes this hard to reverse.** `AppDatabase` has no
> `fallbackToDestructiveMigrationOnDowngrade`, so once a device is on v9 an older
> build cannot open its database. Halting the rollout protects users who have not
> updated; it does not rescue those who have. **Ship internal first, then production
> as a staged rollout** — the percentage dial is the only brake available.
>
> Two things are untested at runtime: saving a newly tracked session (the emulator
> cannot supply GPS velocity, so no session ever completed), and this build on
> physical hardware updating in place from Play.

> ✅ **Purchases work end to end as of versionCode 26** — a real purchase completed on
> a Play-installed build, `verifyPlayPurchase` wrote the entitlement, and `isPremium`
> flipped. That also confirms the Play Console service-account grant and the Android
> Developer API enablement, neither of which could be verified until a purchase existed.
>
> **27 fixes the one thing that stayed wrong afterwards:** Settings still showed
> subscribers the "Go Premium — Upgrade" upsell. The card now reads "Premium is active
> / Thanks for supporting Kinetic. Ads are off across the app." with a **Manage**
> button. Entitlement is read inside `GoPremiumCard` rather than passed in, for the
> same reason `AdMobBanner` guards itself — a caller who forgot the check would go on
> selling premium to someone who already paid.

> **26 points the app at product id `premiumv2`.** The id has changed three times as
> the Play Console product was recreated: `premium` (21) → `premium-v1` (22–24) →
> `premium_v1` (25) → **`premiumv2` (26)**. Only 26 matches the live console product.
> Everything earlier is dead; the code follows the console, never the reverse.
>
> **The hyphen was the original bug.** Play ids permit only `a-z`, `0-9`, `_` and `.`,
> so `premium-v1` was rejected by the Billing Library locally and never reached Play
> at all. It was invisible because the old code read only
> `QueryProductDetailsResult.productDetailsList` and discarded `unfetchedProductList`,
> where Play had been reporting `StatusCode.INVALID_PRODUCT_ID_FORMAT` (2) the whole
> time. That diagnostic shipped in 25 and named the cause on its first run; the code
> then moved to `3` (`PRODUCT_NOT_FOUND`), confirming the query finally reached Play.
>
> Also in 26: **"Try again" no longer claims "No subscription found on this Google
> account."** That message belongs to Restore only — it is about *owned purchases*,
> not about whether the price could be fetched, and answering a price query with it
> sent the user off recreating a console product that was never at fault.

> 🚨 **versionCode 23 crashes on launch — do not ship it, and roll it back if it is
> live.** `PendingPurchasesParams.Builder.build()` throws
> `"Pending purchases for one-time products must be supported."` unless
> `enableOneTimeProducts()` is called, even in a subscriptions-only app. That call
> happens in `BillingManager.start()`, which runs in `Application.onCreate()`, so the
> process dies before any UI is drawn — a black screen, no recoverable state.
> 24 adds the missing call and additionally wraps billing setup in try/catch at both
> the manager and the Application call site, so no future billing SDK failure can
> take the app down at launch.

> **This is the release that makes premium purchasable.** versionCode 20 shipped
> the entitlement *reader* and the ad gates but contained no billing code at all —
> verified by grepping the shipped bundle's dex: zero `com/android/billingclient`
> references. Users on 20 or earlier cannot subscribe by any route.

> **versionCode 21 is superseded and must not be promoted.** It shipped the
> billing library but queried product id `premium`, while the subscription was
> actually created in Play Console as `premiumv2`. Play returns no product details
> for an id that does not exist, so 21's paywall can only ever say "Premium isn't
> available on this account yet". 22 corrected that one constant.
> Replace 21 in the track rather than leaving both active.

> **versionCode 22 and 23 are functionally identical** — 23 is a renumber with no
> code change, issued because a versionCode is permanently consumed once uploaded.
> Either one works; ship whichever Play accepts.

> ⚠️ **Do not publish the store copy below until the `premiumv2` subscription is
> live in Play Console.** The app degrades honestly if the product is missing (the
> paywall says "Premium isn't available on this account yet"), but advertising a
> subscription nobody can buy is worse than shipping quietly. See
> `Docs/PLAY_SUBSCRIPTIONS_SETUP.md` for the three console steps still outstanding.

## Play Store "What's new" (en-US)

> Paste into Play Console → Release → "Release notes". Within Play's 500-character
> limit.

```
✨ Premium is here — remove every ad in the app, unlock deeper AI insights into your commute, and support development. Subscribe from Settings → Go Premium.

🔁 Your subscription follows your account. Reinstall or switch phones and premium comes back on its own — no restore button to hunt for.

🔒 Purchases are verified on our servers, not on the device.
```

---

## Full changelog

### 🔍 New in versionCode 25
- **Play reports *why* a product could not be fetched.** Billing 8.0+ returns
  unfetched products with a per-product status code
  (`QueryProductDetailsResult.unfetchedProductList`); the previous code read only the
  success list and discarded it. The log now carries
  `Unfetched product 'premiumv2': statusCode=N`, which distinguishes a wrong product
  id from a draft base plan from a base plan not priced for the account's country —
  three console mistakes that were previously indistinguishable from each other and
  from an app bug.
- **Restore purchase and Try again now report what happened.** Both call the same
  `refresh()`, which had no user-visible output on any path: a spinner never appeared,
  and a restore that found nothing looked identical to a broken button. They now show
  a spinner while querying and a "No subscription found on this Google account"
  message when Play reports none. The automatic startup sweep stays silent, so cold
  starts still never fire a spurious snackbar.
- **Settings shows the real version.** `appVersion` was the string literal `"V1.5.0"`
  and had been wrong for every release since — actively misleading while verifying
  which build is installed. It now reads `BuildConfig.VERSION_NAME`.

### 🧪 Testing & dependencies
- **Room schema v8 → v9** adds denormalised route endpoints. Covered by
  `SessionMigrationTest` (`MigrationTestHelper`, 4 tests) which replays a real v8
  database and asserts no row is lost, the new columns arrive NULL, and
  `routePathJson` survives. Also verified by hand: a 1197-session database migrated
  with zero loss and no OOM.
- **`kotlinx-serialization` forced to 1.8.1 app-wide.** `room-testing:2.8.3`'s schema
  serializers are compiled against 1.8.1 while `kotlinx-serialization-bom:1.7.3`
  arrives transitively and pins it `strictly 1.7.3`; the mismatch killed
  `MigrationTestHelper` with `AbstractMethodError`. Forcing it on the test classpath
  alone was not enough — the test APK shares a classloader with the app APK — so this
  changes what the shipped app resolves. Nothing here uses kotlinx-serialization
  directly; it arrives only via Firebase. Smoke-tested: app builds, installs, launches,
  App Check initialises, no `AbstractMethodError`/`NoSuchMethodError`.
- **`RouteClusterer` extracted** from `RouteIntelligenceService` so clustering is
  testable on the JVM (18 tests). The service is now fetch-and-delegate.

### ✨ New
- **Premium subscriptions.** Settings → Go Premium now opens a real paywall
  instead of the Play Store listing. It shows Play's own localized price and
  currency, offers a free trial when the account is eligible for one, and shows
  the current plan and renewal date to anyone who already subscribes rather than
  pitching them something they own.
- **Purchases restore themselves.** Owned subscriptions are re-queried on every
  launch and every sign-in, so a reinstall, a new device, or a purchase that
  completed while the app was closed all resolve silently. The paywall still has a
  "Restore purchase" button because users look for one, but it should never be
  needed.

### 🔧 Improved
- **The App Open ad no longer fires when returning from the Play payment sheet** —
  it was the one path that could show an ad to a user in the middle of paying to
  remove ads.

### 🔒 Internal / under the hood (not shown to users)
- **`com.android.billingclient:billing-ktx:9.1.0`** added. Nothing declares the
  `com.android.vending.BILLING` permission by hand; the library's manifest merge
  contributes it.
- **Server-side verification.** `BillingManager` never decides entitlement — it
  posts the purchase token to a new `verifyPlayPurchase` Cloud Function, which
  checks it against the Play Developer API (`purchases.subscriptionsv2.get`) and
  writes `users/{uid}/entitlements/premium` with the Admin SDK. The existing
  `EntitlementRepository` listener picks it up unchanged. `firestore.rules` already
  denied client writes to entitlements; a test now proves it.
- **Acknowledgement is done server-side.** Play auto-refunds anything
  unacknowledged after three days, so acknowledging from the client would silently
  refund any user who closed the app at the wrong moment. The client keeps a
  fallback attempt for the case where the server verified but its own acknowledge
  call failed.
- **Purchases are bound to the Firebase uid** via
  `setObfuscatedAccountId(sha256(uid))`, re-checked server-side, plus a
  `playPurchases/{token}` → uid index. A purchase token cannot be replayed to
  unlock a second account.
- **New `playBillingRtdn` Pub/Sub handler** for renewals, cancellations, expiries,
  holds and refunds — the events that arrive while the app is closed. It re-reads
  full state from Play rather than trusting the notification type, because
  notifications arrive late, out of order and twice. Stale tokens cannot shorten an
  entitlement that a newer token extended; refunds explicitly can.
- **Grace period counts as entitled.** A subscriber whose card failed while Play is
  still retrying keeps access; cutting them off mid-retry turns a payment blip into
  a cancellation.
- `functions/package.json` gains `googleapis` ^174.0.0.
- Two new Firestore rules tests (15 total, all passing) covering the entitlement
  document and the new purchase index.

### ⚠️ Testing notes
- **Launch is verified on a real device** (Samsung SM-M366B, API 36). versionCode 24
  installs, cold-starts, and renders the full UI with zero fatals; `BillingManager`
  initialises and issues exactly one product query per cold start — no reconnect
  loop. This is the check that versionCode 23 never got, and would have caught it.
- **The purchase flow itself is still unexercised.** A debug-signed sideloaded build
  cannot fetch product details — Play only serves them to a build it distributed —
  so a sideloaded test cannot confirm the product resolves. That can only be
  validated from an internal/closed-track install.
- **Do not read an empty product list as a console problem again.** Check the
  `Unfetched product '<id>': statusCode=N` line first: `2` is a malformed id (client
  side, the app's fault), `3` is genuinely not found in Play, `4` means the id exists
  but has no offer this account is eligible for. Those three point at completely
  different fixes and previously looked identical.
- **Sideloading will not work.** A sideloaded APK always fails with
  `BILLING_UNAVAILABLE` regardless of how correct the console configuration is.
  Test with a license-tester account on a build installed from the closed track.
- **Three console steps gate everything** (full runbook in
  `Docs/PLAY_SUBSCRIPTIONS_SETUP.md`): create product `premiumv2` with an *activated*
  base plan; grant the functions service account "View financial data" + "Manage
  orders and subscriptions" in Play Console; create the `play-billing-rtdn` Pub/Sub
  topic and point RTDN at it. Each failure looks identical from the app — check
  console state before debugging code.
- Worth testing explicitly once live: buy as a license tester (monthly plans renew
  every ~5 minutes, six times), confirm ads disappear, cancel in Play and confirm
  the paywall switches from "Renews on" to "Access ends on", then confirm access
  actually ends at that date.
- **Deploy order matters:** deploy `firestore:rules` and the two functions *before*
  the app reaches users, or the first purchases will have nothing to verify against.

---

_Build: versionName `V1.9.13`, versionCode `26`, targetSdk 36, minSdk 24. Signed
with the Kinetic Eco Tracker upload key. Artifact:
`app/build/outputs/bundle/release/app-release.aab`._