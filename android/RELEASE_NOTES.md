# Release Notes — V1.9.7 (versionCode 20)

> **versionCode 20 is a rebuild of 19 with no functional change** — no app code
> differs between them, only the version number.
>
> Contents below cover everything since **versionCode 17 (V1.9.4)**, the last
> version known to be live on Play. versionCodes 18 and 19 were built but are not
> known to have been published; if either was, those entries were already
> delivered.

## Play Store "What's new" (en-US)

> Paste this into the Play Console → Release → "Release notes". It fits Play's
> 500-character limit.

```
🧮 New CO₂ calculator on the Analysis tab — check a trip before you take it. Pick a mode, enter a distance, and see the CO₂ and the energy it uses. Change the vehicle to compare.

🛟 Trips are no longer lost if Android shuts the app down mid-journey — your progress is saved as you go and picked back up.

🔕 Quieter tracking — starting a trip is silent now. Summaries and your daily update still chime.

📦 Smaller, faster app.
```

---

## Full changelog

### ✨ New
- **Manual CO₂ calculator** on the Analysis tab. Pick a mode, enter a distance,
  and get the CO₂ plus the vehicle energy used. Works offline and instantly —
  everything is computed on-device.
  - Uses your saved vehicle profile by default, so an entered trip matches a
    tracked one. The vehicle can be changed inline to compare options without
    touching your profile.
  - Shows the per-km rate alongside the total, so the number is checkable rather
    than magic.
  - Estimates only — nothing entered is saved to your sessions or totals.

### 🐛 Fixed
- **Trips are no longer lost when the app is killed mid-journey.** The session is
  now saved to disk as you travel and recovered when the app comes back, whether
  that is moments later or after a reboot. Previously an Android or
  battery-manager shutdown silently discarded the whole trip.
- **The "Electric vehicle?" prompt no longer waits forever** — it clears itself
  after a minute on screen, the same as tapping "Keep Driving".

### 🔧 Improved
- **The tracking notification is silent.** Starting a session and waiting for a
  GPS lock no longer play a sound or vibrate. The notification still shows live
  distance and time with Save and Discard inline; only the alert is gone.
  Session summaries, activity suggestions, and daily/weekly digests are
  unchanged and still audible.
- **Smaller download** — resource shrinking, optimized resource shrinking and
  class repackaging enabled.
- **Notification settings tidied** — superseded tracking channels from earlier
  versions are removed rather than left as dead duplicate entries.

### 🔒 Internal / under the hood (not shown to users)
- **Session checkpointing.** `TrackingService` returned `START_STICKY` while
  holding the entire session in memory with no persistence until Stop, so a kill
  mid-trip lost everything with no crash and no log. Snapshots now go to
  `filesDir` every 30 s and at each segment boundary, written atomically. Only
  accumulators are captured, never detector state — the Kalman filter and
  activity history are safe to cold-start. Recovery covers both a sticky restart
  (resume, if under 30 min old) and a process that stayed dead (salvage as a
  finished session at next launch).
- **Shared CO₂ calculation layer.** The emitted/saved split moved out of
  `SessionManager` into a dependency-free `Co2Calculator`, which the tracker and
  the calculator both call — a hand-entered 12 km drive and a tracked one now run
  identical code. 15 unit tests added; `app/src/test/` did not previously exist.
- **Explicit vehicle energy factors** (`whPerKm`) on the profile enums rather than
  back-deriving from CO₂, which would have compounded two unstated assumptions.
  Energy is always unsigned and zero for human-powered modes.
- **Premium entitlement groundwork.** Read-only entitlement model backed by
  Firestore, with all three ad surfaces (app-open, interstitial, banner) gated at
  request time. Play Billing is not wired yet; the gate is currently only
  reachable via a debug override. **`firestore.rules` must be deployed** for
  clients to read entitlements.
- **KSP2 migration**, unblocked by bumping Room 2.6.1 → 2.8.3. KSP1 is dropped by
  AGP 9.0 and Kotlin 2.3. No schema change, so no database migration.
- Lint baseline regenerated against compileSdk 36.

### ⚠️ Testing notes
- **Ship through internal or closed testing first.** Resource shrinking fails at
  runtime as `Resources$NotFoundException`, not at build time. Verified
  statically (no `getIdentifier()` calls in `app/src`; all osmdroid resources
  pinned reachable) and the app installs, launches and opens the Terms PDF
  cleanly on API 37 — but the osmdroid map screens have not been exercised from a
  shrunk build.
- **Not yet verified at runtime:** the calculator UI, the EV prompt timeout, the
  silent tracking channel, checkpoint recovery, and ad gating. All sit behind
  Firebase sign-in.
- Checkpoint recovery is worth testing explicitly: start a trip, let it run,
  `adb shell am kill com.kineticecotracker`, confirm it resumes with distance
  intact.

---

_Build: versionName `V1.9.7`, versionCode `20`, targetSdk 36, minSdk 24. Signed
with the Kinetic Eco Tracker upload key. Artifact:
`app/build/outputs/bundle/release/app-release.aab`._
