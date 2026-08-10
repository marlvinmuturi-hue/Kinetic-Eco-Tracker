const functions = require('firebase-functions/v1');
const admin = require('firebase-admin');
const { GoogleGenerativeAI } = require('@google/generative-ai');

// Initialize Firebase Admin
admin.initializeApp();
const db = admin.firestore();

// Required after initializeApp: fcm.js calls admin.messaging() at send time, but
// keeping the require here documents the ordering dependency.
const { sendFcmWithRetry } = require('./fcm');

/** Firestore session.timestamp ms (number or Timestamp). */
function getTimestampMs(data) {
  const t = data.timestamp;
  if (t == null) return 0;
  if (typeof t.toMillis === 'function') return t.toMillis();
  return Number(t) || 0;
}

/** Rolling window: "7days", "30days", "14days", etc. Returns day count 1–366. */
function parseRollingDaysFromTimeframe(timeframe) {
  if (timeframe === '1day') return 1;
  const s = String(timeframe || '').trim();
  const m = /^(\d+)days$/.exec(s);
  if (m) {
    const n = parseInt(m[1], 10);
    return Math.min(366, Math.max(1, n));
  }
  const legacy = { '7days': 7, '30days': 30, '90days': 90 };
  return legacy[s] || 7;
}

/**
 * Simplified CO2 factors (kg per km), mirroring the Android app's baseline
 * defaults (CO2Factors / VehicleProfile.DEFAULT). Session docs only store
 * session-level CO2 totals, not a per-activity split, so these are used to
 * estimate a per-activity breakdown for the AI prompt's narrative context.
 * Negative = net saving vs. an average petrol car; positive = net emission.
 */
const ESTIMATED_CO2_KG_PER_KM = {
  IDLE: 0,
  WALKING: -0.21,
  RUNNING: -0.21,
  CYCLING: -0.17,
  MOTORCYCLE: 0.09,
  TRAIN: -0.17,
  DRIVING: 0.21,
  ELECTRIC_VEHICLE: 0.053,
  FLYING: 0.255
};

/** Average petrol passenger car baseline (IPCC/EEA reference), kg CO2 per km. */
const BASELINE_DRIVING_CO2_PER_KM = 0.21;

/** Calendar day yyyy-MM-dd; prefers sessionDateKey field (matches Android). */
function sessionDateKeyFromDoc(data) {
  if (data.sessionDateKey && typeof data.sessionDateKey === 'string') {
    const s = data.sessionDateKey.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
  }
  const ts = getTimestampMs(data);
  if (!ts) return null;
  return new Date(ts).toISOString().slice(0, 10);
}

// Gemini AI — key injected at runtime via Secret Manager (GEMINI_API_KEY secret).
// Lazy getter so the key is read after secrets are mounted, not at cold-start.
function getGenAI() {
  // Strip BOM (U+FEFF) that Windows editors add when saving secrets to files
  const apiKey = (process.env.GEMINI_API_KEY || '').replace(/^﻿/, '');
  return new GoogleGenerativeAI(apiKey);
}

// Global throttle: max Gemini analyses per minute per instance (reduces Billing API / quota pressure)
const ANALYSES_PER_MINUTE = 8;
const analysisTimestamps = [];
function throttleAnalysis() {
  const now = Date.now();
  const oneMinuteAgo = now - 60000;
  while (analysisTimestamps.length && analysisTimestamps[0] < oneMinuteAgo) analysisTimestamps.shift();
  if (analysisTimestamps.length >= ANALYSES_PER_MINUTE) {
    const retryAfter = Math.ceil((analysisTimestamps[0] + 60000 - now) / 1000);
    return retryAfter;
  }
  analysisTimestamps.push(now);
  return 0;
}

/**
 * Analyze user's activity data using Gemini AI
 * HTTP endpoint with manual authentication
 */
exports.analyzeActivity = functions.runWith({ secrets: ['GEMINI_API_KEY'] }).https.onRequest(async (req, res) => {
  // Enable CORS
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  
  if (req.method === 'OPTIONS') {
    res.status(204).send('');
    return;
  }

  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Method not allowed' });
    return;
  }

  try {
    // Step 1: Get and verify Authorization token
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      console.error('❌ Missing or invalid Authorization header');
      res.status(401).json({ error: 'Unauthorized: Missing auth token' });
      return;
    }

    const idToken = authHeader.split('Bearer ')[1];
    console.log('🔐 Verifying ID token...');
    
    // Verify the ID token using Firebase Admin SDK
    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(idToken);
      console.log(`✅ Token verified for user: ${decodedToken.uid}`);
    } catch (error) {
      console.error('❌ Token verification failed:', error.message);
      res.status(401).json({ error: 'Unauthorized: Invalid token' });
      return;
    }

    const userId = decodedToken.uid;
    // Support both HTTP (body.timeframe) and Callable (body.data.timeframe) payloads
    let timeframe = (req.body.data && req.body.data.timeframe) || req.body.timeframe || '7days';
    const locale = (req.body.data && req.body.data.locale) || req.body.locale || 'en';
    const sessionDateKey = ((req.body.data && req.body.data.sessionDateKey) || req.body.sessionDateKey || '').trim();
    // Device-only facts: vehicle, prices, measured economy, goal, timezone. Absent for
    // older clients, in which case the prompt simply omits those sections.
    const userContext = (req.body.data && req.body.data.context) || req.body.context || null;

    // Logged before the cache check, so a cache hit still tells us what the client
    // sent. Previously the only context logging sat after it, which meant two
    // consecutive cached runs revealed nothing about whether a fix had landed.
    if (userContext) {
      const t = Array.isArray(userContext.recurringTrips) ? userContext.recurringTrips : [];
      const costed = t.filter((x) => x.projectedAnnualSavingsCost != null).length;
      console.log(
        `Context: money=${userContext.money ? 'yes' : 'no'}, ` +
        `vehicle=${userContext.vehicle ? 'yes' : 'no'}, ` +
        `trips=${t.length} (${costed} with cost), ` +
        `measuredEconomy=${userContext.money && userContext.money.measuredLPer100Km ? 'yes' : 'no'}`
      );
    } else {
      console.log('Context: none (legacy client)');
    }
    // If a valid calendar day is sent, single-day analysis must win (ignore mistaken rolling timeframe from client).
    if (/^\d{4}-\d{2}-\d{2}$/.test(sessionDateKey)) {
      timeframe = '1day';
    }
    const effectiveCacheTimeframe =
      timeframe === '1day' && sessionDateKey ? `1day:${sessionDateKey}` : timeframe;
    // The prompt now depends on the vehicle, prices and goal, so those have to take
    // part in the cache key. Without this, changing car or correcting a fuel price
    // would keep serving yesterday's analysis for a full day.
    const contextKey = fingerprintContext(userContext);

    console.log(
      `Analyzing activity for user: ${userId}, timeframe: ${timeframe}, sessionDateKey: ${sessionDateKey || 'n/a'}, locale: ${locale}`
    );

    if (timeframe === '1day' && (!sessionDateKey || !/^\d{4}-\d{2}-\d{2}$/.test(sessionDateKey))) {
      res.status(400).json({ error: 'Single-day analysis requires sessionDateKey (yyyy-MM-dd)' });
      return;
    }

    // Step 2: Check rate limiting (5 analyses per hour per user)
    const ANALYSES_PER_HOUR = 5;
    const rateLimitDoc = await db.collection('analysisRateLimit').doc(userId).get();
    const hourAgo = Date.now() - (60 * 60 * 1000);
    let timestamps = [];
    if (rateLimitDoc.exists) {
      const data = rateLimitDoc.data();
      timestamps = (data.timestamps || (data.timestamp ? [data.timestamp] : [])).filter(t => t > hourAgo);
    }
    if (timestamps.length >= ANALYSES_PER_HOUR) {
      const oldestInWindow = Math.min(...timestamps);
      const minutesLeft = Math.ceil((oldestInWindow + 3600000 - Date.now()) / 60000);
      res.status(429).json({ 
        error: `Limit reached (5 per hour). Please wait ${minutesLeft} more minutes before requesting another analysis` 
      });
      return;
    }

    // Step 3: Check cache (24-hour TTL, keyed by timeframe + locale)
    const cacheDoc = await db.collection('analysisCache').doc(userId).get();
    if (cacheDoc.exists) {
      const cached = cacheDoc.data();
      const dayAgo = Date.now() - (24 * 60 * 60 * 1000);
      
      if (
        cached.timestamp > dayAgo &&
        cached.timeframe === effectiveCacheTimeframe &&
        cached.locale === locale &&
        (cached.contextKey || '') === contextKey
      ) {
        console.log('Returning cached analysis');
        res.status(200).json({
          ...cached.analysis,
          cached: true,
          cacheAge: Math.floor((Date.now() - cached.timestamp) / 1000 / 60) // minutes
        });
        return;
      }
    }

    // Step 3b: Global throttle (avoid Billing API / Gemini quota 429)
    const retryAfterSec = throttleAnalysis();
    if (retryAfterSec > 0) {
      console.warn('Global analysis throttle: too many requests this minute');
      res.set('Retry-After', String(retryAfterSec));
      res.status(429).json({
        error: 'Too many analysis requests. Please wait ' + retryAfterSec + ' seconds and try again.'
      });
      return;
    }

    // Step 4: Fetch user sessions from Firestore
    const sessions = await fetchUserSessions(userId, timeframe, sessionDateKey);
    
    if (!sessions || sessions.length === 0) {
      res.status(404).json({ 
        error: 'No activity data found for analysis. Please track some activities first!' 
      });
      return;
    }

    // Step 5: Calculate aggregate stats
    const tzOffsetMinutes = Number(userContext && userContext.timeZoneOffsetMinutes) || 0;
    const stats = calculateAggregateStats(sessions, tzOffsetMinutes);
    console.log(
      `Stats: ${stats.totalSessions} sessions, ${stats.dailySeries.length} active days, ` +
      `bestDay=${stats.bestDay ? stats.bestDay.date : 'n/a'}, ` +
      `context=${userContext ? 'yes' : 'none'}, money=${userContext && userContext.money ? 'yes' : 'no'}`
    );

    // Step 5b: Fetch the equal-length previous period for a CO2 trend comparison
    // (rolling windows only — a single day has no meaningful "previous period").
    let previousStats = null;
    if (timeframe !== '1day') {
      const windowDays = parseRollingDaysFromTimeframe(timeframe);
      const currentStartMs = Date.now() - windowDays * 24 * 60 * 60 * 1000;
      const previousStartMs = currentStartMs - windowDays * 24 * 60 * 60 * 1000;
      const previousSessions = await fetchUserSessionsInRange(userId, previousStartMs, currentStartMs);
      if (previousSessions.length > 0) {
        previousStats = calculateAggregateStats(previousSessions, tzOffsetMinutes);
      }
    }

    // Step 6: Build prompt for Gemini (locale: en/fr/de/es/zh)
    const prompt = buildAnalysisPrompt(stats, timeframe, locale, sessionDateKey, previousStats, userContext);

    // Step 7: Call Gemini API
    console.log('Calling Gemini API...');
    const model = getGenAI().getGenerativeModel({ model: 'gemini-2.5-flash' });
    const result = await model.generateContent(prompt);
    const responseText = result.response.text();
    console.log('Gemini response received:', responseText);
    
    // Step 8: Parse AI response (extract JSON from markdown if needed)
    let analysis;
    try {
      // Try to extract JSON from markdown code blocks
      const jsonMatch = responseText.match(/```json\n([\s\S]*?)\n```/);
      if (jsonMatch) {
        analysis = JSON.parse(jsonMatch[1]);
      } else {
        // Try direct parse
        analysis = JSON.parse(responseText);
      }
    } catch (parseError) {
      console.error('Failed to parse AI response:', responseText);
      console.error('Parse error:', parseError);
      res.status(500).json({ 
        error: 'Failed to parse AI response. Please try again.' 
      });
      return;
    }

    // Step 9: Update rate limit (append timestamp, keep last 5 in window)
    timestamps.push(Date.now());
    timestamps = timestamps.filter(t => t > hourAgo).slice(-ANALYSES_PER_HOUR);
    await db.collection('analysisRateLimit').doc(userId).set({
      timestamps: timestamps
    });

    // Step 10: Cache the result (keyed by timeframe + locale)
    await db.collection('analysisCache').doc(userId).set({
      analysis: analysis,
      timestamp: Date.now(),
      timeframe: effectiveCacheTimeframe,
      locale: locale,
      contextKey: contextKey
    });

    console.log('✅ Analysis complete successfully');
    res.status(200).json({
      ...analysis,
      cached: false
    });

  } catch (error) {
    console.error('❌ Error in analyzeActivity:', error);
    const msg = error.message || '';
    const is429 = msg.includes('429') || msg.includes('Quota exceeded') || msg.includes('resource-exhausted') || msg.includes('cloudbilling');
    if (is429) {
      res.set('Retry-After', '60');
      res.status(429).json({
        error: 'Analysis is rate-limited. Please wait a minute and try again.'
      });
    } else {
      res.status(500).json({
        error: 'An error occurred during analysis: ' + msg.substring(0, 200)
      });
    }
  }
});

/**
 * Fetch user sessions from Firestore
 */
async function fetchUserSessions(userId, timeframe, sessionDateKey) {
  if (timeframe === '1day' && sessionDateKey) {
    try {
      let snapshot = await db
        .collection('users')
        .doc(userId)
        .collection('sessions')
        .where('sessionDateKey', '==', sessionDateKey)
        .get();

      let list = snapshot.docs.map((doc) => ({ id: doc.id, ...doc.data() }));

      if (list.length === 0) {
        const wide = await db
          .collection('users')
          .doc(userId)
          .collection('sessions')
          .orderBy('timestamp', 'desc')
          .limit(500)
          .get();
        list = wide.docs
          .map((doc) => ({ id: doc.id, ...doc.data() }))
          .filter((s) => sessionDateKeyFromDoc(s) === sessionDateKey);
      }

      console.log(`Found ${list.length} sessions for single day ${sessionDateKey}`);
      return list;
    } catch (error) {
      console.error('Error fetching single-day sessions:', error);
      return [];
    }
  }

  const days = parseRollingDaysFromTimeframe(timeframe);
  const cutoffDate = new Date();
  cutoffDate.setDate(cutoffDate.getDate() - days);

  try {
    const snapshot = await db
      .collection('users')
      .doc(userId)
      .collection('sessions')
      .where('timestamp', '>=', cutoffDate.getTime())
      .orderBy('timestamp', 'desc')
      .get();

    if (snapshot.empty) {
      console.log('No sessions found for user');
      return [];
    }

    console.log(`Found ${snapshot.size} sessions`);
    return snapshot.docs.map((doc) => ({
      id: doc.id,
      ...doc.data()
    }));
  } catch (error) {
    console.error('Error fetching sessions:', error);
    return [];
  }
}

/**
 * Fetch sessions whose timestamp falls in [startMs, endMs) — used to pull the
 * prior equal-length window for CO2 trend comparisons.
 */
async function fetchUserSessionsInRange(userId, startMs, endMs) {
  try {
    const snapshot = await db
      .collection('users')
      .doc(userId)
      .collection('sessions')
      .where('timestamp', '>=', startMs)
      .where('timestamp', '<', endMs)
      .orderBy('timestamp', 'desc')
      .get();

    return snapshot.docs.map((doc) => ({ id: doc.id, ...doc.data() }));
  } catch (error) {
    console.error('Error fetching sessions in range:', error);
    return [];
  }
}

/**
 * Calculate aggregate statistics from sessions
 */
function calculateAggregateStats(sessions, tzOffsetMinutes = 0) {
  const stats = {
    totalSessions: sessions.length,
    totalDistance: 0,
    totalDuration: 0,
    totalCalories: 0,
    co2Emissions: 0,
    co2Conserved: 0,
    activityBreakdown: {
      IDLE: { count: 0, distance: 0, duration: 0 },
      WALKING: { count: 0, distance: 0, duration: 0 },
      RUNNING: { count: 0, distance: 0, duration: 0 },
      CYCLING: { count: 0, distance: 0, duration: 0 },
      MOTORCYCLE: { count: 0, distance: 0, duration: 0 },
      TRAIN: { count: 0, distance: 0, duration: 0 },
      DRIVING: { count: 0, distance: 0, duration: 0 },
      ELECTRIC_VEHICLE: { count: 0, distance: 0, duration: 0 },
      FLYING: { count: 0, distance: 0, duration: 0 }
    },
    activeDays: new Set(),
    avgDistance: 0,
    avgDuration: 0
  };

  sessions.forEach(session => {
    // Add to date set
    const date = new Date(session.timestamp).toDateString();
    stats.activeDays.add(date);

    // Aggregate totals
    stats.totalDistance += session.totalDistance || 0;
    stats.totalDuration += session.totalDuration || 0;
    stats.totalCalories += session.caloriesBurned || 0;
    stats.co2Emissions += session.co2Emissions || 0;
    stats.co2Conserved += session.co2Conserved || 0;

    // Activity breakdown
    if (session.breakdown) {
      Object.keys(session.breakdown).forEach(activity => {
        if (stats.activityBreakdown[activity]) {
          const distance = session.breakdown[activity].distance || 0;
          const duration = session.breakdown[activity].time || 0;
          // Count a session towards a mode only if that mode was actually used.
          //
          // Session documents carry a breakdown map with an entry for every mode,
          // zeroed for the ones that did not happen — so counting key presence made
          // every mode report the total session count. The prompt has been telling
          // the model "FLYING: 48 sessions, 0.00 km" for as long as this has existed,
          // and any average derived from that count was really a division by the
          // overall total wearing a per-mode label.
          if (distance <= 0 && duration <= 0) return;
          stats.activityBreakdown[activity].count++;
          stats.activityBreakdown[activity].distance += distance;
          stats.activityBreakdown[activity].duration += duration;
        }
      });
    }
  });

  stats.activeDaysCount = stats.activeDays.size;
  stats.avgDistance = stats.totalDistance / stats.totalSessions;
  stats.avgDuration = stats.totalDuration / stats.totalSessions;

  stats.dailySeries = buildDailySeries(sessions, tzOffsetMinutes);
  stats.timeOfDay = buildTimeOfDayBuckets(sessions, tzOffsetMinutes);
  stats.bestDay = pickBestDay(stats.dailySeries);

  // Estimate per-activity CO2 from aggregated distance using the simplified
  // factor table — session docs only carry session-level CO2 totals.
  Object.keys(stats.activityBreakdown).forEach((activity) => {
    const data = stats.activityBreakdown[activity];
    const factor = ESTIMATED_CO2_KG_PER_KM[activity] || 0;
    data.estCo2Kg = (data.distance / 1000) * factor;
    // Per-mode averages, computed here so the model never has to divide. Asked to
    // work out "minutes per walking session" it reached for the *total* session
    // count and produced "659 minutes / 48 sessions" — the arithmetic equivalent of
    // the fabricated bestDay, and fixed the same way: hand over the answer.
    data.avgDistanceKm = data.count > 0 ? data.distance / 1000 / data.count : 0;
    data.avgMinutes = data.count > 0 ? data.duration / 60 / data.count : 0;
  });

  return stats;
}

/** Local calendar day for a session, honouring the device's UTC offset. */
function localDayKey(session, tzOffsetMinutes) {
  // Prefer the key the app itself stored: it was computed on the device in the
  // user's own timezone at the moment of the trip, so it is right even if they
  // have since flown somewhere else.
  const stored = sessionDateKeyFromDoc(session);
  if (stored) return stored;
  const shifted = new Date((session.timestamp || 0) + tzOffsetMinutes * 60 * 1000);
  return shifted.toISOString().slice(0, 10);
}

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** Modes the user moves under their own power. */
const HUMAN_POWERED = new Set(['WALKING', 'RUNNING', 'CYCLING']);

/**
 * One row per day that had activity.
 *
 * This is the single biggest gap in what the model used to receive: everything was
 * pre-aggregated into one flat summary, so the AI could not say anything about *when*
 * a user moves. It was nonetheless asked for a "bestDay", which it had no choice but
 * to invent — see [pickBestDay].
 */
function buildDailySeries(sessions, tzOffsetMinutes) {
  const byDay = new Map();

  sessions.forEach((s) => {
    const key = localDayKey(s, tzOffsetMinutes);
    if (!byDay.has(key)) {
      byDay.set(key, {
        date: key,
        weekday: WEEKDAYS[new Date(key + 'T12:00:00Z').getUTCDay()],
        sessions: 0,
        distanceKm: 0,
        activeKm: 0,
        minutes: 0,
        co2SavedKg: 0,
        co2EmittedKg: 0,
        modes: new Set()
      });
    }
    const row = byDay.get(key);
    row.sessions++;
    row.distanceKm += (s.totalDistance || 0) / 1000;
    row.minutes += (s.totalDuration || 0) / 60;
    row.co2SavedKg += s.co2Conserved || 0;
    row.co2EmittedKg += s.co2Emissions || 0;
    if (s.breakdown) {
      Object.keys(s.breakdown).forEach((m) => {
        const d = s.breakdown[m].distance || 0;
        if (m !== 'IDLE' && d > 0) {
          row.modes.add(m);
          if (HUMAN_POWERED.has(m)) row.activeKm += d / 1000;
        }
      });
    }
  });

  return Array.from(byDay.values())
    .map((r) => ({ ...r, modes: Array.from(r.modes) }))
    .sort((a, b) => (a.date < b.date ? -1 : 1));
}

/**
 * Four six-hour windows in the user's local time, matching the buckets the Analysis
 * tab already draws so the AI and the chart cannot disagree.
 */
function buildTimeOfDayBuckets(sessions, tzOffsetMinutes) {
  const buckets = {
    '00-06': { sessions: 0, distanceKm: 0 },
    '06-12': { sessions: 0, distanceKm: 0 },
    '12-18': { sessions: 0, distanceKm: 0 },
    '18-24': { sessions: 0, distanceKm: 0 }
  };
  sessions.forEach((s) => {
    const localHour = new Date((s.timestamp || 0) + tzOffsetMinutes * 60 * 1000).getUTCHours();
    const key = localHour < 6 ? '00-06' : localHour < 12 ? '06-12' : localHour < 18 ? '12-18' : '18-24';
    buckets[key].sessions++;
    buckets[key].distanceKm += (s.totalDistance || 0) / 1000;
  });
  return buckets;
}

/**
 * The most active day, decided here rather than by the model.
 *
 * `highlights.bestDay` is rendered directly in the app, and the model was previously
 * never given per-day data — so every value it produced for this field was fabricated.
 * An argmax is not a judgement call; computing it and handing over the answer removes
 * a whole class of confident, checkable, wrong claims.
 */
function pickBestDay(dailySeries) {
  if (!dailySeries || dailySeries.length === 0) return null;
  // Ranked by CO2 saved, then by human-powered distance — NOT by raw distance. This
  // value is rendered under a "Wins" heading, and ranking on total distance made a
  // 23 km drive beat an 11 km walk, congratulating the user for the one thing the app
  // exists to discourage.
  return dailySeries.reduce((best, row) => {
    if (!best) return row;
    if (row.co2SavedKg > best.co2SavedKg) return row;
    if (row.co2SavedKg === best.co2SavedKg && row.activeKm > best.activeKm) return row;
    return best;
  }, null);
}

/**
 * Short fingerprint of the facts that change what the prompt says.
 *
 * Only the inputs that alter the analysis are included: session data is already
 * covered by the 24-hour TTL, but a new vehicle or a corrected fuel price must
 * invalidate immediately, or the user "fixes" their profile and sees the same stale
 * answer all day.
 */
/**
 * Bump whenever the prompt changes in a way that should invalidate cached answers.
 *
 * Analyses are cached for 24 hours, so without this a prompt fix keeps serving the old
 * text for a full day after deploy — which is how the broken "51.64 kg better than
 * baseline" line would have outlived its own fix.
 */
const PROMPT_VERSION = 'v5-recurring-trips';

function fingerprintContext(ctx) {
  if (!ctx) return `none-${PROMPT_VERSION}`;
  const v = ctx.vehicle || {};
  const m = ctx.money || {};
  const parts = [
    v.primaryFuel, v.iceFuel, v.engineCcBand, v.bodyType, v.kmPerLitre,
    m.currencyCode, m.petrolPerLitre, m.dieselPerLitre, m.electricityPerKwh,
    m.measuredLPer100Km, m.region,
    ctx.weeklyCo2GoalKg, ctx.unitSystem, ctx.timeZoneOffsetMinutes,
    // Includes the savings, not just the shape of each journey. Keyed on
    // count/mode alone, a cluster that gained a cost figure once prices resolved
    // produced an identical fingerprint — so the cache kept serving the kg-only
    // answer and the money never appeared however well the fix worked.
    (ctx.recurringTrips || [])
      .map((t) =>
        `${t.tripCount}:${t.currentMode}:${t.suggestedMode || ''}:` +
        `${t.projectedAnnualSavingsKg || ''}:${t.projectedAnnualSavingsCost || ''}`
      )
      .join(','),
    PROMPT_VERSION
  ].join('|');
  return require('crypto').createHash('sha1').update(parts).digest('hex').slice(0, 12);
}

/**
 * Compares current vs. an equal-length previous window and returns a
 * narrative-ready CO2 trend summary, or null when there's no prior data.
 */
function buildCo2Trend(stats, previousStats) {
  if (!previousStats || previousStats.totalSessions === 0) return null;
  const netNow = stats.co2Conserved - stats.co2Emissions;
  const netPrev = previousStats.co2Conserved - previousStats.co2Emissions;
  const deltaKg = netNow - netPrev;
  const pctChange = netPrev !== 0 ? (deltaKg / Math.abs(netPrev)) * 100 : null;
  return { netNow, netPrev, deltaKg, pctChange };
}

/**
 * Map locale code to language name for the prompt
 */
function getLanguageForLocale(locale) {
  const map = { en: 'English', fr: 'French', de: 'German', es: 'Spanish', zh: 'Chinese' };
  return map[locale] || 'English';
}

/**
 * Build analysis prompt for Gemini
 */
/**
 * Render the device-only facts the client posted as `context`.
 *
 * Sections are omitted when absent rather than filled with zeros. A user with no
 * vehicle set or no prices for their region yields fewer facts, and the model is told
 * nothing rather than something false — the same rule the cost UI follows.
 */
function buildUserContextSection(ctx) {
  if (!ctx) return '';
  const lines = [];

  if (ctx.vehicle) {
    const v = ctx.vehicle;
    const economy = v.kmPerLitre
      ? `${v.kmPerLitre} km/L as stated by the owner`
      : 'not stated (estimated from engine size)';
    lines.push(
      `**Their Vehicle**:
- Fuel: ${v.primaryFuel}${v.primaryFuel === 'ELECTRIC' ? '' : ` (${v.iceFuel})`}
- Engine band: ${v.engineCcBand}, body: ${v.bodyType}
- Fuel economy: ${economy}`
    );
  }

  if (ctx.money) {
    const m = ctx.money;
    const measured = m.measuredLPer100Km
      ? `${m.measuredLPer100Km.toFixed(1)} L/100 km, measured from ${m.measuredFromFillUps} of their own fill-ups`
      : 'no measured economy yet (they have not logged two full tanks)';
    lines.push(
      `**Energy Prices** (region ${m.region || 'unknown'}, source ${m.priceSource}):
- Petrol: ${m.currencyCode} ${m.petrolPerLitre}/L${m.dieselPerLitre ? `, diesel: ${m.currencyCode} ${m.dieselPerLitre}/L` : ''}
- Measured consumption: ${measured}
- You MAY quote costs in ${m.currencyCode}. Never quote a cost in any other currency.`
    );
  } else {
    lines.push(
      `**Energy Prices**: unknown for this user's region.
- Do NOT mention money, cost, savings in currency, or fuel spend anywhere in your response.`
    );
  }

  const trips = Array.isArray(ctx.recurringTrips) ? ctx.recurringTrips : [];
  if (trips.length > 0) {
    // Already clustered, costed and ranked on the device. The model's job is to
    // report and prioritise these, not to rediscover them — and it could not
    // rediscover them anyway, since it never sees individual trips.
    const rows = trips.map((t) => {
      const dist = Number(t.avgDistanceKm || 0).toFixed(1);
      if (!t.suggestedMode) {
        return `- ${t.tripCount}x by ${t.currentMode}, about ${dist} km each — no greener alternative suggested for this one`;
      }
      const kg = t.projectedAnnualSavingsKg != null
        ? `${Number(t.projectedAnnualSavingsKg).toFixed(1)} kg CO2/year`
        : null;
      const money = t.projectedAnnualSavingsCost != null && t.currencyCode
        ? `${t.currencyCode} ${Math.round(t.projectedAnnualSavingsCost).toLocaleString('en-US')}/year`
        : null;
      const saving = [kg, money].filter(Boolean).join(' and ');
      return `- ${t.tripCount}x by ${t.currentMode}, about ${dist} km each — switching to ${t.suggestedMode} would save ${saving || 'an unquantified amount'}`;
    });
    lines.push(
      `**Their Repeat Journeys** (detected from tracked routes; figures already calculated — quote them, do not recompute):
${rows.join('\n')}
- These are the most concrete recommendations available. Prefer them over generic advice.
- Locations are deliberately withheld; refer to a journey by its mode and distance.`
    );
  }

  if (ctx.weeklyCo2GoalKg > 0) {
    lines.push(`**Their Weekly CO2 Goal**: ${ctx.weeklyCo2GoalKg} kg saved per week`);
  }

  if (ctx.unitSystem) {
    lines.push(`**Units**: ${ctx.unitSystem} — express distances accordingly.`);
  }

  return lines.length ? `\n${lines.join('\n\n')}\n` : '';
}

function buildAnalysisPrompt(stats, timeframe, locale, sessionDateKey, previousStats, userContext) {
  const days = parseRollingDaysFromTimeframe(timeframe);
  const language = getLanguageForLocale(locale);
  const periodLabel =
    timeframe === '1day' && sessionDateKey
      ? `Single calendar day (${sessionDateKey}, session start dates as stored in the app)`
      : `Past ${days} days`;

  const totalKm = stats.totalDistance / 1000;
  const netCo2Kg = stats.co2Conserved - stats.co2Emissions;
  const baselineEmissionsKg = totalKm * BASELINE_DRIVING_CO2_PER_KM;
  // Compare emissions with emissions.
  //
  // This previously read `baselineEmissionsKg - netCo2Kg`, where netCo2Kg is
  // conserved-minus-emitted and so goes *negative* for anyone who drives more than
  // they walk. Subtracting a negative inflated the gap by twice their emissions, and
  // a user who drove 122 km was told they came in "51.64 kg better than driving" —
  // a claim contradicted by the same paragraph reporting 22 kg net emitted.
  const baselineDeltaKg = baselineEmissionsKg - stats.co2Emissions;
  const trend = buildCo2Trend(stats, previousStats);

  return `You are a fitness and health analyst. Analyze this user's activity data and provide insights.

**IMPORTANT**: Respond entirely in ${language}. All text in scoreReasoning, insights, recommendations, motivation, environmentalImpact, and highlights must be written in ${language}.

**Time Period**: ${periodLabel}

**Activity Summary**:
- Total Sessions: ${stats.totalSessions}
- Active Days: ${
    timeframe === '1day'
      ? '1 (single-day analysis)'
      : // Never phrased as a fraction. A rolling 7-day window starts mid-day and so
        // can touch 8 calendar dates, which rendered as "8 out of 7 days" — a figure
        // that is arithmetically fine and reads as a bug to the person seeing it.
        `${stats.activeDaysCount} distinct date(s) had activity within the last ${days} days`
  }
- Total Distance: ${(stats.totalDistance / 1000).toFixed(2)} km
- Total Duration: ${(stats.totalDuration / 3600).toFixed(1)} hours
- Calories Burned: ${Math.round(stats.totalCalories)} kcal
- CO2 Emissions: ${stats.co2Emissions.toFixed(2)} kg
- CO2 Conserved: ${stats.co2Conserved.toFixed(2)} kg
- Net CO2 Impact: ${netCo2Kg >= 0 ? `${netCo2Kg.toFixed(2)} kg saved overall` : `${Math.abs(netCo2Kg).toFixed(2)} kg net emitted overall`}

**Activity Breakdown** (CO2 figures are estimates derived from distance using standard per-km factors):
${Object.entries(stats.activityBreakdown)
  .filter(([_, data]) => data.count > 0)
  .map(([activity, data]) => {
    const co2Label = data.estCo2Kg !== 0
      ? `, ~${Math.abs(data.estCo2Kg).toFixed(2)} kg CO2 ${data.estCo2Kg < 0 ? 'saved' : 'emitted'}`
      : '';
    return (
      `- ${activity}: ${data.count} session(s) containing this mode, ` +
      `${(data.distance / 1000).toFixed(2)} km, ${(data.duration / 60).toFixed(0)} minutes${co2Label}` +
      ` — averaging ${data.avgDistanceKm.toFixed(2)} km and ${data.avgMinutes.toFixed(0)} min per session of this mode`
    );
  })
  .join('\n')}

**Average Per Session**:
- Distance: ${(stats.avgDistance / 1000).toFixed(2)} km
- Duration: ${(stats.avgDuration / 60).toFixed(0)} minutes

**Day-by-Day** (local dates; only days with activity appear — gaps are rest days):
${(stats.dailySeries || [])
  .map(
    (d) =>
      `- ${d.date} (${d.weekday}): ${d.sessions} session(s), ${d.distanceKm.toFixed(2)} km ` +
      `(${d.activeKm.toFixed(2)} km human-powered), ` +
      `${d.minutes.toFixed(0)} min, ${d.co2SavedKg.toFixed(2)} kg saved, ` +
      `${d.co2EmittedKg.toFixed(2)} kg emitted, modes: ${d.modes.join('/') || 'none'}`
  )
  .join('\n') || '- (no daily data)'}

**Time of Day** (local time, sessions by start hour):
${Object.entries(stats.timeOfDay || {})
  .map(([window, b]) => `- ${window}: ${b.sessions} session(s), ${b.distanceKm.toFixed(2)} km`)
  .join('\n')}
${
  stats.bestDay
    ? `
**Most Active Day (already computed — use this exact value, do not derive your own)**:
- ${stats.bestDay.date} (${stats.bestDay.weekday}) — ${stats.bestDay.co2SavedKg.toFixed(2)} kg CO2 saved, ${stats.bestDay.activeKm.toFixed(2)} km human-powered, ${stats.bestDay.distanceKm.toFixed(2)} km total
- Ranked by CO2 saved, not raw distance — a long drive is not a win.
`
    : ''
}${buildUserContextSection(userContext)}

**CO2 Baseline Comparison** (emissions vs emissions — do not mix these with the net figure):
- Driving this same ${totalKm.toFixed(2)} km in an average petrol car (${BASELINE_DRIVING_CO2_PER_KM} kg CO2/km) would have emitted ${baselineEmissionsKg.toFixed(2)} kg
- The user actually emitted ${stats.co2Emissions.toFixed(2)} kg — ${Math.abs(baselineDeltaKg).toFixed(2)} kg ${baselineDeltaKg >= 0 ? 'less than' : 'more than'} that all-driving baseline
- Separately, their net figure (saved minus emitted) is ${netCo2Kg.toFixed(2)} kg. ${netCo2Kg >= 0 ? 'They saved more than they emitted.' : 'They emitted more than they saved.'} Never describe a negative net figure as "better than baseline".
${trend ? `
**CO2 Trend vs. Previous ${days}-Day Period**:
- Previous period net CO2: ${trend.netPrev.toFixed(2)} kg
- Current period net CO2: ${trend.netNow.toFixed(2)} kg
- Change: ${trend.deltaKg >= 0 ? '+' : ''}${trend.deltaKg.toFixed(2)} kg${trend.pctChange !== null ? ` (${trend.pctChange >= 0 ? '+' : ''}${trend.pctChange.toFixed(0)}%)` : ''} — ${trend.deltaKg >= 0 ? 'an improvement' : 'a decline'} versus the prior period
` : ''}
Please provide a comprehensive analysis in the following JSON format:

{
  "score": 8.5,
  "scoreReasoning": "Brief explanation of the score",
  "insights": [
    "First key insight with specific numbers",
    "Second key insight with specific numbers",
    "Third key insight with specific numbers"
  ],
  "recommendations": [
    "First actionable recommendation",
    "Second actionable recommendation"
  ],
  "motivation": "One encouraging message celebrating their achievements",
  "environmentalImpact": "Brief summary of their CO2 impact",
  "highlights": {
    "bestDay": "Copy the Most Active Day given above, e.g. 'Tuesday 12 Aug — 8.4 km'",
    "topActivity": "Most frequent activity type",
    "improvement": "Area showing most improvement or potential"
  }
}

Guidelines:
- Be specific with numbers and percentages
- Be encouraging and positive
- Provide actionable recommendations
- Consider activity consistency, variety, and intensity
- Score out of 10 (0-3: needs improvement, 4-6: fair, 7-8: good, 9-10: excellent)
- Keep insights brief but meaningful (1-2 sentences each)

Grounding rules — these override the guidelines above:
- Every number you state must come from the data above. Do not estimate, extrapolate
  or infer a figure that is not present.
- For "bestDay", use the Most Active Day supplied above verbatim. Do not pick your own.
- Use the Day-by-Day and Time of Day sections to say something about *patterns* —
  which weekdays differ, whether trips cluster at particular hours, where the gaps
  are. A pattern the user could not have read off their own dashboard is the most
  valuable thing you can offer.
- Tailor recommendations to their actual vehicle and mode mix. Do not suggest
  switching to a mode they already use for most of their distance, and do not suggest
  cycling or walking a distance they have never covered that way.
- If energy prices are unavailable, do not mention money at all.
- Session counts are per-activity in the Activity Breakdown. The Total Sessions figure
  covers all modes — never describe it as the count for one mode ("the 48 driving
  sessions" when 48 is the overall total is wrong).
- Per-mode averages are given to you. Do not compute your own by dividing a mode's
  total by the overall session count — quote the supplied average instead.
- A rolling window can span more calendar dates than its length. Do not phrase active
  days as a fraction such as "8 out of 7 days".
- "Most Active Day" above is ranked by CO2 saved. If you also mention the day with the
  greatest distance, call it the "longest-distance day" so the two do not read as
  contradicting each other.
- If Repeat Journeys are listed, at least one recommendation must name one of them,
  using its mode, distance and the supplied saving. A specific journey the user
  actually makes beats any general suggestion.
- Never invent or guess where a journey starts or ends. You are not given locations.
- If you do not have enough data to support a claim, say less rather than guessing.
- If CO2 conserved is significant, celebrate it!
- If user is mostly sedentary, gently encourage more activity
- Use the CO2 baseline comparison and trend data (when present) to ground environmentalImpact and insights in concrete numbers — e.g. how their net impact compares to driving the same distance, and whether it improved or declined versus the prior period

Return ONLY the JSON object, no additional text or markdown formatting.`;
}

function breakdownDistance(data, activity) {
  const b = data.breakdown;
  if (!b || !b[activity]) return 0;
  return Number(b[activity].distance) || 0;
}

function computeCombinedScore(totalDistance, co2Conserved, totalSessions) {
  return totalDistance + co2Conserved * 100 + totalSessions * 500;
}

async function getDisplayProfile(userId) {
  let displayName = 'User';
  let photoUrl = '';
  try {
    const u = await db.collection('users').doc(userId).get();
    const data = u.data() || {};
    if (data.displayName) displayName = String(data.displayName);
    if (data.photoUrl) photoUrl = String(data.photoUrl);
    const authUser = await admin.auth().getUser(userId).catch(() => null);
    if (authUser) {
      if (!data.displayName) {
        displayName = authUser.displayName || authUser.email?.split('@')[0] || 'User';
      }
      if (!data.photoUrl && authUser.photoURL) photoUrl = authUser.photoURL;
    }
  } catch (e) {
    console.warn('getDisplayProfile', e.message);
  }
  return { displayName, photoUrl };
}

async function rebuildUserDailyAggregate(userId, dateKey) {
  if (!dateKey || !/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) return;

  const userDoc = await db.collection('users').doc(userId).get();
  const optedIn = !!(userDoc.data() || {}).leaderboardOptIn;
  const dayRef = db.collection('leaderboardDaily').doc(dateKey).collection('users').doc(userId);

  if (!optedIn) {
    await dayRef.delete().catch(() => {});
    return;
  }

  const sessionsSnap = await db.collection('users').doc(userId).collection('sessions').get();
  let totalDistance = 0;
  let totalSessions = 0;
  let co2Conserved = 0;
  let co2Emissions = 0;
  let topSpeedMps = 0;
  let distanceWalking = 0;
  let distanceRunning = 0;
  let distanceCycling = 0;

  sessionsSnap.docs.forEach((doc) => {
    const data = doc.data();
    const dk = sessionDateKeyFromDoc(data);
    if (dk !== dateKey) return;
    totalDistance += Number(data.totalDistance) || 0;
    totalSessions += 1;
    co2Conserved += Number(data.co2Conserved) || 0;
    co2Emissions += Number(data.co2Emissions) || 0;
    topSpeedMps = Math.max(topSpeedMps, Number(data.topSpeedMps) || 0);
    distanceWalking += breakdownDistance(data, 'WALKING');
    distanceRunning += breakdownDistance(data, 'RUNNING');
    distanceCycling += breakdownDistance(data, 'CYCLING');
  });

  if (totalSessions === 0) {
    await dayRef.delete().catch(() => {});
    return;
  }

  const score = computeCombinedScore(totalDistance, co2Conserved, totalSessions);
  const { displayName, photoUrl } = await getDisplayProfile(userId);

  await dayRef.set({
    userId,
    displayName,
    photoUrl: photoUrl || '',
    totalDistance,
    totalSessions,
    co2Conserved,
    co2Emissions,
    score,
    topSpeedMps,
    distanceWalking,
    distanceRunning,
    distanceCycling,
    lastUpdated: admin.firestore.FieldValue.serverTimestamp()
  });
}

exports.onSessionWriteDailyLeaderboard = functions.firestore
  .document('users/{userId}/sessions/{sessionId}')
  .onWrite(async (change, context) => {
    const userId = context.params.userId;
    const before = change.before.exists ? change.before.data() : null;
    const after = change.after.exists ? change.after.data() : null;
    const keys = new Set();
    if (before) {
      const k = sessionDateKeyFromDoc(before);
      if (k) keys.add(k);
    }
    if (after) {
      const k = sessionDateKeyFromDoc(after);
      if (k) keys.add(k);
    }
    for (const dateKey of keys) {
      await rebuildUserDailyAggregate(userId, dateKey);
    }
    return null;
  });

// Leaderboard ranking categories.
// The Android client only sends CO2_SAVED today (the in-app category chip row
// was removed in favour of a single CO₂-saved leaderboard). The legacy keys
// are kept so older app versions and any third-party callers continue to work
// against the same Cloud Function — they map to the same Firestore fields they
// always did. New deployments should default to CO2_SAVED.
const CATEGORY_SORT_FIELD = {
  CO2_SAVED: 'co2Conserved',
  COMBINED: 'score',
  DISTANCE: 'totalDistance',
  TOP_SPEED: 'topSpeedMps',
  WALKING: 'distanceWalking',
  RUNNING: 'distanceRunning',
  CYCLING: 'distanceCycling'
};

exports.dailyLeaderboardTop = functions.https.onRequest(async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.status(204).send('');
    return;
  }
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Method not allowed' });
    return;
  }

  try {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      res.status(401).json({ error: 'Unauthorized' });
      return;
    }
    await admin.auth().verifyIdToken(authHeader.split('Bearer ')[1]);

    const dateKey = ((req.body.data && req.body.data.dateKey) || req.body.dateKey || '').trim();
    const category = ((req.body.data && req.body.data.category) || req.body.category || 'CO2_SAVED')
      .trim()
      .toUpperCase();

    if (!dateKey || !/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) {
      res.status(400).json({ error: 'Invalid or missing dateKey (yyyy-MM-dd)' });
      return;
    }

    const sortField = CATEGORY_SORT_FIELD[category] || CATEGORY_SORT_FIELD.CO2_SAVED;
    const ref = db.collection('leaderboardDaily').doc(dateKey).collection('users');
    const snap = await ref.orderBy(sortField, 'desc').limit(1).get();

    if (snap.empty) {
      res.status(200).json({ dateKey, category, entry: null });
      return;
    }

    const doc = snap.docs[0];
    const d = doc.data();
    res.status(200).json({
      dateKey,
      category,
      entry: {
        userId: d.userId || doc.id,
        displayName: d.displayName || null,
        photoUrl: d.photoUrl || null,
        totalDistance: Number(d.totalDistance) || 0,
        totalSessions: Number(d.totalSessions) || 0,
        co2Conserved: Number(d.co2Conserved) || 0,
        co2Emissions: Number(d.co2Emissions) || 0,
        score: Number(d.score) || 0,
        topSpeedMps: Number(d.topSpeedMps) || 0,
        distanceWalking: Number(d.distanceWalking) || 0,
        distanceRunning: Number(d.distanceRunning) || 0,
        distanceCycling: Number(d.distanceCycling) || 0,
        rank: 1
      }
    });
  } catch (e) {
    console.error('dailyLeaderboardTop', e);
    res.status(500).json({ error: 'Failed to load daily leaderboard' });
  }
});

/**
 * onSessionSave: Firestore trigger that rebuilds the all-time global leaderboard
 * entry for the user whenever a session document is created or updated.
 *
 * Global leaderboard structure: /leaderboard/{userId}
 *   totalDistance, co2Conserved, co2Emissions, totalSessions, score, ...
 */
exports.onSessionSave = functions.firestore
  .document('users/{userId}/sessions/{sessionId}')
  .onWrite(async (change, context) => {
    const userId = context.params.userId;
    try {
      const userDoc = await db.collection('users').doc(userId).get();
      const optedIn = !!(userDoc.data() || {}).leaderboardOptIn;

      const leaderboardRef = db.collection('leaderboard').doc(userId);
      if (!optedIn) {
        // Remove from global leaderboard if user opted out
        await leaderboardRef.delete().catch(() => {});
        return null;
      }

      const sessionsSnap = await db
        .collection('users')
        .doc(userId)
        .collection('sessions')
        .get();

      let totalDistance = 0;
      let totalSessions = 0;
      let co2Conserved = 0;
      let co2Emissions = 0;
      let topSpeedMps = 0;
      let distanceWalking = 0;
      let distanceRunning = 0;
      let distanceCycling = 0;

      sessionsSnap.docs.forEach((doc) => {
        const data = doc.data();
        totalDistance += Number(data.totalDistance) || 0;
        totalSessions += 1;
        co2Conserved += Number(data.co2Conserved) || 0;
        co2Emissions += Number(data.co2Emissions) || 0;
        topSpeedMps = Math.max(topSpeedMps, Number(data.topSpeedMps) || 0);
        distanceWalking += breakdownDistance(data, 'WALKING');
        distanceRunning += breakdownDistance(data, 'RUNNING');
        distanceCycling += breakdownDistance(data, 'CYCLING');
      });

      const score = computeCombinedScore(totalDistance, co2Conserved, totalSessions);
      const { displayName, photoUrl } = await getDisplayProfile(userId);

      await leaderboardRef.set({
        userId,
        displayName,
        photoUrl: photoUrl || '',
        totalDistance,
        totalSessions,
        co2Conserved,
        co2Emissions,
        score,
        topSpeedMps,
        distanceWalking,
        distanceRunning,
        distanceCycling,
        lastUpdated: admin.firestore.FieldValue.serverTimestamp()
      });

      console.log(`✅ Global leaderboard updated for user ${userId}: score=${score}`);
    } catch (e) {
      console.error('onSessionSave leaderboard update failed', e);
    }
    return null;
  });

// ── Weekly digest (FCM push) ────────────────────────────────────────────────
//
// Fires every Monday at 12:00 UTC = 15:00 UTC+3 (EAT).
// Fetches each opted-in user's sessions from the past 7 days, computes a brief
// stats summary, and sends an FCM data-only message to every registered device.
//
// Payload keys match what KineticFirebaseMessagingService expects:
//   data.title, data.body

exports.weeklyDigestScheduled = functions.pubsub
  .schedule('0 12 * * 1')   // Every Monday at 12:00 UTC
  .timeZone('UTC')
  .onRun(async () => {
    console.log('📬 weeklyDigestScheduled: starting fan-out');

    // Collect all users with at least one FCM token
    const usersSnap = await db.collection('users').get();
    const fanOut = [];

    for (const userDoc of usersSnap.docs) {
      const userId = userDoc.id;
      try {
        // Fetch FCM tokens for this user
        const tokensSnap = await db
          .collection('users')
          .doc(userId)
          .collection('fcmTokens')
          .get();

        if (tokensSnap.empty) continue;

        // Compute last-7-days stats
        const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
        const sessionsSnap = await db
          .collection('users')
          .doc(userId)
          .collection('sessions')
          .where('timestamp', '>=', cutoff)
          .get();

        if (sessionsSnap.empty) continue;

        let totalDistanceM = 0;
        let totalCalories = 0;
        let co2SavedKg = 0;
        let co2EmittedKg = 0;
        let distWalking = 0, distRunning = 0, distCycling = 0;
        const totalSessions = sessionsSnap.size;
        sessionsSnap.docs.forEach((d) => {
          totalDistanceM += Number(d.data().totalDistance)  || 0;
          totalCalories  += Number(d.data().caloriesBurned) || 0;
          co2SavedKg     += Number(d.data().co2Conserved)   || 0;
          co2EmittedKg   += Number(d.data().co2Emissions)   || 0;
          const bd = d.data().breakdown || {};
          distWalking += Number(bd.WALKING?.distance) || 0;
          distRunning += Number(bd.RUNNING?.distance) || 0;
          distCycling += Number(bd.CYCLING?.distance) || 0;
        });

        const activities = [
          { label: 'Walking', emoji: '🚶', dist: distWalking },
          { label: 'Running', emoji: '🏃', dist: distRunning },
          { label: 'Cycling', emoji: '🚴', dist: distCycling },
        ].filter(a => a.dist > 0);
        const main = activities.length > 0
          ? activities.reduce((a, b) => a.dist >= b.dist ? a : b)
          : null;

        const distanceKm = (totalDistanceM / 1000).toFixed(1);
        const equiv = pickEquivalency(co2SavedKg);
        const title = '📊 Your Kinetic Eco week recap';
        const lines = [];
        lines.push(`${totalSessions} session${totalSessions !== 1 ? 's' : ''} • ${distanceKm} km • ${Math.round(totalCalories)} kcal`);
        if (main) lines.push(`${main.emoji} Top activity: ${main.label} (${(main.dist / 1000).toFixed(1)} km)`);
        lines.push(`🌱 CO₂ saved: ${co2SavedKg.toFixed(2)} kg${equiv ? ` — like ${equiv}` : ''}`);
        const body = lines.join('\n');

        // Send to every registered device token (with retry)
        for (const tokenDoc of tokensSnap.docs) {
          fanOut.push(sendFcmWithRetry(tokenDoc, { data: { title, body } }, userId));
        }
      } catch (e) {
        console.error(`weeklyDigest: error processing user ${userId}`, e);
      }
    }

    await Promise.allSettled(fanOut);
    console.log(`📬 weeklyDigestScheduled: sent to ${fanOut.length} device(s)`);
    return null;
  });

// ── FCM send with retry ─────────────────────────────────────────────────────

// ── CO₂ equivalency helper ──────────────────────────────────────────────────

/**
 * Returns a short, encouraging equivalency string for a given CO₂ saving in kg.
 * Used in both daily notifications and weekly emails.
 */
function pickEquivalency(kg) {
  if (kg <= 0) return null;
  const equivalencies = [
    { threshold: 0.01, text: (k) => `charging a smartphone ${Math.round(k / 0.008)} time${Math.round(k / 0.008) !== 1 ? 's' : ''}` },
    { threshold: 0.05, text: (k) => `${Math.round(k / 0.033)} LED bulb-hours saved` },
    { threshold: 0.1,  text: (k) => `skipping ${Math.round(k / 0.2 * 10) / 10} cups of coffee worth of emissions` },
    { threshold: 0.5,  text: (k) => `${(k / 0.12).toFixed(1)} km not driven by car` },
    { threshold: 2,    text: (k) => `${(k / 0.575).toFixed(1)} days of a tree's CO₂ absorption` },
    { threshold: 10,   text: (k) => `planting ${(k / 21).toFixed(2)} trees for a year` },
  ];
  for (let i = equivalencies.length - 1; i >= 0; i--) {
    if (kg >= equivalencies[i].threshold) return equivalencies[i].text(kg);
  }
  return `${(kg * 1000).toFixed(0)} g of CO₂ kept out of the atmosphere`;
}

// ── Weekly email helper ─────────────────────────────────────────────────────

function buildWeeklyEmailHtml(firstName, stats) {
  const { sessions, distanceKm, durationMin, calories, co2SavedKg, co2EmittedKg, topActivity } = stats;
  const netKg = co2SavedKg - co2EmittedKg;
  const netLabel = netKg >= 0
    ? `<span style="color:#43A047">+${netKg.toFixed(2)} kg saved</span>`
    : `<span style="color:#E53935">${netKg.toFixed(2)} kg emitted</span>`;
  const equiv = pickEquivalency(Math.abs(netKg));
  const name = firstName || 'there';

  return `<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Your Kinetic Eco Weekly Report</title></head>
<body style="margin:0;padding:0;background:#f4f6f8;font-family:'Segoe UI',Arial,sans-serif;">
<table width="100%" cellpadding="0" cellspacing="0" style="background:#f4f6f8;padding:32px 0;">
<tr><td align="center">
<table width="560" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08);">
  <!-- Header -->
  <tr><td style="background:linear-gradient(135deg,#2E7D32,#43A047);padding:32px 40px;text-align:center;">
    <p style="margin:0;color:rgba(255,255,255,0.8);font-size:13px;letter-spacing:1px;text-transform:uppercase;">Weekly Report</p>
    <h1 style="margin:8px 0 0;color:#ffffff;font-size:26px;font-weight:700;">Hey ${name}! 🌿</h1>
    <p style="margin:8px 0 0;color:rgba(255,255,255,0.85);font-size:15px;">Here's your eco impact from the past 7 days.</p>
  </td></tr>
  <!-- Stats grid -->
  <tr><td style="padding:32px 40px 0;">
    <table width="100%" cellpadding="0" cellspacing="0">
      <tr>
        <td style="width:50%;padding:0 8px 16px 0;vertical-align:top;">
          <div style="background:#f8fdf8;border-radius:12px;padding:18px;text-align:center;">
            <p style="margin:0;font-size:28px;font-weight:700;color:#2E7D32;">${distanceKm}</p>
            <p style="margin:4px 0 0;font-size:12px;color:#888;">km traveled</p>
          </div>
        </td>
        <td style="width:50%;padding:0 0 16px 8px;vertical-align:top;">
          <div style="background:#f8faf8;border-radius:12px;padding:18px;text-align:center;">
            <p style="margin:0;font-size:28px;font-weight:700;color:#1565C0;">${sessions}</p>
            <p style="margin:4px 0 0;font-size:12px;color:#888;">session${sessions !== 1 ? 's' : ''} logged</p>
          </div>
        </td>
      </tr>
      <tr>
        <td style="width:50%;padding:0 8px 16px 0;vertical-align:top;">
          <div style="background:#fff8f0;border-radius:12px;padding:18px;text-align:center;">
            <p style="margin:0;font-size:28px;font-weight:700;color:#E65100;">${Math.round(calories)}</p>
            <p style="margin:4px 0 0;font-size:12px;color:#888;">kcal burned</p>
          </div>
        </td>
        <td style="width:50%;padding:0 0 16px 8px;vertical-align:top;">
          <div style="background:#f3f8ff;border-radius:12px;padding:18px;text-align:center;">
            <p style="margin:0;font-size:28px;font-weight:700;color:#0277BD;">${Math.round(durationMin)}</p>
            <p style="margin:4px 0 0;font-size:12px;color:#888;">minutes active</p>
          </div>
        </td>
      </tr>
    </table>
  </td></tr>
  <!-- CO₂ net impact -->
  <tr><td style="padding:0 40px;">
    <div style="background:#f0faf0;border-radius:12px;padding:20px;text-align:center;">
      <p style="margin:0;font-size:13px;color:#666;text-transform:uppercase;letter-spacing:0.5px;">Net CO₂ impact this week</p>
      <p style="margin:8px 0 4px;font-size:32px;font-weight:700;">${netLabel}</p>
      ${equiv ? `<p style="margin:4px 0 0;font-size:13px;color:#555;">That's like <em>${equiv}</em>.</p>` : ''}
    </div>
  </td></tr>
  <!-- Top activity -->
  ${topActivity ? `<tr><td style="padding:20px 40px 0;">
    <p style="margin:0;font-size:14px;color:#444;">🏆 Your top activity this week: <strong>${topActivity}</strong></p>
  </td></tr>` : ''}
  <!-- Footer -->
  <tr><td style="padding:28px 40px 32px;text-align:center;border-top:1px solid #f0f0f0;margin-top:24px;">
    <p style="margin:0;font-size:13px;color:#888;">Keep building those habits — every trip counts.</p>
    <p style="margin:8px 0 0;font-size:12px;color:#bbb;">Kinetic Eco Tracker &nbsp;·&nbsp; To unsubscribe, open the app → Settings → Notifications</p>
  </td></tr>
</table>
</td></tr>
</table>
</body></html>`;
}

// ── Daily activity digest (FCM push) ────────────────────────────────────────
//
// Fires daily at 17:00 UTC = 20:00 EAT (Nairobi).
// Only sends if the user has at least one session today — no guilt-tripping.
// Includes distance, CO₂ saved, and a fun equivalency comparison.

exports.dailyActivityDigest = functions.pubsub
  .schedule('0 17 * * *')
  .timeZone('UTC')
  .onRun(async () => {
    console.log('🌱 dailyActivityDigest: starting fan-out');

    const todayKey = new Date().toISOString().slice(0, 10); // yyyy-MM-dd UTC
    const startOfTodayUtc = new Date(todayKey + 'T00:00:00Z').getTime();

    const usersSnap = await db.collection('users').get();
    const fanOut = [];

    for (const userDoc of usersSnap.docs) {
      const userId = userDoc.id;
      try {
        const tokensSnap = await db
          .collection('users').doc(userId)
          .collection('fcmTokens').get();
        if (tokensSnap.empty) continue;

        const sessionsSnap = await db
          .collection('users').doc(userId)
          .collection('sessions')
          .where('timestamp', '>=', startOfTodayUtc)
          .get();
        if (sessionsSnap.empty) continue; // no activity today — skip silently

        let distanceM = 0, calories = 0, co2SavedKg = 0, co2EmittedKg = 0;
        let distWalking = 0, distRunning = 0, distCycling = 0;
        sessionsSnap.docs.forEach((d) => {
          distanceM    += Number(d.data().totalDistance)  || 0;
          calories     += Number(d.data().caloriesBurned) || 0;
          co2SavedKg   += Number(d.data().co2Conserved)  || 0;
          co2EmittedKg += Number(d.data().co2Emissions)  || 0;
          const bd = d.data().breakdown || {};
          distWalking += Number(bd.WALKING?.distance) || 0;
          distRunning += Number(bd.RUNNING?.distance) || 0;
          distCycling += Number(bd.CYCLING?.distance) || 0;
        });

        const activities = [
          { label: 'Walking', emoji: '🚶', dist: distWalking },
          { label: 'Running', emoji: '🏃', dist: distRunning },
          { label: 'Cycling', emoji: '🚴', dist: distCycling },
        ].filter(a => a.dist > 0);
        const main = activities.length > 0
          ? activities.reduce((a, b) => a.dist >= b.dist ? a : b)
          : null;

        const distanceKm = (distanceM / 1000).toFixed(1);
        const equiv = pickEquivalency(co2SavedKg);

        const title = '🌿 Today\'s Eco Impact';
        const lines = [];
        if (main) lines.push(`${main.emoji} Main activity: ${main.label} (${(main.dist / 1000).toFixed(1)} km)`);
        lines.push(`📍 ${distanceKm} km total • ${Math.round(calories)} kcal burned`);
        lines.push(`🌱 CO₂ saved: ${co2SavedKg.toFixed(2)} kg${equiv ? ` — like ${equiv}` : ''}`);
        const body = lines.join('\n');

        for (const tokenDoc of tokensSnap.docs) {
          fanOut.push(sendFcmWithRetry(tokenDoc, { data: { type: 'daily', title, body } }, userId));
        }
      } catch (e) {
        console.error(`dailyDigest: error for user ${userId}`, e);
      }
    }

    await Promise.allSettled(fanOut);
    console.log(`🌱 dailyActivityDigest: sent to ${fanOut.length} device(s)`);
    return null;
  });

// ── Weekly email report ─────────────────────────────────────────────────────
//
// Fires every Monday at 05:00 UTC = 08:00 EAT.
// Requires Firebase config:
//   firebase functions:config:set email.user="your@gmail.com" email.pass="app-password"

exports.weeklyEmailReport = functions.runWith({ secrets: ['EMAIL_USER', 'EMAIL_PASS'] }).pubsub
  .schedule('0 5 * * 0')
  .timeZone('UTC')
  .onRun(async () => {
    console.log('📧 weeklyEmailReport: starting');

    const emailUser = process.env.EMAIL_USER;
    const emailPass = process.env.EMAIL_PASS;
    if (!emailUser || !emailPass) {
      console.warn('weeklyEmailReport: EMAIL_USER / EMAIL_PASS secrets not configured — skipping');
      return null;
    }

    const nodemailer = require('nodemailer');
    const transporter = nodemailer.createTransport({
      service: 'gmail',
      auth: { user: emailUser, pass: emailPass }
    });

    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const usersSnap = await db.collection('users').get();

    for (const userDoc of usersSnap.docs) {
      const userId = userDoc.id;
      try {
        let userEmail, displayName;
        try {
          const authUser = await admin.auth().getUser(userId);
          userEmail   = authUser.email;
          displayName = authUser.displayName || '';
        } catch { continue; }

        if (!userEmail) continue;

        const sessionsSnap = await db
          .collection('users').doc(userId)
          .collection('sessions')
          .where('timestamp', '>=', cutoff)
          .get();
        if (sessionsSnap.empty) continue; // no activity — skip silently

        let distanceM = 0, durationSecs = 0, calories = 0,
            co2SavedKg = 0, co2EmittedKg = 0;
        const activityCounts = {};

        sessionsSnap.docs.forEach((d) => {
          const data = d.data();
          distanceM    += Number(data.totalDistance)  || 0;
          durationSecs += Number(data.totalDuration)  || 0;
          calories     += Number(data.caloriesBurned) || 0;
          co2SavedKg   += Number(data.co2Conserved)   || 0;
          co2EmittedKg += Number(data.co2Emissions)   || 0;
          const act = data.activityType || data.activity || 'Unknown';
          activityCounts[act] = (activityCounts[act] || 0) + 1;
        });

        const topActivity = Object.entries(activityCounts)
          .sort((a, b) => b[1] - a[1])[0]?.[0] || null;
        const firstName = displayName.split(' ')[0] || '';

        const stats = {
          sessions:    sessionsSnap.size,
          distanceKm:  (distanceM / 1000).toFixed(1),
          durationMin: durationSecs / 60,
          calories,
          co2SavedKg,
          co2EmittedKg,
          topActivity
        };

        const html = buildWeeklyEmailHtml(firstName, stats);
        const netKg = co2SavedKg - co2EmittedKg;
        const subjectTag = netKg >= 0 ? `+${netKg.toFixed(2)} kg CO₂ saved` : `${netKg.toFixed(2)} kg net CO₂`;

        await transporter.sendMail({
          from: `"Kinetic Eco" <${emailUser}>`,
          to:   userEmail,
          subject: `Your Kinetic Eco week — ${subjectTag}`,
          html
        });

        console.log(`📧 Weekly email sent to ${userEmail}`);
      } catch (e) {
        console.error(`weeklyEmailReport: error for user ${userId}`, e);
      }
    }

    console.log('📧 weeklyEmailReport: done');
    return null;
  });

/**
 * Generate a single-session eco insight via Gemini (server-side, key never exposed to browser).
 */
exports.generateSessionInsight = functions.runWith({ secrets: ['GEMINI_API_KEY'] }).https.onRequest(async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') { res.status(204).send(''); return; }
  if (req.method !== 'POST') { res.status(405).json({ error: 'Method not allowed' }); return; }

  // Authenticate
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Unauthorized' }); return;
  }
  try {
    await admin.auth().verifyIdToken(authHeader.split('Bearer ')[1]);
  } catch {
    res.status(401).json({ error: 'Unauthorized: invalid token' }); return;
  }

  // Global throttle (shared with analyzeActivity)
  const retryAfterSec = throttleAnalysis();
  if (retryAfterSec > 0) {
    res.set('Retry-After', String(retryAfterSec));
    res.status(429).json({ error: `Too many requests. Retry in ${retryAfterSec}s.` }); return;
  }

  const { totalDuration, totalDistance, caloriesBurned, co2Emissions, breakdown } = req.body || {};

  const prompt = `
Analyze the following movement data from a user's tracking session:

Total Duration: ${Math.round(Number(totalDuration) || 0)} seconds
Total Distance: ${((Number(totalDistance) || 0) / 1000).toFixed(2)} km
Calories Burned: ${Math.round(Number(caloriesBurned) || 0)} kcal
CO2 Emissions: ${(Number(co2Emissions) || 0).toFixed(2)} kg

Breakdown:
- Walking: ${((breakdown?.WALKING?.distance || 0) / 1000).toFixed(2)} km, ${Math.round(breakdown?.WALKING?.time || 0)} sec
- Driving: ${((breakdown?.DRIVING?.distance || 0) / 1000).toFixed(2)} km, ${Math.round(breakdown?.DRIVING?.time || 0)} sec
- Flying: ${((breakdown?.FLYING?.distance || 0) / 1000).toFixed(2)} km, ${Math.round(breakdown?.FLYING?.time || 0)} sec

Provide a short, engaging, and personalized summary (approx 100 words).
Focus on the environmental impact and health benefits.
If they walked a lot, praise them for low emissions and high calorie burn.
If they drove or flew a lot, suggest carbon offsetting or mention the environmental cost gently.
Use Markdown for formatting.
`.trim();

  try {
    const model = getGenAI().getGenerativeModel({ model: 'gemini-2.5-flash' });
    const result = await model.generateContent({
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      systemInstruction: "You are an eco-conscious fitness coach named 'EcoStep'.",
    });
    const insight = result.response.text();
    res.status(200).json({ insight });
  } catch (error) {
    console.error('generateSessionInsight: Gemini error', error);
    res.status(500).json({ error: 'AI service error' });
  }
});


/**
 * Monthly statement push — premium only.
 *
 * Fires on the 1st at 06:00 UTC, about the month that just closed.
 *
 * **The numbers in the notification are deliberately limited to distance and trips.**
 * The money figure a subscriber actually cares about depends on their measured fuel
 * economy and the price they pay, both of which live on the device — the server would
 * have to substitute a class average and a national price cap and present the result
 * as their spending. So the push reports what the server genuinely knows and the app
 * computes the cost when the statement is opened.
 */
exports.monthlyStatementPush = functions.pubsub
  .schedule('0 6 1 * *')
  .timeZone('UTC')
  .onRun(async () => {
    const now = new Date();
    const monthEnd = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1);
    const monthStart = Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1);
    const monthLabel = new Date(monthStart).toLocaleString('en-US', { month: 'long', timeZone: 'UTC' });
    console.log(`📄 monthlyStatementPush: ${monthLabel}`);

    const usersSnap = await db.collection('users').get();
    const fanOut = [];
    let eligible = 0;

    for (const userDoc of usersSnap.docs) {
      const userId = userDoc.id;
      try {
        // Premium gate, server-side. The entitlement doc is written only by
        // verifyPlayPurchase/RTDN, so it is the authoritative answer — and expiry is
        // what grants access, not `active`, matching Entitlement.isEntitledAt.
        const ent = await db
          .collection('users').doc(userId)
          .collection('entitlements').doc('premium').get();
        if (!ent.exists || Number(ent.data().expiryMs || 0) <= Date.now()) continue;

        const tokensSnap = await db.collection('users').doc(userId).collection('fcmTokens').get();
        if (tokensSnap.empty) continue;

        const sessionsSnap = await db
          .collection('users').doc(userId).collection('sessions')
          .where('timestamp', '>=', monthStart)
          .where('timestamp', '<', monthEnd)
          .get();
        // Nothing tracked means nothing to report. A statement reading "0 trips" is a
        // notification that only reminds someone they did not use the app.
        if (sessionsSnap.empty) continue;

        let distanceM = 0;
        sessionsSnap.docs.forEach((d) => { distanceM += Number(d.data().totalDistance) || 0; });

        eligible++;
        const title = `📄 Your ${monthLabel} statement`;
        const body =
          `${sessionsSnap.size} trips · ${(distanceM / 1000).toFixed(0)} km. ` +
          'Tap to see what it cost.';

        for (const tokenDoc of tokensSnap.docs) {
          fanOut.push(sendFcmWithRetry(tokenDoc, { data: { type: 'monthly', title, body } }, userId));
        }
      } catch (e) {
        console.error(`monthlyStatementPush: error for user ${userId}`, e);
      }
    }

    await Promise.allSettled(fanOut);
    console.log(`📄 monthlyStatementPush: ${eligible} subscribers, ${fanOut.length} device(s)`);
    return null;
  });

/**
 * Health check endpoint
 */
exports.healthCheck = functions.https.onRequest((req, res) => {
  res.json({
    status: 'ok',
    timestamp: Date.now(),
    version: '1.5.1',
    message: 'Kinetic Eco API is running'
  });
});

// ── Google Play subscriptions ────────────────────────────────────────────────
// Required after admin.initializeApp() above: playBilling.js resolves Firestore
// lazily, but keeping the require here makes the ordering dependency obvious.
// See playBilling.js for the Play Console / IAM setup these functions assume.
const playBilling = require('./playBilling');

exports.verifyPlayPurchase = playBilling.verifyPlayPurchase;
exports.playBillingRtdn = playBilling.playBillingRtdn;
