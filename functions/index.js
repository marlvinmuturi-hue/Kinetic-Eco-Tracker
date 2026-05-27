const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { GoogleGenerativeAI } = require('@google/generative-ai');

// Initialize Firebase Admin
admin.initializeApp();
const db = admin.firestore();

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

// Initialize Gemini AI
// API key will be set via: firebase functions:config:set gemini.key="YOUR_KEY"
const genAI = new GoogleGenerativeAI(functions.config().gemini?.key || 'YOUR_API_KEY_HERE');

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
exports.analyzeActivity = functions.https.onRequest(async (req, res) => {
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
    // If a valid calendar day is sent, single-day analysis must win (ignore mistaken rolling timeframe from client).
    if (/^\d{4}-\d{2}-\d{2}$/.test(sessionDateKey)) {
      timeframe = '1day';
    }
    const effectiveCacheTimeframe =
      timeframe === '1day' && sessionDateKey ? `1day:${sessionDateKey}` : timeframe;

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
      
      if (cached.timestamp > dayAgo && cached.timeframe === effectiveCacheTimeframe && cached.locale === locale) {
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
    const stats = calculateAggregateStats(sessions);
    console.log('Stats calculated:', JSON.stringify(stats, null, 2));

    // Step 6: Build prompt for Gemini (locale: en/fr/de/es/zh)
    const prompt = buildAnalysisPrompt(stats, timeframe, locale, sessionDateKey);

    // Step 7: Call Gemini API
    console.log('Calling Gemini API...');
    const model = genAI.getGenerativeModel({ model: 'gemini-2.0-flash' });
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
      locale: locale
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
 * Calculate aggregate statistics from sessions
 */
function calculateAggregateStats(sessions) {
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
          stats.activityBreakdown[activity].count++;
          stats.activityBreakdown[activity].distance += session.breakdown[activity].distance || 0;
          stats.activityBreakdown[activity].duration += session.breakdown[activity].time || 0;
        }
      });
    }
  });

  stats.activeDaysCount = stats.activeDays.size;
  stats.avgDistance = stats.totalDistance / stats.totalSessions;
  stats.avgDuration = stats.totalDuration / stats.totalSessions;

  return stats;
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
function buildAnalysisPrompt(stats, timeframe, locale, sessionDateKey) {
  const days = parseRollingDaysFromTimeframe(timeframe);
  const language = getLanguageForLocale(locale);
  const periodLabel =
    timeframe === '1day' && sessionDateKey
      ? `Single calendar day (${sessionDateKey}, session start dates as stored in the app)`
      : `Past ${days} days`;

  return `You are a fitness and health analyst. Analyze this user's activity data and provide insights.

**IMPORTANT**: Respond entirely in ${language}. All text in scoreReasoning, insights, recommendations, motivation, environmentalImpact, and highlights must be written in ${language}.

**Time Period**: ${periodLabel}

**Activity Summary**:
- Total Sessions: ${stats.totalSessions}
- Active Days: ${stats.activeDaysCount} out of ${timeframe === '1day' ? 1 : days} day(s)
- Total Distance: ${(stats.totalDistance / 1000).toFixed(2)} km
- Total Duration: ${(stats.totalDuration / 3600).toFixed(1)} hours
- Calories Burned: ${Math.round(stats.totalCalories)} kcal
- CO2 Emissions: ${stats.co2Emissions.toFixed(2)} kg
- CO2 Conserved: ${stats.co2Conserved.toFixed(2)} kg

**Activity Breakdown**:
${Object.entries(stats.activityBreakdown)
  .filter(([_, data]) => data.count > 0)
  .map(([activity, data]) => 
    `- ${activity}: ${data.count} sessions, ${(data.distance / 1000).toFixed(2)} km, ${(data.duration / 60).toFixed(0)} minutes`
  )
  .join('\n')}

**Average Per Session**:
- Distance: ${(stats.avgDistance / 1000).toFixed(2)} km
- Duration: ${(stats.avgDuration / 60).toFixed(0)} minutes

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
    "bestDay": "Day with most activity",
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
- If CO2 conserved is significant, celebrate it!
- If user is mostly sedentary, gently encourage more activity

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
        let totalSessions = sessionsSnap.size;
        sessionsSnap.docs.forEach((d) => {
          totalDistanceM += Number(d.data().totalDistance) || 0;
          totalCalories += Number(d.data().caloriesBurned) || 0;
        });

        const distanceKm = (totalDistanceM / 1000).toFixed(1);
        const title = '📊 Your Kinetic Eco week recap';
        const body =
          `Last 7 days: ${totalSessions} session${totalSessions !== 1 ? 's' : ''}, ` +
          `${distanceKm} km, ${Math.round(totalCalories)} kcal burned. Keep it up!`;

        // Send to every registered device token
        for (const tokenDoc of tokensSnap.docs) {
          const token = tokenDoc.id;
          fanOut.push(
            admin.messaging().send({
              token,
              data: { title, body }
            }).catch((err) => {
              console.warn(`FCM send failed for ${userId}/${token}: ${err.message}`);
              // Stale token cleanup
              if (
                err.code === 'messaging/registration-token-not-registered' ||
                err.code === 'messaging/invalid-registration-token'
              ) {
                tokenDoc.ref.delete().catch(() => {});
              }
            })
          );
        }
      } catch (e) {
        console.error(`weeklyDigest: error processing user ${userId}`, e);
      }
    }

    await Promise.allSettled(fanOut);
    console.log(`📬 weeklyDigestScheduled: sent to ${fanOut.length} device(s)`);
    return null;
  });

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
        sessionsSnap.docs.forEach((d) => {
          distanceM   += Number(d.data().totalDistance)   || 0;
          calories    += Number(d.data().caloriesBurned)  || 0;
          co2SavedKg  += Number(d.data().co2Saved)        || 0;
          co2EmittedKg+= Number(d.data().co2Emitted)      || 0;
        });

        const netKg = co2SavedKg - co2EmittedKg;
        const distanceKm = (distanceM / 1000).toFixed(1);
        const equiv = pickEquivalency(Math.abs(netKg));

        const title = '🌿 Today\'s Eco Impact';
        let body = `You covered ${distanceKm} km today`;
        if (netKg > 0) {
          body += `, saving ${netKg.toFixed(2)} kg CO₂`;
          if (equiv) body += ` — like ${equiv}`;
        }
        body += `. ${Math.round(calories)} kcal burned. Nice work!`;

        for (const tokenDoc of tokensSnap.docs) {
          fanOut.push(
            admin.messaging().send({
              token: tokenDoc.id,
              data: { type: 'daily', title, body }
            }).catch((err) => {
              console.warn(`dailyDigest FCM failed ${userId}/${tokenDoc.id}: ${err.message}`);
              if (
                err.code === 'messaging/registration-token-not-registered' ||
                err.code === 'messaging/invalid-registration-token'
              ) tokenDoc.ref.delete().catch(() => {});
            })
          );
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

exports.weeklyEmailReport = functions.pubsub
  .schedule('0 5 * * 0')
  .timeZone('UTC')
  .onRun(async () => {
    console.log('📧 weeklyEmailReport: starting');

    const emailCfg = functions.config().email || {};
    if (!emailCfg.user || !emailCfg.pass) {
      console.warn('weeklyEmailReport: email.user / email.pass not configured — skipping');
      return null;
    }

    const nodemailer = require('nodemailer');
    const transporter = nodemailer.createTransport({
      service: 'gmail',
      auth: { user: emailCfg.user, pass: emailCfg.pass }
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

        let distanceM = 0, durationMs = 0, calories = 0,
            co2SavedKg = 0, co2EmittedKg = 0;
        const activityCounts = {};

        sessionsSnap.docs.forEach((d) => {
          const data = d.data();
          distanceM    += Number(data.totalDistance)  || 0;
          durationMs   += Number(data.duration)       || 0;
          calories     += Number(data.caloriesBurned) || 0;
          co2SavedKg   += Number(data.co2Saved)       || 0;
          co2EmittedKg += Number(data.co2Emitted)     || 0;
          const act = data.activityType || data.activity || 'Unknown';
          activityCounts[act] = (activityCounts[act] || 0) + 1;
        });

        const topActivity = Object.entries(activityCounts)
          .sort((a, b) => b[1] - a[1])[0]?.[0] || null;
        const firstName = displayName.split(' ')[0] || '';

        const stats = {
          sessions:    sessionsSnap.size,
          distanceKm:  (distanceM / 1000).toFixed(1),
          durationMin: durationMs / 60000,
          calories,
          co2SavedKg,
          co2EmittedKg,
          topActivity
        };

        const html = buildWeeklyEmailHtml(firstName, stats);
        const netKg = co2SavedKg - co2EmittedKg;
        const subjectTag = netKg >= 0 ? `+${netKg.toFixed(2)} kg CO₂ saved` : `${netKg.toFixed(2)} kg net CO₂`;

        await transporter.sendMail({
          from: `"Kinetic Eco" <${emailCfg.user}>`,
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
exports.generateSessionInsight = functions.https.onRequest(async (req, res) => {
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
    const model = genAI.getGenerativeModel({ model: 'gemini-2.0-flash' });
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
 * Health check endpoint
 */
exports.healthCheck = functions.https.onRequest((req, res) => {
  res.json({
    status: 'ok',
    timestamp: Date.now(),
    version: '1.5.0',
    message: 'Kinetic Eco API is running'
  });
});
