# Cloud Billing API 429 – Quota Exceeded

## What the error means

- **Service:** `cloudbilling.googleapis.com` (Google Cloud Billing API)
- **Error:** 429 = "Quota exceeded for … 'All requests per minute'"
- **Cause:** Your GCP project (or something in the chain) is calling the Billing API too many times per minute. Google then blocks further calls for a short time.

This is **not** the Gemini/generativelanguage API quota. It’s the separate Billing API rate limit.

---

## Immediate steps (no code change)

1. **Wait 1–2 minutes**  
   429 is a temporary rate limit. Stop triggering analysis, wait, then try once.

2. **Avoid rapid retries**  
   Don’t tap "Analyze" repeatedly. Use the app’s existing “1 analysis per hour” limit and wait if you see an error.

3. **Reduce concurrent use**  
   If several people (or devices) use AI Analysis at the same time, that can increase load. Stagger use if possible.

---

## If it keeps happening

4. **Check where the calls come from**  
   In [Google Cloud Console](https://console.cloud.google.com) → **APIs & Services** → **Dashboard**, see if **Cloud Billing API** is enabled and who might be calling it (e.g. Console, CLI, or another app). You don’t need to enable it for the Gemini API key used in the Cloud Function.

5. **Request a quota increase (optional)**  
   In **APIs & Services** → **Quotas & System Limits**, search for “Cloud Billing API” and see “All requests per minute”. You can request an increase if your use case needs it (often not needed for normal app usage).

6. **Use an API key for Gemini only**  
   Your Cloud Function already uses a Gemini API key (`functions.config().gemini.key`). That key should be from [Google AI Studio](https://aistudio.google.com/) and only hits `generativelanguage.googleapis.com`, not the Billing API. Don’t switch that call to a different client that uses the project’s default credentials (which can trigger Billing API checks).

---

## Summary

- **Immediate fix:** Wait 1–2 minutes, then retry once; avoid rapid or repeated analysis requests.
- **Longer term:** Rely on the app’s per-user rate limit and cache; avoid enabling or calling the Cloud Billing API unless you need it.
