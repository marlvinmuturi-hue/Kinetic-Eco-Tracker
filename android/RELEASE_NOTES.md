# Release Notes — V1.9.19 (versionCode 32)

> **32 is the single release that carries everything. Production is on versionCode
> 20**, so users jump 20 → 32 in one step: Play Billing, the paywall, the
> week-window fix, the calculator changes, recurring-trip mining, Room schema v9 and
> kotlinx-serialization 1.8.1, plus fuel-and-cost figures in the calculator.
> versionCodes 21–31 were build/test iterations and
> none of them should be promoted — the notes below record why each was superseded.
>
> **32 reverts the frosted-glass appearance that shipped in 31** and returns the app to
> its previous look. The leaderboard fixes 31 introduced are kept in full. 31 was
> uploaded, so its versionCode is permanently consumed and cannot be reused.
>
> **31 superseded 30, which reached an internal track but not production.** It fixed a
> Settings leaderboard toggle that could not be operated at all, and an auto-opt-in that
> could put an opted-out user back on the public board. Everything 30 carried is carried
> here unchanged.
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

### ↩️ New in versionCode 32 — the previous look is back

- **The frosted-glass appearance introduced in 31 is reverted.** `Glass.kt` is deleted, the
  `dev.chrisbanes.haze:haze:0.7.3` dependency is dropped, and `Theme.kt`, the six screens
  and the three card components return to exactly their versionCode 30 source.
- **Nothing else from 31 is lost.** The leaderboard toggle fixes and the opt-in-status fix
  are kept in full; the only surviving change in `SettingsScreen` is the two-line
  `leaderboardOptInBusy` rename those fixes depend on.
- Verified by compiling with no Haze on the classpath, so a missed import would have failed
  the build rather than surfacing later at runtime.
- 31 was uploaded to Play, so its versionCode is spent. This is 32 rather than a re-upload.

### 🔘 New in versionCode 31 — the leaderboard toggle works, and respects "off"

**The Settings leaderboard switch could not be operated.** Tapping it did nothing at all:
it never moved and never showed an error, which is indistinguishable from a dead control.
Three independent causes, all fixed:
- The switch disabled itself on `leaderboardLoading`, a flag shared with leaderboard
  *fetches*. `loadLeaderboard` reads the whole `leaderboard` collection plus a reactions
  subcollection per row, and fires on login and from the dashboard — so a background fetch
  held the switch disabled. It now keys off a dedicated `leaderboardOptInBusy` that covers
  only the opt-in/opt-out write.
- The switch waited for success before moving, and `optIn` aggregates the user's entire
  Firestore session history before writing the flag. It now moves immediately and reverts
  only if the write fails.
- `_leaderboardLoading` was cleared *before* `loadLeaderboard` was called, which
  immediately re-raised it — so the control died a second time right after the tap.

**Settings now reads the stored preference.** `loadLeaderboardOptIn` had no callers anywhere
in `app/src/main`; the switch rendered whatever `autoOptInOnLogin` last left in memory, with
an unresolved `null` collapsed to "off". An opted-in user could be shown an off switch.

**A failed read no longer opts you into a public leaderboard.** `getOptInStatus` returned
`Boolean?` and mapped read errors onto `null` — the same value meaning "no preference ever
set". `autoOptInOnLogin` treated that as a new account and opted the user in, so a cold start
without connectivity could republish someone who had explicitly opted out. It now returns
`Result<Boolean?>`, and auto-opt-in requires positive evidence the preference is absent; a
failed read changes nothing and retries next launch. Seen on device: "Failed to get document
because the client is offline" at 09:01:47 followed by "Leaderboard opt-in complete" at
09:01:54.

**A tap with no signed-in uid** was silently swallowed by `uid?.let { … }`. It now says
"Please sign in to change your leaderboard preference."

**Testing:** the toggle is verified on a real device (SM-M366B, API 36) — opt-in and opt-out
both reached Firestore, confirmed in logcat. The read-failure branch is **not** verified on
device: Firestore serves `get()` from its local cache, so airplane mode cannot reproduce the
failure once the document has been cached. That branch is reasoned and compiled only.

### 🩹 New in versionCode 30 — corrected savings, fewer prompts, four languages

**Repeat-journey CO₂ savings were roughly double. Fixed.**
- `RouteClusterer.suggestAlternative` subtracted the alternative's `netKg` from the
  current mode's. `netKg` is `emittedKg - savedKg`, and a human-powered mode's
  `savedKg` *is* the emissions it avoids versus a car baseline — so the same
  kilograms were counted twice. A 3.8 km drive scored +0.80 and cycling it scored
  −0.80, giving a 1.60 kg "saving" for 0.80 kg of avoided driving.
- It now compares emissions with emissions. A 25-journey cluster drops from about
  146 to about 73 kg CO₂/year. **The money figure was always right**, being derived
  from the fuel bill alone, which is exactly how the error was caught: the two
  numbers appeared in one sentence and disagreed.
- Shipped in 29, so premium users have been shown inflated savings. Three tests now
  pin it, including one asserting that litres implied by the money match litres
  implied by the energy model. Nothing compared those two figures before.
- Cluster prices were also never applied: the Analysis screen keyed cluster loading
  on user/premium/profile only, and prices arrive asynchronously, so clustering
  baked in `prices = null` and every cost stayed null.

**Permissions are asked once, at the point of need.**
- `MainActivity.onCreate` requested location and background location on every cold
  start, so a new user was asked twice — once in onboarding, once on landing — and
  anyone who declined was re-asked every launch. There is now no prompt on launch.
- The Start button asks instead, and begins tracking on grant.
- Background location follows the foreground answer rather than racing it.
- The battery/background reliability dialog is shown once ever, not once per launch.
- Android still requires a separate step for "all the time" location; that is the OS.

**Four languages completed.**
- 371 strings translated into French, German, Spanish and Simplified Chinese; all
  four locales now complete at 694 keys. The monthly statement, fuel log, premium
  screens, pricing region and calculator were previously English-only.

**Fewer interstitials.**
- 3 minutes → 30 minutes between ads, a hard cap of 3 per day, and the counters
  moved to disk. They lived in a process singleton, so Android killing the app
  between trips reset them and the old limit barely applied.

**Also in this release**
- Owner-stated km/L is editable in Settings, so the statement and repeat journeys
  can use your car rather than an engine-band average.
- The calculator leads with cost and is renamed the Mobility Cost Calculator.
- Monthly statement screen and its push notification for premium subscribers.
- Billing failures now notify: a payment problem in grace period, and again when it
  recovers. Previously premium evaporated silently after Play stopped retrying.
- A one-off notice when two brim-full fill-ups first yield a measured economy.
- Region-aware pricing: currency follows the selected country, and the picker no
  longer depends on Firestore being reachable.
- Analysis tab: the recent-sessions card is replaced by a button on the weekly
  report, which owns the same week window.

### 💰 New in versionCode 29 — fuel and cost in the calculator
- **Litres and money alongside CO₂.** Entering a distance now also shows the fuel
  volume and what it costs, for the modes where the user actually buys the energy.
  `MobilityCostCalculator` converts `Co2Estimate.energyWh` via lower heating values
  (petrol 9.7 kWh/L, diesel 10.7 kWh/L). Sanity check: 880 Wh/km for a 1.8–2.5 L car
  works out to 9.1 L/100 km, and a test keeps that in the 7–12 range so a future
  change to the energy model cannot silently produce nonsense litres.
- **Prices resolve user → published → seed.** `EnergyPriceRepository` prefers a price
  the user typed, then `energyPrices/{region}` in Firestore (refreshed monthly from
  EPRA's published maximum pump prices and the Kenya Power tariff), then a compiled-in
  seed so a first launch offline still shows something. Overrides are per-fuel, so
  correcting petrol does not freeze the electricity tariff.
- **Every figure carries its provenance** — source and effective month, always. A
  price with neither is indistinguishable from a guess, and a stale one produces
  confidently wrong money.
- **Train and flying are given no cost.** The user pays a fare, not a share of the
  vehicle's energy bill; quoting the latter would be wrong by an order of magnitude.
  Walking, running and cycling are likewise silent rather than "0".
- **Everything is labelled an estimate.** Driving consumption still comes from an
  engine-displacement band — a class average that two cars in the same band can miss
  by 30%. A CO₂ figure that is 30% out goes unnoticed; a shilling figure that is 30%
  out gets checked against a fuel receipt and takes the credibility of every other
  number with it. The label goes away when a measured fuel economy replaces the class
  average (fuel log, not in this release).
- `firestore.rules` gains a signed-in-read / no-client-write rule for `energyPrices`,
  so nobody can make fuel look free.

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

_Build: versionName `V1.9.19`, versionCode `32`, targetSdk 36, minSdk 24. Signed
with the Kinetic Eco Tracker upload key. Artifact:
`app/build/outputs/bundle/release/app-release.aab`._