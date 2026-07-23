# Release Notes — V1.9.1 (versionCode 14)

## Play Store "What's new" (en-US)

> Paste this into the Play Console → Release → "Release notes". It fits Play's
> 500-character limit.

```
🏅 Achievement badges — earn Bronze to Diamond badges for CO₂ saved, distance, and green streaks. Reach a new milestone and it pops up to celebrate; tap any badge to enlarge it and share to your socials.

📤 Shareable trip recaps — post a branded card of your route, distance, and CO₂ saved.

🛠️ Fixes & polish — trip duration now resets correctly after an auto-stop, plus leaderboard and tracking stability improvements.
```

---

## Full changelog

### ✨ New
- **Achievement badges.** Earn tiered badges (Bronze → Silver → Gold → Diamond)
  for weekly CO₂ saved, eco distance covered, and consecutive green-streak days.
  - A celebration pop-up appears the first time you reach a new tier.
  - Tap any earned badge to open a magnified view and share it to social media as
    an image.
- **Shareable session recaps.** Share a branded impact card of a trip (route
  sketch, distance, time, activity, and CO₂ saved) straight to any app.

### 🔧 Improved
- Leaderboard loading and memory reliability.
- Background tracking and auto-start stability.

### 🐛 Fixed
- **Trip duration no longer lingers after an idle auto-stop** — it now resets to
  zero, matching the behaviour of stopping a trip manually.
- App-open ad initialization no longer runs off the main thread (would have
  crashed on launch once ad consent was granted).

### 🔒 Internal / under the hood (not shown to users)
- Added the App Open ad format (shown when returning to the app), gated behind
  UMP consent and suppressed during sign-in, store, share, and camera flows.
- Fixed the release build failing to compile due to a debug-only App Check
  provider reference (now loaded reflectively so it stays out of release).
- Migrated the AdMob application ID / App Open unit to the new publisher account;
  banner + interstitial units still need re-creating under that account.

---

_Build: versionName `V1.9.1`, versionCode `14`. Signed with the Kinetic Eco
Tracker upload key. Artifact: `app/build/outputs/bundle/release/app-release.aab`._